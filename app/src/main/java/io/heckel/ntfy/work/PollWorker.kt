package io.heckel.ntfy.work

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // Every time the worker is changed, the periodic work has to be REPLACEd.
    // This is facilitated in the MainActivity using the VERSION below.

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Polling for new notifications")
            val database = Database.getInstance(applicationContext)
            val repository = Repository.getInstance(database.subscriptionDao(), database.notificationDao())
            val notifier = NotificationService(applicationContext)
            val api = ApiService()

            try {
                repository.getSubscriptions().forEach{ subscription ->
                    val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic)
                    val newNotifications = repository.onlyNewNotifications(subscription.id, notifications)
                    newNotifications.forEach { notification ->
                        repository.addNotification(subscription.id, notification)
                        notifier.send(subscription, notification.message)
                    }
                }
                Log.d(TAG, "Finished polling for new notifications")
                return@withContext Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Failed checking messages: ${e.message}", e)
                return@withContext Result.failure()
            }
        }
    }

    companion object {
        const val VERSION = BuildConfig.VERSION_CODE
        const val TAG = "NtfyPollWorker"
        const val WORK_NAME_PERIODIC = "NtfyPollWorkerPeriodic"
    }
}
