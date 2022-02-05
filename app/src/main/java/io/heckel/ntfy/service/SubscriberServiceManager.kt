package io.heckel.ntfy.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.*
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.log.Log

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
    class ServiceStartWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            if (context.applicationContext !is Application) {
                Log.d(TAG, "ServiceStartWorker: Failed, no application found (work ID: ${this.id})")
                return Result.failure()
            }
            val app = context.applicationContext as Application
            val subscriptionIdsWithInstantStatus = app.repository.getSubscriptionIdsWithInstantStatus()
            val instantSubscriptions = subscriptionIdsWithInstantStatus.toList().filter { (_, instant) -> instant }.size
            val action = if (instantSubscriptions > 0) SubscriberService.Action.START else SubscriberService.Action.STOP
            val serviceState = SubscriberService.readServiceState(context)
            if (serviceState == SubscriberService.ServiceState.STOPPED && action == SubscriberService.Action.STOP) {
                return Result.success()
            }
            Log.d(TAG, "ServiceStartWorker: Starting foreground service with action $action (work ID: ${this.id})")
            Intent(context, SubscriberService::class.java).also {
                it.action = action.name
                ContextCompat.startForegroundService(context, it)
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
