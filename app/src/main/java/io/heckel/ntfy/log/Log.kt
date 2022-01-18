package io.heckel.ntfy.log

import android.content.Context
import io.heckel.ntfy.db.Database
import io.heckel.ntfy.db.LogDao
import io.heckel.ntfy.db.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Log(private val logsDao: LogDao) {
    private val record: AtomicBoolean = AtomicBoolean(false)
    private val count: AtomicInteger = AtomicInteger(0)
    private val scrubNum: AtomicInteger = AtomicInteger(-1)
    private val scrubTerms = Collections.synchronizedMap(mutableMapOf<String, String>())

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

    fun getAll(): Collection<LogEntry> {
        return logsDao
            .getAll()
            .map { e ->
                e.copy(
                    message = scrub(e.message)!!,
                    exception = scrub(e.exception)
                )
            }
    }

    private fun deleteAll() {
        return logsDao.deleteAll()
    }

    fun addScrubTerm(term: String, type: TermType = TermType.Term) {
        if (scrubTerms[term] != null || IGNORE_TERMS.contains(term)) {
            return
        }
        val replaceTermIndex = scrubNum.incrementAndGet()
        val replaceTerm = REPLACE_TERMS.getOrNull(replaceTermIndex) ?: "scrubbed${replaceTermIndex}"
        scrubTerms[term] = when (type) {
            TermType.Domain -> "$replaceTerm.example.com"
            else -> replaceTerm
        }
    }

    private fun scrub(line: String?): String? {
        var newLine = line ?: return null
        scrubTerms.forEach { (scrubTerm, replaceTerm) ->
            newLine = newLine.replace(scrubTerm, replaceTerm)
        }
        return newLine
    }

    enum class TermType {
        Domain, Term
    }

    companion object {
        private const val TAG = "NtfyLog"
        private const val PRUNE_EVERY = 100
        private const val ENTRIES_MAX = 5000
        private val IGNORE_TERMS = listOf("ntfy.sh")
        private val REPLACE_TERMS = listOf(
            "potato", "banana", "coconut", "kiwi", "avocado", "orange", "apple", "lemon", "olive", "peach"
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

        fun getAll(): Collection<LogEntry> {
            return getInstance()?.getAll().orEmpty()
        }

        fun deleteAll() {
            getInstance()?.deleteAll()
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
