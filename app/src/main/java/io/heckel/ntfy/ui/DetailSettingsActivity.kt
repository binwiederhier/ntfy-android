package io.heckel.ntfy.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.log.Log
import io.heckel.ntfy.service.SubscriberServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Subscription settings
 */
class DetailSettingsActivity : AppCompatActivity() {
    private lateinit var repository: Repository
    private lateinit var serviceManager: SubscriberServiceManager
    private lateinit var settingsFragment: SettingsFragment
    private var subscriptionId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        Log.d(TAG, "Create $this")

        repository = Repository.getInstance(this)
        serviceManager = SubscriberServiceManager(this)
        subscriptionId = intent.getLongExtra(DetailActivity.EXTRA_SUBSCRIPTION_ID, 0)

        if (savedInstanceState == null) {
            settingsFragment = SettingsFragment() // Empty constructor!
            settingsFragment.arguments = Bundle().apply {
                this.putLong(DetailActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
            }
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_layout, settingsFragment)
                .commit()
        }

        title = getString(R.string.detail_settings_title)

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish() // Return to previous activity when nav "back" is pressed!
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var repository: Repository
        private lateinit var serviceManager: SubscriberServiceManager

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.detail_preferences, rootKey)

            // Dependencies (Fragments need a default constructor)
            repository = Repository.getInstance(requireActivity())
            serviceManager = SubscriberServiceManager(requireActivity())

            // Load subscription and users
            val subscriptionId = arguments?.getLong(DetailActivity.EXTRA_SUBSCRIPTION_ID) ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                val subscription = repository.getSubscription(subscriptionId) ?: return@launch
                val users = repository.getUsers().filter { it.baseUrl == subscription.baseUrl }
                activity?.runOnUiThread {
                    loadView(subscription.id, users)
                }
            }
        }

        private fun loadView(subscriptionId: Long, users: List<User>) {
            // Login user
            val authUserPrefId = context?.getString(R.string.detail_settings_auth_user_key) ?: return
            val authUser: ListPreference? = findPreference(authUserPrefId)
            authUser?.entries = users.map { it.username }.toTypedArray()
            authUser?.entryValues = users.map { it.id.toString() }.toTypedArray()
            authUser?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val authUserId = when (value) {
                        "" -> null
                        else -> value?.toLongOrNull()
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        Log.d(TAG, "Updating auth user ID to $authUserId for subscription $subscriptionId")
                        repository.updateSubscriptionAuthUserId(subscriptionId, authUserId)
                        serviceManager.refresh()
                    }
                }
                override fun getString(key: String?, defValue: String?): String? {
                    Log.d(TAG, "getstring called $key $defValue")
                    return "xxx"
                }
            }
        }
    }

    companion object {
        private const val TAG = "NtfyDetailSettingsActiv"
    }
}
