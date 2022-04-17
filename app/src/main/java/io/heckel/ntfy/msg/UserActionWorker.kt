package io.heckel.ntfy.msg

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.*
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.ensureSafeNewFile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
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
        val notificationId = inputData.getString(INPUT_DATA_ID) ?: return Result.failure()
        val action = inputData.getString(INPUT_DATA_ACTION) ?: return Result.failure()
        val url = inputData.getString(INPUT_DATA_URL) ?: return Result.failure()
        val app = context.applicationContext as Application

        http(context, url)

        return Result.success()
    }


    fun http(context: Context, url: String) { // FIXME Worker!
        Log.d(TAG, "HTTP POST againt $url")
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ApiService.USER_AGENT)
            .method("POST", "".toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return
            }
            throw Exception("Unexpected server response ${response.code}")
        }
    }

    companion object {
        const val INPUT_DATA_ID = "id"
        const val INPUT_DATA_ACTION = "action"
        const val INPUT_DATA_URL = "url"

        private const val TAG = "NtfyUserActWrk"
    }
}
