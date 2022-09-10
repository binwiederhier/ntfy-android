package io.heckel.ntfy.util

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import io.heckel.ntfy.R
import io.heckel.ntfy.db.*
import io.heckel.ntfy.msg.MESSAGE_ENCODING_BASE64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DateFormat
import java.text.StringCharacterIterator
import java.util.*
import kotlin.math.abs
import kotlin.math.absoluteValue

fun topicUrl(baseUrl: String, topic: String) = "${baseUrl}/${topic}"
fun topicUrlUp(baseUrl: String, topic: String) = "${baseUrl}/${topic}?up=1" // UnifiedPush
fun topicUrlJson(baseUrl: String, topic: String, since: String) = "${topicUrl(baseUrl, topic)}/json?since=$since"
fun topicUrlWs(baseUrl: String, topic: String, since: String) = "${topicUrl(baseUrl, topic)}/ws?since=$since"
fun topicUrlAuth(baseUrl: String, topic: String) = "${topicUrl(baseUrl, topic)}/auth"
fun topicUrlJsonPoll(baseUrl: String, topic: String, since: String) = "${topicUrl(baseUrl, topic)}/json?poll=1&since=$since"
fun topicShortUrl(baseUrl: String, topic: String) = shortUrl(topicUrl(baseUrl, topic))

fun displayName(subscription: Subscription) : String {
    return subscription.displayName ?: topicShortUrl(subscription.baseUrl, subscription.topic)
}

fun shortUrl(url: String) = url
    .replace("http://", "")
    .replace("https://", "")

fun splitTopicUrl(topicUrl: String): Pair<String, String> {
    if (topicUrl.lastIndexOf("/") == -1) throw Exception("Invalid argument $topicUrl")
    return Pair(topicUrl.substringBeforeLast("/"), topicUrl.substringAfterLast("/"))
}

fun maybeSplitTopicUrl(topicUrl: String): Pair<String, String>? {
    return try {
        splitTopicUrl(topicUrl)
    } catch (_: Exception) {
        null
    }
}

fun validTopic(topic: String): Boolean {
    return "[-_A-Za-z0-9]{1,64}".toRegex().matches(topic) // Must match server side!
}

fun validUrl(url: String): Boolean {
    return "^https?://\\S+".toRegex().matches(url)
}

fun formatDateShort(timestampSecs: Long): String {
    val date = Date(timestampSecs*1000)
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
}

fun toPriority(priority: Int?): Int {
    if (priority != null && (1..5).contains(priority)) return priority
    else return 3
}

fun toPriorityString(context: Context, priority: Int): String {
    return when (priority) {
        1 -> context.getString(R.string.settings_notifications_priority_min)
        2 -> context.getString(R.string.settings_notifications_priority_low)
        3 -> context.getString(R.string.settings_notifications_priority_default)
        4 -> context.getString(R.string.settings_notifications_priority_high)
        5 -> context.getString(R.string.settings_notifications_priority_max)
        else -> context.getString(R.string.settings_notifications_priority_default)
    }
}

fun joinTags(tags: List<String>?): String {
    return tags?.joinToString(",") ?: ""
}

fun joinTagsMap(tags: List<String>?): String {
    return tags?.mapIndexed { i, tag -> "${i+1}=${tag}" }?.joinToString(",") ?: ""
}

fun splitTags(tags: String?): List<String> {
    return if (tags == null || tags == "") {
        emptyList()
    } else {
        tags.split(",")
    }
}

fun toEmojis(tags: List<String>): List<String> {
    return tags.mapNotNull { tag -> toEmoji(tag) }
}

fun toEmoji(tag: String): String? {
    return EmojiManager.getForAlias(tag)?.unicode
}

fun unmatchedTags(tags: List<String>): List<String> {
    return tags.filter { tag -> toEmoji(tag) == null }
}

/**
 * Prepend tags/emojis to message, but only if there is a non-empty title.
 * Otherwise, the tags will be prepended to the title.
 */
fun formatMessage(notification: Notification): String {
    return if (notification.title != "") {
        decodeMessage(notification)
    } else {
        val emojis = toEmojis(splitTags(notification.tags))
        if (emojis.isEmpty()) {
            decodeMessage(notification)
        } else {
            emojis.joinToString("") + " " + decodeMessage(notification)
        }
    }
}

fun decodeMessage(notification: Notification): String {
    return try {
        if (notification.encoding == MESSAGE_ENCODING_BASE64) {
            String(Base64.decode(notification.message, Base64.DEFAULT))
        } else {
            notification.message
        }
    } catch (e: IllegalArgumentException) {
        notification.message + "(invalid base64)"
    }
}

fun decodeBytesMessage(notification: Notification): ByteArray {
    return try {
        if (notification.encoding == MESSAGE_ENCODING_BASE64) {
            Base64.decode(notification.message, Base64.DEFAULT)
        } else {
            notification.message.toByteArray()
        }
    } catch (e: IllegalArgumentException) {
        notification.message.toByteArray()
    }
}

/**
 * See above; prepend emojis to title if the title is non-empty.
 * Otherwise, they are prepended to the message.
 */
fun formatTitle(subscription: Subscription, notification: Notification): String {
    return if (notification.title != "") {
        formatTitle(notification)
    } else {
        displayName(subscription)
    }
}

fun formatTitle(notification: Notification): String {
    val emojis = toEmojis(splitTags(notification.tags))
    return if (emojis.isEmpty()) {
        notification.title
    } else {
        emojis.joinToString("") + " " + notification.title
    }
}

fun formatActionLabel(action: Action): String {
    return when (action.progress) {
        ACTION_PROGRESS_ONGOING -> action.label + " …"
        ACTION_PROGRESS_SUCCESS -> action.label + " ✔️"
        ACTION_PROGRESS_FAILED -> action.label + " ❌️"
        else -> action.label
    }
}

fun maybeAppendActionErrors(message: String, notification: Notification): String {
    val actionErrors = notification.actions
        .orEmpty()
        .mapNotNull { action -> action.error }
        .joinToString("\n")
    if (actionErrors.isEmpty()) {
        return message
    } else {
        return "${message}\n\n${actionErrors}"
    }
}

// Checks in the most horrible way if a content URI exists; I couldn't find a better way
fun fileExists(context: Context, contentUri: String?): Boolean {
    return try {
        fileStat(context, Uri.parse(contentUri)) // Throws if the file does not exist
        true
    } catch (_: Exception) {
        false
    }
}

// Queries the filename of a content URI
fun fileName(context: Context, contentUri: String?, fallbackName: String): String {
    return try {
        val info = fileStat(context, Uri.parse(contentUri))
        info.filename
    } catch (_: Exception) {
        fallbackName
    }
}

fun fileStat(context: Context, contentUri: Uri?): FileInfo {
    if (contentUri == null) {
        throw FileNotFoundException("URI is null")
    }
    val resolver = context.applicationContext.contentResolver
    val cursor = resolver.query(contentUri, null, null, null, null) ?: throw Exception("Query returned null")
    return cursor.use { c ->
        val nameIndex = c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = c.getColumnIndexOrThrow(OpenableColumns.SIZE)
        if (!c.moveToFirst()) {
            throw FileNotFoundException("Not found: $contentUri")
        }
        val size = c.getLong(sizeIndex)
        if (size == 0L) {
            // Content provider URIs (e.g. content://io.heckel.ntfy.provider/cache_files/DQ4o7DitZAmw) return an entry, even
            // when they do not exist, but with an empty size. This is a practical/fast way to weed out non-existing files.
            throw FileNotFoundException("Not found or empty: $contentUri")
        }
        FileInfo(
            filename = c.getString(nameIndex),
            size = c.getLong(sizeIndex)
        )
    }
}

fun maybeFileStat(context: Context, contentUri: String?): FileInfo? {
    return try {
        fileStat(context, Uri.parse(contentUri)) // Throws if the file does not exist
    } catch (_: Exception) {
        null
    }
}

data class FileInfo(
    val filename: String,
    val size: Long,
)

// Status bar color fading to match action bar, see https://stackoverflow.com/q/51150077/1440785
fun fadeStatusBarColor(window: Window, fromColor: Int, toColor: Int) {
    val statusBarColorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
    statusBarColorAnimation.addUpdateListener { animator ->
        val color = animator.animatedValue as Int
        window.statusBarColor = color
    }
    statusBarColorAnimation.start()
}

// Generates a (cryptographically secure) random string of a certain length
fun randomString(len: Int): String {
    val random = SecureRandom()
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
    return (1..len).map { chars[random.nextInt(chars.size)] }.joinToString("")
}

// Generates a random, positive subscription ID between 0-10M. This ensures that it doesn't have issues
// when exported to JSON. It uses SecureRandom, because Random causes issues in the emulator (generating the
// same value again and again), sometimes.
fun randomSubscriptionId(): Long {
    return SecureRandom().nextLong().absoluteValue % 100_000_000
}

// Allows letting multiple variables at once, see https://stackoverflow.com/a/35522422/1440785
inline fun <T1: Any, T2: Any, R: Any> safeLet(p1: T1?, p2: T2?, block: (T1, T2)->R?): R? {
    return if (p1 != null && p2 != null) block(p1, p2) else null
}

fun formatBytes(bytes: Long, decimals: Int = 1): String {
    val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else abs(bytes)
    if (absB < 1024) {
        return "$bytes B"
    }
    var value = absB
    val ci = StringCharacterIterator("KMGTPE")
    var i = 40
    while (i >= 0 && absB > 0xfffccccccccccccL shr i) {
        value = value shr 10
        ci.next()
        i -= 10
    }
    value *= java.lang.Long.signum(bytes).toLong()
    return java.lang.String.format("%.${decimals}f %cB", value / 1024.0, ci.current())
}

fun mimeTypeToIconResource(mimeType: String?): Int {
    return if (mimeType?.startsWith("image/") == true) {
        R.drawable.ic_file_image_red_24dp
    } else if (mimeType?.startsWith("video/") == true) {
        R.drawable.ic_file_video_orange_24dp
    } else if (mimeType?.startsWith("audio/") == true) {
        R.drawable.ic_file_audio_purple_24dp
    } else if (mimeType == "application/vnd.android.package-archive") {
        R.drawable.ic_file_app_gray_24dp
    } else {
        R.drawable.ic_file_document_blue_24dp
    }
}

fun supportedImage(mimeType: String?): Boolean {
    return listOf("image/jpeg", "image/png").contains(mimeType)
}

// Check if battery optimization is enabled, see https://stackoverflow.com/a/49098293/1440785
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    val appName = context.applicationContext.packageName
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        return powerManager.isIgnoringBatteryOptimizations(appName)
    }
    return true
}

// Returns true if dark mode is on, see https://stackoverflow.com/a/60761189/1440785
fun Context.systemDarkThemeOn(): Boolean {
    return resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
}

fun isDarkThemeOn(context: Context): Boolean {
    val darkMode = Repository.getInstance(context).getDarkMode()
    if (darkMode == AppCompatDelegate.MODE_NIGHT_YES) {
        return true
    }
    if (darkMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM && context.systemDarkThemeOn()) {
        return true
    }
    return false
}

// https://cketti.de/2020/05/23/content-uris-and-okhttp/
class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val size: Long
) : RequestBody() {
    override fun contentLength(): Long {
        return size
    }
    override fun contentType(): MediaType? {
        val contentType = resolver.getType(uri)
        return contentType?.toMediaTypeOrNull()
    }
    override fun writeTo(sink: BufferedSink) {
        val inputStream = resolver.openInputStream(uri) ?: throw IOException("Couldn't open content URI for reading")
        inputStream.source().use { source ->
            sink.writeAll(source)
        }
    }
}

// Hack: Make end icon for drop down smaller, see https://stackoverflow.com/a/57098715/1440785
fun View.makeEndIconSmaller(resources: Resources) {
    val dimension = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30f, resources.displayMetrics)
    val endIconImageView = findViewById<ImageView>(R.id.text_input_end_icon)
    endIconImageView.minimumHeight = dimension.toInt()
    endIconImageView.minimumWidth = dimension.toInt()
    requestLayout()
}

// Shows the ripple effect on the view, if it is ripple-able, see https://stackoverflow.com/a/56314062/1440785
fun View.showRipple() {
    if (background is RippleDrawable) {
        background.state = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
    }
}

// Hides the ripple effect on the view, if it is ripple-able, see https://stackoverflow.com/a/56314062/1440785
fun View.hideRipple() {
    if (background is RippleDrawable) {
        background.state = intArrayOf()
    }
}

// Toggles the ripple effect on the view, if it is ripple-able
fun View.ripple(scope: CoroutineScope) {
    showRipple()
    scope.launch(Dispatchers.Main) {
        delay(200)
        hideRipple()
    }
}


fun Uri.readBitmapFromUri(context: Context): Bitmap {
    val resolver = context.applicationContext.contentResolver
    val bitmapStream = resolver.openInputStream(this)
    return BitmapFactory.decodeStream(bitmapStream)
}

fun String.readBitmapFromUri(context: Context): Bitmap {
    return Uri.parse(this).readBitmapFromUri(context)
}

fun String.readBitmapFromUriOrNull(context: Context): Bitmap? {
    return try {
        this.readBitmapFromUri(context)
    } catch (_: Exception) {
        null
    }
}

// TextWatcher that only implements the afterTextChanged method
class AfterChangedTextWatcher(val afterTextChangedFn: (s: Editable?) -> Unit) : TextWatcher {
    override fun afterTextChanged(s: Editable?) {
        afterTextChangedFn(s)
    }
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        // Nothing
    }
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        // Nothing
    }
}

fun ensureSafeNewFile(dir: File, name: String): File {
    val safeName = name.replace("[^-_.()\\w]+".toRegex(), "_");
    val file = File(dir, safeName)
    if (!file.exists()) {
        return file
    }
    (1..1000).forEach { i ->
        val newFile = File(dir, if (file.extension == "") {
            "${file.nameWithoutExtension} ($i)"
        } else {
            "${file.nameWithoutExtension} ($i).${file.extension}"
        })
        if (!newFile.exists()) {
            return newFile
        }
    }
    throw Exception("Cannot find safe file")
}

fun copyToClipboard(context: Context, notification: Notification) {
    val message = decodeMessage(notification)
    val text = message + "\n\n" + formatDateShort(notification.timestamp)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("notification message", text)
    clipboard.setPrimaryClip(clip)
    Toast
        .makeText(context, context.getString(R.string.detail_copied_to_clipboard_message), Toast.LENGTH_LONG)
        .show()
}

fun String.sha256(): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(this.toByteArray())
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}
