package io.heckel.ntfy.up

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Runnable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RaiseAppToForeground(private val context: Context, private val app: String, private val onUnbound: () -> Unit): ServiceConnection, Runnable {

    class Message(val token: String, val content: ByteArray)

    private enum class Bound {
        Binding,
        Bound,
        Unbound
    }

    /**
     * Is the service bound ? This is a per service connection
     */
    private var bound = Bound.Unbound
    private var scheduledFuture: ScheduledFuture<*>? = null
    private val msgsQueue = mutableListOf<Message>()

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


    private fun hasRaiseToForegroundService(): Boolean {
        val intent = Intent(ACTION).apply {
            `package` = app
        }
        return (
                if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager.queryIntentServices(
                        intent,
                        PackageManager.ResolveInfoFlags.of(
                            PackageManager.GET_META_DATA.toLong() +
                                    PackageManager.GET_RESOLVED_FILTER.toLong(),
                        ),
                    )
                } else {
                    context.packageManager.queryIntentServices(
                        Intent(ACTION_REGISTER),
                        PackageManager.GET_RESOLVED_FILTER,
                    )
                }
                ).any {
                it.serviceInfo.exported
            }
    }


    private fun send(message: Message) {
        Log.d(TAG, "Sending msg for $app")
        val broadcastIntent = Intent()
        broadcastIntent.`package` = app
        broadcastIntent.action = ACTION_MESSAGE
        broadcastIntent.putExtra(EXTRA_TOKEN, message.token)
        broadcastIntent.putExtra(EXTRA_BYTES_MESSAGE, message.content)
        context.sendBroadcast(broadcastIntent)
    }

    /**
     * Queue message when the service is binding
     */
    private fun queue(message: Message) {
        msgsQueue.add(message)
    }

    /**
     * If the service is already bound, we delay its unbinding
     */
    private fun delayUnbinding() {
        /**
         * Close current scheduledFuture. We interrupt if it is running (mayInterruptIfRunning = true), so [run] won't
         * unbind this new connection after we release the lock.
         */
        scheduledFuture?.cancel(true)
        /** Call [run] (unbind) in 5 seconds */
        scheduledFuture = unbindExecutor.schedule(this, 5L, TimeUnit.SECONDS)
    }

    private fun bind() {
        Log.d(TAG, "Binding to ${this.app}")
        val intent = Intent().apply {
            `package` = this@RaiseAppToForeground.app
            action = ACTION
        }
        /** Bind to the target raise to the foreground service */
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        /** Call [run] (unbind) in 5 seconds */
        scheduledFuture = unbindExecutor.schedule(this, 5L, TimeUnit.SECONDS)
        bound = Bound.Binding
    }

    /**
     * Raise [app] to the foreground, to follow AND_3 specifications
     *
     * @return `true` if have successfully raised app to foreground
     */
    fun raiseAndSend(token: String, message: ByteArray): Boolean {
        val msg = Message(token, message)
        // Per instance lock
        synchronized(this) {
            when (bound) {
                Bound.Bound -> {
                    Log.d(TAG, "Service connection already bound to ${this.app}")
                    delayUnbinding()
                    send(msg)
                }
                Bound.Binding -> {
                    delayUnbinding()
                    queue(msg)
                }
                Bound.Unbound -> {
                    val isForeground = checkForeground()
                    val targetHasService = hasRaiseToForegroundService()
                    if (isForeground && targetHasService) {
                        bind()
                        queue(msg)
                    } else {
                        Log.d(
                            TAG,
                            "Cannot raise to foreground: isForeground=$isForeground, targetHasService=$targetHasService"
                        )
                        send(msg)
                        return false
                    }
                }
            }
            return true
        }
    }

    private fun unbind() {
        // Per instance lock
        synchronized(this) {
            if (bound != Bound.Unbound) {
                msgsQueue.clear()
                context.unbindService(this)
                bound = Bound.Unbound
                onUnbound()
                Log.d(TAG, "Unbound")
            }
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.d(TAG, "onServiceConnected $name")
        synchronized(this) {
            bound = Bound.Bound
        }
        msgsQueue.forEach { msg ->
            send(msg)
        }
        msgsQueue.clear()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "onServiceDisconnected $name")
        unbind()
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