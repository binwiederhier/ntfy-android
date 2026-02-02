package io.heckel.ntfy.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.msg.AccountManager
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker for periodic account synchronization.
 * Extends the auth token and syncs subscriptions from the server.
 */
class AccountSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // IMPORTANT:
    //   Every time the worker is changed, the periodic work has to be REPLACEd.
    //   This is facilitated in the MainActivity using the VERSION below.

    init {
        Log.init(ctx) // Init in all entrypoints
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val accountManager = AccountManager.getInstance(applicationContext)

            if (!accountManager.isLoggedIn()) {
                Log.d(TAG, "Not logged in, skipping account sync")
                return@withContext Result.success()
            }

            Log.d(TAG, "Running account sync worker")

            // Extend token to keep session alive
            try {
                accountManager.extendToken()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extend token: ${e.message}", e)
            }

            // Sync subscriptions from remote
            try {
                accountManager.syncFromRemote()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync subscriptions: ${e.message}", e)
            }

            Log.d(TAG, "Account sync worker completed")
            return@withContext Result.success()
        }
    }

    companion object {
        const val VERSION = BuildConfig.VERSION_CODE
        const val TAG = "NtfyAccountSyncWorker"
        const val WORK_NAME_PERIODIC = "NtfyAccountSyncWorkerPeriodic" // Do not change
    }
}

