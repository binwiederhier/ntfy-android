package io.heckel.ntfy.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import io.heckel.ntfy.Notification
import io.heckel.ntfy.NotificationListener
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/* Handles operations on topicsLiveData and holds details about it. */
class TopicsRepository {
    private val topics: MutableLiveData<List<Topic>> = MutableLiveData(mutableListOf())
    private val jobs = mutableMapOf<Long, Job>()
    private val gson = GsonBuilder().create()
    private var notificationListener: NotificationListener? = null;

    /* Adds topic to liveData and posts value. */
    fun add(topic: Topic, scope: CoroutineScope) {
        val currentList = topics.value
        if (currentList == null) {
            topics.postValue(listOf(topic))
        } else {
            val updatedList = currentList.toMutableList()
            updatedList.add(0, topic)
            topics.postValue(updatedList)
        }
        jobs[topic.id] = subscribeTopic(topic, scope)
    }

    /* Removes topic from liveData and posts value. */
    fun remove(topic: Topic) {
        val currentList = topics.value
        if (currentList != null) {
            val updatedList = currentList.toMutableList()
            updatedList.remove(topic)
            topics.postValue(updatedList)
        }
        jobs.remove(topic.id)?.cancel() // Cancel and remove
    }

    /* Returns topic given an ID. */
    fun get(id: Long): Topic? {
        topics.value?.let { topics ->
            return topics.firstOrNull{ it.id == id}
        }
        return null
    }

    fun list(): LiveData<List<Topic>> {
        return topics
    }

    fun setNotificationListener(listener: NotificationListener) {
        notificationListener = listener
    }

    private fun subscribeTopic(topic: Topic, scope: CoroutineScope): Job {
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                openURL(this, topic.url, topic.url) // TODO
                delay(5000) // TODO exponential back-off
            }
        }
    }

    private fun openURL(scope: CoroutineScope, topic: String, url: String) {
        println("Connecting to $url ...")
        val conn = (URL(url).openConnection() as HttpURLConnection).also {
            it.doInput = true
        }
        try {
            val input = conn.inputStream.bufferedReader()
            while (scope.isActive) {
                val line = input.readLine() ?: break // Exit if null
                try {
                    val json = gson.fromJson(line, JsonObject::class.java) ?: break // Exit if null
                    if (!json.isJsonNull && json.has("message")) {
                        val message = json.get("message").asString
                        notificationListener?.let { it(Notification(url, message)) }
                    }
                } catch (e: JsonSyntaxException) {
                    // Ignore invalid JSON
                }
            }
        } catch (e: IOException) {
            println("PHIL: " + e.message)
        } finally {
            conn.disconnect()
        }
        println("Connection terminated: $url")
    }

    companion object {
        private var instance: TopicsRepository? = null

        fun getInstance(): TopicsRepository {
            return synchronized(TopicsRepository::class) {
                val newInstance = instance ?: TopicsRepository()
                instance = newInstance
                newInstance
            }
        }
    }
}
