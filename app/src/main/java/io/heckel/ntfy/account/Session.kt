package io.heckel.ntfy.account

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The ntfy account this phone is logged in to, if any. Not part of ntfy's settings export (see Backuper),
 * so importing a backup on another phone doesn't log it in.
 */
class Session private constructor(private val sharedPrefs: SharedPreferences) {
    fun store(baseUrl: String, username: String, token: String) {
        sharedPrefs.edit {
            clear() // Start over: a new login needs a new first sync
            putString(PREF_BASE_URL, baseUrl)
            putString(PREF_USERNAME, username)
            putString(PREF_TOKEN, token)
        }
    }

    /**
     * Drops the token after the server rejected it (revoked, password changed, user deleted), but
     * remembers the user, so Settings can say why syncing stopped.
     */
    fun expire() {
        sharedPrefs.edit {
            remove(PREF_TOKEN)
            remove(PREF_SYNCED)
            putBoolean(PREF_EXPIRED, true)
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

    fun expired(): Boolean {
        return sharedPrefs.getBoolean(PREF_EXPIRED, false)
    }

    /**
     * The subscriptions (and display names) as of the last sync, or null if this login never synced.
     */
    fun synced(): Map<SyncKey, String?>? {
        val json = sharedPrefs.getString(PREF_SYNCED, null) ?: return null
        val entries: List<SyncedEntry> = gson.fromJson(json, object : TypeToken<List<SyncedEntry>>() {}.type)
        return entries.associate { SyncKey(it.baseUrl, it.topic) to it.displayName }
    }

    fun setSynced(synced: Map<SyncKey, String?>) {
        val entries = synced.map { (key, displayName) -> SyncedEntry(key.baseUrl, key.topic, displayName) }
        sharedPrefs.edit {
            putString(PREF_SYNCED, gson.toJson(entries))
        }
    }

    private data class SyncedEntry(val baseUrl: String, val topic: String, val displayName: String?)

    companion object {
        private const val SHARED_PREFS_ID = "NtfySession"
        private const val PREF_BASE_URL = "BaseUrl"
        private const val PREF_USERNAME = "Username"
        private const val PREF_TOKEN = "Token"
        private const val PREF_EXPIRED = "Expired"
        private const val PREF_SYNCED = "Synced"
        private val gson = Gson()

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
