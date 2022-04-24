package io.heckel.ntfy.msg

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.*
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_BROADCAST
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_HTTP
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
                // ACTION_VIEW is not handled here. It's handled in the NotificationService and DetailAdapter.
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

    private fun performBroadcastAction(action: Action) {
        broadcaster.sendUserAction(action)
        if (action.clear == true) {
            notifier.cancel(notification)
        }
    }

    private fun performHttpAction(action: Action) {
        save(action.copy(progress = ACTION_PROGRESS_ONGOING, error = null))

        val url = action.url ?: return
        val method = action.method ?: DEFAULT_HTTP_ACTION_METHOD
        val defaultBody = if (listOf("GET", "HEAD").contains(method)) null else ""
        val body = action.body ?: defaultBody
        val builder = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody())
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

        private const val DEFAULT_HTTP_ACTION_METHOD = "POST" // Cannot be changed without changing the contract
        private const val TAG = "NtfyUserActWrk"
    }
}
