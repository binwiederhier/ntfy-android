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

/**
 * Raises the target application to foreground importance before delivering a UnifiedPush message.
 *
 * ## Background
 *
 * Starting with Android 12 (API level 31), apps running in the background are restricted from
 * starting foreground services. This is problematic for push notification scenarios where the
 * target app (e.g., a messenger like Element, Molly, etc.) needs to start a foreground service
 * to process incoming messages reliably.
 *
 * See: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
 *
 * ## How This Works
 *
 * This class implements the **AND_3 specification** from UnifiedPush, which defines a mechanism
 * for distributors (like ntfy) to "raise" the target application to foreground importance before
 * sending the push message. Here's the flow:
 *
 * 1. **Check ntfy's foreground status**: When ntfy receives a push notification (via FCM or
 *    WebSocket), it temporarily has foreground importance (IMPORTANCE_FOREGROUND or
 *    IMPORTANCE_FOREGROUND_SERVICE). The [checkForeground] method verifies this.
 *
 * 2. **Check target app support**: The target app must export a service with the action
 *    `org.unifiedpush.android.connector.RAISE_TO_FOREGROUND`. This is checked via
 *    [hasRaiseToForegroundService].
 *
 * 3. **Bind to target's service**: If both conditions are met, ntfy binds to the target app's
 *    "raise to foreground" service using [Context.bindService] with [Context.BIND_AUTO_CREATE].
 *    **This is the key mechanism**: when a foreground process binds to a service, the Android
 *    system grants foreground importance to the bound service's process. This allows the target
 *    app to escape background restrictions and start its own foreground service if needed.
 *
 * 4. **Send the message**: Once bound (or immediately if binding isn't possible), the push
 *    message is delivered via a broadcast with action `org.unifiedpush.android.connector.MESSAGE`.
 *
 * 5. **Maintain binding briefly**: The binding is kept alive for 5 seconds (or extended if more
 *    messages arrive) to give the target app time to start its foreground service. After the
 *    timeout, the binding is released via [unbind].
 *
 * ## Why "Foreground" Matters
 *
 * "Foreground" in this context does NOT mean the app is visible on screen (alt-tab style).
 * Rather, it refers to the app's **process importance level** as tracked by the Android system.
 * An app with foreground importance:
 * - Is exempt from doze mode restrictions
 * - Can start foreground services from the background
 * - Has higher priority and is less likely to be killed
 *
 * This mechanism allows ntfy to temporarily "lend" its foreground importance to the target app,
 * enabling reliable push notification processing even when both apps are in the background.
 *
 * ## Fallback Behavior
 *
 * If ntfy isn't in the foreground or the target app doesn't support the raise-to-foreground
 * service, the message is sent directly via broadcast without the binding step. This maintains
 * backward compatibility with older apps but may result in delayed or missed notifications
 * on devices with aggressive battery optimization.
 *
 * ## Connection Lifecycle
 *
 * - [Bound.Unbound]: No active connection. Will attempt to bind on next message.
 * - [Bound.Binding]: Binding in progress. Messages are queued until connected.
 * - [Bound.Bound]: Connected. Messages are sent immediately.
 *
 * The connection auto-unbinds after 5 seconds of inactivity. Subsequent messages reset this timer.
 *
 * @param context The application context for binding services and sending broadcasts.
 * @param app The package name of the target application.
 * @param onUnbound Callback invoked when the service connection is unbound (used by
 *                  [RaiseAppToForegroundFactory] for cleanup).
 *
 * @see RaiseAppToForegroundFactory For singleton management of instances per app.
 * @see Distributor.sendMessage Entry point for sending UnifiedPush messages.
 */
class RaiseAppToForeground(private val context: Context, private val app: String, private val onUnbound: () -> Unit) : ServiceConnection, Runnable {
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
        return (if (Build.VERSION.SDK_INT >= 33) {
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
        }).any {
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
        private const val TAG = "NtfyUpRaiseFg"
        private const val ACTION = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"

        /** Executor to unbind 5 seconds later */
        private val unbindExecutor = Executors.newSingleThreadScheduledExecutor()
    }
}