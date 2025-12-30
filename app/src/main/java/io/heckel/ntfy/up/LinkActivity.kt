package io.heckel.ntfy.up

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * This implements the "Select default distributor" selection for UnifiedPush.
 *
 * To test, install ntfy and another distributor (e.g. SunUp) on the same phone.
 * Install an app that uses UnifiedPush (e.g. UP Example) and click "Register".
 *
 * You should see a popup to select the default distributor.
 * See https://unifiedpush.org/developers/spec/android/#link-activity
 */
class LinkActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.run {
            Log.d(TAG, "Received request for $callingPackage")
            val intent = Intent("org.unifiedpush.register.dummy_app")
            val pendingIntent = PendingIntent.getBroadcast(this@LinkActivity, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val result = Intent().apply {
                putExtra(EXTRA_PI, pendingIntent)
            }
            setResult(RESULT_OK, result)
        } ?: setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private val TAG = "NtfyUpLinkActivity"
    }
}
