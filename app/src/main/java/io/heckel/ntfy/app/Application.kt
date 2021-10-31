package io.heckel.ntfy.app

import android.app.Application
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Repository

class Application : Application() {
    private val database by lazy { Database.getInstance(this) }
    val repository by lazy { Repository.getInstance(database.subscriptionDao(), database.notificationDao()) }
}
