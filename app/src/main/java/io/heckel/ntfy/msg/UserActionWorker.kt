package io.heckel.ntfy.msg

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class UserActionWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.MINUTES) // Total timeout for entire request
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        if (context.applicationContext !is Application) return Result.failure()
        val notificationId = inputData.getString(INPUT_DATA_NOTIFICATION_ID) ?: return Result.failure()
        val actionId = inputData.getString(INPUT_DATA_ACTION_ID) ?: return Result.failure()
        val app = context.applicationContext as Application
        val notification = app.repository.getNotification(notificationId) ?: return Result.failure()
        val action = notification.actions?.first { it.id == actionId } ?: return Result.failure()

        Log.d(TAG, "Executing action $action for notification $notification")
        http(context, action)

        return Result.success()
    }


    fun http(context: Context, action: Action) { // FIXME Worker!
        val url = action.url ?: return
        val method = action.method ?: "GET"
        val body = action.body ?: ""
        Log.d(TAG, "HTTP POST againt ${action.url}")
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ApiService.USER_AGENT)
            .method(method, body.toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return
            }
            throw Exception("Unexpected server response ${response.code}")
        }
    }

    companion object {
        const val INPUT_DATA_NOTIFICATION_ID = "notificationId"
        const val INPUT_DATA_ACTION_ID = "actionId"

        private const val TAG = "NtfyUserActWrk"
    }
}
