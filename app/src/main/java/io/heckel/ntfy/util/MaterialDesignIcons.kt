package io.heckel.ntfy.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import io.heckel.ntfy.R

/**
 * Renders icons from Material Design Icons (https://pictogrammers.com/library/mdi/), e.g. "mdi:garage-open".
 *
 * The icon font (res/font/material_design_icons.ttf) and the name to codepoint mapping
 * (res/raw/material_design_icons.txt) are generated from the @mdi/font package (Apache License 2.0).
 * The mapping is only read when the first icon is requested.
 */
object MaterialDesignIcons {
    private const val PREFIX = "mdi:"
    private var codepoints: Map<String, Int>? = null

    /** Renders the icon in white on a transparent bitmap, or returns null if the icon is unknown. */
    fun render(context: Context, name: String, sizePx: Int): Bitmap? {
        if (!name.startsWith(PREFIX)) {
            return null
        }
        val codepoint = codepoints(context)[name.removePrefix(PREFIX)] ?: return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.material_design_icons)
            color = Color.WHITE
            textSize = sizePx.toFloat()
        }
        val glyph = String(Character.toChars(codepoint))
        val bounds = Rect()
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        val bitmap = createBitmap(sizePx, sizePx)
        Canvas(bitmap).drawText(glyph, (sizePx - bounds.width()) / 2f - bounds.left, (sizePx - bounds.height()) / 2f - bounds.top, paint)
        return bitmap
    }

    @Synchronized
    private fun codepoints(context: Context): Map<String, Int> {
        return codepoints ?: context.resources.openRawResource(R.raw.material_design_icons).bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val (name, codepoint) = line.split(" ")
                    name to codepoint.toInt(16)
                }
        }.also { codepoints = it }
    }
}
