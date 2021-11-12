package io.heckel.ntfy.msg

import android.util.Log
import com.google.gson.Gson
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.topicUrl
import io.heckel.ntfy.data.topicUrlJsonPoll
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiService {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS) // Total timeout for entire request
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun publish(baseUrl: String, topic: String, message: String) {
        val url = topicUrl(baseUrl, topic)
        Log.d(TAG, "Publishing to $url")

        val request = Request.Builder().url(url).put(message.toRequestBody()).build();
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code} when publishing to $url")
            }
            Log.d(TAG, "Successfully published to $url")
        }
    }

    fun poll(subscriptionId: Long, baseUrl: String, topic: String): List<Notification> {
        val url = topicUrlJsonPoll(baseUrl, topic)
        Log.d(TAG, "Polling topic $url")

        val request = Request.Builder().url(url).build();
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code} when polling topic $url")
            }
            val body = response.body?.string()?.trim()
            if (body == null || body.isEmpty()) return emptyList()
            val notifications = body.lines().map { line ->
                fromString(subscriptionId, line)
            }
            Log.d(TAG, "Notifications: $notifications")
            return notifications
        }
    }

    private fun fromString(subscriptionId: Long, s: String): Notification {
        val n = gson.fromJson(s, NotificationData::class.java) // Indirection to prevent accidental field renames, etc.
        return Notification(n.id, subscriptionId, n.time, n.message, false)
    }

    private data class NotificationData(
        val id: String,
        val time: Long,
        val message: String
    )

    companion object {
        private const val TAG = "NtfyApiService"
    }
}
