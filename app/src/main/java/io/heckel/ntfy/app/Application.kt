package io.heckel.ntfy.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.google.android.material.color.DynamicColors
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Application : Application() {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository by lazy {
        val repository = Repository.getInstance(applicationContext)
        if (repository.getRecordLogs()) {
            Log.setRecord(true)
        }
        repository
    }

    override fun onCreate() {
        super.onCreate()
        if (repository.getDynamicColorsEnabled()) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Force reconnect of all WebSocket/JSON connections so they're rebound to the new
                // default network. This catches Wi-Fi <-> cellular handoffs and similar transitions
                // where the underlying socket is bound to a network that's no longer the default.
                // Without this, broken connections would only be detected via the (potentially
                // long) ping/pong timeout.
                ioScope.launch {
                    repository.getSubscriptions()
                        .map { it.baseUrl }
                        .distinct()
                        .forEach { repository.incrementConnectionForceReconnectVersion(it) }
                    SubscriberServiceManager.refresh(this@Application)
                }
            }
            override fun onLost(network: Network) {
                SubscriberServiceManager.refresh(this@Application)
            }
        })
    }
}
