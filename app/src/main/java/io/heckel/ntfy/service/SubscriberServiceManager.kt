package io.heckel.ntfy.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This class only manages the SubscriberService, i.e. it starts or stops it.
 * It's used in multiple activities.
 *
 * We are starting the service via a worker and not directly because since Android 7
 * (but officially since Lollipop!), any process called by a BroadcastReceiver
 * (only manifest-declared receiver) is run at low priority and hence eventually
 * killed by Android.
 */
class SubscriberServiceManager(private val context: Context) {
    fun refresh() {
        Log.d(TAG, "Enqueuing work to refresh subscriber service")
        val workManager = WorkManager.getInstance(context)
        val startServiceRequest = OneTimeWorkRequest.Builder(ServiceStartWorker::class.java).build()
        workManager.enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.KEEP, startServiceRequest) // Unique avoids races!
    }

    fun restart() {
        Intent(context, SubscriberService::class.java).also { intent ->
            context.stopService(intent) // Service will auto-restart
        }
    }

    /**
     * Starts or stops the foreground service by figuring out how many instant delivery subscriptions
     * exist. If there's > 0, then we need a foreground service.
     */
    class ServiceStartWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val id = this.id
            if (context.applicationContext !is Application) {
                Log.d(TAG, "ServiceStartWorker: Failed, no application found (work ID: ${id})")
                return Result.failure()
            }
            withContext(Dispatchers.IO) {
                val app = context.applicationContext as Application
                val subscriptionIdsWithInstantStatus = app.repository.getSubscriptionIdsWithInstantStatus()
                val instantSubscriptions = subscriptionIdsWithInstantStatus.toList().filter { (_, instant) -> instant }.size
                if (instantSubscriptions > 0) {
                    // We have instant subscriptions, start the service
                    Log.d(TAG, "ServiceStartWorker: Starting foreground service (work ID: ${id})")
                    try {
                        Intent(context, SubscriberService::class.java).also {
                            it.action = SubscriberService.Action.START.name
                            ContextCompat.startForegroundService(context, it)
                        }
                    } catch (e: Exception) {
                        // On Android 12+, starting a foreground service from the background is restricted.
                        // ForegroundServiceStartNotAllowedException is thrown when the app is in the background.
                        // The service will be started when the user opens the app next time.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                            Log.w(TAG, "ServiceStartWorker: Cannot start foreground service from background (work ID: ${id}): ${e.message}")
                        } else {
                            throw e
                        }
                    }
                } else {
                    // No instant subscriptions, stop the service using stopService()
                    // This avoids ForegroundServiceDidNotStartInTimeException, see #1520
                    Log.d(TAG, "ServiceStartWorker: Stopping service (work ID: ${id})")
                    Intent(context, SubscriberService::class.java).also {
                        context.stopService(it)
                    }
                }
            }
            return Result.success()
        }
    }

    companion object {
        const val TAG = "NtfySubscriberMgr"
        const val WORK_NAME_ONCE = "ServiceStartWorkerOnce"

        fun refresh(context: Context) {
            val manager = SubscriberServiceManager(context)
            manager.refresh()
        }
    }
}
