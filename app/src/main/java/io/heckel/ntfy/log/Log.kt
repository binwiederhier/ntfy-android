package io.heckel.ntfy.log

import android.content.Context
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Logs
import io.heckel.ntfy.data.LogsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Log(private val logsDao: LogsDao) {
    private var record: AtomicBoolean = AtomicBoolean(false)
    private var count: AtomicInteger = AtomicInteger(0)

    private fun log(level: Int, tag: String, message: String, exception: Throwable?) {
        if (!record.get()) return
        GlobalScope.launch(Dispatchers.IO) {
            logsDao.insert(Logs(System.currentTimeMillis(), tag, level, message, exception?.stackTraceToString()))
            val current = count.incrementAndGet()
            if (current >= PRUNE_EVERY) {
                logsDao.prune(ENTRIES_MAX)
                count.set(0) // I know there is a race here, but this is good enough
            }
        }
    }

    companion object {
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

        fun init(context: Context) {
            return synchronized(Log::class) {
                if (instance == null) {
                    val database = Database.getInstance(context.applicationContext)
                    instance = Log(database.logsDao())
                }
            }
        }

        private const val TAG = "NtfyLog"
        private const val PRUNE_EVERY = 100
        private const val ENTRIES_MAX = 10000
        private var instance: Log? = null

        private fun getInstance(): Log? {
            return synchronized(Log::class) {
                instance
            }
        }
    }
}
