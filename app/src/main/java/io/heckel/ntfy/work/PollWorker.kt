package io.heckel.ntfy.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.BroadcastService
import io.heckel.ntfy.msg.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // IMPORTANT WARNING:
    //   Every time the worker is changed, the periodic work has to be REPLACEd.
    //   This is facilitated in the MainActivity using the VERSION below.

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Polling for new notifications")
            val database = Database.getInstance(applicationContext)
            val sharedPrefs = applicationContext.getSharedPreferences(Repository.SHARED_PREFS_ID, Context.MODE_PRIVATE)
            val repository = Repository.getInstance(sharedPrefs, database.subscriptionDao(), database.notificationDao())
            val notifier = NotificationService(applicationContext)
            val broadcaster = BroadcastService(applicationContext)
            val api = ApiService()

            try {
                repository.getSubscriptions().forEach{ subscription ->
                    val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic)
                    val newNotifications = repository
                        .onlyNewNotifications(subscription.id, notifications)
                        .map { it.copy(notificationId = Random.nextInt()) }
                    newNotifications.forEach { notification ->
                        val result = repository.addNotification(notification)
                        if (result.notify) {
                            notifier.send(subscription, notification)
                        }
                        if (result.broadcast) {
                            broadcaster.send(subscription, notification, result.muted)
                        }
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
