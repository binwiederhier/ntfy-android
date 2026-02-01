package io.heckel.ntfy.msg

import android.content.Context
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Session
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.randomSubscriptionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages ntfy account login state and subscription synchronization.
 * Coordinates between Session, AccountApiService, and Repository.
 */
class AccountManager(private val context: Context) {
    private val session = Session.getInstance(context)
    private val repository = Repository.getInstance(context)
    private val accountApi = AccountApiService(context)

    /**
     * Check if user is currently logged in.
     */
    fun isLoggedIn(): Boolean = session.isLoggedIn()

    /**
     * Get the currently logged in username.
     */
    fun getUsername(): String? = session.username()

    /**
     * Get the base URL of the logged-in account.
     */
    fun getBaseUrl(): String? = session.baseUrl()

    /**
     * Login with username and password.
     * Stores the session and triggers initial sync.
     */
    suspend fun login(baseUrl: String, username: String, password: String) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Logging in as $username to $baseUrl")
            val token = accountApi.login(baseUrl, username, password)
            session.store(username, token, baseUrl)
            Log.d(TAG, "Login successful, syncing subscriptions")
            syncFromRemote()
        }
    }

    /**
     * Logout and clear the session.
     */
    suspend fun logout() {
        withContext(Dispatchers.IO) {
            val baseUrl = session.baseUrl()
            val token = session.token()
            if (baseUrl != null && token != null) {
                try {
                    accountApi.logout(baseUrl, token)
                } catch (e: Exception) {
                    Log.w(TAG, "Logout request failed", e)
                }
            }
            session.clear()
            Log.d(TAG, "Logged out successfully")
        }
    }

    /**
     * Extend the current token's expiration.
     * Should be called periodically to keep the session alive.
     */
    suspend fun extendToken() {
        if (!session.isLoggedIn()) return
        withContext(Dispatchers.IO) {
            val baseUrl = session.baseUrl() ?: return@withContext
            val token = session.token() ?: return@withContext
            try {
                accountApi.extendToken(baseUrl, token)
                Log.d(TAG, "Token extended successfully")
            } catch (e: AccountApiService.UnauthorizedException) {
                Log.w(TAG, "Token expired, clearing session", e)
                session.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extend token", e)
            }
        }
    }

    /**
     * Sync subscriptions from the remote server to the local database.
     * This adds remote subscriptions locally and removes local ones that don't exist remotely.
     * Similar to web app's SubscriptionManager.syncFromRemote().
     */
    suspend fun syncFromRemote() {
        if (!session.isLoggedIn()) return
        withContext(Dispatchers.IO) {
            val baseUrl = session.baseUrl() ?: return@withContext
            val token = session.token() ?: return@withContext
            try {
                Log.d(TAG, "Syncing subscriptions from remote")
                val account = accountApi.getAccount(baseUrl, token)
                val remoteSubscriptions = account.subscriptions ?: emptyList()

                // Add remote subscriptions that don't exist locally
                val remoteIds = mutableSetOf<String>()
                for (remote in remoteSubscriptions) {
                    val subscriptionId = "${remote.baseUrl}/${remote.topic}"
                    remoteIds.add(subscriptionId)

                    val local = repository.getSubscription(remote.baseUrl, remote.topic)
                    if (local == null) {
                        // Add new subscription locally
                        Log.d(TAG, "Adding remote subscription: ${remote.topic} at ${remote.baseUrl}")
                        val subscription = Subscription(
                            id = randomSubscriptionId(),
                            baseUrl = remote.baseUrl,
                            topic = remote.topic,
                            instant = true, // Default to instant for synced subscriptions
                            dedicatedChannels = false,
                            mutedUntil = 0,
                            minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
                            autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
                            insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
                            lastNotificationId = null,
                            icon = null,
                            upAppId = null,
                            upConnectorToken = null,
                            displayName = remote.displayName
                        )
                        repository.addSubscription(subscription)
                    } else if (remote.displayName != null && local.displayName != remote.displayName) {
                        // Update display name if changed
                        Log.d(TAG, "Updating display name for ${remote.topic}")
                        repository.updateSubscription(local.copy(displayName = remote.displayName))
                    }
                }

                // Remove local subscriptions that don't exist remotely
                // Only remove non-UnifiedPush subscriptions (upAppId is null)
                val localSubscriptions = repository.getSubscriptions()
                for (local in localSubscriptions) {
                    val subscriptionId = "${local.baseUrl}/${local.topic}"
                    if (local.upAppId == null && !remoteIds.contains(subscriptionId)) {
                        Log.d(TAG, "Removing local subscription not in remote: ${local.topic}")
                        repository.removeSubscription(local)
                    }
                }

                Log.d(TAG, "Sync completed: ${remoteSubscriptions.size} remote subscriptions")
            } catch (e: AccountApiService.UnauthorizedException) {
                Log.w(TAG, "Token expired during sync, clearing session", e)
                session.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync subscriptions", e)
            }
        }
    }

    /**
     * Add a subscription to the remote server.
     * Called when a new subscription is added locally.
     */
    suspend fun addSubscriptionToRemote(subscription: Subscription) {
        if (!session.isLoggedIn()) return
        // Don't sync UnifiedPush subscriptions
        if (subscription.upAppId != null) return

        withContext(Dispatchers.IO) {
            val baseUrl = session.baseUrl() ?: return@withContext
            val token = session.token() ?: return@withContext
            try {
                Log.d(TAG, "Adding subscription to remote: ${subscription.topic}")
                accountApi.addSubscription(baseUrl, token, subscription.baseUrl, subscription.topic)
            } catch (e: AccountApiService.UnauthorizedException) {
                Log.w(TAG, "Token expired, clearing session", e)
                session.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add subscription to remote", e)
            }
        }
    }

    /**
     * Update a subscription on the remote server.
     * Called when a subscription is updated locally.
     */
    suspend fun updateSubscriptionOnRemote(subscription: Subscription) {
        if (!session.isLoggedIn()) return
        // Don't sync UnifiedPush subscriptions
        if (subscription.upAppId != null) return

        withContext(Dispatchers.IO) {
            val baseUrl = session.baseUrl() ?: return@withContext
            val token = session.token() ?: return@withContext
            try {
                Log.d(TAG, "Updating subscription on remote: ${subscription.topic}")
                accountApi.updateSubscription(
                    baseUrl,
                    token,
                    subscription.baseUrl,
                    subscription.topic,
                    subscription.displayName
                )
            } catch (e: AccountApiService.UnauthorizedException) {
                Log.w(TAG, "Token expired, clearing session", e)
                session.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update subscription on remote", e)
            }
        }
    }

    /**
     * Delete a subscription from the remote server.
     * Called when a subscription is removed locally.
     */
    suspend fun deleteSubscriptionFromRemote(subscriptionBaseUrl: String, topic: String) {
        if (!session.isLoggedIn()) return

        withContext(Dispatchers.IO) {
            val baseUrl = session.baseUrl() ?: return@withContext
            val token = session.token() ?: return@withContext
            try {
                Log.d(TAG, "Deleting subscription from remote: $topic")
                accountApi.deleteSubscription(baseUrl, token, subscriptionBaseUrl, topic)
            } catch (e: AccountApiService.UnauthorizedException) {
                Log.w(TAG, "Token expired, clearing session", e)
                session.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete subscription from remote", e)
            }
        }
    }

    /**
     * Get account information.
     */
    suspend fun getAccountInfo(): AccountResponse? {
        if (!session.isLoggedIn()) return null
        return withContext(Dispatchers.IO) {
            val baseUrl = session.baseUrl() ?: return@withContext null
            val token = session.token() ?: return@withContext null
            try {
                accountApi.getAccount(baseUrl, token)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get account info", e)
                null
            }
        }
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

