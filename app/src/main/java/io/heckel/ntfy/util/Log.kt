package io.heckel.ntfy.util

import android.content.Context
import android.os.Build
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.backup.Backuper
import io.heckel.ntfy.db.Database
import io.heckel.ntfy.db.LogDao
import io.heckel.ntfy.db.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Log(private val logsDao: LogDao) {
    private val record: AtomicBoolean = AtomicBoolean(false)
    private val count: AtomicInteger = AtomicInteger(0)
    private val scrubNum: AtomicInteger = AtomicInteger(-1)
    private val scrubTerms = Collections.synchronizedMap(mutableMapOf<String, ReplaceTerm>())

    private fun log(level: Int, tag: String, message: String, exception: Throwable?) {
        if (!record.get()) return
        GlobalScope.launch(Dispatchers.IO) { // FIXME This does not guarantee the log order
            logsDao.insert(LogEntry(System.currentTimeMillis(), tag, level, message, exception?.stackTraceToString()))
            val current = count.incrementAndGet()
            if (current >= PRUNE_EVERY) {
                logsDao.prune(ENTRIES_MAX)
                count.set(0) // I know there is a race here, but this is good enough
            }
        }
    }

    fun getFormatted(context: Context, scrub: Boolean): String {
        val backuper = Backuper(context)
        return if (scrub) {
            val logs = formatEntries(scrubEntries(logsDao.getAll()))
            val settings = scrub(backuper.settingsAsString()) ?: ""
            prependDeviceInfo(logs, settings, scrubLine = true)
        } else {
            val logs = formatEntries(logsDao.getAll())
            val settings = backuper.settingsAsString()
            prependDeviceInfo(logs, settings, scrubLine = false)
        }
    }

    private fun prependDeviceInfo(logs: String, settings: String, scrubLine: Boolean): String {
        val maybeScrubLine = if (scrubLine) "Server URLs (aside from ntfy.sh) and topics have been replaced with fruits 🍌🥝🍋🥥🥑🍊🍎🍑.\n" else ""
        return """
            This is a log of the ntfy Android app. The log shows up to 1,000 entries.
            $maybeScrubLine
            Device info:
            --
            ntfy: ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})
            OS: ${System.getProperty("os.version")}
            Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            Model: ${Build.DEVICE}
            Product: ${Build.PRODUCT}

            --
            Settings:                    
        """.trimIndent() + "\n$settings\n\nLogs\n--\n\n$logs"
    }

    fun addScrubTerm(term: String, type: TermType = TermType.Term) {
        if (scrubTerms[term] != null || IGNORE_TERMS.contains(term)) {
            return
        }
        if (type == TermType.Password) {
            scrubTerms[term] = ReplaceTerm(type, "********")
            return
        }
        val replaceTermIndex = scrubNum.incrementAndGet()
        val replaceTerm = REPLACE_TERMS.getOrNull(replaceTermIndex) ?: "fruit${replaceTermIndex}"
        scrubTerms[term] = ReplaceTerm(type, when (type) {
            TermType.Domain -> "$replaceTerm.example.com"
            TermType.Username -> "${replaceTerm}user"
            else -> replaceTerm
        })
    }

    private fun scrubEntries(entries: List<LogEntry>): List<LogEntry> {
        return entries
            .map { e ->
                e.copy(
                    message = scrub(e.message)!!,
                    exception = scrub(e.exception)
                )
            }
    }

    private fun scrub(line: String?): String? {
        var newLine = line ?: return null
        scrubTerms.forEach { (scrubTerm, replaceTerm) ->
            newLine = newLine.replace(scrubTerm, replaceTerm.replaceTerm)
        }
        return newLine
    }

    private fun formatEntries(entries: List<LogEntry>): String {
        return entries.joinToString(separator = "\n") { e ->
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date(e.timestamp))
            val level = when (e.level) {
                android.util.Log.DEBUG -> "D"
                android.util.Log.INFO -> "I"
                android.util.Log.WARN -> "W"
                android.util.Log.ERROR -> "E"
                else -> "?"
            }
            val tag = e.tag.format("%23s")
            val prefix = "${e.timestamp} $date $level $tag"
            val message = if (e.exception != null) {
                "${e.message}\nException:\n${e.exception}"
            } else {
                e.message
            }
            "$prefix $message"
        }
    }

    private fun deleteAll() {
        return logsDao.deleteAll()
    }

    enum class TermType {
        Domain, Username, Password, Term
    }

    data class ReplaceTerm(
        val termType: TermType,
        val replaceTerm: String
    )

    companion object {
        private const val TAG = "NtfyLog"
        private const val PRUNE_EVERY = 100
        private const val ENTRIES_MAX = 1000
        private val IGNORE_TERMS = listOf("ntfy.sh")
        private val REPLACE_TERMS = listOf(
            "banana", "kiwi", "lemon", "coconut", "avocado", "orange", "apple", "peach",
            "pineapple", "dragonfruit", "durian", "starfruit"
        )
        private var instance: Log? = null

        fun d(tag: String, message: String, exception: Throwable? = null) {
            if (exception == null) android.util.Log.d(tag, message) else android.util.Log.d(tag, message, exception)
            getInstance()?.log(android.util.Log.DEBUG, tag, message, exception)
        }

        fun i(tag: String, message: String, exception: Throwable? = null) {
            if (exception == null) android.util.Log.i(tag, message) else android.util.Log.i(tag, message, exception)
            getInstance()?.log(android.util.Log.INFO, tag, message, exception)
        }

        fun w(tag: String, message: String, exception: Throwable? = null) {
            if (exception == null) android.util.Log.w(tag, message) else android.util.Log.w(tag, message, exception)
            getInstance()?.log(android.util.Log.WARN, tag, message, exception)
        }

        fun e(tag: String, message: String, exception: Throwable? = null) {
            if (exception == null) android.util.Log.e(tag, message) else android.util.Log.e(tag, message, exception)
            getInstance()?.log(android.util.Log.ERROR, tag, message, exception)
        }

        fun setRecord(enable: Boolean) {
            if (!enable) d(TAG, "Disabled log recording")
            getInstance()?.record?.set(enable)
            if (enable) d(TAG, "Enabled log recording")
        }

        fun getRecord(): Boolean {
            return getInstance()?.record?.get() ?: false
        }

        fun getFormatted(context: Context, scrub: Boolean): String {
            return getInstance()?.getFormatted(context, scrub) ?: "(no logs)"
        }

        fun getScrubTerms(): Map<String, String> {
            return getInstance()?.scrubTerms!!
                .filter { e -> e.value.termType != TermType.Password } // We do not want to display passwords
                .map { e -> e.key to e.value.replaceTerm }
                .toMap()
        }

        fun deleteAll() {
            getInstance()?.deleteAll()
            d(TAG, "Log was truncated")
        }

        fun addScrubTerm(term: String, type: TermType = TermType.Term) {
            getInstance()?.addScrubTerm(term, type)
        }

        fun init(context: Context) {
            return synchronized(Log::class) {
                if (instance == null) {
                    val database = Database.getInstance(context.applicationContext)
                    instance = Log(database.logDao())
                }
            }
        }

        private fun getInstance(): Log? {
            return synchronized(Log::class) {
                instance
            }
        }
    }
}
