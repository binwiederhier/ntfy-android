package io.heckel.ntfy.msg

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.heckel.ntfy.util.Log

/**
 * Trigger user actions clicked from notification popups.
 *
 * The indirection via WorkManager is required since this code may be executed
 * in a doze state and Internet may not be available. It's also best practice, apparently.
 */
object UserActionManager {
    private const val TAG = "NtfyUserActionEx"
    private const val WORK_NAME_PREFIX = "io.heckel.ntfy.USER_ACTION_"

    fun enqueue(context: Context, notificationId: String, actionId: String) {
        val workManager = WorkManager.getInstance(context)
        val workName = WORK_NAME_PREFIX + notificationId + "_" + actionId
        Log.d(TAG,"Enqueuing work to execute user action for notification $notificationId, action $actionId, work: $workName")
        val workRequest = OneTimeWorkRequest.Builder(UserActionWorker::class.java)
            .setInputData(workDataOf(
                UserActionWorker.INPUT_DATA_NOTIFICATION_ID to notificationId,
                UserActionWorker.INPUT_DATA_ACTION_ID to actionId,
            ))
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
    }
}
