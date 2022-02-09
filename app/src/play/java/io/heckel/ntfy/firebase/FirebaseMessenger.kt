package io.heckel.ntfy.firebase

import com.google.firebase.messaging.FirebaseMessaging
import io.heckel.ntfy.util.Log

class FirebaseMessenger {
    fun subscribe(topic: String) {
        val firebase = maybeInstance() ?: return
        firebase
            .subscribeToTopic(topic)
            .addOnCompleteListener {
                Log.d(TAG, "Subscribing to topic $topic complete: result=${it.result}, exception=${it.exception}, successful=${it.isSuccessful}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Subscribing to topic $topic failed: ${e.message}", e)
            }
    }

    fun unsubscribe(topic: String) {
        val firebase = maybeInstance() ?: return
        firebase.unsubscribeFromTopic(topic)
    }

    private fun maybeInstance(): FirebaseMessaging? {
        return try {
            FirebaseMessaging.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Firebase instance unavailable: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "NtfyFirebase"
    }
}
