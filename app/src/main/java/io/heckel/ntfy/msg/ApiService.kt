package io.heckel.ntfy.msg

import android.os.Build
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.User
import io.heckel.ntfy.log.Log
import io.heckel.ntfy.util.topicUrl
import io.heckel.ntfy.util.topicUrlAuth
import io.heckel.ntfy.util.topicUrlJson
import io.heckel.ntfy.util.topicUrlJsonPoll
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import kotlin.random.Random


class ApiService {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS) // Total timeout for entire request
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val subscriberClient = OkHttpClient.Builder()
        .readTimeout(77, TimeUnit.SECONDS) // Assuming that keepalive messages are more frequent than this
        .build()
    private val parser = NotificationParser()

    fun publish(baseUrl: String, topic: String, message: String, title: String, priority: Int, tags: List<String>, delay: String) {
        val url = topicUrl(baseUrl, topic)
        Log.d(TAG, "Publishing to $url")

        // XXXXXXXXXXXx

        var builder = Request.Builder()
            .url(url)
            .put(message.toRequestBody())
            .addHeader("User-Agent", USER_AGENT)
        if (priority in 1..5) {
            builder = builder.addHeader("X-Priority", priority.toString())
        }
        if (tags.isNotEmpty()) {
            builder = builder.addHeader("X-Tags", tags.joinToString(","))
        }
        if (title.isNotEmpty()) {
            builder = builder.addHeader("X-Title", title)
        }
        if (delay.isNotEmpty()) {
            builder = builder.addHeader("X-Delay", delay)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code} when publishing to $url")
            }
            Log.d(TAG, "Successfully published to $url")
        }
    }

    fun poll(subscriptionId: Long, baseUrl: String, topic: String, since: Long = 0L): List<Notification> {
        val sinceVal = if (since == 0L) "all" else since.toString()
        val url = topicUrlJsonPoll(baseUrl, topic, sinceVal)
        Log.d(TAG, "Polling topic $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        // XXXXXXXXXXXx

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code} when polling topic $url")
            }
            val body = response.body?.string()?.trim()
            if (body == null || body.isEmpty()) return emptyList()
            val notifications = body.lines().mapNotNull { line ->
                parser.parse(line, subscriptionId = subscriptionId, notificationId = 0) // No notification when we poll
            }

            Log.d(TAG, "Notifications: $notifications")
            return notifications
        }
    }

    fun subscribe(
        baseUrl: String,
        topics: String,
        since: Long,
        user: User?,
        notify: (topic: String, Notification) -> Unit,
        fail: (Exception) -> Unit
    ): Call {
        val sinceVal = if (since == 0L) "all" else since.toString()
        val url = topicUrlJson(baseUrl, topics, sinceVal)
        Log.d(TAG, "Opening subscription connection to $url")
        val builder = Request.Builder()
            .get()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
        if (user != null) {
            builder.addHeader("Authorization", Credentials.basic(user.username, user.password, UTF_8))
        }
        val request = builder.build()
        val call = subscriberClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        throw Exception("Unexpected response ${response.code} when subscribing to topic $url")
                    }
                    val source = response.body?.source() ?: throw Exception("Unexpected response for $url: body is empty")
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: throw Exception("Unexpected response for $url: line is null")
                        val notification = parser.parseWithTopic(line, notificationId = Random.nextInt(), subscriptionId = 0) // subscriptionId to be set downstream
                        if (notification != null) {
                            notify(notification.topic, notification.notification)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection to $url failed (1): ${e.message}", e)
                    fail(e)
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Connection to $url failed (2): ${e.message}", e)
                fail(e)
            }
        })
        return call
    }

    fun authTopicRead(baseUrl: String, topic: String, user: User?): Boolean {
        if (user == null) {
            Log.d(TAG, "Checking anonymous read against ${topicUrl(baseUrl, topic)}")
        } else {
            Log.d(TAG, "Checking read access for user ${user.username} against ${topicUrl(baseUrl, topic)}")
        }
        val url = topicUrlAuth(baseUrl, topic)
        val builder = Request.Builder()
            .get()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
        if (user != null) {
            builder.addHeader("Authorization", Credentials.basic(user.username, user.password, UTF_8))
        }
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            return if (user == null) {
                response.isSuccessful || response.code == 404 // Treat 404 as success (old server; to be removed in future versions)
            } else {
                response.isSuccessful
            }
        }
    }

    companion object {
        val USER_AGENT = "ntfy/${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT})"
        private const val TAG = "NtfyApiService"

        // These constants have corresponding values in the server codebase!
        const val CONTROL_TOPIC = "~control"
        const val EVENT_MESSAGE = "message"
        const val EVENT_KEEPALIVE = "keepalive"
    }
}
