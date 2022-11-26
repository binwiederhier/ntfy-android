package io.heckel.ntfy.ui

import android.content.Context
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.util.isDarkThemeOn

class Colors {
    companion object {
        const val refreshProgressIndicator = R.color.teal

        fun notificationIcon(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.teal_light else R.color.teal
        }

        fun itemSelectedBackground(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.black_800b else R.color.gray_400
        }

        fun cardBackground(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.black_800b else R.color.white
        }

        fun cardSelectedBackground(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.black_700b else R.color.gray_500
        }

        fun cardBackgroundColor(context: Context): Int {
            return ContextCompat.getColor(context, cardBackground(context))
        }

        fun cardSelectedBackgroundColor(context: Context): Int {
            return ContextCompat.getColor(context, cardSelectedBackground(context))
        }

        fun statusBarNormal(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.black_900 else R.color.teal
        }

        fun statusBarActionMode(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.black_900 else R.color.teal_dark
        }

        fun dangerText(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.red_light else R.color.red_dark
        }
    }
}
