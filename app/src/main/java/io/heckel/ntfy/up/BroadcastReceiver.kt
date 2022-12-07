package io.heckel.ntfy.up

import android.content.Context
import android.content.Intent
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

/**
 * This is the UnifiedPush broadcast receiver to handle the distributor actions REGISTER and UNREGISTER.
 * See https://unifiedpush.org/spec/android/ for details.
 */
class BroadcastReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        Log.init(context) // Init in all entrypoints
        when (intent.action) {
            ACTION_REGISTER -> register(context, intent)
            ACTION_UNREGISTER -> unregister(context, intent)
        }
    }

    private fun register(context: Context, intent: Intent) {
        val appId = intent.getStringExtra(EXTRA_APPLICATION) ?: return
        val connectorToken = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val app = context.applicationContext as Application
        val repository = app.repository
        val distributor = Distributor(app)
        Log.d(TAG, "REGISTER received for app $appId (connectorToken=$connectorToken)")
        if (appId.isBlank()) {
            Log.w(TAG, "Refusing registration: Empty application")
            distributor.sendRegistrationFailed(appId, connectorToken, "Empty application string")
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            // We're doing all of this inside a critical section, because of possible races.
            // See https://github.com/binwiederhier/ntfy/issues/230 for details.

            mutex.withLock {
                val existingSubscription = repository.getSubscriptionByConnectorToken(connectorToken)
                if (existingSubscription != null) {
                    if (existingSubscription.upAppId == appId) {
                        val endpoint = topicUrlUp(existingSubscription.baseUrl, existingSubscription.topic)
                        Log.d(TAG, "Subscription with connectorToken $connectorToken exists. Sending endpoint $endpoint.")
                        distributor.sendEndpoint(appId, connectorToken, endpoint)
                    } else {
                        Log.d(TAG, "Subscription with connectorToken $connectorToken exists for a different app. Refusing registration.")
                        distributor.sendRegistrationFailed(appId, connectorToken, "Connector token already exists")
                    }
                    return@launch
                }

                // Add subscription
                val baseUrl = repository.getDefaultBaseUrl() ?: context.getString(R.string.app_base_url)
                val topic = UP_PREFIX + randomString(TOPIC_RANDOM_ID_LENGTH)
                val endpoint = topicUrlUp(baseUrl, topic)
                val subscription = Subscription(
                    id = randomSubscriptionId(),
                    baseUrl = baseUrl,
                    topic = topic,
                    instant = true, // No Firebase, always instant!
                    dedicatedChannels = false,
                    mutedUntil = 0,
                    minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
                    autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
                    insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
                    lastNotificationId = null,
                    icon = null,
                    upAppId = appId,
                    upConnectorToken = connectorToken,
                    displayName = null,
                    totalCount = 0,
                    newCount = 0,
                    lastActive = Date().time/1000
                )
                Log.d(TAG, "Adding subscription with for app $appId (connectorToken $connectorToken): $subscription")
                try {
                    // Note, this may fail due to a SQL constraint exception, see https://github.com/binwiederhier/ntfy/issues/185
                    repository.addSubscription(subscription)
                    distributor.sendEndpoint(appId, connectorToken, endpoint)

                    // Refresh (and maybe start) foreground service
                    SubscriberServiceManager.refresh(app)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add subscription", e)
                    distributor.sendRegistrationFailed(appId, connectorToken, e.message)
                }

                // Add to log scrubber
                Log.addScrubTerm(shortUrl(baseUrl), Log.TermType.Domain)
                Log.addScrubTerm(topic)
            }
        }
    }

    private fun unregister(context: Context, intent: Intent) {
        val connectorToken = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val app = context.applicationContext as Application
        val repository = app.repository
        val distributor = Distributor(app)
        Log.d(TAG, "UNREGISTER received (connectorToken=$connectorToken)")
        GlobalScope.launch(Dispatchers.IO) {
            // We're doing all of this inside a critical section, because of possible races.
            // See https://github.com/binwiederhier/ntfy/issues/230 for details.

            mutex.withLock {
                val existingSubscription = repository.getSubscriptionByConnectorToken(connectorToken)
                if (existingSubscription == null) {
                    Log.d(TAG, "Subscription with connectorToken $connectorToken does not exist. Ignoring.")
                    return@launch
                }

                // Remove subscription
                Log.d(TAG, "Removing subscription ${existingSubscription.id} with connectorToken $connectorToken")
                repository.removeSubscription(existingSubscription.id)
                existingSubscription.upAppId?.let { appId -> distributor.sendUnregistered(appId, connectorToken) }

                // Refresh (and maybe stop) foreground service
                SubscriberServiceManager.refresh(context)
            }
        }
    }

    companion object {
        private const val TAG = "NtfyUpBroadcastRecv"
        private const val UP_PREFIX = "up"
        private const val TOPIC_RANDOM_ID_LENGTH = 12

        val mutex = Mutex() // https://github.com/binwiederhier/ntfy/issues/230
    }
}
