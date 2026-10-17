package io.heckel.ntfy.account

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The ntfy account this phone is logged in to, if any. Not part of ntfy's settings export (see Backuper),
 * so importing a backup on another phone doesn't log it in.
 */
class Session private constructor(private val sharedPrefs: SharedPreferences) {
    fun store(baseUrl: String, username: String, token: String) {
        sharedPrefs.edit {
            putString(PREF_BASE_URL, baseUrl)
            putString(PREF_USERNAME, username)
            putString(PREF_TOKEN, token)
        }
    }

    fun clear() {
        sharedPrefs.edit {
            clear()
        }
    }

    fun loggedIn(): Boolean {
        return baseUrl() != null && username() != null && token() != null
    }

    fun baseUrl(): String? {
        return sharedPrefs.getString(PREF_BASE_URL, null)
    }

    fun username(): String? {
        return sharedPrefs.getString(PREF_USERNAME, null)
    }

    fun token(): String? {
        return sharedPrefs.getString(PREF_TOKEN, null)
    }

    companion object {
        private const val SHARED_PREFS_ID = "NtfySession"
        private const val PREF_BASE_URL = "BaseUrl"
        private const val PREF_USERNAME = "Username"
        private const val PREF_TOKEN = "Token"

        @Volatile
        private var instance: Session? = null

        fun getInstance(context: Context): Session {
            return instance ?: synchronized(this) {
                val sharedPrefs = context.applicationContext.getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
                val newInstance = instance ?: Session(sharedPrefs)
                instance = newInstance
                newInstance
            }
        }
    }
}
