package io.heckel.ntfy.up

import android.content.Context
import android.content.Intent
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.util.topicUrlUp

class Distributor(val context: Context) {
    fun sendMessage(app: String, token: String, message: String) {
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_MESSAGE
        broadcastIntent.putExtra(EXTRA_TOKEN, token)
        broadcastIntent.putExtra(EXTRA_MESSAGE, message)
        context.sendBroadcast(broadcastIntent)
    }

    fun sendEndpoint(app: String, token: String) {
        val appBaseUrl = context.getString(R.string.app_base_url) // FIXME
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_NEW_ENDPOINT
        broadcastIntent.putExtra(EXTRA_TOKEN, token)
        broadcastIntent.putExtra(EXTRA_ENDPOINT, topicUrlUp(appBaseUrl, token))
        context.sendBroadcast(broadcastIntent)
    }

    fun sendUnregistered(app: String, token: String) {
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_UNREGISTERED
        broadcastIntent.putExtra(EXTRA_TOKEN, token)
        context.sendBroadcast(broadcastIntent)
    }
}
