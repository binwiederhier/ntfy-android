package io.heckel.ntfy.service

import android.app.AlarmManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.heckel.ntfy.db.*
import io.heckel.ntfy.msg.ApiService.Companion.requestBuilder
import io.heckel.ntfy.msg.NotificationParser
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.util.topicUrlWs
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    private val connectionId: ConnectionId,
    private val repository: Repository,
    private val user: User?,
    private val sinceId: String?,
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

    private val globalId = GLOBAL_ID.incrementAndGet()
    private val listenerId = AtomicLong(0)

    private val since = AtomicReference<String?>(sinceId)
    private val baseUrl = connectionId.baseUrl
    private val topicsToSubscriptionIds = connectionId.topicsToSubscriptionIds
    private val topicIsUnifiedPush = connectionId.topicIsUnifiedPush
    private val subscriptionIds = topicsToSubscriptionIds.values
    private val topicsStr = topicsToSubscriptionIds.keys.joinToString(separator = ",")
    private val unifiedPushTopicsStr = topicIsUnifiedPush.filter { entry -> entry.value }.keys.joinToString(separator = ",")
    private val shortUrl = topicShortUrl(baseUrl, topicsStr)

    init {
        Log.d(TAG, "$shortUrl (gid=$globalId): New connection with global ID $globalId")
    }

    @Synchronized
    override fun start() {
        if (closed || state == State.Connecting || state == State.Connected) {
            Log.d(TAG,"$shortUrl (gid=$globalId): Not (re-)starting, because connection is marked closed/connecting/connected")
            return
        }
        if (webSocket != null) {
            webSocket!!.close(WS_CLOSE_NORMAL, "")
        }
        state = State.Connecting
        val nextListenerId = listenerId.incrementAndGet()
        val sinceId = since.get()
        val sinceVal = sinceId ?: "all"
        val urlWithSince = topicUrlWs(baseUrl, topicsStr, sinceVal)
        val request = requestBuilder(urlWithSince, user, unifiedPushTopicsStr).build()
        Log.d(TAG, "$shortUrl (gid=$globalId): Opening $urlWithSince with listener ID $nextListenerId ...")
        webSocket = client.newWebSocket(request, Listener(nextListenerId))
    }

    @Synchronized
    override fun close() {
        closed = true
        if (webSocket == null) {
            Log.d(TAG,"$shortUrl (gid=$globalId): Not closing existing connection, because there is no active web socket")
            return
        }
        Log.d(TAG, "$shortUrl (gid=$globalId): Closing connection")
        state = State.Disconnected
        webSocket!!.close(WS_CLOSE_NORMAL, "")
        webSocket = null
    }

    @Synchronized
    override fun since(): String? {
        return since.get()
    }

    @Synchronized
    fun scheduleReconnect(seconds: Int) {
        if (closed || state == State.Connecting || state == State.Connected) {
            Log.d(TAG,"$shortUrl (gid=$globalId): Not rescheduling connection, because connection is marked closed/connecting/connected")
            return
        }
        state = State.Scheduled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG,"$shortUrl (gid=$globalId): Scheduling a restart in $seconds seconds (via alarm manager)")
            val reconnectTime = Calendar.getInstance()
            reconnectTime.add(Calendar.SECOND, seconds)
            if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    reconnectTime.timeInMillis,
                    RECONNECT_TAG,
                    { start() },
                    null
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    reconnectTime.timeInMillis,
                    RECONNECT_TAG,
                    { start() },
                    null
                )
            }
        } else {
            Log.d(TAG, "$shortUrl (gid=$globalId): Scheduling a restart in $seconds seconds (via handler)")
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({ start() }, TimeUnit.SECONDS.toMillis(seconds.toLong()))
        }
    }

    private inner class Listener(private val id: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronize("onOpen") {
                Log.d(TAG, "$shortUrl (gid=$globalId, lid=$id): Opened connection")
                state = State.Connected
                if (errorCount > 0) {
                    errorCount = 0
                }
                stateChangeListener(subscriptionIds, ConnectionState.CONNECTED)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronize("onMessage") {
                Log.d(TAG, "$shortUrl (gid=$globalId, lid=$id): Received message: $text")
                val notificationWithTopic = parser.parseWithTopic(text, subscriptionId = 0, notificationId = Random.nextInt())
                if (notificationWithTopic == null) {
                    Log.d(TAG, "$shortUrl (gid=$globalId, lid=$id): Irrelevant or unknown message. Discarding.")
                    return@synchronize
                }
                val topic = notificationWithTopic.topic
                val notification = notificationWithTopic.notification
                val subscriptionId = topicsToSubscriptionIds[topic] ?: return@synchronize
                val subscription = repository.getSubscription(subscriptionId) ?: return@synchronize
                val notificationWithSubscriptionId = notification.copy(subscriptionId = subscription.id)
                notificationListener(subscription, notificationWithSubscriptionId)
                since.set(notification.id)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronize("onClosed") {
                Log.w(TAG, "$shortUrl (gid=$globalId, lid=$id): Closed connection")
                state = State.Disconnected
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronize("onFailure") {
                if (response == null) {
                    Log.e(TAG, "$shortUrl (gid=$globalId, lid=$id): Connection failed (response is null): ${t.message}", t)
                } else {
                    Log.e(TAG, "$shortUrl (gid=$globalId, lid=$id): Connection failed (response code ${response.code}, message: ${response.message}): ${t.message}", t)
                }
                if (closed) {
                    Log.d(TAG, "$shortUrl (gid=$globalId, lid=$id): Connection marked as closed. Not retrying.")
                    return@synchronize
                }
                stateChangeListener(subscriptionIds, ConnectionState.CONNECTING)
                state = State.Disconnected
                errorCount++
                val retrySeconds = RETRY_SECONDS.getOrNull(errorCount) ?: RETRY_SECONDS.last()
                scheduleReconnect(retrySeconds)
            }
        }

        private fun synchronize(tag: String, fn: () -> Unit) {
            synchronized(this) {
                if (listenerId.get() == id) {
                    fn()
                } else {
                    Log.w(TAG, "$shortUrl (gid=$globalId, lid=$id): Skipping synchronized block '$tag', because listener ID does not match ${listenerId.get()}")
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
        private val GLOBAL_ID = AtomicLong(0)
    }
}
