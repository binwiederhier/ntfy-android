package io.heckel.ntfy.app

import android.app.Application
import io.heckel.ntfy.db.Database
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log

class Application : Application() {
    private val database by lazy {
        Log.init(this) // What a hack, but this is super early and used everywhere
        Database.getInstance(this)
    }
    val repository by lazy {
        val repository = Repository.getInstance(applicationContext)
        if (repository.getRecordLogs()) {
            Log.setRecord(true)
        }
        repository
    }
}
