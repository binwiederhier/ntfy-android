package io.heckel.ntfy.msg

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.*
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.sha256
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

class DownloadIconWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
    private val client = OkHttpClient.Builder()
        .callTimeout(1, TimeUnit.MINUTES) // Total timeout for entire request
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val notifier = NotificationService(context)
    private lateinit var repository: Repository
    private lateinit var subscription: Subscription
    private lateinit var notification: Notification
    private lateinit var icon: Icon
    private var uri: Uri? = null

    override fun doWork(): Result {
        if (context.applicationContext !is Application) return Result.failure()
        val notificationId = inputData.getString(INPUT_DATA_ID) ?: return Result.failure()
        val app = context.applicationContext as Application
        repository = app.repository
        notification = repository.getNotification(notificationId) ?: return Result.failure()
        subscription = repository.getSubscription(notification.subscriptionId) ?: return Result.failure()
        icon = notification.icon ?: return Result.failure()
        try {
            val iconFile = createIconFile(icon)
            val yesterdayTimestamp = Date().time - MAX_CACHE_MILLIS
            if (!iconFile.exists() || iconFile.lastModified() < yesterdayTimestamp) {
                downloadIcon(iconFile)
            } else {
                Log.d(TAG, "Loading icon from cache: $iconFile")
                val iconUri = createIconUri(iconFile)
                this.uri = iconUri // Required for cleanup in onStopped()
                save(icon.copy(contentUri = iconUri.toString()))
            }
        } catch (e: Exception) {
            failed(e)
        }
        return Result.success()
    }

    override fun onStopped() {
        Log.d(TAG, "Icon download was canceled")
        maybeDeleteFile()
    }

    private fun downloadIcon(iconFile: File) {
        Log.d(TAG, "Downloading icon from ${icon.url}")
        try {
            val request = Request.Builder()
                .url(icon.url)
                .addHeader("User-Agent", ApiService.USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "Headers received: $response, Content-Length: ${response.headers["Content-Length"]}")
                if (!response.isSuccessful || response.body == null) {
                    throw Exception("Unexpected response: ${response.code}")
                } else if (shouldAbortDownload(response)) {
                    Log.d(TAG, "Aborting download: Content-Length is larger than auto-download setting")
                    return
                }
                val resolver = applicationContext.contentResolver
                val uri = createIconUri(iconFile)
                this.uri = uri // Required for cleanup in onStopped()

                Log.d(TAG, "Starting download to content URI: $uri")
                var bytesCopied: Long = 0
                val outFile = resolver.openOutputStream(uri) ?: throw Exception("Cannot open output stream")
                val downloadLimit = getDownloadLimit()
                outFile.use { fileOut ->
                    val fileIn = response.body!!.byteStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytes = fileIn.read(buffer)
                    while (bytes >= 0) {
                        if (bytesCopied > downloadLimit) {
                            throw Exception("Icon is longer than max download size.")
                        }
                        fileOut.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        bytes = fileIn.read(buffer)
                    }
                }
                Log.d(TAG, "Icon download: successful response, proceeding with download")
                save(icon.copy(contentUri = uri.toString()))
            }
        } catch (e: Exception) {
            failed(e)
        }
    }

    private fun failed(e: Exception) {
        Log.w(TAG, "Icon download failed", e)
        maybeDeleteFile()
    }

    private fun maybeDeleteFile() {
        val uriCopy = uri
        if (uriCopy != null) {
            Log.d(TAG, "Deleting leftover icon $uriCopy")
            val resolver = applicationContext.contentResolver
            resolver.delete(uriCopy, null, null)
        }
    }

    private fun save(newIcon: Icon) {
        Log.d(TAG, "Updating icon: $newIcon")
        icon = newIcon
        notification = notification.copy(icon = newIcon)
        notifier.update(subscription, notification)
        repository.updateNotification(notification)
    }

    private fun shouldAbortDownload(response: Response): Boolean {
        val maxAutoDownloadSize = getDownloadLimit()
        val size = response.headers["Content-Length"]?.toLongOrNull() ?: return false // Don't abort here if size unknown
        return size > maxAutoDownloadSize
    }

    private fun getDownloadLimit(): Long {
        return if (repository.getAutoDownloadMaxSize() != Repository.AUTO_DOWNLOAD_NEVER && repository.getAutoDownloadMaxSize() != Repository.AUTO_DOWNLOAD_ALWAYS) {
            Math.min(repository.getAutoDownloadMaxSize(), MAX_ICON_DOWNLOAD_BYTES)
        } else {
            DEFAULT_MAX_ICON_DOWNLOAD_BYTES
        }
    }

    private fun createIconFile(icon: Icon): File {
        val iconDir = File(context.cacheDir, ICON_CACHE_DIR)
        if (!iconDir.exists() && !iconDir.mkdirs()) {
            throw Exception("Cannot create cache directory for icons: $iconDir")
        }
        val hash = icon.url.sha256()
        return File(iconDir, hash)
    }

    private fun createIconUri(iconFile: File): Uri {
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, iconFile)
    }

    companion object {
        const val INPUT_DATA_ID = "id"
        const val FILE_PROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".provider" // See AndroidManifest.xml
        const val DEFAULT_MAX_ICON_DOWNLOAD_BYTES = 307_200L // 300 KB
        const val MAX_ICON_DOWNLOAD_BYTES = 5_242_880L // 5 MB
        const val MAX_CACHE_MILLIS = 1000*60*60*24 // 24 hours
        const val ICON_CACHE_DIR = "icons"

        private const val TAG = "NtfyIconDownload"
        private const val BUFFER_SIZE = 8 * 1024
    }
}
