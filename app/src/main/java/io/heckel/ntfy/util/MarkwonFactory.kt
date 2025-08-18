package io.heckel.ntfy.util

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.style.*
import android.text.util.Linkify
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R
import io.noties.markwon.*
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal object MarkwonFactory {
    fun createForMessage(context: Context): Markwon {
        val headingSizes = floatArrayOf(1.7f, 1.5f, 1.2f, 1f, .8f, .7f)
        val bulletGapWidth = (8 * context.resources.displayMetrics.density + 0.5f).toInt()

        return Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(MovementMethodPlugin.create(BetterLinkMovementMethod.getInstance()))
            .usePlugin(ImagesPlugin.create())
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(ContextCompat.getColor(context, R.color.teal))
                        .isLinkUnderlined(true)
                }

                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder
                        .linkResolver(LinkResolverDef())
                }

                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder
                        .setFactory(Heading::class.java) { _, props: RenderProps? ->
                            arrayOf(
                                RelativeSizeSpan(headingSizes[CoreProps.HEADING_LEVEL.require(props!!) - 1]),
                                StyleSpan(Typeface.BOLD)
                            )
                        }
                        .setFactory(Emphasis::class.java) { _, _ -> StyleSpan(Typeface.ITALIC) }
                        .setFactory(StrongEmphasis::class.java) { _, _ -> StyleSpan(Typeface.BOLD) }
                        .setFactory(ListItem::class.java) { _, _ -> BulletSpan(bulletGapWidth) }
                }

            })
            .build()
    }

    fun createForNotification(context: Context): Markwon {
        val headingSizes = floatArrayOf(2f, 1.5f, 1.17f, 1f, .83f, .67f)
        val bulletGapWidth = (8 * context.resources.displayMetrics.density + 0.5f).toInt()

        return Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(ImagesPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder
                        .setFactory(Heading::class.java) { _, props: RenderProps? ->
                            arrayOf(
                                RelativeSizeSpan(headingSizes[CoreProps.HEADING_LEVEL.require(props!!) - 1]),
                                StyleSpan(Typeface.BOLD)
                            )
                        }
                        .setFactory(Emphasis::class.java) { _, _ -> StyleSpan(Typeface.ITALIC) }
                        .setFactory(StrongEmphasis::class.java) { _, _ -> StyleSpan(Typeface.BOLD) }
                        .setFactory(ListItem::class.java) { _, _ -> BulletSpan(bulletGapWidth) }
                        .setFactory(Link::class.java) { _, _ -> null }
                }

                override fun configureParser(builder: Parser.Builder) {
                    builder.extensions(setOf(TablesExtension.create()))
                }

                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    builder.on(TableCell::class.java) { visitor: MarkwonVisitor, node: TableCell? ->
                        visitor.visitChildren(node!!)
                        visitor.builder().append(' ')
                    }
                }
            })
            .build()
    }
}
