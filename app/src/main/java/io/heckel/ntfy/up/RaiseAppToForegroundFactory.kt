package io.heckel.ntfy.up

import android.content.Context
import android.util.Log

/**
 * [RaiseAppToForeground] Factory, to avoid having one service connection per push message
 *
 * There is a very small chance that 2 connections exist at the same time\*, but that's not important.
 * We just want to avoid tens of it.
 *
 * \* When [getInstance] returns an existing instance, that runs [remove] before
 * [RaiseAppToForeground.raise] is called.
 */
object RaiseAppToForegroundFactory {
    fun getInstance(context: Context, app: String): RaiseAppToForeground {
        synchronized(this) {
            return instances[app] ?: new(context, app)
        }
    }

    private fun new(context: Context, app: String): RaiseAppToForeground {
        return RaiseAppToForeground(context, app, onUnbound = {
            remove(app)
        }).also {
            Log.d(TAG, "New instance for $app")
            instances[app] = it
        }
    }

    private fun remove(app: String) {
        Log.d(TAG, "Removing instance for $app")
        synchronized(this) {
            instances.remove(app)
        }
    }
    private val instances: MutableMap<String, RaiseAppToForeground> = mutableMapOf()
    private const val TAG = "RaiseAppToF.Factory"
}