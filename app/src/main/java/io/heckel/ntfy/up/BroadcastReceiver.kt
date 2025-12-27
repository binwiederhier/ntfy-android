package io.heckel.ntfy.up

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
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
        val appId = getApplication(context, intent) ?: return
        val connectorToken = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val app = context.applicationContext as Application
        val repository = app.repository
        val distributor = Distributor(app)
        Log.d(TAG, "REGISTER received for app $appId (connectorToken=$connectorToken)")
        if (!repository.getUnifiedPushEnabled()) {
            Log.w(TAG, "Refusing registration because 'EnableUP' is disabled")
            // Action required: tell the app to not try again before an action as be done manually
            // by the user
            distributor.sendRegistrationFailed(appId, connectorToken, FailedReason.ACTION_REQUIRED)
            return
        }
        if (appId.isBlank()) {
            Log.w(TAG, "Refusing registration: Empty application")
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
                        // Internal_error: try again with a new token
                        distributor.sendRegistrationFailed(appId, connectorToken, FailedReason.INTERNAL_ERROR)
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
                    // Try again when there is network
                    distributor.sendRegistrationFailed(appId, connectorToken, FailedReason.NETWORK)
                }

                // Add to log scrubber
                Log.addScrubTerm(shortUrl(baseUrl), Log.TermType.Domain)
                Log.addScrubTerm(topic)
            }
        }
    }

    /**
     * Get application package name
     */
    private fun getApplication(context: Context, intent: Intent): String? {
        return getApplicationAnd3(context, intent)
            ?: getApplicationAnd2(intent)
    }

    /**
     * Get application package name following AND_3 specifications.
     */
    private fun getApplicationAnd3(context: Context, intent: Intent): String? {
        return if (Build.VERSION.SDK_INT >= 34) {
            getApplicationAnd3SharedId(context, intent)
        } else {
            getApplicationAnd3PendingIntent(intent)
        }
    }

    /**
     * Try get application package name following AND_3 specifications for SDK>=34, with the shared
     * identity.
     *
     * It fallback to [getApplicationAnd3PendingIntent] if the other application targets SDK<34.
     */
    @RequiresApi(34)
    private fun getApplicationAnd3SharedId(context: Context, intent: Intent): String? {
        return sentFromPackage?.also {
            // We got the package name with the shared identity
            android.util.Log.d(TAG, "Registering $it. Package name retrieved with shared identity")
        } ?: getApplicationAnd3PendingIntent(intent)?.let { packageId ->
            // We got the package name with the pending intent, checking if that app targets SDK<34
            return if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getApplicationInfo(
                    packageId,
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.GET_META_DATA.toLong()
                    )
                )
            } else {
                context.packageManager.getApplicationInfo(packageId, 0)
            }.let { ai ->
                if (ai.targetSdkVersion >= 34) {
                    android.util.Log.d(TAG, "App targeting Sdk >= 34 without shared identity, ignoring.")
                    null
                } else {
                    packageId
                }
            }
        }
    }

    /**
     * Try get application package name following AND_3 specifications when running on
     * a device with SDK<34 or receiving message from an application targeting SDK<34, with a pending
     * intent.
     *
     * Always prefer [getApplicationAnd3SharedId] if possible.
     */
    private fun getApplicationAnd3PendingIntent(intent: Intent): String? {
        return intent.getParcelableExtra<PendingIntent>(EXTRA_PI)?.creatorPackage?.also {
            android.util.Log.d(TAG, "Registering $it. Package name retrieved with PendingIntent")
        }
    }

    /**
     * Try get the application package name using AND_2 specifications
     */
    @Deprecated("This follows AND_2 specifications. Will be removed.")
    private fun getApplicationAnd2(intent: Intent): String? {
        return intent.getStringExtra(EXTRA_APPLICATION)?.also {
            android.util.Log.d(TAG, "Registering $it. Package name retrieved with legacy String extra")
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
