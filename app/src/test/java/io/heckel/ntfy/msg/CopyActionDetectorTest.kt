package io.heckel.ntfy.msg

import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.msg.CopyActionDetector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CopyActionDetectorTest {
    @Test
    fun findsCodeLinkAndEmailInOrderOfPriority() {
        val text = "Write to help@example.com or open https://example.com/login, then enter 482913"
        assertEquals(
            listOf(Kind.CODE to "482913", Kind.LINK to "https://example.com/login", Kind.EMAIL to "help@example.com"),
            CopyActionDetector.findValues(text)
        )
    }

    @Test
    fun findsNothing() {
        assertEquals(emptyList<Pair<Kind, String>>(), CopyActionDetector.findValues("Backup finished in 42 seconds"))
        assertEquals(emptyList<Pair<Kind, String>>(), CopyActionDetector.findValues(""))
    }

    @Test
    fun findsCodes() {
        assertEquals("482913", code("Your verification code is 482913."))
        assertEquals("48291", code("48291 is your login code"))
        assertEquals("482913", code("G-482913 is your Google verification code"))
        assertEquals("482913", code("Doğrulama kodunuz: 482913'tür"))
        assertEquals("482913", code("Code: **482913**"))
        assertEquals("482913", code("Code:\n482913"))
        assertEquals("111111", code("Codes: 111111, 222222"))
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
        assertEquals("https://first.example.com", link("https://first.example.com and https://second.example.com"))
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
    fun findsValuesInTitleAndMessage() {
        val notification = notification(title = "Your code is 482913", message = "Questions? Write to help@example.com")
        assertEquals(listOf(Kind.CODE to "482913", Kind.EMAIL to "help@example.com"), CopyActionDetector.findValues(notification))
    }

    @Test
    fun fillsOnlyFreeActionSlots() {
        val message = "Code 482913, see https://example.com or write to help@example.com"
        assertEquals(
            listOf(Kind.CODE to "482913", Kind.LINK to "https://example.com", Kind.EMAIL to "help@example.com"),
            CopyActionDetector.findValues(notification(message = message))
        )
        assertEquals(
            listOf(Kind.CODE to "482913", Kind.LINK to "https://example.com"),
            CopyActionDetector.findValues(notification(message = message, actions = listOf(viewAction())))
        )
        assertEquals(
            listOf(Kind.CODE to "482913"),
            CopyActionDetector.findValues(notification(message = message, actions = listOf(viewAction(), viewAction())))
        )
        assertEquals(
            emptyList<Pair<Kind, String>>(),
            CopyActionDetector.findValues(notification(message = message, actions = listOf(viewAction(), viewAction(), viewAction())))
        )
    }

    @Test
    fun skipsValuesThePublisherAlreadyCopies() {
        val notification = notification(message = "Code 482913, see https://example.com", actions = listOf(copyAction("482913")))
        assertEquals(listOf(Kind.LINK to "https://example.com"), CopyActionDetector.findValues(notification))
    }

    private fun code(text: String) = CopyActionDetector.findValues(text).toMap()[Kind.CODE]
    private fun link(text: String) = CopyActionDetector.findValues(text).toMap()[Kind.LINK]
    private fun email(text: String) = CopyActionDetector.findValues(text).toMap()[Kind.EMAIL]

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

    private fun viewAction() = action(NotificationService.ACTION_VIEW, url = "https://ntfy.sh", value = null)
    private fun copyAction(value: String) = action(NotificationService.ACTION_COPY, url = null, value = value)

    private fun action(action: String, url: String?, value: String?): Action {
        return Action(
            id = "action1",
            action = action,
            label = "Label",
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
