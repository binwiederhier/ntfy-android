package io.heckel.ntfy.service

import io.heckel.ntfy.db.ConnectionState
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationParser
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.topicUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.EOFException
import kotlin.random.Random

class JsonConnection(
    private val connectionId: ConnectionId,
    private val scope: CoroutineScope,
    private val repository: Repository,
    private val api: ApiService,
    private val user: User?,
    private val sinceId: String?,
    private val connectionDetailsListener: (Collection<Long>, ConnectionState, Throwable?, Long) -> Unit,
    private val notificationListener: (Subscription, Notification) -> Unit,
    private val serviceActive: () -> Boolean
) : Connection {
    private val baseUrl = connectionId.baseUrl
    private val topicsToSubscriptionIds = connectionId.topicsToSubscriptionIds
    private val subscriptionIds = topicsToSubscriptionIds.values
    private val topicsStr = topicsToSubscriptionIds.keys.joinToString(separator = ",")
    private val url = topicUrl(baseUrl, topicsStr)
    private val parser = NotificationParser()

    private var since: String? = sinceId
    private lateinit var call: Call
    private lateinit var job: Job

    override fun start() {
        job = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "[$url] Starting connection for subscriptions: $topicsToSubscriptionIds")

            var retryMillis = 0L
            while (isActive && serviceActive()) {
                Log.d(TAG, "[$url] (Re-)starting connection for subscriptions: $topicsToSubscriptionIds")
                val startTime = System.currentTimeMillis()
                
                try {
                    val (newCall, source) = api.subscribe(baseUrl, topicsStr, since, user)
                    call = newCall
                    connectionDetailsListener(subscriptionIds, ConnectionState.CONNECTED, null, 0L)
                    
                    // Blocking read loop: reads JSON lines until connection closes or is cancelled
                    while (isActive && serviceActive() && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val notificationWithTopic = parser.parseWithTopic(line, notificationId = Random.nextInt(), subscriptionId = 0)
                        if (notificationWithTopic != null) {
                            since = notificationWithTopic.notification.id
                            val topic = notificationWithTopic.topic
                            val subscriptionId = topicsToSubscriptionIds[topic] ?: continue
                            val subscription = repository.getSubscription(subscriptionId) ?: continue
                            val notification = notificationWithTopic.notification.copy(subscriptionId = subscription.id)
                            notificationListener(subscription, notification)
                        }
                    }
                    
                    // Clean disconnect - reset backoff
                    retryMillis = 0L
                    Log.d(TAG, "[$url] Connection closed cleanly")
                } catch (e: Exception) {
                    if (!isActive) {
                        Log.d(TAG, "[$url] Connection cancelled")
                        break
                    }
                    Log.d(TAG, "[$url] Connection broken, reconnecting ...")
                    retryMillis = nextRetryMillis(retryMillis, startTime)
                    val nextRetryTime = System.currentTimeMillis() + retryMillis
                    val error = if (isConnectionBrokenException(e)) null else e
                    connectionDetailsListener(subscriptionIds, ConnectionState.CONNECTING, error, nextRetryTime)
                    Log.w(TAG, "[$url] Retrying connection in ${retryMillis / 1000}s ...")
                    delay(retryMillis)
                }
            }
            Log.d(TAG, "[$url] Connection job SHUT DOWN")
        }
    }

    override fun since(): String? {
        return since
    }

    override fun close() {
        Log.d(TAG, "[$url] Cancelling connection")
        if (this::job.isInitialized) job.cancel()
        if (this::call.isInitialized) call.cancel()
    }

    private fun nextRetryMillis(retryMillis: Long, startTime: Long): Long {
        val connectionDurationMillis = System.currentTimeMillis() - startTime
        if (connectionDurationMillis > RETRY_RESET_AFTER_MILLIS) {
            return RETRY_STEP_MILLIS
        } else if (retryMillis + RETRY_STEP_MILLIS >= RETRY_MAX_MILLIS) {
            return RETRY_MAX_MILLIS
        }
        return retryMillis + RETRY_STEP_MILLIS
    }

    companion object {
        private const val TAG = "NtfyJsonConnection"
        private const val RETRY_STEP_MILLIS = 5_000L
        private const val RETRY_MAX_MILLIS = 60_000L
        private const val RETRY_RESET_AFTER_MILLIS = 60_000L
    }
}
