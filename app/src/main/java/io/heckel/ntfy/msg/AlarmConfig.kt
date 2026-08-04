package io.heckel.ntfy.msg

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.splitTags

/**
 * Configuration for a full-screen alarm notification, entirely controlled by the publisher
 * via message tags, e.g. "fullscreen,sound=Cesium,vibrate=0,timeout=120,snooze=10,volume=80".
 *
 * The "fullscreen" tag triggers the alarm; the key=value tags tune its behavior. This rides on
 * tags because the official ntfy server forwards them unmodified, unlike custom fields/headers.
 */
data class AlarmConfig(
    val sound: String = ALARM_SOUND_DEFAULT, // "default", "none", or a ringtone title to match
    val vibrate: Boolean = true,
    val timeoutSeconds: Int? = null, // null = no explicit timeout (a safety cap still applies)
    val snoozeMinutes: Int = ALARM_DEFAULT_SNOOZE_MINUTES,
    val volumePercent: Int? = null // null = leave the device's alarm volume alone
)

const val ALARM_TRIGGER_TAG = "fullscreen"
const val ALARM_SOUND_DEFAULT = "default"
const val ALARM_SOUND_NONE = "none"
const val ALARM_DEFAULT_SNOOZE_MINUTES = 10
const val ALARM_MAX_RING_SECONDS = 900 // Safety cap: never ring forever if no timeout is given

private const val ALARM_TAG_PREFIX_SOUND = "sound="
private const val ALARM_TAG_PREFIX_VIBRATE = "vibrate="
private const val ALARM_TAG_PREFIX_TIMEOUT = "timeout="
private const val ALARM_TAG_PREFIX_SNOOZE = "snooze="
private const val ALARM_TAG_PREFIX_VOLUME = "volume="

/**
 * Parses the alarm config from the raw comma-separated tags string. Returns null if the
 * message does not carry the "fullscreen" trigger tag. Malformed values fall back to defaults;
 * the last occurrence of a duplicated key wins.
 */
fun parseAlarmConfig(tags: String?): AlarmConfig? {
    val tagList = splitTags(tags).map { it.trim() }
    if (!tagList.contains(ALARM_TRIGGER_TAG)) {
        return null
    }
    var config = AlarmConfig()
    tagList.forEach { tag ->
        val value = tag.substringAfter("=") // Split on first "=" only, so "sound=a=b" keeps "a=b"
        when {
            tag.startsWith(ALARM_TAG_PREFIX_SOUND) -> {
                config = config.copy(sound = value.trim().ifEmpty { ALARM_SOUND_DEFAULT })
            }
            tag.startsWith(ALARM_TAG_PREFIX_VIBRATE) -> {
                val off = value.trim().lowercase() in listOf("0", "false", "no")
                config = config.copy(vibrate = !off)
            }
            tag.startsWith(ALARM_TAG_PREFIX_TIMEOUT) -> {
                val seconds = value.trim().toIntOrNull()
                if (seconds != null && seconds in 1..86400) {
                    config = config.copy(timeoutSeconds = seconds)
                }
            }
            tag.startsWith(ALARM_TAG_PREFIX_SNOOZE) -> {
                val minutes = value.trim().toIntOrNull()
                if (minutes != null && minutes in 1..1440) {
                    config = config.copy(snoozeMinutes = minutes)
                }
            }
            tag.startsWith(ALARM_TAG_PREFIX_VOLUME) -> {
                val percent = value.trim().toIntOrNull()
                if (percent != null && percent in 0..100) {
                    config = config.copy(volumePercent = percent)
                }
            }
        }
    }
    return config
}

/**
 * True if the tag is part of the alarm config vocabulary (trigger or key=value option).
 */
fun isAlarmConfigTag(tag: String): Boolean {
    return tag == ALARM_TRIGGER_TAG
            || tag.startsWith(ALARM_TAG_PREFIX_SOUND)
            || tag.startsWith(ALARM_TAG_PREFIX_VIBRATE)
            || tag.startsWith(ALARM_TAG_PREFIX_TIMEOUT)
            || tag.startsWith(ALARM_TAG_PREFIX_SNOOZE)
            || tag.startsWith(ALARM_TAG_PREFIX_VOLUME)
}

/**
 * Filters alarm config tags from user-visible tag rendering. Only filters when the message is
 * actually an alarm ("fullscreen" present); a lone "sound=x" tag on a normal message still renders.
 */
fun visibleTags(tags: List<String>): List<String> {
    if (!tags.map { it.trim() }.contains(ALARM_TRIGGER_TAG)) {
        return tags
    }
    return tags.filterNot { isAlarmConfigTag(it.trim()) }
}

/**
 * Resolves the configured sound to a playable URI. Returns null for "none" (silent).
 * Named sounds are matched case-insensitively against the device's alarm sounds first,
 * then ringtones, then by substring; unresolvable names fall back to the default alarm sound.
 */
fun resolveAlarmSoundUri(context: Context, config: AlarmConfig): Uri? {
    return when {
        config.sound.equals(ALARM_SOUND_NONE, ignoreCase = true) -> null
        config.sound.equals(ALARM_SOUND_DEFAULT, ignoreCase = true) -> defaultAlarmSoundUri()
        else -> findSoundByTitle(context, config.sound) ?: defaultAlarmSoundUri()
    }
}

private fun defaultAlarmSoundUri(): Uri? {
    return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}

private fun findSoundByTitle(context: Context, title: String): Uri? {
    val types = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE)
    try {
        // Exact match first (per type), then substring match
        types.forEach { type ->
            findSound(context, type) { it.equals(title, ignoreCase = true) }?.let { return it }
        }
        types.forEach { type ->
            findSound(context, type) { it.contains(title, ignoreCase = true) }?.let { return it }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to resolve alarm sound '$title'", e)
    }
    return null
}

private fun findSound(context: Context, type: Int, matches: (String) -> Boolean): Uri? {
    val manager = RingtoneManager(context)
    manager.setType(type)
    val cursor = manager.cursor
    while (cursor.moveToNext()) {
        val soundTitle = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
        if (matches(soundTitle)) {
            return manager.getRingtoneUri(cursor.position)
        }
    }
    return null
}

private const val TAG = "NtfyAlarmConfig"
