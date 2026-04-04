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
                SubscriberServiceManager.refresh(this@Application)
            }
            override fun onLost(network: Network) {
                SubscriberServiceManager.refresh(this@Application)
            }
        })
    }
}
