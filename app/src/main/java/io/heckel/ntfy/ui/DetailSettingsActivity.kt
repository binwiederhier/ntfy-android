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
                val authUser = users.firstOrNull { it.id == subscription.authUserId }
                Log.d(TAG, "subscription: $subscription")
                activity?.runOnUiThread {
                    loadView(subscription, authUser, users)
                }
            }
        }

        private fun loadView(subscription: Subscription, authUser: User?, users: List<User>) {
            // Login user
            val anonUser = User(0, "", getString(R.string.detail_settings_auth_user_entry_anon), "")
            val usersWithAnon = users.toMutableList()
            usersWithAnon.add(0, anonUser)
            val authUserPrefId = getString(R.string.detail_settings_auth_user_key)
            val authUserPref: ListPreference? = findPreference(authUserPrefId)
            authUserPref?.entries = usersWithAnon.map { it.username }.toTypedArray()
            authUserPref?.entryValues = usersWithAnon.map { it.id.toString() }.toTypedArray()
            authUserPref?.value = authUser?.id?.toString() ?: anonUser.id.toString()
            Log.d(TAG, "--> ${authUser?.id?.toString() ?: anonUser.id.toString()}")
            authUserPref?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                when (pref.value) {
                    anonUser.id.toString() -> getString(R.string.detail_settings_auth_user_summary_none)
                    else -> {
                        val username = users.firstOrNull { it.id.toString() == pref.value } ?: "?"
                        getString(R.string.detail_settings_auth_user_summary_user_x, username)
                    }
                }
            }
            authUserPref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val newAuthUserId = when (value) {
                        anonUser.id.toString() -> null
                        else -> value?.toLongOrNull()
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        Log.d(TAG, "Updating subscription ${subscription.id} with new auth user ID $newAuthUserId")
                        repository.updateSubscriptionAuthUserId(subscription.id, newAuthUserId)
                        Log.d(TAG, "after save: ${repository.getSubscription(subscription.id)}")
                        serviceManager.refresh()
                    }
                }
                override fun getString(key: String?, defValue: String?): String? {
                    Log.d(TAG, "getstring called $key $defValue -> ${authUser?.id?.toString() ?: anonUser.id.toString()}")
                    return authUser?.id?.toString() ?: anonUser.id.toString()
                }
            }
        }
    }

    companion object {
        private const val TAG = "NtfyDetailSettingsActiv"
    }
}
