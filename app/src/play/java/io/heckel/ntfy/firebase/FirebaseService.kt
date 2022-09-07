package io.heckel.ntfy.firebase

import android.content.Intent
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Attachment
import io.heckel.ntfy.db.Icon
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.msg.NotificationParser
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.util.toPriority
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.work.PollWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

class FirebaseService : FirebaseMessagingService() {
    private val repository by lazy { (application as Application).repository }
    private val dispatcher by lazy { NotificationDispatcher(this, repository) }
    private val job = SupervisorJob()
    private val messenger = FirebaseMessenger()
    private val parser = NotificationParser()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Init log (this is done in all entrypoints)
        Log.init(this)

        // We only process data messages
        if (remoteMessage.data.isEmpty()) {
            Log.d(TAG, "Discarding unexpected message (1): from=${remoteMessage.from}")
            return
        }

        // Dispatch event
        val data = remoteMessage.data
        when (data["event"]) {
            ApiService.EVENT_MESSAGE -> handleMessage(remoteMessage)
            ApiService.EVENT_KEEPALIVE -> handleKeepalive(remoteMessage)
            ApiService.EVENT_POLL_REQUEST -> handlePollRequest(remoteMessage)
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

    private fun handlePollRequest(remoteMessage: RemoteMessage) {
        val baseUrl = getString(R.string.app_base_url) // Everything from Firebase comes from main service URL!
        val topic = remoteMessage.data["topic"] ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workName = "${PollWorker.WORK_NAME_ONCE_SINGE_PREFIX}_${baseUrl}_${topic}"
        val workManager = WorkManager.getInstance(this)
        val workRequest = OneTimeWorkRequest.Builder(PollWorker::class.java)
            .setInputData(workDataOf(
                PollWorker.INPUT_DATA_BASE_URL to baseUrl,
                PollWorker.INPUT_DATA_TOPIC to topic
            ))
            .setConstraints(constraints)
            .build()
        Log.d(TAG, "Poll request for ${topicShortUrl(baseUrl, topic)} received, scheduling unique poll worker with name $workName")

        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workRequest)
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
        val click = data["click"]
        val iconUrl = data["icon"]
        val actions = data["actions"] // JSON array as string, sigh ...
        val encoding = data["encoding"]
        val attachmentName = data["attachment_name"] ?: "attachment.bin"
        val attachmentType = data["attachment_type"]
        val attachmentSize = data["attachment_size"]?.toLongOrNull()
        val attachmentExpires = data["attachment_expires"]?.toLongOrNull()
        val attachmentUrl = data["attachment_url"]
        val truncated = (data["truncated"] ?: "") == "1"
        if (id == null || topic == null || message == null || timestamp == null) {
            Log.d(TAG, "Discarding unexpected message: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}, data=${data}")
            return
        }
        Log.d(TAG, "Received message: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}, data=${data}")

        CoroutineScope(job).launch {
            val baseUrl = getString(R.string.app_base_url) // Everything from Firebase comes from main service URL!

            // Check if notification was truncated and discard if it will (or likely already did) arrive via instant delivery
            val subscription = repository.getSubscription(baseUrl, topic) ?: return@launch
            if (truncated && subscription.instant) {
                Log.d(TAG, "Discarding truncated message that did/will arrive via instant delivery: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}, data=${data}")
                return@launch
            }

            // Add notification
            val attachment = if (attachmentUrl != null) {
                Attachment(
                    name = attachmentName,
                    type = attachmentType,
                    size = attachmentSize,
                    expires = attachmentExpires,
                    url = attachmentUrl,
                )
            } else null
            val icon: Icon? = iconUrl?.let { Icon(url = it) }
            val notification = Notification(
                id = id,
                subscriptionId = subscription.id,
                timestamp = timestamp,
                title = title ?: "",
                message = message,
                encoding = encoding ?: "",
                priority = toPriority(priority),
                tags = tags ?: "",
                click = click ?: "",
                icon = icon,
                actions = parser.parseActions(actions),
                attachment = attachment,
                notificationId = Random.nextInt(),
                deleted = false
            )
            if (repository.addNotification(notification)) {
                Log.d(TAG, "Dispatching notification: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}, data=${data}")
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
