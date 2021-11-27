package io.heckel.ntfy.util

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.Window
import com.vdurmont.emoji.EmojiManager
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Subscription
import java.text.DateFormat
import java.util.*

fun topicUrl(baseUrl: String, topic: String) = "${baseUrl}/${topic}"
fun topicUrlJson(baseUrl: String, topic: String, since: String) = "${topicUrl(baseUrl, topic)}/json?since=$since"
fun topicUrlJsonPoll(baseUrl: String, topic: String) = "${topicUrl(baseUrl, topic)}/json?poll=1"
fun topicShortUrl(baseUrl: String, topic: String) =
    topicUrl(baseUrl, topic)
        .replace("http://", "")
        .replace("https://", "")

fun formatDateShort(timestampSecs: Long): String {
    val mutedUntilDate = Date(timestampSecs*1000)
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(mutedUntilDate)
}

fun toPriority(priority: Int?): Int {
    if (priority != null && (1..5).contains(priority)) return priority
    else return 3
}

fun joinTags(tags: List<String>?): String {
    return tags?.joinToString(",") ?: ""
}

fun toTags(tags: String?): String {
    return tags ?: ""
}

fun emojify(tags: List<String>): List<String> {
    return tags.mapNotNull {
        when (it.toLowerCase()) {
            "warn", "warning" -> "\u26A0\uFE0F"
            "success" -> "\u2714\uFE0F"
            "failure" -> "\u274C"
            else -> EmojiManager.getForAlias(it)?.unicode
        }
    }
}

/**
 * Prepend tags/emojis to message, but only if there is a non-empty title.
 * Otherwise the tags will be prepended to the title.
 */
fun formatMessage(notification: Notification): String {
    return if (notification.title != "") {
        notification.message
    } else {
        val emojis = emojify(notification.tags.split(","))
        if (emojis.isEmpty()) {
            notification.message
        } else {
            emojis.joinToString("") + " " + notification.message
        }
    }
}

/**
 * See above; prepend emojis to title if the title is non-empty.
 * Otherwise, they are prepended to the message.
 */
fun formatTitle(subscription: Subscription, notification: Notification): String {
    return if (notification.title != "") {
        formatTitle(notification)
    } else {
        topicShortUrl(subscription.baseUrl, subscription.topic)
    }
}

fun formatTitle(notification: Notification): String {
    val emojis = emojify(notification.tags.split(","))
    return if (emojis.isEmpty()) {
        notification.title
    } else {
        emojis.joinToString("") + " " + notification.title
    }
}

// Status bar color fading to match action bar, see https://stackoverflow.com/q/51150077/1440785
fun fadeStatusBarColor(window: Window, fromColor: Int, toColor: Int) {
    val statusBarColorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
    statusBarColorAnimation.addUpdateListener { animator ->
        val color = animator.animatedValue as Int
        window.statusBarColor = color
    }
    statusBarColorAnimation.start()
}
