package io.heckel.ntfy.msg

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the currently ringing full-screen alarm. Owns its own MediaPlayer
 * (deliberately not Repository.mediaPlayer, so swiping the regular topic notification does not
 * silence the alarm) and the Vibrator, and schedules the ring timeout and snooze re-fire via
 * AlarmManager so they survive process death.
 *
 * Sound/vibration are started at notification-display time (not in AlarmActivity), because
 * Android only shows a heads-up instead of launching the full-screen activity when the screen
 * is on and unlocked. The activity is UI-only; it observes [activeNotificationId] to auto-close.
 */
class AlarmSessionManager private constructor(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var mediaPlayer: MediaPlayer? = null
    // Device alarm volume before this session overrode it; restored on stop so a one-off
    // "ring at 100%" cannot quietly become the phone's permanent alarm volume.
    private var previousAlarmVolume: Int? = null
    private var activeAndroidNotificationId: Int = 0
    private var timeoutPendingIntent: PendingIntent? = null

    private val activeNotificationIdMutable = MutableStateFlow<String?>(null)
    val activeNotificationId: StateFlow<String?> = activeNotificationIdMutable

    /**
     * Starts ringing/vibrating for the given notification and schedules the ring timeout.
     * A second alarm replaces the currently active one (but keeps the first one's notification).
     */
    @Synchronized
    fun start(notification: Notification, config: AlarmConfig) {
        Log.d(TAG, "Starting alarm session for notification ${notification.id} with $config")
        stop(cancelNotification = false)
        maybePlaySound(config)
        maybeVibrate(config)
        scheduleTimeout(notification, config)
        activeAndroidNotificationId = notification.notificationId
        activeNotificationIdMutable.value = notification.id
    }

    /**
     * Stops sound/vibration, cancels the scheduled timeout and (optionally) the alarm
     * notification. Safe to call when no session is active.
     */
    @Synchronized
    fun stop(cancelNotification: Boolean = true) {
        Log.d(TAG, "Stopping alarm session (cancelNotification=$cancelNotification)")
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Media player in unexpected state", e)
            }
            player.release()
        }
        mediaPlayer = null
        restoreVolume()
        vibrator().cancel()
        timeoutPendingIntent?.let { alarmManager.cancel(it) }
        timeoutPendingIntent = null
        if (cancelNotification && activeAndroidNotificationId != 0) {
            notificationManager.cancel(activeAndroidNotificationId)
        }
        activeAndroidNotificationId = 0
        activeNotificationIdMutable.value = null
    }

    /**
     * Silences the alarm and schedules it to fire again after the given number of minutes.
     * The re-fire reloads the notification from the database, so it works after process death.
     */
    @Synchronized
    fun snooze(notificationDbId: String, androidNotificationId: Int, minutes: Int) {
        Log.d(TAG, "Snoozing alarm for notification $notificationDbId by $minutes minute(s)")
        stop(cancelNotification = true)
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = AlarmBroadcastReceiver.ACTION_ALARM_FIRE
            putExtra(AlarmBroadcastReceiver.EXTRA_NOTIFICATION_ID, notificationDbId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            androidNotificationId + REQUEST_CODE_OFFSET_FIRE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleExact(System.currentTimeMillis() + minutes * 60_000L, pendingIntent)
    }

    fun isActive(notificationDbId: String): Boolean {
        return activeNotificationIdMutable.value == notificationDbId
    }

    private fun maybePlaySound(config: AlarmConfig) {
        val soundUri = resolveAlarmSoundUri(context, config) ?: return
        maybeForceVolume(config)
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            Log.d(TAG, "Alarm volume is 0; not playing alarm sound")
            return
        }
        try {
            val player = MediaPlayer()
            player.setDataSource(context, soundUri)
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            player.isLooping = true
            player.prepare()
            player.start()
            mediaPlayer = player
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play alarm sound $soundUri", e)
        }
    }

    /**
     * Applies the publisher's "volume=<0-100>" tag by setting the device's ALARM stream, so a
     * critical alarm rings loudly even on a phone whose alarm volume was turned down. The stream
     * volume is what actually decides loudness here: MediaPlayer.setVolume only attenuates
     * *within* it, so it can never make a quiet phone loud.
     *
     * The old value is saved and restored in stop(). Note the publisher can therefore silence an
     * alarm with volume=0 — that is deliberate and symmetric with sound=none.
     */
    private fun maybeForceVolume(config: AlarmConfig) {
        val percent = config.volumePercent ?: return
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (max * percent / 100).coerceIn(0, max)
            if (previousAlarmVolume == null) {
                previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            Log.d(TAG, "Forced alarm volume to $percent% ($target/$max), was $previousAlarmVolume")
        } catch (e: Exception) {
            // A locked-down device (DND policy, restricted profile) can refuse this; ringing at
            // whatever volume the phone already had beats not ringing at all.
            Log.w(TAG, "Unable to force alarm volume", e)
        }
    }

    private fun restoreVolume() {
        val previous = previousAlarmVolume ?: return
        previousAlarmVolume = null
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to restore alarm volume", e)
        }
    }

    private fun maybeVibrate(config: AlarmConfig) {
        if (!config.vibrate) {
            return
        }
        try {
            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0) // 0 = repeat from start
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION") // The (effect, AudioAttributes) overload works on all supported API levels
            vibrator().vibrate(effect, attributes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate", e)
        }
    }

    private fun vibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun scheduleTimeout(notification: Notification, config: AlarmConfig) {
        val timeoutSeconds = config.timeoutSeconds ?: ALARM_MAX_RING_SECONDS // Never ring forever
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = AlarmBroadcastReceiver.ACTION_ALARM_TIMEOUT
            putExtra(AlarmBroadcastReceiver.EXTRA_ANDROID_NOTIFICATION_ID, notification.notificationId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notification.notificationId + REQUEST_CODE_OFFSET_TIMEOUT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        timeoutPendingIntent = pendingIntent
        scheduleExact(System.currentTimeMillis() + timeoutSeconds * 1000L, pendingIntent)
    }

    private fun scheduleExact(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            // SCHEDULE_EXACT_ALARM was revoked by the user; timeout/snooze become approximate
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    companion object {
        private const val TAG = "NtfyAlarmSession"
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 600)

        // Stable PendingIntent request codes, derived from the (positive) Android notification ID.
        // Offsets keep the alarm intents distinct from each other; the shade action buttons built
        // in NotificationService use offsets 2 and 3.
        const val REQUEST_CODE_OFFSET_TIMEOUT = 0
        const val REQUEST_CODE_OFFSET_FIRE = 1
        const val REQUEST_CODE_OFFSET_DISMISS = 2
        const val REQUEST_CODE_OFFSET_SNOOZE = 3

        private var instance: AlarmSessionManager? = null

        fun getInstance(context: Context): AlarmSessionManager {
            return synchronized(AlarmSessionManager::class) {
                val newInstance = instance ?: AlarmSessionManager(context.applicationContext)
                instance = newInstance
                newInstance
            }
        }
    }
}
