package io.heckel.ntfy.service

import android.app.AlarmManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.heckel.ntfy.db.ConnectionState
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.log.Log
import io.heckel.ntfy.msg.NotificationParser
import io.heckel.ntfy.util.topicUrl
import io.heckel.ntfy.util.topicUrlWs
import okhttp3.*
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Connect to ntfy server via WebSockets. This connection represents a single connection to a server, with
 * one or more topics. When the topics are changed, the connection is recreated by the service.
 *
 * The connection re-connects on failure, indefinitely. It reports limited status via the stateChangeListener,
 * and forwards incoming messages via the notificationListener.
 *
 * The original class is taken from the fantastic Gotify project (MIT). Thank you:
 * https://github.com/gotify/android/blob/master/app/src/main/java/com/github/gotify/service/WebSocketConnection.java
 */
class WsConnection(
    private val repository: Repository,
    private val baseUrl: String,
    private val sinceTime: Long,
    private val topicsToSubscriptionIds: Map<String, Long>, // Topic -> Subscription ID
    private val stateChangeListener: (Collection<Long>, ConnectionState) -> Unit,
    private val notificationListener: (Subscription, Notification) -> Unit,
    private val alarmManager: AlarmManager
) : Connection {
    private val parser = NotificationParser()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(1, TimeUnit.MINUTES) // The server pings us too, so this doesn't matter much
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private var errorCount = 0
    private var webSocket: WebSocket? = null
    private var state: State? = null
    private var closed = false

    private var since: Long = sinceTime
    private val subscriptionIds = topicsToSubscriptionIds.values
    private val topicsStr = topicsToSubscriptionIds.keys.joinToString(separator = ",")
    private val url = topicUrl(baseUrl, topicsStr)

    @Synchronized
    override fun start() {
        if (closed || state == State.Connecting || state == State.Connected) {
            return
        }
        if (webSocket != null) {
            webSocket!!.close(WS_CLOSE_NORMAL, "")
        }
        state = State.Connecting
        val nextId = ID.incrementAndGet()
        val sinceVal = if (since == 0L) "all" else since.toString()
        val urlWithSince = topicUrlWs(baseUrl, topicsStr, sinceVal)
        val request = Request.Builder().url(urlWithSince).get().build()
        Log.d(TAG, "[$url] WebSocket($nextId): opening $urlWithSince ...")
        webSocket = client.newWebSocket(request, Listener(nextId))
    }

    @Synchronized
    override fun close() {
        closed = true
        if (webSocket == null) {
            return
        }
        Log.d(TAG, "[$url] WebSocket(${ID.get()}): closing existing connection")
        state = State.Disconnected
        webSocket!!.close(WS_CLOSE_NORMAL, "")
        webSocket = null
    }

    @Synchronized
    override fun since(): Long {
        return since
    }

    override fun matches(otherSubscriptionIds: Collection<Long>): Boolean {
        return subscriptionIds.toSet() == otherSubscriptionIds.toSet()
    }

    @Synchronized
    fun scheduleReconnect(seconds: Int) {
        if (closed || state == State.Connecting || state == State.Connected) {
            return
        }
        state = State.Scheduled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG,"[$url] WebSocket: Scheduling a restart in $seconds seconds (via alarm manager)")
            val reconnectTime = Calendar.getInstance()
            reconnectTime.add(Calendar.SECOND, seconds)
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, reconnectTime.timeInMillis, RECONNECT_TAG, { start() }, null)
        } else {
            Log.d(TAG, "[$url] WebSocket: Scheduling a restart in $seconds seconds (via handler)")
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({ start() }, TimeUnit.SECONDS.toMillis(seconds.toLong()))
        }
    }

    private inner class Listener(private val id: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            syncExec {
                Log.d(TAG, "[$url] WebSocket($id): opened")
                state = State.Connected
                if (errorCount > 0) {
                    errorCount = 0
                }
                stateChangeListener(subscriptionIds, ConnectionState.CONNECTED)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            syncExec {
                Log.d(TAG, "[$url] WebSocket($id): received message: $text")
                val notificationWithTopic = parser.parseWithTopic(text, subscriptionId = 0, notificationId = Random.nextInt())
                if (notificationWithTopic == null) {
                    return@syncExec
                }
                val topic = notificationWithTopic.topic
                val notification = notificationWithTopic.notification
                val subscriptionId = topicsToSubscriptionIds[topic] ?: return@syncExec
                val subscription = repository.getSubscription(subscriptionId) ?: return@syncExec
                val notificationWithSubscriptionId = notification.copy(subscriptionId = subscription.id)
                notificationListener(subscription, notificationWithSubscriptionId)
                since = notification.timestamp
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            syncExec {
                Log.w(TAG, "[$url] WebSocket($id): closed")
                state = State.Disconnected
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "[$url] WebSocket($id): failure ${response?.code}: ${response?.message}", t)
            syncExec {
                if (closed) {
                    Log.d(TAG, "WebSocket($id): Connection marked as closed. Not retrying.")
                    return@syncExec
                }
                stateChangeListener(subscriptionIds, ConnectionState.CONNECTING)
                state = State.Disconnected
                errorCount++
                val retrySeconds = RETRY_SECONDS.getOrNull(errorCount) ?: RETRY_SECONDS.last()
                scheduleReconnect(retrySeconds)
            }
        }

        private fun syncExec(fn: () -> Unit) {
            synchronized(this) {
                if (ID.get() == id) {
                    fn()
                }
            }
        }
    }

    internal enum class State {
        Scheduled, Connecting, Connected, Disconnected
    }

    companion object {
        private const val TAG = "NtfyWsConnection"
        private const val RECONNECT_TAG = "WsReconnect"
        private const val WS_CLOSE_NORMAL = 1000
        private val RETRY_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 120)
        private val ID = AtomicLong(0)
    }
}
