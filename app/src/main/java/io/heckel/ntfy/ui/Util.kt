package io.heckel.ntfy.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.Window
import java.text.DateFormat
import java.util.*

// Status bar color fading to match action bar, see https://stackoverflow.com/q/51150077/1440785
fun fadeStatusBarColor(window: Window, fromColor: Int, toColor: Int) {
    val statusBarColorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
    statusBarColorAnimation.addUpdateListener { animator ->
        val color = animator.animatedValue as Int
        window.statusBarColor = color
    }
    statusBarColorAnimation.start()
}

fun formatDateShort(timestampSecs: Long): String {
    val mutedUntilDate = Date(timestampSecs*1000)
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(mutedUntilDate)
}
