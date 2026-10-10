package io.heckel.ntfy.msg

import io.heckel.ntfy.R
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.msg.ContextualActionDetector.Kind
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_COPY
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_VIEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualActionDetectorTest {
    @Test
    fun findsValuesInOrderOfPriority() {
        val text = "Write to help@example.com or open https://example.com/login, then enter 482913"
        assertEquals(
            listOf(Kind.CODE to "482913", Kind.LINK to "https://example.com/login", Kind.EMAIL to "help@example.com"),
            ContextualActionDetector.findValues(text)
        )
    }

    @Test
    fun findsAllValuesOfAKindInOrderOfAppearance() {
        val text = "See https://a.example.com and https://b.example.com, then https://a.example.com again. Codes: 111111, 222222"
        assertEquals(
            listOf(
                Kind.CODE to "111111",
                Kind.CODE to "222222",
                Kind.LINK to "https://a.example.com",
                Kind.LINK to "https://b.example.com"
            ),
            ContextualActionDetector.findValues(text)
        )
    }

    @Test
    fun findsNothing() {
        assertEquals(emptyList<Pair<Kind, String>>(), ContextualActionDetector.findValues("Backup finished in 42 seconds"))
        assertEquals(emptyList<Pair<Kind, String>>(), ContextualActionDetector.findValues(""))
    }

    @Test
    fun findsCodes() {
        assertEquals("482913", code("Your verification code is 482913."))
        assertEquals("48291", code("48291 is your login code"))
        assertEquals("482913", code("G-482913 is your Google verification code"))
        assertEquals("482913", code("Doğrulama kodunuz: 482913'tür"))
        assertEquals("482913", code("Code: **482913**"))
        assertEquals("482913", code("Code:\n482913"))
    }

    @Test
    fun ignoresNumbersThatAreNotCodes() {
        assertNull(code("Disk 1234 is full")) // Too short
        assertNull(code("Order 1234567 shipped")) // Too long
        assertNull(code("Call +905321234567"))
        assertNull(code("Serial ABC123456")) // Part of a word
        assertNull(code("Serial 123456ABC"))
        assertNull(code("Paid 12345.67 USD")) // Part of a decimal number
        assertNull(code("Paid 123456,78 TL"))
        assertNull(code("Version 1.123456"))
        assertNull(code("Date 2026-10-09 12:34:56"))
        assertNull(code("Issue https://example.com/issues/123456")) // Part of a link
        assertNull(code("Mail 123456@example.com")) // Part of an email
    }

    @Test
    fun findsLinks() {
        assertEquals("https://example.com", link("Go to https://example.com."))
        assertEquals("https://example.com/a?b=c&d=e#f", link("https://example.com/a?b=c&d=e#f"))
        assertEquals("http://example.com", link("(see http://example.com)"))
        assertEquals("https://example.com/docs", link("Read the [docs](https://example.com/docs)"))
        assertEquals("https://example.com", link("[https://example.com](https://example.com)"))
        assertEquals("https://en.wikipedia.org/wiki/Ntfy_(app)", link("https://en.wikipedia.org/wiki/Ntfy_(app)"))
        assertEquals("https://example.com", link("<https://example.com>"))
        assertEquals("https://example.com", link("**https://example.com**"))
        assertEquals("https://example.com", link("\"https://example.com\""))
        assertEquals("HTTPS://EXAMPLE.COM", link("HTTPS://EXAMPLE.COM"))
        assertEquals("www.example.com", link("Visit www.example.com!"))
        assertEquals("http://[::1]:8080/topic", link("Server at http://[::1]:8080/topic"))
        assertEquals("https://example.com/giriş", link("https://example.com/giriş adresine gidin"))
    }

    @Test
    fun ignoresTextThatIsNotALink() {
        assertNull(link("Read example.com or report.pdf"))
        assertNull(link("ftp://example.com"))
        assertNull(link("https:// is not a link, and neither is www."))
        assertNull(link("awww.example.com"))
    }

    @Test
    fun findsEmails() {
        assertEquals("help@example.com", email("Contact help@example.com."))
        assertEquals("first.last+tag@mail.example.co.uk", email("first.last+tag@mail.example.co.uk"))
        assertEquals("help@example.com", email("[mail](mailto:help@example.com)"))
        assertEquals("help@example.com", email("<help@example.com>"))
        assertEquals("ali@şirket.com.tr", email("ali@şirket.com.tr adresine yazın"))
    }

    @Test
    fun ignoresTextThatIsNotAnEmail() {
        assertNull(email("Meet me @home"))
        assertNull(email("root@localhost"))
        assertNull(email("help@example.com123"))
        assertNull(email("Log in at https://user@example.com")) // Part of a link
    }

    @Test
    fun opensLinksAndCopiesCodesAndEmails() {
        val actions = ContextualActionDetector.detect(notification(
            title = "Your code is 482913",
            message = "Open https://example.com/login or write to help@example.com"
        ))
        assertEquals(
            listOf(
                contextualAction("contextual_0", ACTION_COPY, label = "482913", url = null, value = "482913"),
                contextualAction("contextual_1", ACTION_VIEW, label = "example.com/login", url = "https://example.com/login", value = null),
                contextualAction("contextual_2", ACTION_COPY, label = "help@example.com", url = null, value = "help@example.com")
            ),
            actions
        )
        assertEquals(listOf(R.drawable.ic_content_copy_teal_24dp, R.drawable.ic_open_in_new_teal_24dp, R.drawable.ic_content_copy_teal_24dp), actions.map { ContextualActionDetector.icon(it) })
    }

    @Test
    fun offersAtMostThreeValuesInOrderOfPriority() {
        val links = "https://a.example.com https://b.example.com https://c.example.com https://d.example.com"
        assertEquals(
            listOf("a.example.com", "b.example.com", "c.example.com"),
            ContextualActionDetector.detect(notification(message = "Links: $links, mail help@example.com")).map { it.label }
        )
        assertEquals(
            listOf("482913", "a.example.com", "b.example.com"),
            ContextualActionDetector.detect(notification(message = "Links: $links, code 482913")).map { it.label }
        )
    }

    @Test
    fun shortensLabelsButNotValues() {
        val link = "https://www.example.com/reset-password?token=abcdef123456"
        val email = "first.last.with.a.long.name@example.com"
        val actions = ContextualActionDetector.detect(notification(message = "$link $email www.example.com/"))
        assertEquals(listOf("example.com/reset-passw…", "example.com", "first.last.with.a.long.…"), actions.map { it.label })
        assertEquals(listOf(link, "http://www.example.com/", null), actions.map { it.url })
        assertEquals(listOf(null, null, email), actions.map { it.value })
    }

    @Test
    fun skipsValuesThePublisherAlreadyOpensOrCopies() {
        val publisherActions = listOf(
            publisherAction(ACTION_COPY, url = null, value = "482913"),
            publisherAction(ACTION_VIEW, url = "https://example.com/login", value = null)
        )
        val actions = ContextualActionDetector.detect(notification(
            message = "Code 482913, open https://example.com/login or https://example.com/help",
            actions = publisherActions
        ))
        assertEquals(listOf("https://example.com/help"), actions.map { it.url })
    }

    private fun code(text: String) = ContextualActionDetector.findValues(text).firstOrNull { it.first == Kind.CODE }?.second
    private fun link(text: String) = ContextualActionDetector.findValues(text).firstOrNull { it.first == Kind.LINK }?.second
    private fun email(text: String) = ContextualActionDetector.findValues(text).firstOrNull { it.first == Kind.EMAIL }?.second

    private fun notification(title: String = "", message: String, actions: List<Action>? = null): Notification {
        return Notification(
            id = "msg1",
            subscriptionId = 1,
            timestamp = 1760000000,
            sequenceId = "msg1",
            title = title,
            message = message,
            contentType = "",
            encoding = "",
            notificationId = 1,
            priority = 3,
            tags = "",
            click = "",
            icon = null,
            actions = actions,
            attachment = null,
            deleted = false
        )
    }

    private fun publisherAction(action: String, url: String?, value: String?) = contextualAction("action1", action, "Label", url, value)

    private fun contextualAction(id: String, action: String, label: String, url: String?, value: String?): Action {
        return Action(
            id = id,
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
