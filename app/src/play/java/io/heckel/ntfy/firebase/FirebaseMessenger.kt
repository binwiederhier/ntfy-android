package io.heckel.ntfy.firebase

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

class FirebaseMessenger {
    fun subscribe(topic: String) {
        FirebaseMessaging
            .getInstance()
            .subscribeToTopic(topic)
            .addOnCompleteListener {
                Log.d(TAG, "Subscribing to topic complete: result=${it.result}, exception=${it.exception}, successful=${it.isSuccessful}")
            }
            .addOnFailureListener {
                Log.e(TAG, "Subscribing to topic failed: $it")
            }
    }

    fun unsubscribe(topic: String) {
        FirebaseMessaging
            .getInstance()
            .unsubscribeFromTopic(topic)
    }

    companion object {
        private const val TAG = "NtfyFirebase"
    }
}
