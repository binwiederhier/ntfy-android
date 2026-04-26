package io.heckel.ntfy.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.*
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.isNetworkAvailable
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
                val workManager = WorkManager.getInstance(context)
                val subscriptionIdsWithInstantStatus = app.repository.getSubscriptionIdsWithInstantStatus()
                val hasNetwork = isNetworkAvailable(context)
                val instantSubscriptions = subscriptionIdsWithInstantStatus.toList().filter { (_, instant) -> instant }.size
                if (instantSubscriptions > 0 && hasNetwork) {
                    // We have instant subscriptions, start the service
                    Log.d(TAG, "ServiceStartWorker: Starting foreground service (work ID: ${id})")
                    Intent(context, SubscriberService::class.java).also {
                        it.action = SubscriberService.Action.START.name
                        try {
                            ContextCompat.startForegroundService(context, it)
                            // Service started successfully: cancel any pending "wait for network"
                            // worker that may have been scheduled during an earlier network outage.
                            workManager.cancelUniqueWork(WORK_NAME_ON_NETWORK_AVAILABLE)
                        } catch (e: Exception) {
                            // ForegroundServiceDidNotStartInTimeException or other exceptions can occur
                            // due to race conditions or system constraints. We log and continue;
                            // the service will be retried on the next refresh() call.
                            Log.w(TAG, "ServiceStartWorker: Failed to start foreground service: ${e.message}")
                        }
                    }
                } else {
                    // No instant subscriptions (or no network), stop the service using stopService()
                    // This avoids ForegroundServiceDidNotStartInTimeException, see #1520
                    Log.d(TAG, "ServiceStartWorker: Stopping service (work ID: ${id})")
                    if (!hasNetwork) {
                        app.repository.clearConnectionDetails()
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(SubscriberService.NOTIFICATION_CONNECTION_ALERT_ID)
                        // Schedule a one-time, network-constrained worker that restarts the service
                        // as soon as network comes back. This works even if the process has been
                        // killed by the OS while the service was stopped, because WorkManager /
                        // JobScheduler is tied to the platform's connectivity monitor. Without this,
                        // recovery when offline->online happens after process death would only occur
                        // via the periodic NtfyAutoRestartWorkerPeriodic worker (up to ~30 minutes).
                        // Doze mode may still defer the job until the next maintenance window, but
                        // users with instant delivery are prompted to disable battery optimization.
                        if (instantSubscriptions > 0) {
                            val networkConstraints = Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                            val onNetworkRequest = OneTimeWorkRequest.Builder(ServiceStartWorker::class.java)
                                .setConstraints(networkConstraints)
                                .build()
                            workManager.enqueueUniqueWork(WORK_NAME_ON_NETWORK_AVAILABLE, ExistingWorkPolicy.REPLACE, onNetworkRequest)
                        }
                    }
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
        const val WORK_NAME_ON_NETWORK_AVAILABLE = "ServiceStartWorkerOnNetworkAvailable"

        fun refresh(context: Context) {
            val manager = SubscriberServiceManager(context)
            manager.refresh()
        }
    }
}
