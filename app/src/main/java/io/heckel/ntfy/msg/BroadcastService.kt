package io.heckel.ntfy.msg

import android.content.Context
import android.content.Intent
import android.util.Log
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.util.joinTagsMap
import io.heckel.ntfy.util.splitTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class BroadcastService(private val ctx: Context) {
    fun send(subscription: Subscription, notification: Notification, muted: Boolean) {
        val intent = Intent()
        intent.action = MESSAGE_RECEIVED_ACTION
        intent.putExtra("id", notification.id)
        intent.putExtra("base_url", subscription.baseUrl)
        intent.putExtra("topic", subscription.topic)
        intent.putExtra("title", notification.title)
        intent.putExtra("message", notification.message)
        intent.putExtra("tags", notification.tags)
        intent.putExtra("tags_map", joinTagsMap(splitTags(notification.tags)))
        intent.putExtra("priority", notification.priority)
        intent.putExtra("muted", muted)

        Log.d(TAG, "Sending intent broadcast: $intent")
        ctx.sendBroadcast(intent)
    }

    class BroadcastReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Broadcast received: $intent")
            when (intent.action) {
                MESSAGE_SEND_ACTION -> send(context, intent)
            }
        }

        private fun send(ctx: Context, intent: Intent) {
            val api = ApiService()
            val topic = intent.getStringExtra("topic") ?: return
            val message = intent.getStringExtra("message") ?: return
            val baseUrl = intent.getStringExtra("base_url") ?: ctx.getString(R.string.app_base_url)
            val title = intent.getStringExtra("title") ?: ""
            val tags = intent.getStringExtra("tags") ?: ""
            val priority = if (intent.getStringExtra("priority") != null) {
                when (intent.getStringExtra("priority")) {
                    "min", "1" -> 1
                    "low", "2" -> 2
                    "default", "3" -> 3
                    "high", "4" -> 4
                    "urgent", "max", "5" -> 5
                    else -> 0
                }
            } else {
                intent.getIntExtra("priority", 0)
            }
            val delay = intent.getStringExtra("delay") ?: ""
            GlobalScope.launch(Dispatchers.IO) {
                api.publish(
                    baseUrl = baseUrl,
                    topic = topic,
                    message = message,
                    title = title,
                    priority = priority,
                    tags = splitTags(tags),
                    delay = delay
                )
            }
        }
    }

    companion object {
        private const val TAG = "NtfyBroadcastService"
        private const val MESSAGE_RECEIVED_ACTION = "io.heckel.ntfy.MESSAGE_RECEIVED"
        private const val MESSAGE_SEND_ACTION = "io.heckel.ntfy.SEND_MESSAGE" // If changed, change in manifest too!
    }
}
