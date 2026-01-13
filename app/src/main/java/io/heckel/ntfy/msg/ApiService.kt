package io.heckel.ntfy.msg

import android.content.Context
import com.google.gson.Gson
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.service.NotAuthorizedException
import io.heckel.ntfy.util.ALL_PRIORITIES
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.PRIORITY_DEFAULT
import io.heckel.ntfy.util.topicUrl
import io.heckel.ntfy.util.topicUrlAuth
import io.heckel.ntfy.util.topicUrlJson
import io.heckel.ntfy.util.topicUrlJsonPoll
import okhttp3.Call
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.net.URLEncoder

class ApiService(private val context: Context) {
    private val repository = Repository.getInstance(context)
    private val gson = Gson()
    private val parser = NotificationParser()

    suspend fun publish(
        baseUrl: String,
        topic: String,
        user: User? = null,
        message: String,
        title: String = "",
        priority: Int = PRIORITY_DEFAULT,
        tags: List<String> = emptyList(),
        delay: String = "",
        body: RequestBody? = null,
        filename: String = "",
        click: String = "",
        attach: String = "",
        email: String = "",
        call: String = "",
        markdown: Boolean = false,
        onCancelAvailable: ((cancel: () -> Unit) -> Unit)? = null // Called when the HTTP request was started and cancellable (caller can cancel)
    ) {
        val url = topicUrl(baseUrl, topic)
        val query = mutableListOf<String>()
        if (priority in ALL_PRIORITIES) {
            query.add("priority=$priority")
        }
        if (tags.isNotEmpty()) {
            query.add("tags=${URLEncoder.encode(tags.joinToString(","), "UTF-8")}")
        }
        if (title.isNotEmpty()) {
            query.add("title=${URLEncoder.encode(title, "UTF-8")}")
        }
        if (delay.isNotEmpty()) {
            query.add("delay=${URLEncoder.encode(delay, "UTF-8")}")
        }
        if (filename.isNotEmpty()) {
            query.add("filename=${URLEncoder.encode(filename, "UTF-8")}")
        }
        if (click.isNotEmpty()) {
            query.add("click=${URLEncoder.encode(click, "UTF-8")}")
        }
        if (attach.isNotEmpty()) {
            query.add("attach=${URLEncoder.encode(attach, "UTF-8")}")
        }
        if (email.isNotEmpty()) {
            query.add("email=${URLEncoder.encode(email, "UTF-8")}")
        }
        if (call.isNotEmpty()) {
            query.add("call=${URLEncoder.encode(call, "UTF-8")}")
        }
        if (markdown) {
            query.add("markdown=true")
        }
        if (body != null) {
            query.add("message=${URLEncoder.encode(message.replace("\n", "\\n"), "UTF-8")}")
        }
        val urlWithQuery = if (query.isNotEmpty()) {
            url + "?" + query.joinToString("&")
        } else {
            url
        }
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(urlWithQuery, user, customHeaders)
            .put(body ?: message.toRequestBody())
            .build()
        Log.d(TAG, "Publishing to $request")
        val httpCall = HttpUtil.longCallClient(context, baseUrl).newCall(request)
        onCancelAvailable?.invoke { httpCall.cancel() } // Notify caller that HTTP request can now be canceled
        httpCall.execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException(user)
            } else if (response.code == 413) {
                throw EntityTooLargeException()
            } else if (!response.isSuccessful) {
                // Try to parse error response from server
                val errorBody = response.body.string()
                val apiError = try {
                    gson.fromJson(errorBody, ErrorResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                if (apiError?.error != null && apiError.code != null) {
                    throw ApiException(apiError.error, apiError.code)
                }
                throw Exception("Unexpected response ${response.code} when publishing to $url")
            }
            Log.d(TAG, "Successfully published to $url")
        }
    }

    suspend fun poll(subscriptionId: Long, baseUrl: String, topic: String, user: User?, since: String? = null): List<Notification> {
        val sinceVal = since ?: "all"
        val url = topicUrlJsonPoll(baseUrl, topic, sinceVal)
        Log.d(TAG, "Polling topic $url")

        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(url, user, customHeaders).build()
        HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code} when polling topic $url")
            }
            val body = response.body.string().trim()
            if (body.isEmpty()) return emptyList()
            val notifications = body.lines().mapNotNull { line ->
                parser.parse(line, subscriptionId = subscriptionId)
            }

            Log.d(TAG, "Notifications: $notifications")
            return notifications
        }
    }

    suspend fun subscribe(
        baseUrl: String,
        topics: String,
        since: String?,
        user: User?
    ): Pair<Call, BufferedSource> {
        val sinceVal = since ?: "all"
        val url = topicUrlJson(baseUrl, topics, sinceVal)
        Log.d(TAG, "Opening subscription connection to $url")
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(url, user, customHeaders).build()
        val call = HttpUtil.subscriberClient(context, baseUrl).newCall(request)
        val response = call.execute()
        if (!response.isSuccessful) {
            val code = response.code
            val message = response.message
            response.close()
            if (code == 401 || code == 403) {
                throw NotAuthorizedException(code, message)
            }
            throw IOException("Unexpected response $code when subscribing")
        }
        return Pair(call, response.body.source())
    }

    suspend fun checkAuth(baseUrl: String, topic: String, user: User?): Boolean {
        if (user == null) {
            Log.d(TAG, "Checking anonymous read against ${topicUrl(baseUrl, topic)}")
        } else {
            Log.d(TAG, "Checking read access for user ${user.username} against ${topicUrl(baseUrl, topic)}")
        }
        val url = topicUrlAuth(baseUrl, topic)
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(url, user, customHeaders).build()
        HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return true
            } else if (user == null && response.code == 404) {
                return true // Special case: Anonymous login to old servers return 404 since /<topic>/auth doesn't exist
            } else if (response.code == 401 || response.code == 403) { // See server/server.go
                return false
            }
            throw Exception("Unexpected server response ${response.code}")
        }
    }

    class UnauthorizedException(val user: User?) : Exception()
    class EntityTooLargeException : Exception()
    class ApiException(val error: String, val code: Int) : Exception(error)

    private data class ErrorResponse(
        val code: Int?,
        val http: Int?,
        val error: String?
    )

    companion object {
        private const val TAG = "NtfyApiService"

        // These constants have corresponding values in the server codebase!
        const val CONTROL_TOPIC = "~control"
        const val EVENT_MESSAGE = "message"
        const val EVENT_MESSAGE_DELETE = "message_delete"
        const val EVENT_MESSAGE_CLEAR = "message_clear"
        const val EVENT_KEEPALIVE = "keepalive"
        const val EVENT_POLL_REQUEST = "poll_request"
    }
}
