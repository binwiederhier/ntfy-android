package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import com.google.android.material.color.MaterialColors
import com.google.android.material.elevation.SurfaceColors
import io.heckel.ntfy.R

class Colors {
    companion object {
        fun primary(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
        }

        fun onPrimary(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorOnPrimary, Color.GREEN)
        }

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

        fun statusBarNormal(context: Context, dynamicColors: Boolean, darkMode: Boolean): Int {
            val default = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.resources.getColor(R.color.action_bar, context.theme)
            } else {
                @Suppress("DEPRECATION")
                context.resources.getColor(R.color.action_bar)
            }
            return if (dynamicColors) {
                if (darkMode) {
                    MaterialColors.getColor(context, R.attr.colorSurface, default)
                } else {
                    MaterialColors.getColor(context, R.attr.colorOnPrimaryContainer, default)
                }
            } else {
                default
            }
        }

        fun dangerText(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorError, Color.RED)
        }

        fun swipeToRefreshColor(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
        }
    }
}
