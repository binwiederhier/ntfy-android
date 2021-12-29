package io.heckel.ntfy.up

import android.content.Context
import android.content.Intent
import android.util.Log
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.ui.SubscriberManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.random.Random

class BroadcastReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent!!.action) {
            ACTION_REGISTER -> {
                val appId = intent.getStringExtra(EXTRA_APPLICATION) ?: ""
                val connectorToken = intent.getStringExtra(EXTRA_TOKEN) ?: ""
                Log.d(TAG, "Register: app=$appId, connectorToken=$connectorToken")
                if (appId.isBlank()) {
                    Log.w(TAG, "Trying to register an app without packageName")
                    return
                }

                val baseUrl = context!!.getString(R.string.app_base_url) // FIXME
                val topic = connectorToken // FIXME
                val app = context!!.applicationContext as Application
                val repository = app.repository
                val distributor = Distributor(app)
                val subscription = Subscription(
                    id = Random.nextLong(),
                    baseUrl = baseUrl,
                    topic = topic,
                    instant = true,
                    mutedUntil = 0,
                    upAppId = appId,
                    upConnectorToken = connectorToken,
                    totalCount = 0,
                    newCount = 0,
                    lastActive = Date().time/1000
                )
                GlobalScope.launch(Dispatchers.IO) {
                    repository.addSubscription(subscription)
                    val subscriptionIdsWithInstantStatus = repository.getSubscriptionIdsWithInstantStatus()
                    val subscriberManager = SubscriberManager(context!!)
                    subscriberManager.refreshService(subscriptionIdsWithInstantStatus)
                }
                distributor.sendEndpoint(appId, connectorToken)
                // XXXXXXXXX
            }
            ACTION_UNREGISTER -> {
                val connectorToken = intent.getStringExtra(EXTRA_TOKEN) ?: ""
                Log.d(TAG, "Unregister: connectorToken=$connectorToken")
                // XXXXXXX
                val distributor = Distributor(context!!)
                distributor.sendUnregistered("org.unifiedpush.example", connectorToken)
            }
        }
    }

    companion object {
        private const val TAG = "NtfyUpBroadcastRecv"
    }
}
