package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Color
import com.google.android.material.color.MaterialColors
import com.google.android.material.elevation.SurfaceColors
import io.heckel.ntfy.R

class Colors {
    companion object {
        fun notificationIcon(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
        }

        fun linkColor(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
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

        fun dangerText(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorError, Color.RED)
        }

        fun swipeToRefreshColor(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
        }
    }
}
