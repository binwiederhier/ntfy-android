package io.heckel.ntfy.up

import android.content.Context
import android.content.Intent
import io.heckel.ntfy.R
import io.heckel.ntfy.util.topicUrlUp

fun sendMessage(context: Context, app: String, token: String, message: String) {
    val broadcastIntent = Intent()
    broadcastIntent.`package` = app
    broadcastIntent.action = ACTION_MESSAGE
    broadcastIntent.putExtra(EXTRA_TOKEN, token)
    broadcastIntent.putExtra(EXTRA_MESSAGE, message)
    context.sendBroadcast(broadcastIntent)
}

fun sendEndpoint(context: Context, app: String, token: String) {
    val appBaseUrl = context.getString(R.string.app_base_url)
    val broadcastIntent = Intent()
    broadcastIntent.`package` = app
    broadcastIntent.action = ACTION_NEW_ENDPOINT
    broadcastIntent.putExtra(EXTRA_TOKEN, token)
    broadcastIntent.putExtra(EXTRA_ENDPOINT, topicUrlUp(appBaseUrl, token))
    context.sendBroadcast(broadcastIntent)
}

fun sendUnregistered(context: Context, app: String, token: String) {
    val broadcastIntent = Intent()
    broadcastIntent.`package` = app
    broadcastIntent.action = ACTION_UNREGISTERED
    broadcastIntent.putExtra(EXTRA_TOKEN, token)
    context.sendBroadcast(broadcastIntent)
}

