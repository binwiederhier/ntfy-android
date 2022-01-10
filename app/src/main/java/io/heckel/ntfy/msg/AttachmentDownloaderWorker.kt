package io.heckel.ntfy.msg

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AttachmentDownloadWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES) // Total timeout for entire request
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val notifier = NotificationService(context)

    override fun doWork(): Result {
        if (context.applicationContext !is Application) return Result.failure()
        val notificationId = inputData.getString("id") ?: return Result.failure()
        val app = context.applicationContext as Application
        val repository = app.repository
        val notification = repository.getNotification(notificationId) ?: return Result.failure()
        val subscription = repository.getSubscription(notification.subscriptionId) ?: return Result.failure()
        downloadAttachment(repository, subscription, notification)
        return Result.success()
    }

    private fun downloadAttachment(repository: Repository, subscription: Subscription, notification: Notification) {
        val attachment = notification.attachment ?: return
        Log.d(TAG, "Downloading attachment from ${attachment.url}")

        val request = Request.Builder()
            .url(attachment.url)
            .addHeader("User-Agent", ApiService.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) {
                throw Exception("Attachment download failed: ${response.code}")
            }
            val name = attachment.name
            val size = attachment.size ?: 0
            val resolver = applicationContext.contentResolver
            val details = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                if (attachment.type != null) {
                    put(MediaStore.MediaColumns.MIME_TYPE, attachment.type)
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_DOWNLOAD, 1)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), details)
                ?: throw Exception("Cannot get content URI")
            Log.d(TAG, "Starting download to content URI: $uri")
            var bytesCopied: Long = 0
            val out = resolver.openOutputStream(uri) ?: throw Exception("Cannot open output stream")
            out.use { fileOut ->
                val fileIn = response.body!!.byteStream()
                val buffer = ByteArray(8 * 1024)
                var bytes = fileIn.read(buffer)
                var lastProgress = 0L
                while (bytes >= 0) {
                    if (System.currentTimeMillis() - lastProgress > 500) {
                        val progress = if (size > 0) (bytesCopied.toFloat()/size.toFloat()*100).toInt() else PROGRESS_INDETERMINATE
                        val newAttachment = attachment.copy(progress = progress)
                        val newNotification = notification.copy(attachment = newAttachment)
                        notifier.update(subscription, newNotification)
                        repository.updateNotification(newNotification)
                        lastProgress = System.currentTimeMillis()
                    }
                    fileOut.write(buffer, 0, bytes)
                    bytesCopied += bytes
                    bytes = fileIn.read(buffer)
                }
            }
            Log.d(TAG, "Attachment download: successful response, proceeding with download")
            val newAttachment = attachment.copy(contentUri = uri.toString(), size = bytesCopied, progress = PROGRESS_DONE)
            val newNotification = notification.copy(attachment = newAttachment)
            repository.updateNotification(newNotification)
            notifier.update(subscription, newNotification)
        }
    }

    companion object {
        private const val TAG = "NtfyAttachDownload"
    }
}
