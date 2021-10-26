/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.heckel.ntfy.list

import android.content.Context
import androidx.lifecycle.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import io.heckel.ntfy.data.DataSource
import io.heckel.ntfy.data.Topic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Notification(val topic: String, val message: String)
typealias NotificationListener = (notification: Notification) -> Unit

class TopicsViewModel(val datasource: DataSource) : ViewModel() {
    private val gson = GsonBuilder().create()
    private val jobs = mutableMapOf<Long, Job>()
    private var notificationListener: NotificationListener? = null;

    fun add(topic: Topic) {
        println("Adding topic $topic $this")
        datasource.add(topic)
        jobs[topic.id] = subscribeTopic(topic.url)
    }

    fun get(id: Long) : Topic? {
        return datasource.get(id)
    }

    fun list(): LiveData<List<Topic>> {
        return datasource.list()
    }

    fun remove(topic: Topic) {
        println("Removing topic $topic $this")
        jobs[topic.id]?.cancel()
        println("${jobs[topic.id]}")

        jobs.remove(topic.id)?.cancel() // Cancel and remove
        println("${jobs[topic.id]}")
        datasource.remove(topic)
    }

    fun setNotificationListener(listener: NotificationListener) {
        notificationListener = listener
    }

    private fun subscribeTopic(url: String): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                openURL(this, url)
                delay(5000) // TODO exponential back-off
            }
        }
    }

    private fun openURL(scope: CoroutineScope, url: String) {
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
}

class TopicsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TopicsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TopicsViewModel(
                datasource = DataSource.getDataSource(context.resources)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
