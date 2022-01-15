package io.heckel.ntfy.service

import android.app.AlarmManager
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import io.heckel.ntfy.data.*
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.joinTags
import io.heckel.ntfy.util.toPriority
import io.heckel.ntfy.util.topicUrlWs
import okhttp3.*
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class WsConnection(
    private val repository: Repository,
    private val baseUrl: String,
    private val sinceTime: Long,
    private val topicsToSubscriptionIds: Map<String, Long>, // Topic -> Subscription ID
    private val stateChangeListener: (Collection<Long>, ConnectionState) -> Unit,
    private val notificationListener: (Subscription, Notification) -> Unit,
    private val alarmManager: AlarmManager
) : Connection {
    private val client: OkHttpClient
    //private val reconnectHandler = Handler()
    //private val reconnectCallback = Runnable { start() }
    private var errorCount = 0
    private var webSocket: WebSocket? = null
    private var state: State? = null
    private val gson = Gson()

    private val subscriptionIds = topicsToSubscriptionIds.values
    private val topicsStr = topicsToSubscriptionIds.keys.joinToString(separator = ",")
    private val sinceVal = if (sinceTime == 0L) "all" else sinceTime.toString()
    private val wsurl = topicUrlWs(baseUrl, topicsStr, sinceVal)

    init {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            //.pingInterval(1, TimeUnit.MINUTES)
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
        client = builder.build()
    }

    private fun request(): Request {
        return Request.Builder()
            .url(wsurl)
            .get()
            .build()
    }

    @Synchronized
    override fun start() {
        if (state == State.Connecting || state == State.Connected) {
            return
        }
        cancel()
        state = State.Connecting
        val nextId = ID.incrementAndGet()
        Log.d(TAG, "WebSocket($nextId): starting...")
        webSocket = client.newWebSocket(request(), Listener(nextId))
    }

    @Synchronized
    override fun cancel() {
        if (webSocket != null) {
            Log.d(TAG, "WebSocket(" + ID.get() + "): closing existing connection.")
            state = State.Disconnected
            webSocket!!.close(1000, "")
            webSocket = null
        }
    }

    override fun since(): Long {
        return 0L
    }

    override fun matches(otherSubscriptionIds: Collection<Long>): Boolean {
        return subscriptionIds.toSet() == otherSubscriptionIds.toSet()
    }

    @Synchronized
    fun scheduleReconnect(seconds: Long) {
        if (state == State.Connecting || state == State.Connected) {
            return
        }
        state = State.Scheduled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG,
                "WebSocket: scheduling a restart in "
                        + seconds
                        + " second(s) (via alarm manager)"
            )
            val future = Calendar.getInstance()
            future.add(Calendar.SECOND, seconds.toInt())
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                future.timeInMillis,
                "reconnect-tag", { start() },
                null
            )
        } else {
            Log.d(TAG, "WebSocket: scheduling a restart in $seconds second(s)")
            //reconnectHandler.removeCallbacks(reconnectCallback)
            //reconnectHandler.postDelayed(reconnectCallback, TimeUnit.SECONDS.toMillis(seconds))
        }
    }

    private inner class Listener(private val id: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            syncExec {
                state = State.Connected
                Log.d(TAG, "WebSocket(" + id + "): opened")
                if (errorCount > 0) {
                    Log.d(TAG, "reconnected")
                    errorCount = 0
                }
                stateChangeListener(subscriptionIds, ConnectionState.CONNECTED)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            syncExec {
                Log.d(TAG, "WebSocket(" + id + "): received message " + text)
                val message = gson.fromJson(text, ApiService.Message::class.java)
                if (message.event == ApiService.EVENT_MESSAGE) {
                    val topic = message.topic
                    val attachment = if (message.attachment?.url != null) {
                        Attachment(
                            name = message.attachment.name,
                            type = message.attachment.type,
                            size = message.attachment.size,
                            expires = message.attachment.expires,
                            url = message.attachment.url,
                        )
                    } else null
                    val notification = Notification(
                        id = message.id,
                        subscriptionId = 0, // TO BE SET downstream
                        timestamp = message.time,
                        title = message.title ?: "",
                        message = message.message,
                        priority = toPriority(message.priority),
                        tags = joinTags(message.tags),
                        click = message.click ?: "",
                        attachment = attachment,
                        notificationId = Random.nextInt(),
                        deleted = false
                    )
                    val subscriptionId = topicsToSubscriptionIds[topic] ?: return@syncExec
                    val subscription = repository.getSubscription(subscriptionId) ?: return@syncExec
                    val notificationWithSubscriptionId = notification.copy(subscriptionId = subscription.id)
                    notificationListener(subscription, notificationWithSubscriptionId)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            syncExec {
                if (state == State.Connected) {
                    Log.w(TAG, "WebSocket(" + id + "): closed")
                }
                state = State.Disconnected
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = if (response != null) "StatusCode: " + response.code else ""
            val message = response?.message ?: ""
            Log.e(TAG, "WebSocket($id): failure $code Message: $message", t)
            syncExec {
                stateChangeListener(subscriptionIds, ConnectionState.CONNECTING)
                state = State.Disconnected
                if ((response != null) && (response.code >= 400) && (response.code <= 499)) {
                    Log.d(TAG, "bad request")
                    cancel()
                    return@syncExec
                }
                errorCount++
                val minutes: Int = Math.min(errorCount * 2 - 1, 20)
                //scheduleReconnect(TimeUnit.MINUTES.toSeconds(minutes.toLong()))
                scheduleReconnect(30)
            }
        }

        private fun syncExec(runnable: Runnable) {
            synchronized(this) {
                if (ID.get() == id) {
                    runnable.run()
                }
            }
        }
    }

    internal enum class State {
        Scheduled, Connecting, Connected, Disconnected
    }

    companion object {
        private const val TAG = "NtfyWsConnection"
        private val ID = AtomicLong(0)
    }
}
