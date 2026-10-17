package io.heckel.ntfy.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.heckel.ntfy.account.AccountManager
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Syncs subscriptions with the ntfy account (see AccountManager.sync), periodically and shortly
 * after topics are added, removed or renamed on this phone. Does nothing when logged out.
 */
class AccountSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    init {
        Log.init(ctx) // Init in all entrypoints
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val result = AccountManager.getInstance(applicationContext).sync()
                Log.d(TAG, "Account sync finished: $result")
            } catch (e: Exception) {
                Log.w(TAG, "Account sync failed, retrying with the next run", e)
            }
            Result.success()
        }
    }

    companion object {
        const val TAG = "NtfyAccountSyncWorker"
        private const val WORK_NAME_PERIODIC = "NtfyAccountSyncWorkerPeriodic" // Do not change
        private const val WORK_NAME_ONCE = "NtfyAccountSyncWorkerOnce" // Do not change
        private const val PERIODIC_INTERVAL_MINUTES = 60L
        private const val ONCE_DELAY_SECONDS = 3L // Groups quick changes (e.g. deleting several topics) into one sync

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            if (!AccountManager.getInstance(context).loggedIn()) return
            val work = PeriodicWorkRequestBuilder<AccountSyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        /**
         * Syncs soon, after a local change. Appends to a running sync, so changes made while it
         * runs aren't missed.
         */
        fun syncSoon(context: Context) {
            if (!AccountManager.getInstance(context).loggedIn()) return
            val work = OneTimeWorkRequestBuilder<AccountSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(ONCE_DELAY_SECONDS, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
            workManager.cancelUniqueWork(WORK_NAME_ONCE)
        }
    }
}
