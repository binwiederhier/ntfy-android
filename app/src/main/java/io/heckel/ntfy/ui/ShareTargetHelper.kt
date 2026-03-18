package io.heckel.ntfy.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.readBitmapFromUriOrNull
import io.heckel.ntfy.util.topicUrl

object ShareTargetHelper {
    private const val CATEGORY_SHARE_TARGET = "io.heckel.ntfy.SHARE_TARGET"
    private const val SHORTCUT_ID_PREFIX = "share_"

    private const val TAG = "ShareTargetHelper"

    /**
     * Publishes one dynamic sharing shortcut per subscription so that topics appear
     * as direct-share targets in the system share sheet (Android 10+).
     */
    fun update(context: Context, subscriptions: List<Subscription>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val shortcuts = subscriptions
            .sortedByDescending { it.lastActive }
            .take(max)
            .map { sub -> buildShortcut(context, sub) }
        try {
            val success = ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            android.util.Log.i(
                TAG,
                "setDynamicShortcuts returned $success"
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "setDynamicShortcuts returned failed", e)
        }
    }

    /**
     * Removes the dynamic sharing shortcut for a single subscription.
     */
    fun remove(context: Context, subscription: Subscription) {
        val id = SHORTCUT_ID_PREFIX + topicUrl(subscription.baseUrl, subscription.topic)
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(id))
    }

    /**
     * Returns the topicUrl encoded in a shortcut ID, or null if the ID doesn't belong to us.
     */
    fun topicUrlFromShortcutId(shortcutId: String): String? {
        if (!shortcutId.startsWith(SHORTCUT_ID_PREFIX)) return null
        return shortcutId.removePrefix(SHORTCUT_ID_PREFIX)
    }

    private fun buildShortcut(context: Context, sub: Subscription): ShortcutInfoCompat {
        val label = sub.displayName ?: sub.topic
        val url = topicUrl(sub.baseUrl, sub.topic)
        val icon = sub.icon?.let { IconCompat.createWithAdaptiveBitmapContentUri(it) }
            ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        return ShortcutInfoCompat.Builder(context, SHORTCUT_ID_PREFIX + url)
            .setShortLabel(label)
            .setIcon(icon)
            .setCategories(setOf(CATEGORY_SHARE_TARGET))
            .setIntent(Intent(context, ShareActivity::class.java).apply {
                action = Intent.ACTION_VIEW
            })
            .setLongLived(true)
            .build()
    }
}
