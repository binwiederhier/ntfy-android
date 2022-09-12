package io.heckel.ntfy.work

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.db.ATTACHMENT_PROGRESS_DELETED
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.DownloadIconWorker
import io.heckel.ntfy.ui.DetailAdapter
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.fileStat
import io.heckel.ntfy.util.topicShortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * Deletes notifications marked for deletion and attachments for deleted notifications.
 */
class DeleteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // IMPORTANT:
    //   Every time the worker is changed, the periodic work has to be REPLACEd.
    //   This is facilitated in the MainActivity using the VERSION below.

    init {
        Log.init(ctx) // Init in all entrypoints
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            deleteExpiredIcons() // Before notifications, so we will also catch manually deleted notifications
            deleteExpiredAttachments() // Before notifications, so we will also catch manually deleted notifications
            deleteExpiredNotifications()
            return@withContext Result.success()
        }
    }

    private fun deleteExpiredAttachments() {
        Log.d(TAG, "Deleting attachments for deleted notifications")
        val resolver = applicationContext.contentResolver
        val repository = Repository.getInstance(applicationContext)
        val notifications = repository.getDeletedNotificationsWithAttachments()
        notifications.forEach { notification ->
            try {
                val attachment = notification.attachment ?: return
                val contentUri = Uri.parse(attachment.contentUri ?: return)
                Log.d(TAG, "Deleting attachment for notification ${notification.id}: ${attachment.contentUri} (${attachment.name})")
                val deleted = resolver.delete(contentUri, null, null) > 0
                if (!deleted) {
                    Log.w(TAG, "Unable to delete attachment for notification ${notification.id}")
                }
                val newAttachment = attachment.copy(
                    contentUri = null,
                    progress = ATTACHMENT_PROGRESS_DELETED
                )
                val newNotification = notification.copy(attachment = newAttachment)
                repository.updateNotification(newNotification)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete attachment for notification: ${e.message}", e)
            }
        }
    }

    private fun deleteExpiredIcons() {
        Log.d(TAG, "Deleting icons for deleted notifications")
        val repository = Repository.getInstance(applicationContext)
        val activeIconUris = repository.getActiveIconUris()
        val activeIconFilenames = activeIconUris.map{ fileStat(applicationContext, Uri.parse(it)).filename }.toSet()
        val iconDir = File(applicationContext.cacheDir, DownloadIconWorker.ICON_CACHE_DIR)
        val allIconFilenames = iconDir.listFiles()?.map{ file -> file.name }.orEmpty()
        val filenamesToDelete = allIconFilenames.minus(activeIconFilenames)
        filenamesToDelete.forEach { filename ->
            try {
                val file = File(iconDir, filename)
                val deleted = file.delete()
                if (!deleted) {
                    Log.w(TAG, "Unable to delete icon: $filename")
                }
                val uri = FileProvider.getUriForFile(applicationContext,
                    DownloadIconWorker.FILE_PROVIDER_AUTHORITY, file).toString()
                repository.clearIconUri(uri)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete icon: ${e.message}", e)
            }
        }
    }

    private suspend fun deleteExpiredNotifications() {
        Log.d(TAG, "Deleting expired notifications")
        val repository = Repository.getInstance(applicationContext)
        val subscriptions = repository.getSubscriptions()
        subscriptions.forEach { subscription ->
            val logId = topicShortUrl(subscription.baseUrl, subscription.topic)
            val deleteAfterSeconds = if (subscription.autoDelete == Repository.AUTO_DELETE_USE_GLOBAL) {
                repository.getAutoDeleteSeconds()
            } else {
                subscription.autoDelete
            }
            if (deleteAfterSeconds == Repository.AUTO_DELETE_NEVER) {
                Log.d(TAG, "[$logId] Not deleting any notifications; global setting set to NEVER")
                return@forEach
            }

            // Mark as deleted
            val markDeletedOlderThanTimestamp = (System.currentTimeMillis()/1000) - deleteAfterSeconds
            Log.d(TAG, "[$logId] Marking notifications older than $markDeletedOlderThanTimestamp as deleted")
            repository.markAsDeletedIfOlderThan(subscription.id, markDeletedOlderThanTimestamp)

            // Hard delete
            val deleteOlderThanTimestamp = (System.currentTimeMillis()/1000) - HARD_DELETE_AFTER_SECONDS
            Log.d(TAG, "[$logId] Hard deleting notifications older than $markDeletedOlderThanTimestamp")
            repository.removeNotificationsIfOlderThan(subscription.id, deleteOlderThanTimestamp)
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
