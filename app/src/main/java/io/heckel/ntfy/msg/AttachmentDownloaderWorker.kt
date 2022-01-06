package io.heckel.ntfy.msg

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.data.Subscription
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
        if (notification.attachmentPreviewUrl != null) {
            downloadPreview(repository, subscription, notification)
        }
        downloadAttachment(repository, subscription, notification)
        return Result.success()
    }

    private fun downloadPreview(repository: Repository, subscription: Subscription, notification: Notification) {
        val url = notification.attachmentPreviewUrl ?: return
        Log.d(TAG, "Downloading preview from $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ApiService.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) {
                throw Exception("Preview download failed: ${response.code}")
            }
            val previewFile = File(applicationContext.cacheDir.absolutePath, "preview-" + notification.id)
            Log.d(TAG, "Downloading preview to cache file: $previewFile")
            FileOutputStream(previewFile).use { fileOut ->
                response.body!!.byteStream().copyTo(fileOut)
            }
            Log.d(TAG, "Preview downloaded; updating notification")
            notifier.update(subscription, notification)
        }
    }

    private fun downloadAttachment(repository: Repository, subscription: Subscription, notification: Notification) {
        val url = notification.attachmentUrl ?: return
        Log.d(TAG, "Downloading attachment from $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ApiService.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) {
                throw Exception("Attachment download failed: ${response.code}")
            }
            val name = notification.attachmentName ?: "attachment.bin"
            val mimeType = notification.attachmentType ?: "application/octet-stream"
            val size = notification.attachmentSize ?: 0
            val resolver = applicationContext.contentResolver
            val details = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_DOWNLOAD, 1)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), details)
                ?: throw Exception("Cannot get content URI")
            Log.d(TAG, "Starting download to content URI: $uri")
            val out = resolver.openOutputStream(uri) ?: throw Exception("Cannot open output stream")
            out.use { fileOut ->
                val fileIn = response.body!!.byteStream()
                var bytesCopied: Long = 0
                val buffer = ByteArray(8 * 1024)
                var bytes = fileIn.read(buffer)
                var lastProgress = 0L
                while (bytes >= 0) {
                    if (System.currentTimeMillis() - lastProgress > 500) {
                        val progress = if (size > 0) (bytesCopied.toFloat()/size.toFloat()*100).toInt() else NotificationService.PROGRESS_INDETERMINATE
                        notifier.update(subscription, notification, progress = progress)
                        lastProgress = System.currentTimeMillis()
                    }
                    fileOut.write(buffer, 0, bytes)
                    bytesCopied += bytes
                    bytes = fileIn.read(buffer)
                }
            }
            Log.d(TAG, "Attachment download: successful response, proceeding with download")
            val newNotification = notification.copy(attachmentContentUri = uri.toString())
            repository.updateNotification(newNotification)
            notifier.update(subscription, newNotification)
        }
    }

    companion object {
        private const val TAG = "NtfyAttachDownload"
    }
}
