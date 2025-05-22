package io.heckel.ntfy.up

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log

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
        private val TAG = LinkActivity::class.simpleName
    }
}