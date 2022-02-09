package io.heckel.ntfy.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeleteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // IMPORTANT:
    //   Every time the worker is changed, the periodic work has to be REPLACEd.
    //   This is facilitated in the MainActivity using the VERSION below.

    init {
        Log.init(ctx) // Init in all entrypoints
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Deleting expired notifications")
            val repository = Repository.getInstance(applicationContext)
            val deleteAfterSeconds = repository.getAutoDeleteSeconds()
            if (deleteAfterSeconds == Repository.AUTO_DELETE_NEVER) {
                Log.d(TAG, "Not deleting any notifications; global setting set to NEVER")
                return@withContext Result.success()
            }

            // Mark as deleted
            val markDeletedOlderThanTimestamp = (System.currentTimeMillis()/1000) - deleteAfterSeconds
            Log.d(TAG, "Marking notifications older than $markDeletedOlderThanTimestamp as deleted")
            repository.markAsDeletedIfOlderThan(markDeletedOlderThanTimestamp)

            // Hard delete
            val deleteOlderThanTimestamp = (System.currentTimeMillis()/1000) - HARD_DELETE_AFTER_SECONDS
            Log.d(TAG, "Hard deleting notifications older than $markDeletedOlderThanTimestamp")
            repository.removeNotificationsIfOlderThan(deleteOlderThanTimestamp)
            return@withContext Result.success()
        }
    }

    companion object {
        const val VERSION = BuildConfig.VERSION_CODE
        const val TAG = "NtfyDeleteWorker"
        const val WORK_NAME_PERIODIC_ALL = "NtfyDeleteWorkerPeriodic" // Do not change

        private const val ONE_DAY_SECONDS = 24 * 60 * 60L
        const val HARD_DELETE_AFTER_SECONDS = 4 * 30 * ONE_DAY_SECONDS // 4 months
    }
}
