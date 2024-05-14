package io.heckel.ntfy.msg

import android.content.Context
import android.content.Intent
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * The broadcast service is responsible for sending and receiving broadcast intents
 * in order to facilitate tasks app integrations.
 */
class BroadcastService(private val ctx: Context) {
    fun sendMessage(subscription: Subscription, notification: Notification, muted: Boolean) {
        val intent = Intent()
        intent.action = MESSAGE_RECEIVED_ACTION
        intent.putExtra("id", notification.id)
        intent.putExtra("base_url", subscription.baseUrl)
        intent.putExtra("topic", subscription.topic)
        intent.putExtra("time", notification.timestamp.toInt())
        intent.putExtra("title", notification.title)
        intent.putExtra("message", decodeMessage(notification))
        intent.putExtra("message_bytes", decodeBytesMessage(notification))
        intent.putExtra("message_encoding", notification.encoding)
        intent.putExtra("content_type", notification.contentType)
        intent.putExtra("tags", notification.tags)
        intent.putExtra("tags_map", joinTagsMap(splitTags(notification.tags)))
        intent.putExtra("priority", notification.priority)
        intent.putExtra("click", notification.click)
        intent.putExtra("muted", muted)
        intent.putExtra("muted_str", muted.toString())
        intent.putExtra("attachment_name", notification.attachment?.name ?: "")
        intent.putExtra("attachment_type", notification.attachment?.type ?: "")
        intent.putExtra("attachment_size", notification.attachment?.size ?: 0L)
        intent.putExtra("attachment_expires", notification.attachment?.expires ?: 0L)
        intent.putExtra("attachment_url", notification.attachment?.url ?: "")

        Log.d(TAG, "Sending message intent broadcast: ${intent.action} with extras ${intent.extras}")
        ctx.sendBroadcast(intent)
    }

    fun sendUserAction(action: Action) {
        val intent = Intent()
        intent.action = action.intent ?: USER_ACTION_ACTION
        action.extras?.forEach { (key, value) ->
            intent.putExtra(key, value)
        }
        Log.d(TAG, "Sending user action intent broadcast: ${intent.action} with extras ${intent.extras}")
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
                val repository = Repository.getInstance(ctx)
                val user = repository.getUser(baseUrl) // May be null
                try {
                    Log.d(TAG, "Publishing message $intent")
                    api.publish(
                        baseUrl = baseUrl,
                        topic = topic,
                        user = user,
                        message = message,
                        title = title,
                        priority = priority,
                        tags = splitTags(tags),
                        delay = delay
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to publish message: ${e.message}", e)
                }
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
        private const val DOES_NOT_EXIST = -2586000

        // These constants cannot be changed without breaking the contract; also see manifest
        private const val MESSAGE_RECEIVED_ACTION = "io.heckel.ntfy.MESSAGE_RECEIVED"
        private const val MESSAGE_SEND_ACTION = "io.heckel.ntfy.SEND_MESSAGE"
        private const val USER_ACTION_ACTION = "io.heckel.ntfy.USER_ACTION"
    }
}
