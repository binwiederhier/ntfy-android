package io.heckel.ntfy.firebase

import android.app.Service
import android.content.Intent
import android.os.IBinder

// Dummy to keep F-Droid flavor happy
class FirebaseService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
