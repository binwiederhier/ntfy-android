package io.heckel.ntfy.firebase

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.msg.*
import io.heckel.ntfy.util.toPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

class FirebaseService : FirebaseMessagingService() {
    private val repository by lazy { (application as Application).repository }
    private val dispatcher by lazy { NotificationDispatcher(this, repository) }
    private val job = SupervisorJob()
    private val messenger = FirebaseMessenger()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // We only process data messages
        if (remoteMessage.data.isEmpty()) {
            Log.d(TAG, "Discarding unexpected message (1): from=${remoteMessage.from}")
            return
        }

        // Dispatch event
        val data = remoteMessage.data
        when (data["event"]) {
            ApiService.EVENT_KEEPALIVE -> handleKeepalive(remoteMessage)
            ApiService.EVENT_MESSAGE -> handleMessage(remoteMessage)
            else -> Log.d(TAG, "Discarding unexpected message (2): from=${remoteMessage.from}, data=${data}")
        }
    }

    private fun handleKeepalive(remoteMessage: RemoteMessage) {
        Log.d(TAG, "Keepalive received, sending auto restart broadcast for foregrounds service")
        sendBroadcast(Intent(this, SubscriberService.AutoRestartReceiver::class.java)) // Restart it if necessary!
        val topic = remoteMessage.data["topic"]
        if (topic != ApiService.CONTROL_TOPIC) {
            Log.d(TAG, "Keepalive on non-control topic $topic received, subscribing to control topic ${ApiService.CONTROL_TOPIC}")
            messenger.subscribe(ApiService.CONTROL_TOPIC)
        }
    }

    private fun handleMessage(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val id = data["id"]
        val timestamp = data["time"]?.toLongOrNull()
        val topic = data["topic"]
        val title = data["title"]
        val message = data["message"]
        val priority = data["priority"]?.toIntOrNull()
        val tags = data["tags"]
        if (id == null || topic == null || message == null || timestamp == null) {
            Log.d(TAG, "Discarding unexpected message: from=${remoteMessage.from}, data=${data}")
            return
        }
        Log.d(TAG, "Received notification: from=${remoteMessage.from}, data=${data}")

        CoroutineScope(job).launch {
            val baseUrl = getString(R.string.app_base_url) // Everything from Firebase comes from main service URL!

            // Add notification
            val subscription = repository.getSubscription(baseUrl, topic) ?: return@launch
            val notification = Notification(
                id = id,
                subscriptionId = subscription.id,
                timestamp = timestamp,
                title = title ?: "",
                message = message,
                notificationId = Random.nextInt(),
                priority = toPriority(priority),
                tags = tags ?: "",
                deleted = false
            )
            if (repository.addNotification(notification)) {
                Log.d(TAG, "Dispatching notification for message: from=${remoteMessage.from}, data=${data}")
                dispatcher.dispatch(subscription, notification)
            }
        }
    }

    override fun onNewToken(token: String) {
        // Called if the FCM registration token is updated
        // We don't actually use or care about the token, since we're using topics
        Log.d(TAG, "Registration token was updated: $token")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        private const val TAG = "NtfyFirebase"
    }
}
