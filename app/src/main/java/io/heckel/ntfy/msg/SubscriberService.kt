package io.heckel.ntfy.msg

import android.app.*
import android.content.BroadcastReceiver
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
import io.heckel.ntfy.data.ConnectionState
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicUrl
import io.heckel.ntfy.ui.MainActivity
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

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
    private val connections = ConcurrentHashMap<String, SubscriberConnection>() // Base URL -> Connection
    private val api = ApiService()
    private val notifier = NotificationService(this)
    private var notificationManager: NotificationManager? = null
    private var serviceNotification: Notification? = null

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
        Log.d(TAG, "Subscriber service has been created")

        val title = getString(R.string.channel_subscriber_notification_title)
        val text = getString(R.string.channel_subscriber_notification_text)
        notificationManager = createNotificationChannel()
        serviceNotification = createNotification(title, text)

        startForeground(NOTIFICATION_SERVICE_ID, serviceNotification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Subscriber service has been destroyed")
    }

    private fun startService() {
        if (isServiceStarted) {
            refreshConnections()
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
        refreshConnections()
    }

    private fun stopService() {
        Log.d(TAG, "Stopping the foreground service")

        // Cancelling all remaining jobs and open HTTP calls
        connections.values.forEach { connection -> connection.cancel() }
        connections.clear()

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

    private fun refreshConnections() =
        GlobalScope.launch(Dispatchers.IO) {
            // Group subscriptions by base URL (Base URL -> Map<SubId -> Sub>.
            // There is only one connection per base URL.
            val subscriptions = repository.getSubscriptions()
                .filter { s -> s.instant }
            val subscriptionsByBaseUrl = subscriptions
                .groupBy { s -> s.baseUrl }
                .mapValues { entry -> entry.value.associateBy { it.id } }

            Log.d(TAG, "Refreshing subscriptions")
            Log.d(TAG, "- Subscriptions: $subscriptionsByBaseUrl")
            Log.d(TAG, "- Active connections: $connections")

            // Start new connections and restart connections (if subscriptions have changed)
            subscriptionsByBaseUrl.forEach { (baseUrl, subscriptions) ->
                val connection = connections[baseUrl]
                var since = 0L
                if (connection != null && !connection.matches(subscriptions)) {
                    since = connection.since()
                    connections.remove(baseUrl)
                    connection.cancel()
                }
                if (!connections.containsKey(baseUrl)) {
                    val serviceActive = { -> isServiceStarted }
                    val connection = SubscriberConnection(api, baseUrl, since, subscriptions, ::onStateChanged, ::onNotificationReceived, serviceActive)
                    connections[baseUrl] = connection
                    connection.start(this)
                }
            }

            // Close connections without subscriptions
            val baseUrls = subscriptionsByBaseUrl.keys
            connections.keys().toList().forEach { baseUrl ->
                if (!baseUrls.contains(baseUrl)) {
                    val connection = connections.remove(baseUrl)
                    connection?.cancel()
                }
            }

            // Update foreground service notification popup
            if (connections.size > 0) {
                synchronized(this) {
                    val title = getString(R.string.channel_subscriber_notification_title)
                    val text = when (subscriptions.size) {
                        1 -> getString(R.string.channel_subscriber_notification_text_one)
                        2 -> getString(R.string.channel_subscriber_notification_text_two)
                        3 -> getString(R.string.channel_subscriber_notification_text_three)
                        4 -> getString(R.string.channel_subscriber_notification_text_four)
                        else -> getString(R.string.channel_subscriber_notification_text_more, subscriptions.size)
                    }
                    serviceNotification = createNotification(title, text)
                    notificationManager?.notify(NOTIFICATION_SERVICE_ID, serviceNotification)
                }
            }
        }

    private fun onStateChanged(subscriptions: Collection<Subscription>, state: ConnectionState) {
        val subscriptionIds = subscriptions.map { it.id }
        repository.updateState(subscriptionIds, state)
    }

    private fun onNotificationReceived(subscription: Subscription, n: io.heckel.ntfy.data.Notification) {
        val url = topicUrl(subscription.baseUrl, subscription.topic)
        Log.d(TAG, "[$url] Received notification: $n")
        GlobalScope.launch(Dispatchers.IO) {
            val shouldNotify = repository.addNotification(n)
            if (shouldNotify) {
                Log.d(TAG, "[$url] Showing notification: $n")
                notifier.send(subscription, n)
            }
        }
    }

    private fun createNotificationChannel(): NotificationManager? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelName = getString(R.string.channel_subscriber_service_name) // Show's up in UI
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW).let {
                it.setShowBadge(false) // Don't show long-press badge
                it
            }
            notificationManager.createNotificationChannel(channel)
            return notificationManager
        }
        return null
    }

    private fun createNotification(title: String, text: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_instant)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setSound(null)
            .setShowWhen(false) // Don't show date/time
            .build()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null // We don't provide binding, so return null
    }

    /* This re-schedules the task when the "Clear recent apps" button is pressed */
    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, SubscriberService::class.java).also {
            it.setPackage(packageName)
        };
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT);
        applicationContext.getSystemService(Context.ALARM_SERVICE);
        val alarmService: AlarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager;
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent);
    }

    /* This re-starts the service on reboot; see manifest */
    class StartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && readServiceState(context) == ServiceState.STARTED) {
                Intent(context, SubscriberService::class.java).also {
                    it.action = Actions.START.name
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d(TAG, "Starting subscriber service in >=26 Mode from a BroadcastReceiver")
                        context.startForegroundService(it)
                        return
                    }
                    Log.d(TAG, "Starting subscriber service in < 26 Mode from a BroadcastReceiver")
                    context.startService(it)
                }
            }
        }
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
        private const val NOTIFICATION_CHANNEL_ID = "ntfy-subscriber"
        private const val NOTIFICATION_SERVICE_ID = 2586
        private const val SHARED_PREFS_ID = "SubscriberService"
        private const val SHARED_PREFS_SERVICE_STATE = "ServiceState"

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
