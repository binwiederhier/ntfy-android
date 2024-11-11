package io.heckel.ntfy.up

import android.content.Context
import android.content.Intent
import io.heckel.ntfy.util.Log

/**
 * This is the UnifiedPush distributor, an amalgamation of messages to be sent as part of the spec.
 * See https://unifiedpush.org/spec/android/ for details.
 */
class Distributor(val context: Context) {
    fun sendMessage(app: String, connectorToken: String, message: ByteArray) {
        Log.d(TAG, "Sending MESSAGE to $app (token=$connectorToken): ${message.size} bytes")
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_MESSAGE
        broadcastIntent.putExtra(EXTRA_TOKEN, connectorToken)
        broadcastIntent.putExtra(EXTRA_BYTES_MESSAGE, message)
        context.sendBroadcast(broadcastIntent)
    }

    fun sendEndpoint(app: String, connectorToken: String, endpoint: String) {
        Log.d(TAG, "Sending NEW_ENDPOINT to $app (token=$connectorToken): $endpoint")
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_NEW_ENDPOINT
        broadcastIntent.putExtra(EXTRA_TOKEN, connectorToken)
        broadcastIntent.putExtra(EXTRA_ENDPOINT, endpoint)
        context.sendBroadcast(broadcastIntent)
    }

    fun sendUnregistered(app: String, connectorToken: String) {
        Log.d(TAG, "Sending UNREGISTERED to $app (token=$connectorToken)")
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_UNREGISTERED
        broadcastIntent.putExtra(EXTRA_TOKEN, connectorToken)
        context.sendBroadcast(broadcastIntent)
    }

    fun sendRegistrationFailed(app: String, connectorToken: String, reason: FailedReason) {
        Log.d(TAG, "Sending REGISTRATION_FAILED to $app (token=$connectorToken)")
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_REGISTRATION_FAILED
        broadcastIntent.putExtra(EXTRA_TOKEN, connectorToken)
        broadcastIntent.putExtra(EXTRA_FAILED_REASON, reason)
        context.sendBroadcast(broadcastIntent)
    }

    companion object {
        private const val TAG = "NtfyUpDistributor"
    }
}
