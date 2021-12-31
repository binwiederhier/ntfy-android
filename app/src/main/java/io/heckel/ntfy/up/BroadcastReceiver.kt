package io.heckel.ntfy.up

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.randomString
import io.heckel.ntfy.util.topicUrlUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.random.Random

class BroadcastReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
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
        if (!repository.getUnifiedPushEnabled() || appId.isBlank()) {
            Log.w(TAG, "Refusing registration: UnifiedPush disabled or empty application")
            distributor.sendRegistrationRefused(appId, connectorToken)
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            val existingSubscription = repository.getSubscriptionByConnectorToken(connectorToken)
            if (existingSubscription != null) {
                if (existingSubscription.upAppId == appId) {
                    val endpoint = topicUrlUp(existingSubscription.baseUrl, existingSubscription.topic)
                    Log.d(TAG, "Subscription with connectorToken $connectorToken exists. Sending endpoint $endpoint.")
                    distributor.sendEndpoint(appId, connectorToken, endpoint)
                } else {
                    Log.d(TAG, "Subscription with connectorToken $connectorToken exists for a different app. Refusing registration.")
                    distributor.sendRegistrationRefused(appId, connectorToken)
                }
                return@launch
            }

            // Add subscription
            val baseUrl = repository.getUnifiedPushBaseUrl() ?: context.getString(R.string.app_base_url)
            val topic = UP_PREFIX + randomString(TOPIC_RANDOM_ID_LENGTH)
            val endpoint = topicUrlUp(baseUrl, topic)
            val subscription = Subscription(
                id = Random.nextLong(),
                baseUrl = baseUrl,
                topic = topic,
                instant = true, // No Firebase, always instant!
                mutedUntil = 0,
                upAppId = appId,
                upConnectorToken = connectorToken,
                totalCount = 0,
                newCount = 0,
                lastActive = Date().time/1000
            )

            Log.d(TAG, "Adding subscription with for app $appId (connectorToken $connectorToken): $subscription")
            repository.addSubscription(subscription)
            distributor.sendEndpoint(appId, connectorToken, endpoint)

            // Refresh (and maybe start) foreground service
            SubscriberServiceManager.refresh(app)
        }
    }

    private fun unregister(context: Context, intent: Intent) {
        val connectorToken = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val app = context.applicationContext as Application
        val repository = app.repository
        val distributor = Distributor(app)
        Log.d(TAG, "UNREGISTER received (connectorToken=$connectorToken)")
        GlobalScope.launch(Dispatchers.IO) {
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

    companion object {
        private const val TAG = "NtfyUpBroadcastRecv"
        private const val UP_PREFIX = "up"
        private const val TOPIC_RANDOM_ID_LENGTH = 12
    }
}
