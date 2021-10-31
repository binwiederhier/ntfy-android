package io.heckel.ntfy.msg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.data.topicShortUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.*
import kotlin.random.Random

class MessagingService : FirebaseMessagingService() {
    private val database by lazy { Database.getInstance(this) }
    private val repository by lazy { Repository.getInstance(database.subscriptionDao(), database.notificationDao()) }
    private val job = SupervisorJob()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // We only process data messages
        if (remoteMessage.data.isEmpty()) {
            Log.d(TAG, "Discarding unexpected message: from=${remoteMessage.from}")
            return
        }

        // Check if valid data, and send notification
        val data = remoteMessage.data
        val timestamp = data["time"]?.toLongOrNull()
        val topic = data["topic"]
        val message = data["message"]
        if (topic == null || message == null || timestamp == null) {
            Log.d(TAG, "Discarding unexpected message: from=${remoteMessage.from}, data=${data}")
            return
        }

        CoroutineScope(job).launch {
            val baseUrl = getString(R.string.app_base_url) // Everything from Firebase comes from main service URL!

            // Update message counter
            val subscription = repository.getSubscription(baseUrl, topic) ?: return@launch
            val newSubscription = subscription.copy(notifications = subscription.notifications + 1, lastActive = Date().time/1000)
            repository.updateSubscription(newSubscription)

            // Add notification
            val notification = Notification(id = Random.nextLong(), subscriptionId = subscription.id, timestamp = timestamp, message = message)
            repository.addNotification(notification)

            // Send notification
            Log.d(TAG, "Sending notification for message: from=${remoteMessage.from}, data=${data}")
            val title = topicShortUrl(baseUrl, topic)
            sendNotification(title, message)
        }
    }

    override fun onNewToken(token: String) {
        // Called if the FCM registration token is updated
        // We don't actually use or care about the token, since we're using topics
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun sendNotification(title: String, message: String) {
        val channelId = getString(R.string.notification_channel_id)
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setSound(defaultSoundUri)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.notification_channel_name)
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
        notificationManager.notify(Random.nextInt(), notificationBuilder.build())
    }

    companion object {
        private const val TAG = "NtfyFirebase"
    }
}
