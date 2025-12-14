package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import io.heckel.ntfy.R
import io.heckel.ntfy.util.isDarkThemeOn

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
            return ContextCompat.getColor(context, R.color.md_theme_surfaceContainerHigh)
        }

        fun cardBackgroundColor(context: Context): Int {
            return if (isDarkThemeOn(context)) {
                MaterialColors.getColor(context, R.attr.colorSurfaceContainer, Color.GRAY)
            } else {
                MaterialColors.getColor(context, R.attr.colorSurface, Color.WHITE)
            }
        }

        fun cardSelectedBackgroundColor(context: Context): Int {
            return if (isDarkThemeOn(context)) {
                MaterialColors.getColor(context, R.attr.colorSurfaceContainerHigh, Color.GRAY)
            } else {
                MaterialColors.getColor(context, R.attr.colorSurfaceContainerHighest, Color.GRAY)
            }
        }

        fun statusBarNormal(context: Context, dynamicColors: Boolean, darkMode: Boolean): Int {
            val default = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.resources.getColor(R.color.action_bar, null)
            } else {
                @Suppress("DEPRECATION")
                context.resources.getColor(R.color.action_bar)
            }
            return if (dynamicColors) {
                // Use colorSurface for both light and dark mode when dynamic colors are enabled
                MaterialColors.getColor(context, R.attr.colorSurface, default)
            } else {
                default
            }
        }

        fun shouldUseLightStatusBar(dynamicColors: Boolean, darkMode: Boolean): Boolean {
            // Use light status bar (dark icons) when dynamic colors are enabled in light mode
            return dynamicColors && !darkMode
        }

        fun toolbarTextColor(context: Context, dynamicColors: Boolean, darkMode: Boolean): Int {
            return if (dynamicColors) {
                // Use colorOnSurface (dark on light, light on dark) when dynamic colors are enabled
                MaterialColors.getColor(context, R.attr.colorOnSurface, Color.BLACK)
            } else {
                if (darkMode) {
                    // In dark mode, toolbar is gray (surfaceContainer), so use light text
                    MaterialColors.getColor(context, R.attr.colorOnSurface, Color.WHITE)
                } else {
                    // In light mode, toolbar is teal (primary), so use white text
                    MaterialColors.getColor(context, R.attr.colorOnPrimary, Color.WHITE)
                }
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

