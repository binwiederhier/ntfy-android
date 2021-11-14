package io.heckel.ntfy.msg

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicUrl
import io.heckel.ntfy.ui.MainActivity
import kotlinx.coroutines.*
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 *
 * Largely modeled after this fantastic resource:
 * - https://robertohuertas.com/2019/06/29/android_foreground_services/
 * - https://github.com/robertohuertasm/endless-service/blob/master/app/src/main/java/com/robertohuertas/endless/EndlessService.kt
 */
class SubscriberService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private val repository by lazy { (application as Application).repository }
    private val jobs = ConcurrentHashMap<Long, Job>() // Subscription ID -> Job
    private val calls = ConcurrentHashMap<Long, Call>() // Subscription ID -> Cal
    private val api = ApiService()
    private val notifier = NotificationService(this)

    override fun onBind(intent: Intent): IBinder? {
        return null // We don't provide binding, so return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand executed with startId: $startId")
        if (intent != null) {
            val action = intent.action
            Log.d(TAG, "using an intent with action $action")
            when (action) {
                Actions.START.name -> startService()
                Actions.STOP.name -> stopService()
                else -> Log.e(TAG, "This should never happen. No action in the received intent")
            }
        } else {
            Log.d(TAG, "with a null intent. It has been probably restarted by the system.")
        }
        return START_STICKY // restart if system kills the service
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "The service has been created".toUpperCase())
        val notification = createNotification()
        startForeground(SERVICE_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "The service has been destroyed".toUpperCase())
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, SubscriberService::class.java).also {
            it.setPackage(packageName)
        };
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT);
        applicationContext.getSystemService(Context.ALARM_SERVICE);
        val alarmService: AlarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager;
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent);
    }

    private fun startService() {
        if (isServiceStarted) {
            launchOrCancelJobs()
            return
        }
        Log.d(TAG, "Starting the foreground service task")
        isServiceStarted = true
        saveServiceState(this, ServiceState.STARTED)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                acquire()
            }
        }
        launchOrCancelJobs()
    }

    private fun stopService() {
        Log.d(TAG, "Stopping the foreground service")

        // Cancelling all remaining jobs and open HTTP calls
        jobs.values.forEach { job -> job.cancel() }
        calls.values.forEach { call -> call.cancel() }
        jobs.clear()
        calls.clear()

        // Releasing wake-lock and stopping ourselves
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            Log.d(TAG, "Service stopped without being started: ${e.message}")
        }

        isServiceStarted = false
        saveServiceState(this, ServiceState.STOPPED)
    }

    private fun launchOrCancelJobs() = GlobalScope.launch(Dispatchers.IO) {
        val subscriptions = repository.getSubscriptions().filter { s -> s.instant }
        val subscriptionIds = subscriptions.map { it.id }
        Log.d(TAG, "Starting/stopping jobs for current subscriptions")
        Log.d(TAG, "- Subscriptions: $subscriptions")
        Log.d(TAG, "- Jobs: $jobs")
        Log.d(TAG, "- HTTP calls: $calls")
        subscriptions.forEach { subscription ->
            if (!jobs.containsKey(subscription.id)) {
                Log.d(TAG, "Starting job for $subscription")
                jobs[subscription.id] = launchJob(this, subscription)
            }
        }
        jobs.keys().toList().forEach { subscriptionId ->
            if (!subscriptionIds.contains(subscriptionId)) {
                Log.d(TAG, "Cancelling job for $subscriptionId")
                val job = jobs.remove(subscriptionId)
                val call = calls.remove(subscriptionId)
                job?.cancel()
                call?.cancel()
            }
        }
    }

    private fun launchJob(scope: CoroutineScope, subscription: Subscription): Job = scope.launch(Dispatchers.IO) {
        val url = topicUrl(subscription.baseUrl, subscription.topic)
        Log.d(TAG, "[$url] Starting connection job")
        var since = 0L
        var retryMillis = 0L
        while (isActive && isServiceStarted) {
            Log.d(TAG, "[$url] (Re-)starting subscription for $subscription")
            val startTime = System.currentTimeMillis()

            try {
                val failed = AtomicBoolean(false)
                val notify = { n: io.heckel.ntfy.data.Notification ->
                    Log.d(TAG, "[$url] Received new notification: $n")
                    since = n.timestamp
                    scope.launch(Dispatchers.IO) {
                        val added = repository.addNotification(n)
                        if (added) {
                            Log.d(TAG, "[$url] Showing notification: $n")
                            notifier.send(subscription, n.message)
                        }
                    }
                    Unit
                }
                val fail = { e: Exception ->
                    Log.e(TAG, "[$url] Connection failed (1): ${e.message}", e)
                    failed.set(true)
                }
                val call = api.subscribe(subscription.id, subscription.baseUrl, subscription.topic, since, notify, fail)
                calls[subscription.id] = call
                while (!failed.get() && !call.isCanceled() && isActive && isServiceStarted) {
                    Log.d(TAG, "[$url] Connection is active (failed=$failed, callCanceled=${call.isCanceled()}, jobActive=$isActive, serviceStarted=$isServiceStarted")
                    delay(CONNECTION_LOOP_DELAY_MILLIS) // Resumes immediately if job is cancelled
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$url] Connection failed (2): ${e.message}", e)
            }
            if (isActive && isServiceStarted) {
                val connectionDurationMillis = System.currentTimeMillis() - startTime
                if (connectionDurationMillis > RETRY_RESET_AFTER_MILLIS) {
                    retryMillis = RETRY_STEP_MILLIS
                } else if (retryMillis + RETRY_STEP_MILLIS >= RETRY_MAX_MILLIS) {
                    retryMillis = RETRY_MAX_MILLIS
                } else {
                    retryMillis += RETRY_STEP_MILLIS
                }
                Log.d(TAG, "Connection failed, retrying connection in ${retryMillis/1000}s ...")
                delay(retryMillis)
            }
        }
        Log.d(TAG, "[$url] Connection job SHUT DOWN")
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelName = getString(R.string.channel_subscriber_service_name) // Show's up in UI
            val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW).let {
                it.setShowBadge(false) // Don't show long-press badge
                it
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }

        val title = getString(R.string.channel_subscriber_notification_title)
        val text = getString(R.string.channel_subscriber_notification_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setSound(null)
            .setShowWhen(false) // Don't show date/time
            .build()
    }

    enum class Actions {
        START,
        STOP
    }

    enum class ServiceState {
        STARTED,
        STOPPED,
    }

    companion object {
        private const val TAG = "NtfySubscriberService"
        private const val WAKE_LOCK_TAG = "SubscriberService:lock"
        private const val CHANNEL_ID = "ntfy-subscriber"
        private const val SERVICE_ID = 2586
        private const val SHARED_PREFS_ID = "SubscriberService"
        private const val SHARED_PREFS_SERVICE_STATE = "ServiceState"
        private const val CONNECTION_LOOP_DELAY_MILLIS = 30_000L
        private const val RETRY_STEP_MILLIS = 5_000L
        private const val RETRY_MAX_MILLIS = 60_000L
        private const val RETRY_RESET_AFTER_MILLIS = 30_000L

        fun saveServiceState(context: Context, state: ServiceState) {
            val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
            sharedPrefs.edit()
                .putString(SHARED_PREFS_SERVICE_STATE, state.name)
                .apply()
        }

        fun readServiceState(context: Context): ServiceState {
            val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
            val value = sharedPrefs.getString(SHARED_PREFS_SERVICE_STATE, ServiceState.STOPPED.name)
            return ServiceState.valueOf(value!!)
        }
    }
}
