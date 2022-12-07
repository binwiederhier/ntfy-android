package io.heckel.ntfy.msg

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.heckel.ntfy.util.Log

/**
 * Download attachment in the background via WorkManager
 *
 * The indirection via WorkManager is required since this code may be executed
 * in a doze state and Internet may not be available. It's also best practice, apparently.
 */
object DownloadManager {
    private const val TAG = "NtfyDownloadManager"
    private const val DOWNLOAD_WORK_ATTACHMENT_NAME_PREFIX = "io.heckel.ntfy.DOWNLOAD_FILE_"
    private const val DOWNLOAD_WORK_ICON_NAME_PREFIX = "io.heckel.ntfy.DOWNLOAD_ICON_"
    private const val DOWNLOAD_WORK_BOTH_NAME_PREFIX = "io.heckel.ntfy.DOWNLOAD_BOTH_"

    fun enqueue(context: Context, notificationId: String, userAction: Boolean, type: DownloadType) {
        when (type) {
            DownloadType.ATTACHMENT -> enqueueAttachment(context, notificationId, userAction)
            DownloadType.ICON -> enqueueIcon(context, notificationId)
            DownloadType.BOTH -> enqueueAttachmentAndIcon(context, notificationId, userAction)
        }
    }

    private fun enqueueAttachment(context: Context, notificationId: String, userAction: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val workName = DOWNLOAD_WORK_ATTACHMENT_NAME_PREFIX + notificationId
        Log.d(TAG,"Enqueuing work to download attachment for notification $notificationId, work: $workName")
        val workRequest = OneTimeWorkRequest.Builder(DownloadAttachmentWorker::class.java)
            .setInputData(workDataOf(
                DownloadAttachmentWorker.INPUT_DATA_ID to notificationId,
                DownloadAttachmentWorker.INPUT_DATA_USER_ACTION to userAction
            ))
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
    }

    private fun enqueueIcon(context: Context, notificationId: String) {
        val workManager = WorkManager.getInstance(context)
        val workName = DOWNLOAD_WORK_ICON_NAME_PREFIX + notificationId
        Log.d(TAG,"Enqueuing work to download icon for notification $notificationId, work: $workName")
        val workRequest = OneTimeWorkRequest.Builder(DownloadIconWorker::class.java)
            .setInputData(workDataOf(
                DownloadAttachmentWorker.INPUT_DATA_ID to notificationId
            ))
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
    }

    private fun enqueueAttachmentAndIcon(context: Context, notificationId: String, userAction: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val workName = DOWNLOAD_WORK_BOTH_NAME_PREFIX + notificationId
        val attachmentWorkRequest = OneTimeWorkRequest.Builder(DownloadAttachmentWorker::class.java)
            .setInputData(workDataOf(
                DownloadAttachmentWorker.INPUT_DATA_ID to notificationId,
                DownloadAttachmentWorker.INPUT_DATA_USER_ACTION to userAction
            ))
            .build()
        val iconWorkRequest = OneTimeWorkRequest.Builder(DownloadIconWorker::class.java)
            .setInputData(workDataOf(
                DownloadAttachmentWorker.INPUT_DATA_ID to notificationId
            ))
            .build()
        Log.d(TAG,"Enqueuing work to download both attachment and icon for notification $notificationId, work: $workName")
        workManager.beginUniqueWork(workName, ExistingWorkPolicy.KEEP, attachmentWorkRequest)
            .then(iconWorkRequest)
            .enqueue()
    }

    fun cancel(context: Context, id: String) {
        val workManager = WorkManager.getInstance(context)
        val workName = DOWNLOAD_WORK_ATTACHMENT_NAME_PREFIX + id
        Log.d(TAG, "Cancelling attachment download for notification $id, work: $workName")
        workManager.cancelUniqueWork(workName)
    }
}

enum class DownloadType {
    ATTACHMENT,
    ICON,
    BOTH
}
