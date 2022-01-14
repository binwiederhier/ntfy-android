package io.heckel.ntfy.msg

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Download attachment in the background via WorkManager
 *
 * The indirection via WorkManager is required since this code may be executed
 * in a doze state and Internet may not be available. It's also best practice apparently.
 */
class DownloadManager {
    companion object {
        private const val TAG = "NtfyDownloadManager"
        private const val DOWNLOAD_WORK_NAME_PREFIX = "io.heckel.ntfy.DOWNLOAD_FILE_"

        fun enqueue(context: Context, id: String, userAction: Boolean) {
            val workManager = WorkManager.getInstance(context)
            val workName = DOWNLOAD_WORK_NAME_PREFIX + id
            Log.d(TAG,"Enqueuing work to download attachment for notification $id, work: $workName")
            val workRequest = OneTimeWorkRequest.Builder(DownloadWorker::class.java)
                .setInputData(workDataOf(
                    DownloadWorker.INPUT_DATA_ID to id,
                    DownloadWorker.INPUT_DATA_USER_ACTION to userAction
                ))
                .build()
            workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
        }

        fun cancel(context: Context, id: String) {
            val workManager = WorkManager.getInstance(context)
            val workName = DOWNLOAD_WORK_NAME_PREFIX + id
            Log.d(TAG, "Cancelling download for notification $id, work: $workName")
            workManager.cancelUniqueWork(workName)
        }

    }
}
