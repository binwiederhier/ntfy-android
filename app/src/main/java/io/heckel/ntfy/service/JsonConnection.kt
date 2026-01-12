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
    private var errorCount = 0
    private lateinit var call: Call
    private lateinit var job: Job

    override fun start() {
        job = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "[$url] Starting connection for subscriptions: $topicsToSubscriptionIds")

            while (isActive && serviceActive()) {
                Log.d(TAG, "[$url] (Re-)starting connection for subscriptions: $topicsToSubscriptionIds")
                
                try {
                    val (newCall, source) = api.subscribe(baseUrl, topicsStr, since, user)
                    call = newCall
                    if (errorCount > 0) {
                        errorCount = 0
                    }
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
                    
                    Log.d(TAG, "[$url] Connection closed cleanly")
                } catch (e: Exception) {
                    if (!isActive) {
                        Log.d(TAG, "[$url] Connection cancelled")
                        break
                    }
                    Log.d(TAG, "[$url] Connection broken, reconnecting ...")
                    errorCount++
                    val retrySeconds = RETRY_SECONDS.getOrNull(errorCount-1) ?: RETRY_SECONDS.last()
                    val nextRetryTime = System.currentTimeMillis() + (retrySeconds * 1000L)
                    val error = if (isConnectionBrokenException(e)) null else e
                    connectionDetailsListener(subscriptionIds, ConnectionState.CONNECTING, error, nextRetryTime)
                    Log.w(TAG, "[$url] Retrying connection in ${retrySeconds}s ...")
                    delay(retrySeconds * 1000L)
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

    companion object {
        private const val TAG = "NtfyJsonConnection"
        private val RETRY_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 120)
    }
}
