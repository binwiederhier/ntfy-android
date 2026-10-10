package io.heckel.ntfy.msg

import android.content.Context
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_COPY
import io.heckel.ntfy.util.decodeMessage

/**
 * Finds values worth copying in a notification's title and message, and offers them as "copy" action
 * buttons: a 5-6 digit code first, then a link, then an email address (at most one of each).
 *
 * Detected actions are not stored with the notification. They are derived again whenever they are needed
 * (notification popup, detail view, UserActionWorker), and only fill the action slots the publisher's own
 * actions left free. A value that one of the publisher's "copy" actions already copies is not offered again.
 */
object CopyActionDetector {
    private const val MAX_ACTIONS = 3 // Same limit as the server, see "only 3 actions allowed"

    // Links start with "http://", "https://" or "www.", and end at whitespace or a character that is never
    // part of a URL. Emails and digits inside a link are not detected separately (see findValues).
    private val linkRegex = Regex("""(?i)(?<![\p{L}\p{N}])(?:https?://|www\.)[\p{L}\p{N}\[][^\s<>"'`\{\}|\\^‘’“”«»]*""")
    private val emailRegex = Regex("""(?<![\p{L}\p{N}._%+\-])[\p{L}\p{N}][\p{L}\p{N}._%+\-]*@[\p{L}\p{N}](?:[\p{L}\p{N}\-]*[\p{L}\p{N}])?(?:\.[\p{L}\p{N}](?:[\p{L}\p{N}\-]*[\p{L}\p{N}])?)*\.\p{L}{2,}(?![\p{L}\p{N}\-])""")

    // Codes are 5-6 digits on their own: not part of a longer number, a word ("ABC12345"), or a decimal number ("12345.67")
    private val codeRegex = Regex("""(?<![\p{L}\p{N}_])(?<![0-9][.,])[0-9]{5,6}(?![\p{L}\p{N}_])(?![.,][0-9])""")

    /**
     * Kinds of detected values, in order of priority. The action IDs cannot clash with the
     * IDs of the publisher's actions, which the server generates as random alphanumeric strings.
     */
    enum class Kind(val actionId: String, val label: Int) {
        CODE("detected_code", R.string.notification_popup_action_copy_code),
        LINK("detected_link", R.string.notification_popup_action_copy_link),
        EMAIL("detected_email", R.string.notification_popup_action_copy_email)
    }

    fun detect(context: Context, notification: Notification): List<Action> {
        return findValues(notification).map { (kind, value) -> copyAction(kind.actionId, context.getString(kind.label), value) }
    }

    /**
     * Returns the values to offer for this notification, in order of priority: only as many as there are
     * free action slots, and none that one of the publisher's "copy" actions already copies.
     */
    internal fun findValues(notification: Notification): List<Pair<Kind, String>> {
        val actions = notification.actions.orEmpty()
        val copiedValues = actions.filter { it.action == ACTION_COPY }.mapNotNull { it.value }
        val freeSlots = (MAX_ACTIONS - actions.size).coerceAtLeast(0)
        return findValues("${notification.title}\n${decodeMessage(notification)}")
            .filter { (_, value) -> value !in copiedValues }
            .take(freeSlots)
    }

    /**
     * Returns the first code, link, and email address found in the text, in order of priority.
     * Links are blanked out before looking for emails, and both before looking for codes, so that
     * "https://example.com/orders/123456" is a link, not a code.
     */
    internal fun findValues(text: String): List<Pair<Kind, String>> {
        val links = linkRegex.findAll(text).toList()
        val textWithoutLinks = text.blankOut(links)
        val emails = emailRegex.findAll(textWithoutLinks).toList()
        val textWithoutLinksAndEmails = textWithoutLinks.blankOut(emails)
        val code = codeRegex.find(textWithoutLinksAndEmails)?.value
        return listOfNotNull(
            code?.let { Kind.CODE to it },
            links.firstOrNull()?.let { Kind.LINK to trimLink(it.value) },
            emails.firstOrNull()?.let { Kind.EMAIL to it.value }
        )
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

    private fun copyAction(id: String, label: String, value: String): Action {
        return Action(
            id = id,
            action = ACTION_COPY,
            label = label,
            clear = false,
            url = null,
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
