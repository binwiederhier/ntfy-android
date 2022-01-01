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

/**
 * The broadcast service is responsible for sending and receiving broadcast intents
 * in order to facilitate tasks app integrations.
 */
class BroadcastService(private val ctx: Context) {
    fun send(subscription: Subscription, notification: Notification, muted: Boolean) {
        val intent = Intent()
        intent.action = MESSAGE_RECEIVED_ACTION
        intent.putExtra("id", notification.id)
        intent.putExtra("base_url", subscription.baseUrl)
        intent.putExtra("topic", subscription.topic)
        intent.putExtra("time", notification.timestamp.toInt())
        intent.putExtra("title", notification.title)
        intent.putExtra("message", notification.message)
        intent.putExtra("tags", notification.tags)
        intent.putExtra("tags_map", joinTagsMap(splitTags(notification.tags)))
        intent.putExtra("priority", notification.priority)
        intent.putExtra("muted", muted)
        intent.putExtra("muted_str", muted.toString())

        Log.d(TAG, "Sending intent broadcast: $intent")
        ctx.sendBroadcast(intent)
    }

    /**
     * This receiver is triggered when the SEND_MESSAGE intent is received.
     * See AndroidManifest.xml for details.
     */
    class BroadcastReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Broadcast received: $intent")
            when (intent.action) {
                MESSAGE_SEND_ACTION -> send(context, intent)
            }
        }

        private fun send(ctx: Context, intent: Intent) {
            val api = ApiService()
            val baseUrl = getStringExtra(intent, "base_url") ?: ctx.getString(R.string.app_base_url)
            val topic = getStringExtra(intent, "topic") ?: return
            val message = getStringExtra(intent, "message") ?: return
            val title = getStringExtra(intent, "title") ?: ""
            val tags = getStringExtra(intent,"tags") ?: ""
            val priority = when (getStringExtra(intent, "priority")) {
                "min", "1" -> 1
                "low", "2" -> 2
                "default", "3" -> 3
                "high", "4" -> 4
                "urgent", "max", "5" -> 5
                else -> 0
            }
            val delay = getStringExtra(intent,"delay") ?: ""
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

        /**
         * Gets an extra as a String value, even if the extra may be an int or a long.
         */
        private fun getStringExtra(intent: Intent, name: String): String? {
            if (intent.getStringExtra(name) != null) {
                return intent.getStringExtra(name)
            } else if (intent.getIntExtra(name, DOES_NOT_EXIST) != DOES_NOT_EXIST) {
                return intent.getIntExtra(name, DOES_NOT_EXIST).toString()
            } else if (intent.getLongExtra(name, DOES_NOT_EXIST.toLong()) != DOES_NOT_EXIST.toLong()) {
                return intent.getLongExtra(name, DOES_NOT_EXIST.toLong()).toString()
            }
            return null
        }
    }

    companion object {
        private const val TAG = "NtfyBroadcastService"
        private const val MESSAGE_RECEIVED_ACTION = "io.heckel.ntfy.MESSAGE_RECEIVED"
        private const val MESSAGE_SEND_ACTION = "io.heckel.ntfy.SEND_MESSAGE" // If changed, change in manifest too!
        private const val DOES_NOT_EXIST = -2586000
    }
}
