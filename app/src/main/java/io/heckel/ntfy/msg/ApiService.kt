package io.heckel.ntfy.msg

import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.util.topicUrl
import io.heckel.ntfy.util.topicUrlJson
import io.heckel.ntfy.util.topicUrlJsonPoll
import io.heckel.ntfy.util.toPriority
import io.heckel.ntfy.util.joinTags
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ApiService {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS) // Total timeout for entire request
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val subscriberClient = OkHttpClient.Builder()
        .readTimeout(77, TimeUnit.SECONDS) // Assuming that keepalive messages are more frequent than this
        .build()

    fun publish(baseUrl: String, topic: String, message: String, title: String, priority: Int, tags: List<String>, delay: String) {
        val url = topicUrl(baseUrl, topic)
        Log.d(TAG, "Publishing to $url")

        var builder = Request.Builder()
            .url(url)
            .put(message.toRequestBody())
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

    fun subscribe(
        baseUrl: String,
        topics: String,
        since: Long,
        notify: (topic: String, Notification) -> Unit,
        fail: (Exception) -> Unit
    ): Call {
        val sinceVal = if (since == 0L) "all" else since.toString()
        val url = topicUrlJson(baseUrl, topics, sinceVal)
        Log.d(TAG, "Opening subscription connection to $url")

        val request = Request.Builder().url(url).build()
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
                        val message = gson.fromJson(line, Message::class.java)
                        if (message.event == EVENT_MESSAGE) {
                            val topic = message.topic
                            val notification = Notification(
                                id = message.id,
                                subscriptionId = 0, // TO BE SET downstream
                                timestamp = message.time,
                                title = message.title ?: "",
                                message = message.message,
                                priority = toPriority(message.priority),
                                tags = joinTags(message.tags),
                                notificationId = Random.nextInt(),
                                deleted = false
                            )
                            notify(topic, notification)
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

    private fun fromString(subscriptionId: Long, s: String): Notification {
        val message = gson.fromJson(s, Message::class.java)
        return Notification(
            id = message.id,
            subscriptionId = subscriptionId,
            timestamp = message.time,
            title = message.title ?: "",
            message = message.message,
            priority = toPriority(message.priority),
            tags = joinTags(message.tags),
            notificationId = 0,
            deleted = false
        )
    }

    /* This annotation ensures that proguard still works in production builds,
     * see https://stackoverflow.com/a/62753300/1440785 */
    @Keep
    private data class Message(
        val id: String,
        val time: Long,
        val event: String,
        val topic: String,
        val priority: Int?,
        val tags: List<String>?,
        val title: String?,
        val message: String
    )

    companion object {
        private const val TAG = "NtfyApiService"
        private const val EVENT_MESSAGE = "message"
    }
}
