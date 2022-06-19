package io.heckel.ntfy.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // IMPORTANT:
    //   Every time the worker is changed, the periodic work has to be REPLACEd.
    //   This is facilitated in the MainActivity using the VERSION below.

    init {
        Log.init(ctx) // Init in all entrypoints
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Polling for new notifications")
            val repository = Repository.getInstance(applicationContext)
            val dispatcher = NotificationDispatcher(applicationContext, repository)
            val api = ApiService()

            val baseUrl = inputData.getString(INPUT_DATA_BASE_URL)
            val topic = inputData.getString(INPUT_DATA_TOPIC)
            val subscriptions = if (baseUrl != null && topic != null) {
                val subscription = repository.getSubscription(baseUrl, topic) ?: return@withContext Result.success()
                listOf(subscription)
            } else {
                repository.getSubscriptions()
            }

            subscriptions.forEach{ subscription ->
                try {
                    val user = repository.getUser(subscription.baseUrl)
                    val notifications = api.poll(
                        subscriptionId = subscription.id,
                        baseUrl = subscription.baseUrl,
                        topic = subscription.topic,
                        user = user,
                        since = subscription.lastNotificationId
                    )
                    val newNotifications = repository
                        .onlyNewNotifications(subscription.id, notifications)
                        .map { it.copy(notificationId = Random.nextInt()) }
                    newNotifications.forEach { notification ->
                        if (repository.addNotification(notification)) {
                            dispatcher.dispatch(subscription, notification)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed checking messages: ${e.message}", e)
                }
            }
            Log.d(TAG, "Finished polling for new notifications")
            return@withContext Result.success()
        }
    }

    companion object {
        const val VERSION =  BuildConfig.VERSION_CODE
        const val TAG = "NtfyPollWorker"
        const val WORK_NAME_PERIODIC_ALL = "NtfyPollWorkerPeriodic" // Do not change
        const val WORK_NAME_ONCE_SINGE_PREFIX = "NtfyPollWorkerSingle" // e.g. NtfyPollWorkerSingle_https://ntfy.sh_mytopic
        const val INPUT_DATA_BASE_URL = "baseUrl"
        const val INPUT_DATA_TOPIC = "topic"
    }
}
