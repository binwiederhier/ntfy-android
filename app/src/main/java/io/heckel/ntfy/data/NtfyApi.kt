package io.heckel.ntfy.data

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Event(val name: String = "", val data: JsonObject = JsonObject())

class NtfyApi(context: Context) {
    private val gson = GsonBuilder().create()

    fun createEventsFlow(url: String): Flow<Event> = flow {
        coroutineScope {
            val conn = getStreamConnection(url)
            println("2222222222222")
            val input = conn.inputStream.bufferedReader()
            try {
                conn.connect()
                var event = Event()
                while (isActive) {
                    val line = input.readLine()
                    println("PHIL: " + line)
                    when {
                        line == null -> {
                            this.cancel(CancellationException("EOF"))
                            break
                        }
                        line.startsWith("event:") -> {
                            event = event.copy(name = line.substring(6).trim())
                        }
                        line.startsWith("data:") -> {
                            val data = line.substring(5).trim()
                            try {
                                event = event.copy(data = gson.fromJson(data, JsonObject::class.java))
                            } catch (e: JsonSyntaxException) {
                                // Nothing
                            }
                        }
                        line.isEmpty() -> {
                            emit(event)
                            event = Event()
                        }
                    }
                }
            } catch (e: IOException) {
                println("PHIL: " + e.message)
                this.cancel(CancellationException("Network Problem", e))
            } finally {
                conn.disconnect()
                input.close()
            }
        }
    }

    private suspend fun getStreamConnection(url: String): HttpURLConnection =
        withContext(Dispatchers.IO) {
            return@withContext (URL(url).openConnection() as HttpURLConnection).also {
                it.doInput = true
            }
        }
}
