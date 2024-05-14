package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.elevation.SurfaceColors
import io.heckel.ntfy.R
import io.heckel.ntfy.util.isDarkThemeOn

class Colors {
    companion object {
        fun notificationIcon(context: Context): Int {
            return if (isDarkThemeOn(context)) R.color.teal_light else R.color.teal
        }

        fun itemSelectedBackground(context: Context): Int {
            return SurfaceColors.getColorForElevation(context, 10f)
        }

        fun cardBackgroundColor(context: Context): Int {
            return SurfaceColors.getColorForElevation(context, 5f)
        }

        fun cardSelectedBackgroundColor(context: Context): Int {
            return SurfaceColors.getColorForElevation(context, 20f)
        }

        fun statusBarNormal(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.backgroundColor, Color.BLACK)
        }

        fun statusBarActionMode(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.backgroundColor, Color.BLACK)
        }

        fun dangerText(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorError, Color.RED)
        }

        fun swipeToRefreshColor(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
        }
    }
}
