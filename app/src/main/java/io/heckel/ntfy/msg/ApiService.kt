package io.heckel.ntfy.msg

import android.content.Context
import android.util.Log
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.gson.Gson
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.*
import io.heckel.ntfy.ui.DetailActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.*

class ApiService(context: Context) {
    private val queue = Volley.newRequestQueue(context)
    private val parser = NotificationParser()

    fun publish(baseUrl: String, topic: String, message: String, successFn: Response.Listener<String>, failureFn: (VolleyError) -> Unit) {
        val url = topicUrl(baseUrl, topic)
        val stringRequest = object : StringRequest(Method.PUT, url, successFn, failureFn) {
            override fun getBody(): ByteArray {
                return message.toByteArray()
            }
        }
        queue.add(stringRequest)
    }

    fun poll(subscriptionId: Long, baseUrl: String, topic: String, successFn: (List<Notification>) -> Unit, failureFn: (Exception) -> Unit) {
        val url = topicUrlJsonPoll(baseUrl, topic)
        val parseSuccessFn = { response: String ->
            try {
                val notifications = response.trim().lines().map { line ->
                    parser.fromString(subscriptionId, line)
                }
                Log.d(TAG, "Notifications: $notifications")
                successFn(notifications)
            } catch (e: Exception) {
                failureFn(e)
            }
        }
        val stringRequest = StringRequest(Request.Method.GET, url, parseSuccessFn, failureFn)
        queue.add(stringRequest)
    }

    companion object {
        private const val TAG = "NtfyApiService"
    }
}
