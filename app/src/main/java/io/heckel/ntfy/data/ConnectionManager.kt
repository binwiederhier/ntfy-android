package io.heckel.ntfy.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val READ_TIMEOUT = 60_000 // Keep alive every 30s assumed

class ConnectionManager {
    private val jobs = mutableMapOf<Long, Job>()
    private val gson = GsonBuilder().create()
    private var listener: ConnectionListener? = null;

    fun start(subscription: Subscription, scope: CoroutineScope) {
        jobs[subscription.id] = launchConnection(subscription, scope)
    }

    fun stop(subscription: Subscription) {
        jobs.remove(subscription.id)?.cancel() // Cancel coroutine and remove
    }

    fun setListener(listener: ConnectionListener) {
        this.listener = listener
    }

    private fun launchConnection(subscription: Subscription, scope: CoroutineScope): Job {
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                openConnection(this, subscription)
                delay(5000) // TODO exponential back-off
            }
        }
    }

    private fun openConnection(scope: CoroutineScope, subscription: Subscription) {
        val url = "${subscription.baseUrl}/${subscription.topic}/json"
        println("Connecting to $url ...")
        val conn = (URL(url).openConnection() as HttpURLConnection).also {
            it.doInput = true
            it.readTimeout = READ_TIMEOUT
        }
        try {
            listener?.onStatusChanged(subscription.id, Status.CONNECTED)
            val input = conn.inputStream.bufferedReader()
            while (scope.isActive) {
                val line = input.readLine() ?: break // Break if EOF is reached, i.e. readLine is null
                if (!scope.isActive) {
                    break // Break if scope is not active anymore; readLine blocks for a while, so we want to be sure
                }
                try {
                    val json = gson.fromJson(line, JsonObject::class.java) ?: break // Break on unexpected line
                    if (!json.isJsonNull && !json.has("event") && json.has("message")) {
                        val message = json.get("message").asString
                        listener?.onNotification(subscription.id, Notification(subscription, message))
                    }
                } catch (e: JsonSyntaxException) {
                    break // Break on unexpected line
                }
            }
        } catch (e: IOException) {
            println("Connection error: " + e.message)
        } finally {
            conn.disconnect()
        }
        listener?.onStatusChanged(subscription.id, Status.CONNECTING)
        println("Connection terminated: $url")
    }

    companion object {
        private var instance: ConnectionManager? = null

        fun getInstance(): ConnectionManager {
            return synchronized(ConnectionManager::class) {
                val newInstance = instance ?: ConnectionManager()
                instance = newInstance
                newInstance
            }
        }
    }
}
