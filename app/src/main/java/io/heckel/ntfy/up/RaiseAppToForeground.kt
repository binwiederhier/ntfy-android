package io.heckel.ntfy.up

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Runnable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RaiseAppToForeground(private val context: Context, private val app: String, private val onUnbound: () -> Unit): ServiceConnection, Runnable {

    /**
     * Is the service bound ? This is a per service connection
     */
    private var bound = false
    private var scheduledFuture: ScheduledFuture<*>? = null

    private val foregroundImportance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        listOf(
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
        )
    } else {
        listOf(
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        )
    }

    /**
     * @return `true` if the app is in Foreground importance
     */
    private fun checkForeground(): Boolean {
        val appProcesses = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
        for (appProcess in appProcesses) {
            if (appProcess.importance in foregroundImportance) {
                Log.i(TAG, "Found foreground process: ${appProcess.processName}")
                return true
            }
        }
        return false
    }

    /**
     * Raise [app] to the foreground, to follow AND_3 specifications
     *
     * @return `true` if have successfully raised app to foreground
     */
    fun raise(): Boolean {
        // Per instance lock
        synchronized(this) {
            if (bound) {
                Log.w(TAG, "This service connection is already bound to $app. Aborting.")
                /**
                 * Close current scheduledFuture. We interrupt if it is running, so [run] won't
                 * unbind this new connection after we release the lock.
                 */
                scheduledFuture?.cancel(/* mayInterruptIfRunning = */ true)
                /** Call [run] (unbind) in 5 seconds */
                scheduledFuture = unbindExecutor.schedule(this, 5L, TimeUnit.SECONDS)
                return true
            } else if (checkForeground()) {
                Log.d(TAG, "Binding to $app")
                val intent = Intent().apply {
                    `package` = app
                    action = ACTION
                }
                //val sConnection = RaiseAppToForeground(context, app)
                /** Bind to the target raise to the foreground service */
                context.bindService(intent, this, Context.BIND_AUTO_CREATE)
                /** Call [run] (unbind) in 5 seconds */
                scheduledFuture = unbindExecutor.schedule(this, 5L, TimeUnit.SECONDS)
                Log.d(TAG, "Bound to $app")
                bound = true
                return true
            } else {
                Log.d(TAG, "We are not in foreground, can't raise $app to foreground")
                return false
            }
        }
    }

    private fun unbind() {
        // Per instance lock
        synchronized(this) {
            if (bound) {
                context.unbindService(this)
                bound = false
                onUnbound()
                Log.d(TAG, "Unbound")
            }
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.d(TAG, "onServiceConnected $name")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "onServiceDisconnected $name")
    }

    override fun onBindingDied(name: ComponentName?) {
        Log.d(TAG, "onBindingDied")
        unbind()
    }

    override fun onNullBinding(name: ComponentName?) {
        Log.d(TAG, "onBindingDied")
        unbind()
    }

    /**
     * Unbinding when the timeout passes.
     */
    override fun run() {
        Log.d(TAG, "Timeout expired, unbinding")
        unbind()
    }

    private companion object {
        private const val TAG = "RaiseAppToForeground"
        private const val ACTION = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"
        /** Executor to unbind 5 seconds later */
        private val unbindExecutor = Executors.newSingleThreadScheduledExecutor()
    }
}