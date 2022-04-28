package io.heckel.ntfy.ui

import android.content.Context
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.util.isDarkThemeOn

class Colors {
    companion object {
        const val refreshProgressIndicator = R.color.teal
        const val notificationIcon = R.color.teal

        fun itemSelectedBackground(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.gray_dark else R.color.gray_light
        }

        fun itemSelectedBackgroundColor(context: Context): Int {
            return ContextCompat.getColor(context, itemSelectedBackground(context))
        }

        fun statusBarNormal(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.black_light else R.color.teal
        }

        fun statusBarActionMode(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.black_light else R.color.teal_dark
        }

        fun dangerText(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.red_light else R.color.red_dark
        }
    }
}
