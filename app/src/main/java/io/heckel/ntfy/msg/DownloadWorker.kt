package io.heckel.ntfy.msg

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.*
import io.heckel.ntfy.util.queryFilename
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class DownloadWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
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

        try {
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
                val uri = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    val file = ensureSafeNewFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                    FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
                } else {
                    val details = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        if (attachment.type != null) {
                            put(MediaStore.MediaColumns.MIME_TYPE, attachment.type)
                        }
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_DOWNLOAD, 1)
                    }
                    resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), details)
                        ?: throw Exception("Cannot get content URI")
                }
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
                            if (isStopped) {
                                Log.d(TAG, "Attachment download was canceled")
                                val newAttachment = attachment.copy(progress = PROGRESS_NONE)
                                val newNotification = notification.copy(attachment = newAttachment)
                                notifier.update(subscription, newNotification)
                                repository.updateNotification(newNotification)
                                return
                            }
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
                val actualName = queryFilename(context, uri.toString(), name)
                val newAttachment = attachment.copy(
                    name = actualName,
                    size = bytesCopied,
                    contentUri = uri.toString(),
                    progress = PROGRESS_DONE
                )
                val newNotification = notification.copy(attachment = newAttachment)
                repository.updateNotification(newNotification)
                notifier.update(subscription, newNotification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Attachment download failed", e)

            val newAttachment = attachment.copy(progress = PROGRESS_FAILED)
            val newNotification = notification.copy(attachment = newAttachment)
            notifier.update(subscription, newNotification)
            repository.updateNotification(newNotification)

            // Toast in a Worker: https://stackoverflow.com/a/56428145/1440785
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                Toast
                    .makeText(context, context.getString(R.string.detail_item_download_failed, e.message), Toast.LENGTH_LONG)
                    .show()
            }, 200)
        }
    }

    private fun ensureSafeNewFile(dir: File, name: String): File {
        val safeName = name.replace("[^-_.()\\w]+".toRegex(), "_");
        val file = File(dir, safeName)
        if (!file.exists()) {
            return file
        }
        (1..1000).forEach { i ->
            val newFile = File(dir, if (file.extension == "") {
                "${file.nameWithoutExtension} ($i)"
            } else {
                "${file.nameWithoutExtension} ($i).${file.extension}"
            })
            if (!newFile.exists()) {
                return newFile
            }
        }
        throw Exception("Cannot find safe file")
    }

    companion object {
        private const val TAG = "NtfyAttachDownload"
        private const val FILE_PROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".provider" // See AndroidManifest.xml
    }
}
