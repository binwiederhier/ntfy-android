package io.heckel.ntfy.data

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class Repository(private val sharedPrefs: SharedPreferences, private val subscriptionDao: SubscriptionDao, private val notificationDao: NotificationDao) {
    private val connectionStates = ConcurrentHashMap<Long, ConnectionState>()
    private val connectionStatesLiveData = MutableLiveData(connectionStates)
    val detailViewSubscriptionId = AtomicLong(0L) // Omg, what a hack ...

    init {
        Log.d(TAG, "Created $this")
    }

    fun getSubscriptionsLiveData(): LiveData<List<Subscription>> {
        return subscriptionDao
            .listFlow()
            .asLiveData()
            .combineWith(connectionStatesLiveData) { subscriptionsWithMetadata, _ ->
                toSubscriptionList(subscriptionsWithMetadata.orEmpty())
            }
    }

    fun getSubscriptionIdsWithInstantStatusLiveData(): LiveData<Set<Pair<Long, Boolean>>> {
        return subscriptionDao
            .listFlow()
            .asLiveData()
            .map { list -> list.map { Pair(it.id, it.instant) }.toSet() }
    }

    fun getSubscriptions(): List<Subscription> {
        return toSubscriptionList(subscriptionDao.list())
    }

    fun getSubscriptionIdsWithInstantStatus(): Set<Pair<Long, Boolean>> {
        return subscriptionDao
            .list()
            .map { Pair(it.id, it.instant) }.toSet()
    }

    fun getSubscription(subscriptionId: Long): Subscription? {
        return toSubscription(subscriptionDao.get(subscriptionId))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscription(baseUrl: String, topic: String): Subscription? {
        return toSubscription(subscriptionDao.get(baseUrl, topic))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscriptionByConnectorToken(connectorToken: String): Subscription? {
        return toSubscription(subscriptionDao.getByConnectorToken(connectorToken))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addSubscription(subscription: Subscription) {
        subscriptionDao.add(subscription)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun updateSubscription(subscription: Subscription) {
        subscriptionDao.update(subscription)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun removeSubscription(subscriptionId: Long) {
        subscriptionDao.remove(subscriptionId)
    }

    fun getNotificationsLiveData(subscriptionId: Long): LiveData<List<Notification>> {
        return notificationDao.listFlow(subscriptionId).asLiveData()
    }

    fun clearAllNotificationIds(subscriptionId: Long) {
        return notificationDao.clearAllNotificationIds(subscriptionId)
    }

    fun getNotification(notificationId: String): Notification? {
        return notificationDao.get(notificationId)
    }

    fun onlyNewNotifications(subscriptionId: Long, notifications: List<Notification>): List<Notification> {
        val existingIds = notificationDao.listIds(subscriptionId)
        return notifications.filterNot { existingIds.contains(it.id) }
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addNotification(notification: Notification): Boolean {
        val maybeExistingNotification = notificationDao.get(notification.id)
        if (maybeExistingNotification != null) {
            return false
        }
        notificationDao.add(notification)
        return true
    }

    fun updateNotification(notification: Notification) {
        notificationDao.update(notification)
    }


    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun markAsDeleted(notificationId: String) {
        notificationDao.markAsDeleted(notificationId)
    }

    fun markAllAsDeleted(subscriptionId: Long) {
        notificationDao.markAllAsDeleted(subscriptionId)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    fun removeAllNotifications(subscriptionId: Long) {
        notificationDao.removeAll(subscriptionId)
    }

    fun getPollWorkerVersion(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_POLL_WORKER_VERSION, 0)
    }

    fun setPollWorkerVersion(version: Int) {
        sharedPrefs.edit()
            .putInt(SHARED_PREFS_POLL_WORKER_VERSION, version)
            .apply()
    }

    fun getAutoRestartWorkerVersion(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_AUTO_RESTART_WORKER_VERSION, 0)
    }

    fun setAutoRestartWorkerVersion(version: Int) {
        sharedPrefs.edit()
            .putInt(SHARED_PREFS_AUTO_RESTART_WORKER_VERSION, version)
            .apply()
    }

    fun setMinPriority(minPriority: Int) {
        if (minPriority <= 1) {
            sharedPrefs.edit()
                .remove(SHARED_PREFS_MIN_PRIORITY)
                .apply()
        } else {
            sharedPrefs.edit()
                .putInt(SHARED_PREFS_MIN_PRIORITY, minPriority)
                .apply()
        }
    }

    fun getMinPriority(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_MIN_PRIORITY, 1) // 1/low means all priorities
    }

    fun getAutoDownloadMaxSize(): Long {
        val defaultValue = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            AUTO_DOWNLOAD_NEVER // Need to request permission on older versions
        } else {
            AUTO_DOWNLOAD_DEFAULT
        }
        return sharedPrefs.getLong(SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE, defaultValue)
    }

    fun setAutoDownloadMaxSize(maxSize: Long) {
        sharedPrefs.edit()
            .putLong(SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE, maxSize)
            .apply()
    }

    fun getWakelockEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_WAKELOCK_ENABLED, true) // Enabled by default
    }

    fun setWakelockEnabled(enabled: Boolean) {
        sharedPrefs.edit()
            .putBoolean(SHARED_PREFS_WAKELOCK_ENABLED, enabled)
            .apply()
    }

    fun getBroadcastEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_BROADCAST_ENABLED, true) // Enabled by default
    }

    fun setBroadcastEnabled(enabled: Boolean) {
        sharedPrefs.edit()
            .putBoolean(SHARED_PREFS_BROADCAST_ENABLED, enabled)
            .apply()
    }

    fun getUnifiedPushEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_UNIFIED_PUSH_ENABLED, true) // Enabled by default
    }

    fun setUnifiedPushEnabled(enabled: Boolean) {
        sharedPrefs.edit()
            .putBoolean(SHARED_PREFS_UNIFIED_PUSH_ENABLED, enabled)
            .apply()
    }

    fun getUnifiedPushBaseUrl(): String? {
        return sharedPrefs.getString(SHARED_PREFS_UNIFIED_PUSH_BASE_URL, null)
    }

    fun setUnifiedPushBaseUrl(baseUrl: String) {
        if (baseUrl == "") {
            sharedPrefs
                .edit()
                .remove(SHARED_PREFS_UNIFIED_PUSH_BASE_URL)
                .apply()
        } else {
            sharedPrefs.edit()
                .putString(SHARED_PREFS_UNIFIED_PUSH_BASE_URL, baseUrl)
                .apply()
        }
    }

    fun isGlobalMuted(): Boolean {
        val mutedUntil = getGlobalMutedUntil()
        return mutedUntil == 1L || (mutedUntil > 1L && mutedUntil > System.currentTimeMillis()/1000)
    }

    fun getGlobalMutedUntil(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
    }

    fun setGlobalMutedUntil(mutedUntilTimestamp: Long) {
        sharedPrefs.edit()
            .putLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, mutedUntilTimestamp)
            .apply()
    }

    fun checkGlobalMutedUntil(): Boolean {
        val mutedUntil = sharedPrefs.getLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
        val expired = mutedUntil > 1L && System.currentTimeMillis()/1000 > mutedUntil
        if (expired) {
            sharedPrefs.edit()
                .putLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
                .apply()
            return true
        }
        return false
    }

    private fun toSubscriptionList(list: List<SubscriptionWithMetadata>): List<Subscription> {
        return list.map { s ->
            val connectionState = connectionStates.getOrElse(s.id) { ConnectionState.NOT_APPLICABLE }
            Subscription(
                id = s.id,
                baseUrl = s.baseUrl,
                topic = s.topic,
                instant = s.instant,
                mutedUntil = s.mutedUntil,
                upAppId = s.upAppId,
                upConnectorToken = s.upConnectorToken,
                totalCount = s.totalCount,
                newCount = s.newCount,
                lastActive = s.lastActive,
                state = connectionState
            )
        }
    }

    private fun toSubscription(s: SubscriptionWithMetadata?): Subscription? {
        if (s == null) {
            return null
        }
        return Subscription(
            id = s.id,
            baseUrl = s.baseUrl,
            topic = s.topic,
            instant = s.instant,
            mutedUntil = s.mutedUntil,
            upAppId = s.upAppId,
            upConnectorToken = s.upConnectorToken,
            totalCount = s.totalCount,
            newCount = s.newCount,
            lastActive = s.lastActive,
            state = getState(s.id)
        )
    }

    fun updateState(subscriptionIds: Collection<Long>, newState: ConnectionState) {
        var changed = false
        subscriptionIds.forEach { subscriptionId ->
            val state = connectionStates.getOrElse(subscriptionId) { ConnectionState.NOT_APPLICABLE }
            if (state !== newState) {
                changed = true
                if (newState == ConnectionState.NOT_APPLICABLE) {
                    connectionStates.remove(subscriptionId)
                } else {
                    connectionStates[subscriptionId] = newState
                }
            }
        }
        if (changed) {
            connectionStatesLiveData.postValue(connectionStates)
        }
    }

    private fun getState(subscriptionId: Long): ConnectionState {
        return connectionStatesLiveData.value!!.getOrElse(subscriptionId) { ConnectionState.NOT_APPLICABLE }
    }

    companion object {
        const val SHARED_PREFS_ID = "MainPreferences"
        const val SHARED_PREFS_POLL_WORKER_VERSION = "PollWorkerVersion"
        const val SHARED_PREFS_AUTO_RESTART_WORKER_VERSION = "AutoRestartWorkerVersion"
        const val SHARED_PREFS_MUTED_UNTIL_TIMESTAMP = "MutedUntil"
        const val SHARED_PREFS_MIN_PRIORITY = "MinPriority"
        const val SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE = "AutoDownload"
        const val SHARED_PREFS_WAKELOCK_ENABLED = "WakelockEnabled"
        const val SHARED_PREFS_BROADCAST_ENABLED = "BroadcastEnabled"
        const val SHARED_PREFS_UNIFIED_PUSH_ENABLED = "UnifiedPushEnabled"
        const val SHARED_PREFS_UNIFIED_PUSH_BASE_URL = "UnifiedPushBaseURL"

        const val AUTO_DOWNLOAD_NEVER = 0L
        const val AUTO_DOWNLOAD_ALWAYS = 1L
        const val AUTO_DOWNLOAD_DEFAULT = 1024 * 1024L // Must match a value in values.xml

        private const val TAG = "NtfyRepository"
        private var instance: Repository? = null

        fun getInstance(sharedPrefs: SharedPreferences, subscriptionDao: SubscriptionDao, notificationDao: NotificationDao): Repository {
            return synchronized(Repository::class) {
                val newInstance = instance ?: Repository(sharedPrefs, subscriptionDao, notificationDao)
                instance = newInstance
                newInstance
            }
        }
    }
}

/* https://stackoverflow.com/a/57079290/1440785 */
fun <T, K, R> LiveData<T>.combineWith(
    liveData: LiveData<K>,
    block: (T?, K?) -> R
): LiveData<R> {
    val result = MediatorLiveData<R>()
    result.addSource(this) {
        result.value = block(this.value, liveData.value)
    }
    result.addSource(liveData) {
        result.value = block(this.value, liveData.value)
    }
    return result
}
