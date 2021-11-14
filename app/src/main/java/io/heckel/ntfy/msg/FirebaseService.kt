package io.heckel.ntfy.msg

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Notification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FirebaseService : FirebaseMessagingService() {
    private val repository by lazy { (application as Application).repository }
    private val job = SupervisorJob()
    private val notifier = NotificationService(this)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // We only process data messages
        if (remoteMessage.data.isEmpty()) {
            Log.d(TAG, "Discarding unexpected message: from=${remoteMessage.from}")
            return
        }

        // Check if valid data, and send notification
        val data = remoteMessage.data
        val id = data["id"]
        val timestamp = data["time"]?.toLongOrNull()
        val topic = data["topic"]
        val message = data["message"]
        if (id == null || topic == null || message == null || timestamp == null) {
            Log.d(TAG, "Discarding unexpected message: from=${remoteMessage.from}, data=${data}")
            return
        }

        CoroutineScope(job).launch {
            val baseUrl = getString(R.string.app_base_url) // Everything from Firebase comes from main service URL!

            // Add notification
            val subscription = repository.getSubscription(baseUrl, topic) ?: return@launch
            val notification = Notification(id = id, subscriptionId = subscription.id, timestamp = timestamp, message = message, deleted = false)
            val added = repository.addNotification(notification)

            // Send notification (only if it's not already known)
            if (added) {
                Log.d(TAG, "Sending notification for message: from=${remoteMessage.from}, data=${data}")
                notifier.send(subscription, message)
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
