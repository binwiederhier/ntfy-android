package io.heckel.ntfy.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

const val READ_TIMEOUT = 60_000 // Keep alive every 30s assumed

class ConnectionManager(private val repository: Repository) {
    private val jobs = mutableMapOf<Long, Job>()
    private val gson = GsonBuilder().create()
    private var listener: NotificationListener? = null;

    fun start(s: Subscription) {
        jobs[s.id] = launchConnection(s.id, topicJsonUrl(s))
    }

    fun stop(s: Subscription) {
        jobs.remove(s.id)?.cancel() // Cancel coroutine and remove
    }

    fun setListener(l: NotificationListener) {
        this.listener = l
    }

    private fun launchConnection(subscriptionId: Long, topicUrl: String): Job {
        return GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                openConnection(subscriptionId, topicUrl)
                delay(5000) // TODO exponential back-off
            }
        }
    }

    private fun openConnection(subscriptionId: Long, topicUrl: String) {
        println("Connecting to $topicUrl ...")
        val conn = (URL(topicUrl).openConnection() as HttpURLConnection).also {
            it.doInput = true
            it.readTimeout = READ_TIMEOUT
        }
        try {
            updateStatus(subscriptionId, Status.CONNECTED)
            val input = conn.inputStream.bufferedReader()
            while (GlobalScope.isActive) {
                val line = input.readLine() ?: break // Break if EOF is reached, i.e. readLine is null
                if (!GlobalScope.isActive) {
                    break // Break if scope is not active anymore; readLine blocks for a while, so we want to be sure
                }
                val json = gson.fromJson(line, JsonObject::class.java) ?: break // Break on unexpected line
                val validNotification = !json.isJsonNull
                        && !json.has("event") // No keepalive or open messages
                        && json.has("message")
                if (validNotification) {
                    notify(subscriptionId, json.get("message").asString)
                }
            }
        } catch (e: Exception) {
            println("Connection error: " + e)
        } finally {
            conn.disconnect()
        }
        updateStatus(subscriptionId, Status.RECONNECTING)
        println("Connection terminated: $topicUrl")
    }

    private fun updateStatus(subscriptionId: Long, status: Status) {
        val subscription = repository.get(subscriptionId)
        repository.update(subscription?.copy(status = status))
    }

    private fun notify(subscriptionId: Long, message: String) {
        val subscription = repository.get(subscriptionId)
        if (subscription != null) {
            listener?.let { it(Notification(subscription, message)) }
            repository.update(subscription.copy(messages = subscription.messages + 1))
        }
    }

    companion object {
        private var instance: ConnectionManager? = null

        fun getInstance(repository: Repository): ConnectionManager {
            return synchronized(ConnectionManager::class) {
                val newInstance = instance ?: ConnectionManager(repository)
                instance = newInstance
                newInstance
            }
        }
    }
}
