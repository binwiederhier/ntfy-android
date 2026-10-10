package io.heckel.ntfy.msg

import io.heckel.ntfy.R
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_COPY
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_VIEW
import io.heckel.ntfy.util.decodeMessage

/**
 * Finds codes (5-6 digits), links, and email addresses in a notification's title and message, and turns them into
 * contextual actions that show the value itself: tapping a link opens it, tapping a code or an email address copies
 * it. Android shows contextual actions as chips below the message (Android 10+), and so does the detail view.
 *
 * Up to three values are offered, codes first, then links, then email addresses. A value that one of the publisher's
 * own actions already opens or copies is skipped.
 *
 * Contextual actions are not stored with the notification. They are derived again whenever they are needed
 * (notification popup, detail view, UserActionWorker).
 */
object ContextualActionDetector {
    private const val MAX_ACTIONS = 3
    private const val MAX_LABEL_LENGTH = 24 // Android hides chips that don't fit next to each other
    private const val ACTION_ID_PREFIX = "contextual_" // Cannot clash with the publisher's action IDs, which are alphanumeric

    // Links start with "http://", "https://" or "www.", and end at whitespace or a character that is never
    // part of a URL. Emails and digits inside a link are not detected separately (see findValues).
    private val linkRegex = Regex("""(?i)(?<![\p{L}\p{N}])(?:https?://|www\.)[\p{L}\p{N}\[][^\s<>"'`\{\}|\\^‘’“”«»]*""")
    private val emailRegex = Regex("""(?<![\p{L}\p{N}._%+\-])[\p{L}\p{N}][\p{L}\p{N}._%+\-]*@[\p{L}\p{N}](?:[\p{L}\p{N}\-]*[\p{L}\p{N}])?(?:\.[\p{L}\p{N}](?:[\p{L}\p{N}\-]*[\p{L}\p{N}])?)*\.\p{L}{2,}(?![\p{L}\p{N}\-])""")

    // Codes are 5-6 digits on their own: not part of a longer number, a word ("ABC12345"), or a decimal number ("12345.67")
    private val codeRegex = Regex("""(?<![\p{L}\p{N}_])(?<![0-9][.,])[0-9]{5,6}(?![\p{L}\p{N}_])(?![.,][0-9])""")

    // Removed from link labels to keep them short, e.g. "https://www.example.com/" is shown as "example.com"
    private val linkLabelPrefixRegex = Regex("""(?i)^(?:https?://)?(?:www\.)?""")

    /** Kinds of detected values, in order of priority */
    enum class Kind { CODE, LINK, EMAIL }

    fun detect(notification: Notification): List<Action> {
        val publisherActions = notification.actions.orEmpty()
        return findValues("${notification.title}\n${decodeMessage(notification)}")
            .map { (kind, value) -> toAction(kind, value) }
            .filterNot { action -> publisherActions.any { it.action == action.action && it.url == action.url && it.value == action.value } }
            .take(MAX_ACTIONS)
            .mapIndexed { i, action -> action.copy(id = ACTION_ID_PREFIX + i) }
    }

    /**
     * Icons are colored, because Android does not tint the icons of contextual actions. The color
     * has a light and a dark variant, so the icon is visible in both light and dark mode.
     */
    fun icon(action: Action): Int {
        return if (action.action == ACTION_VIEW) R.drawable.ic_open_in_new_teal_24dp else R.drawable.ic_content_copy_teal_24dp
    }

    /**
     * Returns all codes, links, and email addresses found in the text, in order of priority (and in the order they
     * appear in the text within each kind). Links are blanked out before looking for emails, and both before looking
     * for codes, so that "https://example.com/orders/123456" is a link, not a code.
     */
    internal fun findValues(text: String): List<Pair<Kind, String>> {
        val links = linkRegex.findAll(text).toList()
        val textWithoutLinks = text.blankOut(links)
        val emails = emailRegex.findAll(textWithoutLinks).toList()
        val textWithoutLinksAndEmails = textWithoutLinks.blankOut(emails)
        val codes = codeRegex.findAll(textWithoutLinksAndEmails).toList()
        return (codes.map { Kind.CODE to it.value } + links.map { Kind.LINK to trimLink(it.value) } + emails.map { Kind.EMAIL to it.value })
            .distinct()
    }

    /**
     * Cuts a link at the first closing bracket it did not open, as in "(see https://example.com)" or the
     * Markdown link "[docs](https://example.com)", and drops trailing punctuation, as in "Go to https://example.com."
     */
    private fun trimLink(link: String): String {
        var openParens = 0
        var openBrackets = 0
        var end = link.length
        for ((i, c) in link.withIndex()) {
            if ((c == ')' && openParens == 0) || (c == ']' && openBrackets == 0)) {
                end = i
                break
            }
            when (c) {
                '(' -> openParens++
                ')' -> openParens--
                '[' -> openBrackets++
                ']' -> openBrackets--
            }
        }
        return link.substring(0, end).trimEnd('.', ',', ';', ':', '!', '?', '*', '…')
    }

    private fun String.blankOut(matches: List<MatchResult>): String {
        val chars = toCharArray()
        matches.forEach { match -> chars.fill(' ', match.range.first, match.range.last + 1) }
        return String(chars)
    }

    private fun toAction(kind: Kind, value: String): Action {
        return when (kind) {
            Kind.LINK -> {
                val url = if (value.startsWith("www.", ignoreCase = true)) "http://$value" else value // Like Linkify
                action(ACTION_VIEW, label = shorten(value.replace(linkLabelPrefixRegex, "").removeSuffix("/")), url = url, value = null)
            }
            Kind.CODE, Kind.EMAIL -> action(ACTION_COPY, label = shorten(value), url = null, value = value)
        }
    }

    private fun shorten(label: String): String {
        return if (label.length > MAX_LABEL_LENGTH) label.take(MAX_LABEL_LENGTH - 1) + "…" else label
    }

    private fun action(action: String, label: String, url: String?, value: String?): Action {
        return Action(
            id = "", // Set in detect()
            action = action,
            label = label,
            clear = false,
            url = url,
            method = null,
            headers = null,
            body = null,
            intent = null,
            extras = null,
            value = value,
            progress = null,
            error = null
        )
    }
}
