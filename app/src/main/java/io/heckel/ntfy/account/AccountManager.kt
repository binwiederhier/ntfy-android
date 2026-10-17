package io.heckel.ntfy.account

import android.content.Context
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log

/**
 * Logs this phone in to and out of an ntfy account.
 */
class AccountManager private constructor(private val context: Context) {
    private val repository = Repository.getInstance(context)
    private val session = Session.getInstance(context)
    private val api = AccountApi(context)

    fun loggedIn(): Boolean {
        return session.loggedIn()
    }

    fun username(): String? {
        return session.username()
    }

    fun baseUrl(): String? {
        return session.baseUrl()
    }

    suspend fun login(baseUrl: String, username: String, password: String) {
        val token = api.login(baseUrl, username, password)
        session.store(baseUrl, username, token)
        Log.d(TAG, "Logged in as $username at $baseUrl")

        // Topics on the account's server (synced or not) need credentials, so we add the user if
        // there isn't one yet. An existing user or a custom Authorization header is left alone.
        val hasAuthorizationHeader = repository.getCustomHeaders(baseUrl)
            .any { it.name.equals("Authorization", ignoreCase = true) }
        if (repository.getUser(baseUrl) == null && !hasAuthorizationHeader) {
            Log.d(TAG, "Adding user $username for $baseUrl")
            repository.addUser(User(baseUrl, username, password))
            SubscriberServiceManager.refresh(context)
        }
    }

    /**
     * Logs out and revokes the token. Topics and the user stay on the phone; they just stop syncing.
     */
    suspend fun logout() {
        val baseUrl = session.baseUrl()
        val token = session.token()
        session.clear()
        if (baseUrl != null && token != null) {
            try {
                api.logout(baseUrl, token)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to revoke token, ignoring", e)
            }
        }
        Log.d(TAG, "Logged out")
    }

    companion object {
        private const val TAG = "NtfyAccountManager"

        @Volatile
        private var instance: AccountManager? = null

        fun getInstance(context: Context): AccountManager {
            return instance ?: synchronized(this) {
                val newInstance = instance ?: AccountManager(context.applicationContext)
                instance = newInstance
                newInstance
            }
        }
    }
}
