package io.heckel.ntfy.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.service.SubscriberServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Subscription settings
 *
 * THIS IS CURRENTLY UNUSED.
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
                activity?.runOnUiThread {
                    loadView(subscription)
                }
            }
        }

        private fun loadView(subscription: Subscription) {
            // ...
        }
    }

    companion object {
        private const val TAG = "NtfyDetailSettingsActiv"
    }
}
