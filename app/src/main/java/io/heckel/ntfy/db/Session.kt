package io.heckel.ntfy.db

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.heckel.ntfy.util.Log

/**
 * Manages the logged-in user's session and access token.
 * This is used for ntfy account login (optional feature).
 * When logged in, subscriptions are synchronized with the server.
 */
class Session private constructor(private val sharedPrefs: SharedPreferences) {

    fun store(username: String, token: String, baseUrl: String) {
        Log.d(TAG, "Storing session for user $username at $baseUrl")
        sharedPrefs.edit {
            putString(PREF_USERNAME, username)
            putString(PREF_TOKEN, token)
            putString(PREF_BASE_URL, baseUrl)
        }
    }

    fun clear() {
        Log.d(TAG, "Clearing session")
        sharedPrefs.edit {
            remove(PREF_USERNAME)
            remove(PREF_TOKEN)
            remove(PREF_BASE_URL)
        }
    }

    fun isLoggedIn(): Boolean {
        return token() != null && username() != null
    }

    fun username(): String? {
        return sharedPrefs.getString(PREF_USERNAME, null)
    }

    fun token(): String? {
        return sharedPrefs.getString(PREF_TOKEN, null)
    }

    fun baseUrl(): String? {
        return sharedPrefs.getString(PREF_BASE_URL, null)
    }

    companion object {
        private const val TAG = "NtfySession"
        private const val SHARED_PREFS_ID = "NtfySession"
        private const val PREF_USERNAME = "Username"
        private const val PREF_TOKEN = "Token"
        private const val PREF_BASE_URL = "BaseUrl"

        @Volatile
        private var instance: Session? = null

        fun getInstance(context: Context): Session {
            return instance ?: synchronized(this) {
                val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
                val newInstance = instance ?: Session(sharedPrefs)
                instance = newInstance
                newInstance
            }
        }
    }
}

