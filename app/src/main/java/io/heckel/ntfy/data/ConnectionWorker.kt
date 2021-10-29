package io.heckel.ntfy.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import io.heckel.ntfy.R
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class ConnectionWorker(val ctx: Context, workerParams: WorkerParameters) : CoroutineWorker(ctx, workerParams) {
    private val gson = GsonBuilder().create()

    override suspend fun doWork(): Result {
        println("PHIL work started")

        while (isStopped) {
            openConnection(Random.nextLong(), "https://ntfy.sh/test/json")
        }

        println("PHIL work ended")
        // Indicate whether the work finished successfully with the Result
        return Result.success()
    }

    private fun openConnection(subscriptionId: Long, topicUrl: String) {
        println("Connecting to $topicUrl ...")
        val conn = (URL(topicUrl).openConnection() as HttpURLConnection).also {
            it.doInput = true
            it.readTimeout = READ_TIMEOUT
        }
        try {
            println("PHIL connected")
            val input = conn.inputStream.bufferedReader()
            while (isStopped) {
                val line = input.readLine() ?: break // Break if EOF is reached, i.e. readLine is null
                val json = gson.fromJson(line, JsonObject::class.java) ?: break // Break on unexpected line
                val validNotification = !json.isJsonNull
                        && !json.has("event") // No keepalive or open messages
                        && json.has("message")
                if (validNotification) {
                    val title = "ntfy.sh/test"
                    val message = json.get("message").asString
                    displayNotification(title, message)
                    println("notification received: ${json.get("message").asString}")
                }
            }
        } catch (e: Exception) {
            println("Connection error: " + e)
        } finally {
            conn.disconnect()
        }
        println("Connection terminated: $topicUrl")
    }

    private fun displayNotification(title: String, message: String) {
        val notificationManager =
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ctx.getString(R.string.notification_channel_id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = ctx.getString(R.string.notification_channel_name)
            val descriptionText = ctx.getString(R.string.notification_channel_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ntfy)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(Random.nextInt(), notification)
    }

    /**
     * Create the NotificationChannel, but only on API 26+ because
     * the NotificationChannel class is new and not in the support library
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = ctx.getString(R.string.notification_channel_id)
            val name = ctx.getString(R.string.notification_channel_name)
            val descriptionText = ctx.getString(R.string.notification_channel_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

}
