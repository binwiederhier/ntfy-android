package io.heckel.ntfy.app

import android.app.Application
import android.content.Context
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Repository

class Application : Application() {
    private val database by lazy { Database.getInstance(this) }
    val repository by lazy {
        val sharedPrefs = applicationContext.getSharedPreferences(Repository.SHARED_PREFS_ID, Context.MODE_PRIVATE)
        Repository.getInstance(sharedPrefs, database.subscriptionDao(), database.notificationDao())
    }
}
