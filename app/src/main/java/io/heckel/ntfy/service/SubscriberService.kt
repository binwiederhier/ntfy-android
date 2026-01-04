package io.heckel.ntfy.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.ConnectionState
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.topicUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * The subscriber service manages the foreground service for instant delivery.
 *
 * This should be so easy but it's a hot mess due to all the Android restrictions, and all the hoops you have to jump
 * through to make your service not die or restart.
 *
 * Cliff notes:
 * - If the service is running, we keep one connection per base URL open (we group all topics together)
 * - Incoming notifications are immediately forwarded and broadcasted
 *
 * "Trying to keep the service running" cliff notes:
 * - Manages the service SHOULD-BE state in a SharedPref, so that we know whether or not to restart the service
 * - The foreground service is STICKY, so it is restarted by Android if it's killed
 * - On destroy (onDestroy), we send a broadcast to AutoRestartReceiver (see AndroidManifest.xml) which will schedule
 *   a one-off AutoRestartWorker to restart the service (this is weird, but necessary because services started from
 *   receivers are apparently low priority, see the gist below for details)
 * - The MainActivity schedules a periodic worker (AutoRestartWorker) which restarts the service
 * - FCM receives keepalive message from the main ntfy.sh server, which broadcasts an intent to AutoRestartReceiver,
 *   which will schedule a one-off AutoRestartWorker to restart the service (see above)
 * - On boot, the BootStartReceiver is triggered to restart the service (see AndroidManifest.xml)
 *
 * This is all a hot mess, but you do what you gotta do.
 *
 * Largely modeled after this fantastic resource:
 * - https://robertohuertas.com/2019/06/29/android_foreground_services/
 * - https://github.com/robertohuertasm/endless-service/blob/master/app/src/main/java/com/robertohuertas/endless/EndlessService.kt
 * - https://gist.github.com/varunon9/f2beec0a743c96708eb0ef971a9ff9cd
 */
class SubscriberService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private val repository by lazy { (application as Application).repository }
    private val dispatcher by lazy { NotificationDispatcher(this, repository) }
    private val api by lazy { ApiService(this) }
    private val connections = ConcurrentHashMap<ConnectionId, Connection>()
    private var notificationManager: NotificationManager? = null
    private var serviceNotification: Notification? = null
    private val refreshMutex = Mutex() // Ensure refreshConnections() is only run one at a time

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand executed with startId: $startId")

        // Safety check: ensure we're in foreground state. This handles edge cases where
        // onCreate() may not have been called or completed before onStartCommand(). See #1520.
        if (serviceNotification == null) {
            Log.d(TAG, "onStartCommand: Notification not set, initializing foreground state")
            initializeForegroundState()
        }

        if (intent != null) {
            Log.d(TAG, "using an intent with action ${intent.action}")
            when (intent.action) {
                Action.START.name -> startService()
                Action.STOP.name -> stopService()
                else -> Log.w(TAG, "This should never happen. No action in the received intent")
            }
        } else {
            Log.d(TAG, "with a null intent. It has been probably restarted by the system.")
        }
        return START_STICKY // restart if system kills the service
    }

    override fun onCreate() {
        super.onCreate()

        Log.init(this) // Init logs in all entry points
        Log.d(TAG, "Subscriber service has been created")

        initializeForegroundState()
    }

    /**
     * Initializes the foreground state by creating the notification channel and notification,
     * then calling startForeground(). This is called from onCreate() and as a safety fallback
     * from onStartCommand() if onCreate() didn't complete properly.
     */
    private fun initializeForegroundState() {
        val title = getString(R.string.channel_subscriber_notification_title)
        val text = if (BuildConfig.FIREBASE_AVAILABLE) {
            getString(R.string.channel_subscriber_notification_instant_text)
        } else {
            getString(R.string.channel_subscriber_notification_noinstant_text)
        }
        notificationManager = createNotificationChannel()
        serviceNotification = createNotification(title, text)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_SERVICE_ID, serviceNotification!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_SERVICE_ID, serviceNotification)
            }
        } catch (e: Exception) {
            // On Android 12+, starting a foreground service from the background is restricted.
            // ForegroundServiceStartNotAllowedException is thrown when the app is in the background.
            // We stop ourselves gracefully; the service will be started when the user opens the app.
            // This should not happen if the battery optimization exemption was granted by the user.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Cannot start foreground service from background, stopping: ${e.message}")
                stopSelf()
            } else {
                Log.w(TAG, "Failed to start foreground: ${e.message}")
                // Don't rethrow: let the service continue and hope for the best,
                // or Android will kill it. Either way, we don't crash the app (see #1520).
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Subscriber service has been destroyed")
        stopService()
        sendBroadcast(Intent(this, AutoRestartReceiver::class.java)) // Restart it if necessary!
        super.onDestroy()
    }

    private fun startService() {
        if (isServiceStarted) {
            refreshConnections()
            return
        }
        Log.d(TAG, "Starting the foreground service task")
        isServiceStarted = true
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        }
        refreshConnections()
    }

    private fun stopService() {
        Log.d(TAG, "Stopping the foreground service")

        // Cancelling all remaining jobs and open HTTP calls
        connections.values.forEach { connection -> connection.close() }
        connections.clear()

        // Releasing wake-lock and stopping ourselves
        try {
            wakeLock?.let {
                // Release all acquire()
                while (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            Log.d(TAG, "Service stopped without being started: ${e.message}")
        }

        isServiceStarted = false
    }

    private fun refreshConnections() {
        GlobalScope.launch(Dispatchers.IO) {
            if (!refreshMutex.tryLock()) {
                Log.d(TAG, "Refreshing subscriptions already in progress. Skipping.")
                return@launch
            }
            try {
                reallyRefreshConnections(this)
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    /**
     * Start/stop connections based on the desired state
     * It is guaranteed that only one of function is run at a time (see mutex above).
     */
    private suspend fun reallyRefreshConnections(scope: CoroutineScope) {
        // Group INSTANT subscriptions by base URL, there is only one connection per base URL
        val instantSubscriptions = repository.getSubscriptions()
            .filter { s -> s.instant }
        val activeConnectionIds = connections.keys().toList().toSet()
        val connectionProtocol = repository.getConnectionProtocol()
        val desiredConnectionIds = instantSubscriptions // Set<ConnectionId>
            .groupBy { s -> s.baseUrl }
            .map { (baseUrl, subs) ->
                // Create a unique connection ID for each base URL. Each change in the connection ID will
                // trigger a new connection, and close existing connections. We want to make sure that when the
                // connection protocol (JSON/WS), the user or the custom headers are updated, that we kill existing
                // connections and start new ones.
                val credentialsHash = repository.getUser(baseUrl)?.let { "${it.username}:${it.password}".hashCode() } ?: 0
                val headersHash = repository.getCustomHeaders(baseUrl)
                    .sortedBy { "${it.name}:${it.value}" }
                    .joinToString(",") { "${it.name}:${it.value}" }
                    .hashCode()
                ConnectionId(
                    baseUrl = baseUrl,
                    topicsToSubscriptionIds = subs.associate { s -> s.topic to s.id },
                    connectionProtocol = connectionProtocol,
                    credentialsHash = credentialsHash,
                    headersHash = headersHash
                )
            }
            .toSet()
        val newConnectionIds = desiredConnectionIds.subtract(activeConnectionIds)
        val obsoleteConnectionIds = activeConnectionIds.subtract(desiredConnectionIds)
        val match = activeConnectionIds == desiredConnectionIds
        val sinceByBaseUrl = connections
            .map { e -> e.key.baseUrl to e.value.since() } // Use since=<id>, avoid retrieving old messages (see comment below)
            .toMap()

        Log.d(TAG, "Refreshing subscriptions")
        Log.d(TAG, "- Desired connections: $desiredConnectionIds")
        Log.d(TAG, "- Active connections: $activeConnectionIds")
        Log.d(TAG, "- New connections: $newConnectionIds")
        Log.d(TAG, "- Obsolete connections: $obsoleteConnectionIds")
        Log.d(TAG, "- Match? --> $match")

        if (match) {
            Log.d(TAG, "- No action required.")
            return
        }

        // Open new connections
        newConnectionIds.forEach { connectionId ->
            // IMPORTANT: Do NOT request old messages for new connections; we call poll() in MainActivity to
            // retrieve old messages. This is important, so we don't download attachments from old messages.

            val since = sinceByBaseUrl[connectionId.baseUrl] ?: "none"
            val serviceActive = { isServiceStarted }
            val user = repository.getUser(connectionId.baseUrl)
            val connection = if (connectionId.connectionProtocol == Repository.CONNECTION_PROTOCOL_WS) {
                val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                WsConnection(connectionId, repository, user, since, ::onStateChanged, ::onNotificationReceived, alarmManager)
            } else {
                JsonConnection(connectionId, scope, repository, api, user, since, ::onStateChanged, ::onNotificationReceived, serviceActive)
            }
            connections[connectionId] = connection
            connection.start()
        }

        // Close connections without subscriptions
        obsoleteConnectionIds.forEach { connectionId ->
            val connection = connections.remove(connectionId)
            connection?.close()
        }

        // Update foreground service notification popup
        if (connections.isNotEmpty()) {
            val title = getString(R.string.channel_subscriber_notification_title)
            val text = if (BuildConfig.FIREBASE_AVAILABLE) {
                when (instantSubscriptions.size) {
                    1 -> getString(R.string.channel_subscriber_notification_instant_text_one)
                    2 -> getString(R.string.channel_subscriber_notification_instant_text_two)
                    3 -> getString(R.string.channel_subscriber_notification_instant_text_three)
                    4 -> getString(R.string.channel_subscriber_notification_instant_text_four)
                    5 -> getString(R.string.channel_subscriber_notification_instant_text_five)
                    6 -> getString(R.string.channel_subscriber_notification_instant_text_six)
                    else -> getString(R.string.channel_subscriber_notification_instant_text_more, instantSubscriptions.size)
                }
            } else {
                when (instantSubscriptions.size) {
                    1 -> getString(R.string.channel_subscriber_notification_noinstant_text_one)
                    2 -> getString(R.string.channel_subscriber_notification_noinstant_text_two)
                    3 -> getString(R.string.channel_subscriber_notification_noinstant_text_three)
                    4 -> getString(R.string.channel_subscriber_notification_noinstant_text_four)
                    5 -> getString(R.string.channel_subscriber_notification_noinstant_text_five)
                    6 -> getString(R.string.channel_subscriber_notification_noinstant_text_six)
                    else -> getString(R.string.channel_subscriber_notification_noinstant_text_more, instantSubscriptions.size)
                }
            }
            serviceNotification = createNotification(title, text)
            notificationManager?.notify(NOTIFICATION_SERVICE_ID, serviceNotification)
        }
    }

    private fun onStateChanged(subscriptionIds: Collection<Long>, state: ConnectionState) {
        repository.updateState(subscriptionIds, state)
    }

    private fun onNotificationReceived(subscription: Subscription, notification: io.heckel.ntfy.db.Notification) {
        // Wakelock while notifications are being dispatched
        // Wakelocks are reference counted by default so that should work neatly here
        wakeLock?.acquire(NOTIFICATION_RECEIVED_WAKELOCK_TIMEOUT_MILLIS)

        val url = topicUrl(subscription.baseUrl, subscription.topic)
        Log.d(TAG, "[$url] Received notification: $notification")
        GlobalScope.launch(Dispatchers.IO) {
            if (repository.addNotification(notification)) {
                Log.d(TAG, "[$url] Dispatching notification $notification")
                dispatcher.dispatch(subscription, notification)
            }
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        }
    }

    private fun createNotificationChannel(): NotificationManager {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelName = getString(R.string.channel_subscriber_service_name) // Show's up in UI
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW).let {
            it.setShowBadge(false) // Don't show long-press badge
            it
        }
        notificationManager.createNotificationChannel(channel)
        return notificationManager
    }

    private fun createNotification(title: String, text: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_instant)
            .setColor(Colors.notificationIcon(this))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setSound(null)
            .setShowWhen(false) // Don't show date/time
            .setOngoing(true) // Starting SDK 33 / Android 13, foreground notifications can be swiped away
            .setGroup(NOTIFICATION_GROUP_ID) // Do not group with other notifications
            .build()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null // We don't provide binding, so return null
    }

    /* This re-schedules the task when the "Clear recent apps" button is pressed */
    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, SubscriberService::class.java).also {
            it.setPackage(packageName)
        }
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        applicationContext.getSystemService(ALARM_SERVICE)
        val alarmService: AlarmManager = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
    }

    /* This re-starts the service on reboot; see manifest */
    class BootStartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "BootStartReceiver: onReceive called")
            SubscriberServiceManager.refresh(context)
        }
    }

    // We are starting MyService via a worker and not directly because since Android 7
    // (but officially since Lollipop!), any process called by a BroadcastReceiver
    // (only manifest-declared receiver) is run at low priority and hence eventually
    // killed by Android.
    class AutoRestartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "AutoRestartReceiver: onReceive called")
            SubscriberServiceManager.refresh(context)
        }
    }

    enum class Action {
        START,
        STOP
    }

    companion object {
        const val TAG = "NtfySubscriberService"
        const val SERVICE_START_WORKER_VERSION = BuildConfig.VERSION_CODE
        const val SERVICE_START_WORKER_WORK_NAME_PERIODIC = "NtfyAutoRestartWorkerPeriodic" // Do not change!

        private const val WAKE_LOCK_TAG = "SubscriberService:lock"
        private const val NOTIFICATION_CHANNEL_ID = "ntfy-subscriber"
        private const val NOTIFICATION_GROUP_ID = "io.heckel.ntfy.NOTIFICATION_GROUP_SERVICE"
        private const val NOTIFICATION_SERVICE_ID = 2586
        private const val NOTIFICATION_RECEIVED_WAKELOCK_TIMEOUT_MILLIS = 10*60*1000L /*10 minutes*/
    }
}
