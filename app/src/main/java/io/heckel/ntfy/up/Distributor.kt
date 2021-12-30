package io.heckel.ntfy.up

import android.content.Context
import android.content.Intent

class Distributor(val context: Context) {
    fun sendMessage(app: String, connectorToken: String, message: String) {
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_MESSAGE
        broadcastIntent.putExtra(EXTRA_TOKEN, connectorToken)
        broadcastIntent.putExtra(EXTRA_MESSAGE, message)
        context.sendBroadcast(broadcastIntent)
    }

    fun sendEndpoint(app: String, connectorToken: String, endpoint: String) {
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_NEW_ENDPOINT
        broadcastIntent.putExtra(EXTRA_TOKEN, connectorToken)
        broadcastIntent.putExtra(EXTRA_ENDPOINT, endpoint)
        context.sendBroadcast(broadcastIntent)
    }

    fun sendUnregistered(app: String, connectorToken: String) {
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_UNREGISTERED
        broadcastIntent.putExtra(EXTRA_TOKEN, connectorToken)
        context.sendBroadcast(broadcastIntent)
    }

    fun sendRegistrationRefused(app: String, connectorToken: String) {
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_REGISTRATION_REFUSED
        broadcastIntent.putExtra(EXTRA_TOKEN, connectorToken)
        context.sendBroadcast(broadcastIntent)
    }
}
