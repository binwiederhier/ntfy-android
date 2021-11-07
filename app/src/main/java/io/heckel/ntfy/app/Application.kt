package io.heckel.ntfy.app

import android.app.Application
import com.google.firebase.messaging.FirebaseMessagingService
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.msg.ApiService

class Application : Application() {
    private val database by lazy { Database.getInstance(this) }
    val repository by lazy { Repository.getInstance(database.subscriptionDao(), database.notificationDao()) }
}
