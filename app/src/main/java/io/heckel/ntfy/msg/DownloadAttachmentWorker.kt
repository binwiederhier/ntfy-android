package io.heckel.ntfy.msg

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.ATTACHMENT_PROGRESS_DONE
import io.heckel.ntfy.db.ATTACHMENT_PROGRESS_FAILED
import io.heckel.ntfy.db.ATTACHMENT_PROGRESS_INDETERMINATE
import io.heckel.ntfy.db.ATTACHMENT_PROGRESS_NONE
import io.heckel.ntfy.db.Attachment
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.ensureSafeNewFile
import io.heckel.ntfy.util.extractBaseUrl
import okhttp3.Response
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class DownloadAttachmentWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifier = NotificationService(context)
    private lateinit var repository: Repository
    private lateinit var subscription: Subscription
    private lateinit var notification: Notification
    private lateinit var attachment: Attachment
    private var uri: Uri? = null

    override suspend fun doWork(): Result {
        if (context.applicationContext !is Application) return Result.failure()
        val notificationId = inputData.getString(INPUT_DATA_ID) ?: return Result.failure()
        val userAction = inputData.getBoolean(INPUT_DATA_USER_ACTION, false)
        val app = context.applicationContext as Application
        repository = app.repository
        notification = repository.getNotification(notificationId) ?: return Result.failure()
        subscription = repository.getSubscription(notification.subscriptionId) ?: return Result.failure()
        attachment = notification.attachment ?: return Result.failure()
        try {
            downloadAttachment(userAction)
        } catch (e: CancellationException) {
            Log.d(TAG, "Attachment download was canceled")
            maybeDeleteFile()
            throw e // We must re-throw this to stop the worker
        } catch (e: Exception) {
            failed(e)
        }
        return Result.success()
    }

    private suspend fun downloadAttachment(userAction: Boolean) {
        Log.d(TAG, "Downloading attachment from ${attachment.url}")

        try {
            val user = repository.getUser(extractBaseUrl(attachment.url))
            val customHeaders = repository.getCustomHeaders(extractBaseUrl(attachment.url))
            val request = HttpUtil.requestBuilder(attachment.url, user, customHeaders).build()
            val client = HttpUtil.longCallClient(context, extractBaseUrl(attachment.url))
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "Download: headers received: $response")
                if (!response.isSuccessful) {
                    throw Exception("Unexpected response: ${response.code}")
                }
                save(updateAttachmentFromResponse(response))
                if (!userAction && shouldAbortDownload()) {
                    Log.d(TAG, "Aborting download: Content-Length is larger than auto-download setting")
                    return
                }
                val resolver = applicationContext.contentResolver
                val uri = createUri(notification)
                this.uri = uri // Required for cleanup in onStopped()

                Log.d(TAG, "Starting download to content URI: $uri")
                var bytesCopied: Long = 0
                val outFile = resolver.openOutputStream(uri) ?: throw Exception("Cannot open output stream")
                val downloadLimit = getDownloadLimit(userAction)
                outFile.use { fileOut ->
                    val fileIn = response.body.byteStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytes = fileIn.read(buffer)
                    var lastProgress = 0L
                    while (bytes >= 0) {
                        if (System.currentTimeMillis() - lastProgress > NOTIFICATION_UPDATE_INTERVAL_MILLIS) {
                            if (isStopped) { // Canceled by user
                                save(attachment.copy(progress = ATTACHMENT_PROGRESS_NONE))
                                return // File will be deleted in onStopped()
                            }
                            val progress = if (attachment.size != null && attachment.size!! > 0) {
                                (bytesCopied.toFloat()/attachment.size!!.toFloat()*100).toInt()
                            } else {
                                ATTACHMENT_PROGRESS_INDETERMINATE
                            }
                            save(attachment.copy(progress = progress))
                            lastProgress = System.currentTimeMillis()
                        }
                        if (downloadLimit != null && bytesCopied > downloadLimit) {
                            throw Exception("Attachment is longer than max download size.")
                        }
                        fileOut.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        bytes = fileIn.read(buffer)
                    }
                }
                Log.d(TAG, "Attachment download: successful response, proceeding with download")
                save(attachment.copy(
                    size = bytesCopied,
                    contentUri = uri.toString(),
                    progress = ATTACHMENT_PROGRESS_DONE
                ))
            }
        } catch (e: Exception) {
            failed(e)

            // Toast in a Worker: https://stackoverflow.com/a/56428145/1440785
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                Toast
                    .makeText(context, context.getString(R.string.detail_item_download_failed, e.message), Toast.LENGTH_LONG)
                    .show()
            }, 200)
        }
    }

    private fun updateAttachmentFromResponse(response: Response): Attachment {
        val size = if (response.headers["Content-Length"]?.toLongOrNull() != null) {
            response.headers["Content-Length"]?.toLong()
        } else {
            attachment.size // May be null!
        }
        val mimeType = if (response.headers["Content-Type"] != null) {
            response.headers["Content-Type"]
        } else {
            val ext = MimeTypeMap.getFileExtensionFromUrl(attachment.url)
            if (ext != null) {
                val typeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                typeFromExt ?: attachment.type // May be null!
            } else {
                attachment.type // May be null!
            }
        }
        return attachment.copy(
            size = size,
            type = mimeType
        )
    }

    private fun failed(e: Exception) {
        Log.w(TAG, "Attachment download failed", e)
        save(attachment.copy(progress = ATTACHMENT_PROGRESS_FAILED))
        maybeDeleteFile()
    }

    private fun maybeDeleteFile() {
        val uriCopy = uri
        if (uriCopy != null) {
            Log.d(TAG, "Deleting leftover attachment $uriCopy")
            val resolver = applicationContext.contentResolver
            resolver.delete(uriCopy, null, null)
        }
    }

    private fun save(newAttachment: Attachment) {
        Log.d(TAG, "Updating attachment: $newAttachment")
        attachment = newAttachment
        notification = notification.copy(attachment = newAttachment)
        notifier.update(subscription, notification)
        repository.updateNotification(notification)
    }

    private fun shouldAbortDownload(): Boolean {
        when (val maxAutoDownloadSize = repository.getAutoDownloadMaxSize()) {
            Repository.AUTO_DOWNLOAD_NEVER -> return true
            Repository.AUTO_DOWNLOAD_ALWAYS -> return false
            else -> {
                val size = attachment.size ?: return false // Don't abort if size unknown
                return size > maxAutoDownloadSize
            }
        }
    }

    private fun getDownloadLimit(userAction: Boolean): Long? {
        return if (userAction || repository.getAutoDownloadMaxSize() == Repository.AUTO_DOWNLOAD_ALWAYS) {
            null
        } else {
            repository.getAutoDownloadMaxSize()
        }
    }

    private fun createUri(notification: Notification): Uri {
        val attachmentDir = File(context.cacheDir, ATTACHMENT_CACHE_DIR)
        if (!attachmentDir.exists() && !attachmentDir.mkdirs()) {
            throw Exception("Cannot create cache directory for attachments: $attachmentDir")
        }
        val file = ensureSafeNewFile(attachmentDir, notification.id)
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    }

    companion object {
        const val INPUT_DATA_ID = "id"
        const val INPUT_DATA_USER_ACTION = "userAction"
        const val FILE_PROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".provider" // See AndroidManifest.xml

        private const val TAG = "NtfyAttachDownload"
        private const val ATTACHMENT_CACHE_DIR = "attachments"
        private const val BUFFER_SIZE = 8 * 1024
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 800
    }
}
