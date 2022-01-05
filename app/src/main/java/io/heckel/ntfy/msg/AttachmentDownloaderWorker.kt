package io.heckel.ntfy.msg

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Subscription
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit


class AttachmentDownloadWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS) // Total timeout for entire request
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val notifier = NotificationService(context)

    override fun doWork(): Result {
        if (context.applicationContext !is Application) return Result.failure()
        val notificationId = inputData.getString("id") ?: return Result.failure()
        val app = context.applicationContext as Application
        val notification = app.repository.getNotification(notificationId) ?: return Result.failure()
        val subscription = app.repository.getSubscription(notification.subscriptionId) ?: return Result.failure()
        if (notification.attachmentPreviewUrl != null) {
            downloadPreview(subscription, notification)
        }
        downloadAttachment(subscription, notification)
        return Result.success()
    }

    private fun downloadPreview(subscription: Subscription, notification: Notification) {
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
            Log.d(TAG, "Preview download: successful response, proceeding with download")
            /*val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            Log.d(TAG, "dir: $dir")
            if (dir == null /*|| !dir.mkdirs()*/) {
                throw Exception("Cannot access target storage dir")
            }*/
            val contentResolver = applicationContext.contentResolver
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "flower.jpg")
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            val uri = contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), contentValues)
                ?: throw Exception("Cannot get content URI")
            val out = contentResolver.openOutputStream(uri) ?: throw Exception("Cannot open output stream")
            out.use { fileOut ->
                response.body!!.byteStream().copyTo(fileOut)
            }

            /*

             val file = File(context.cacheDir, "somefile")
            context.openFileOutput(file.absolutePath, Context.MODE_PRIVATE).use { fileOut ->
                response.body!!.byteStream().copyTo(fileOut)
            }

            val file = File(dir, "myfile.txt")
            Log.d(TAG, "dir: $dir, file: $file")
            FileOutputStream(file).use { fileOut ->
                response.body!!.byteStream().copyTo(fileOut)
            }*/
            /*
            context.openFileOutput(file.absolutePath, Context.MODE_PRIVATE).use { fileOut ->
                response.body!!.byteStream().copyTo(fileOut)
            }*/
            //val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            Log.d(TAG, "now we would display the preview image")
            //displayInternal(subscription, notification, bitmap)
        }
    }

    private fun downloadAttachment(subscription: Subscription, notification: Notification) {
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
                response.body!!.byteStream().copyTo(fileOut)
            }
            Log.d(TAG, "Attachment download: successful response, proceeding with download")
            notifier.update(subscription, notification)
        }
    }

    companion object {
        private const val TAG = "NtfyAttachDownload"
    }
}
