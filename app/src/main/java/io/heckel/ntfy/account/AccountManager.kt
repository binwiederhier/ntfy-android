package io.heckel.ntfy.account

import android.content.Context
import androidx.core.net.toUri
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.Poller
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.randomSubscriptionId
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.work.AccountSyncWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date

/**
 * Logs this phone in to and out of an ntfy account, and syncs its subscriptions (topics and their
 * display names) with the account. UnifiedPush subscriptions are never synced, since they belong to
 * other apps on this phone.
 */
class AccountManager private constructor(private val context: Context) {
    private val repository = Repository.getInstance(context)
    private val session = Session.getInstance(context)
    private val api = AccountApi(context)
    private val appBaseUrl = context.getString(R.string.app_base_url)
    private val syncMutex = Mutex() // Protects against concurrent syncs (worker, UI)

    sealed class SyncResult {
        object Done : SyncResult()
        data class NeedsFirstSyncChoice(val phoneOnly: Int, val accountOnly: Int) : SyncResult() // Topics that differ, see FirstSyncMode
        object NotLoggedIn : SyncResult()
        object Expired : SyncResult() // The server rejected the token
    }

    fun loggedIn(): Boolean {
        return session.loggedIn()
    }

    fun expired(): Boolean {
        return session.expired()
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
        AccountSyncWorker.schedulePeriodic(context)
    }

    /**
     * Logs out and revokes the token. Topics and the user stay on the phone; they just stop syncing.
     */
    suspend fun logout() {
        val baseUrl = session.baseUrl()
        val token = session.token()
        syncMutex.withLock {
            session.clear()
        }
        AccountSyncWorker.cancel(context)
        if (baseUrl != null && token != null) {
            try {
                api.logout(baseUrl, token)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to revoke token, ignoring", e)
            }
        }
        Log.d(TAG, "Logged out")
    }

    /**
     * Syncs subscriptions in both directions. On the first sync after logging in, [firstSyncMode] says
     * how to reconcile the phone and the account; without it, the sync stops with
     * [SyncResult.NeedsFirstSyncChoice] if there is anything to decide.
     *
     * Remote changes that fail (e.g. offline) are retried on the next sync. Other errors are thrown.
     */
    suspend fun sync(firstSyncMode: FirstSyncMode? = null): SyncResult {
        return syncMutex.withLock {
            val baseUrl = session.baseUrl()
            val token = session.token()
            if (baseUrl == null || token == null) {
                return@withLock SyncResult.NotLoggedIn
            }
            try {
                syncNoLock(baseUrl, token, firstSyncMode)
            } catch (_: AccountApi.UnauthorizedException) {
                Log.w(TAG, "Token for ${session.username()} at $baseUrl was rejected, stopping sync")
                session.expire()
                AccountSyncWorker.cancel(context)
                SyncResult.Expired
            }
        }
    }

    private suspend fun syncNoLock(baseUrl: String, token: String, firstSyncMode: FirstSyncMode?): SyncResult {
        // Gather both sides
        val remote = api.subscriptions(baseUrl, token)
            .associate { SyncKey(it.baseUrl, it.topic) to it.displayName }
        val localSubscriptions = repository.getSubscriptions()
            .filter { it.upAppId == null }
            .associateBy { SyncKey(it.baseUrl, it.topic) }
        val local = localSubscriptions.mapValues { it.value.displayName }
        val synced = session.synced()
        if (synced == null && firstSyncMode == null && SyncPlanner.needsFirstSyncChoice(local.keys, remote.keys)) {
            return SyncResult.NeedsFirstSyncChoice(
                phoneOnly = (local.keys - remote.keys).size,
                accountOnly = (remote.keys - local.keys).size
            )
        }
        val plan = SyncPlanner.plan(local, remote, synced, firstSyncMode ?: FirstSyncMode.MERGE)
        if (plan.isEmpty()) {
            session.setSynced(plan.synced)
            return SyncResult.Done
        }
        Log.d(TAG, "Syncing: ${plan.addLocal.size}/${plan.removeLocal.size}/${plan.renameLocal.size} local and " +
                "${plan.addRemote.size}/${plan.removeRemote.size}/${plan.renameRemote.size} remote adds/removes/renames")

        // Apply remote changes first; failures are remembered and retried next time
        val failed = mutableSetOf<SyncKey>()
        plan.addRemote.forEach { (key, displayName) ->
            tryRemote(key, failed) {
                api.addSubscription(baseUrl, token, AccountApi.RemoteSubscription(key.baseUrl, key.topic, displayName))
            }
        }
        plan.renameRemote.forEach { (key, displayName) ->
            tryRemote(key, failed) {
                api.updateSubscription(baseUrl, token, AccountApi.RemoteSubscription(key.baseUrl, key.topic, displayName))
            }
        }
        plan.removeRemote.forEach { key ->
            tryRemote(key, failed) {
                api.deleteSubscription(baseUrl, token, key.baseUrl, key.topic)
            }
        }

        // Apply local changes
        plan.addLocal.forEach { (key, displayName) ->
            addLocal(key, displayName)
        }
        plan.renameLocal.forEach { (key, displayName) ->
            localSubscriptions[key]?.let { repository.updateSubscription(it.copy(displayName = displayName)) }
        }
        plan.removeLocal.forEach { key ->
            localSubscriptions[key]?.let { removeLocal(it) }
        }
        if (plan.addLocal.isNotEmpty() || plan.removeLocal.isNotEmpty()) {
            SubscriberServiceManager.refresh(context)
        }
        session.setSynced(plan.syncedAfter(failed))
        return SyncResult.Done
    }

    private suspend fun tryRemote(key: SyncKey, failed: MutableSet<SyncKey>, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: AccountApi.UnauthorizedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to sync ${topicShortUrl(key.baseUrl, key.topic)} to account, will retry", e)
            failed.add(key)
        }
    }

    /**
     * Subscribes like a deep link does (see DetailActivity): instant delivery for other servers,
     * Firebase for ntfy.sh, and fetches cached messages.
     */
    private suspend fun addLocal(key: SyncKey, displayName: String?) {
        if (repository.getSubscription(key.baseUrl, key.topic) != null) {
            return // E.g. a UnifiedPush subscription on the same topic
        }
        Log.d(TAG, "Adding subscription ${topicShortUrl(key.baseUrl, key.topic)} from account")
        val subscription = Subscription(
            id = randomSubscriptionId(),
            baseUrl = key.baseUrl,
            topic = key.topic,
            instant = key.baseUrl != appBaseUrl,
            dedicatedChannels = false,
            mutedUntil = 0,
            minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
            autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
            insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
            lastNotificationId = null,
            icon = null,
            upAppId = null,
            upConnectorToken = null,
            displayName = displayName,
            totalCount = 0,
            newCount = 0,
            lastActive = Date().time/1000
        )
        repository.addSubscription(subscription)
        if (key.baseUrl == appBaseUrl) {
            FirebaseMessenger().subscribe(key.topic)
        }
        try {
            Poller(ApiService(context), repository).poll(subscription)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to fetch cached messages for ${topicShortUrl(key.baseUrl, key.topic)}", e)
        }
    }

    /**
     * Unsubscribes like deleting a topic in the app does (see SubscriptionsViewModel.remove).
     */
    private suspend fun removeLocal(subscription: Subscription) {
        Log.d(TAG, "Removing subscription ${topicShortUrl(subscription.baseUrl, subscription.topic)}, removed from account")
        repository.removeSubscription(subscription)
        if (subscription.icon != null) {
            try {
                context.contentResolver.delete(subscription.icon.toUri(), null, null)
            } catch (_: Exception) {
                // Don't care
            }
        }
        if (subscription.baseUrl == appBaseUrl) {
            FirebaseMessenger().unsubscribe(subscription.topic)
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
