package io.heckel.ntfy.data

import android.content.SharedPreferences
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

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscription(subscriptionId: Long): Subscription? {
        return toSubscription(subscriptionDao.get(subscriptionId))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscription(baseUrl: String, topic: String): Subscription? {
        return toSubscription(subscriptionDao.get(baseUrl, topic))
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
        if (maybeExistingNotification == null) {
            notificationDao.add(notification)
            return shouldNotify(notification)
        }
        return false
    }

    private suspend fun shouldNotify(notification: Notification): Boolean {
        val detailViewOpen = detailViewSubscriptionId.get() == notification.subscriptionId
        val muted = isMuted(notification.subscriptionId)
        return !detailViewOpen && !muted
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

    private suspend fun isMuted(subscriptionId: Long): Boolean {
        if (isGlobalMuted()) {
            return true
        }
        val s = getSubscription(subscriptionId) ?: return true
        return s.mutedUntil == 1L || (s.mutedUntil > 1L && s.mutedUntil > System.currentTimeMillis()/1000)
    }

    private fun isGlobalMuted(): Boolean {
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
        const val SHARED_PREFS_MUTED_UNTIL_TIMESTAMP = "MutedUntil"

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
