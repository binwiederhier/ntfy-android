package io.heckel.ntfy.app

import android.app.Application
import android.content.Context
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.log.Log

class Application : Application() {
    private val database by lazy {
        Log.init(this) // What a hack, but this is super early and used everywhere
        Database.getInstance(this)
    }
    val repository by lazy {
        val sharedPrefs = applicationContext.getSharedPreferences(Repository.SHARED_PREFS_ID, Context.MODE_PRIVATE)
        val repository = Repository.getInstance(sharedPrefs, database.subscriptionDao(), database.notificationDao())
        if (repository.getRecordLogs()) {
            Log.setRecord(true)
        }
        repository
    }
}
