package io.heckel.ntfy.msg

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.*
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_BROADCAST
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_HTTP
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_VIEW
import io.heckel.ntfy.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.concurrent.TimeUnit

class UserActionWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS) // Total timeout for entire request
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val notifier = NotificationService(context)
    private val broadcaster = BroadcastService(context)
    private lateinit var repository: Repository
    private lateinit var subscription: Subscription
    private lateinit var notification: Notification
    private lateinit var action: Action

    override fun doWork(): Result {
        if (context.applicationContext !is Application) return Result.failure()
        val notificationId = inputData.getString(INPUT_DATA_NOTIFICATION_ID) ?: return Result.failure()
        val actionId = inputData.getString(INPUT_DATA_ACTION_ID) ?: return Result.failure()
        val app = context.applicationContext as Application

        repository = app.repository
        notification = repository.getNotification(notificationId) ?: return Result.failure()
        subscription = repository.getSubscription(notification.subscriptionId) ?: return Result.failure()
        action = notification.actions?.first { it.id == actionId } ?: return Result.failure()

        Log.d(TAG, "Executing action $action for notification $notification")
        try {
            when (action.action) {
                // ACTION_VIEW is not handled here. It has to be handled in the foreground to avoid
                // weird Android behavior.

                ACTION_VIEW -> performViewAction(action)
                ACTION_BROADCAST -> performBroadcastAction(action)
                ACTION_HTTP -> performHttpAction(action)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error executing action: ${e.message}", e)
            save(action.copy(
                progress = ACTION_PROGRESS_FAILED,
                error = context.getString(R.string.notification_popup_user_action_failed, action.label, e.message)
            ))
        }
        return Result.success()
    }

    private fun performViewAction(action: Action) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        if (action.clear == true) {
            notifier.cancel(notification)
        }

        // Close notification drawer. This seems to be a bug in Android that when a new activity is started from
        // a receiver or worker, the drawer does not close. Using this deprecated intent is the only option I have found.
        //
        // See https://stackoverflow.com/questions/18261969/clicking-android-notification-actions-does-not-close-notification-drawer
        try {
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (e: Exception) {
            Log.w(TAG, "Cannot close system dialogs", e)
        }
    }

    private fun performBroadcastAction(action: Action) {
        broadcaster.sendUserAction(action)
        if (action.clear == true) {
            notifier.cancel(notification)
        }
    }

    private fun performHttpAction(action: Action) {
        save(action.copy(progress = ACTION_PROGRESS_ONGOING, error = null))

        val url = action.url ?: return
        val method = action.method ?: "POST" // (not GET, because POST as a default makes more sense!)
        val body = action.body ?: ""
        val builder = Request.Builder()
            .url(url)
            .method(method, body.toRequestBody())
            .addHeader("User-Agent", ApiService.USER_AGENT)
        action.headers?.forEach { (key, value) ->
            builder.addHeader(key, value)
        }
        val request = builder.build()

        Log.d(TAG, "Executing HTTP request: ${method.uppercase(Locale.getDefault())} ${action.url}")
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                save(action.copy(progress = ACTION_PROGRESS_SUCCESS, error = null))
                return
            }
            throw Exception("HTTP ${response.code}")
        }
    }

    private fun save(newAction: Action) {
        Log.d(TAG, "Updating action: $newAction")
        val clear = newAction.progress == ACTION_PROGRESS_SUCCESS && action.clear == true
        val newActions = notification.actions?.map { a -> if (a.id == newAction.id) newAction else a }
        val newNotification = notification.copy(actions = newActions)
        action = newAction
        notification = newNotification
        repository.updateNotification(notification)
        if (clear) {
            notifier.cancel(notification)
        } else {
            notifier.update(subscription, notification)
        }
    }

    companion object {
        const val INPUT_DATA_NOTIFICATION_ID = "notificationId"
        const val INPUT_DATA_ACTION_ID = "actionId"

        private const val TAG = "NtfyUserActWrk"
    }
}
