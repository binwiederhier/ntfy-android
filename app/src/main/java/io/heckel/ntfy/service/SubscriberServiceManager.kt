package io.heckel.ntfy.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.up.BroadcastReceiver

/**
 * This class only manages the SubscriberService, i.e. it starts or stops it.
 * It's used in multiple activities.
 */
class SubscriberServiceManager(private val context: Context) {
    fun refresh() {
        Log.d(TAG, "Enqueuing work to refresh subscriber service")
        val workManager = WorkManager.getInstance(context)
        val startServiceRequest = OneTimeWorkRequest.Builder(RefreshWorker::class.java).build()
        workManager.enqueue(startServiceRequest)
    }

    class RefreshWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            if (context.applicationContext !is Application) {
                Log.d(TAG, "RefreshWorker: Failed, no application found (work ID: ${this.id})")
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
            Log.d(TAG, "RefreshWorker: Starting foreground service with action $action (work ID: ${this.id})")
            Intent(context, SubscriberService::class.java).also {
                it.action = action.name
                ContextCompat.startForegroundService(context, it)
            }
            return Result.success()
        }
    }

    companion object {
        const val TAG = "NtfySubscriberMgr"

        fun refresh(context: Context) {
            val manager = SubscriberServiceManager(context)
            manager.refresh()
        }
    }
}
