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
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.toPriorityString
import io.heckel.ntfy.util.topicShortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

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

        // Title
        val baseUrl = intent.getStringExtra(DetailActivity.EXTRA_SUBSCRIPTION_BASE_URL) ?: return
        val topic = intent.getStringExtra(DetailActivity.EXTRA_SUBSCRIPTION_TOPIC) ?: return
        title = topicShortUrl(baseUrl, topic)

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
        private lateinit var subscription: Subscription

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.detail_preferences, rootKey)

            // Dependencies (Fragments need a default constructor)
            repository = Repository.getInstance(requireActivity())
            serviceManager = SubscriberServiceManager(requireActivity())

            // Load subscription and users
            val subscriptionId = arguments?.getLong(DetailActivity.EXTRA_SUBSCRIPTION_ID) ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                subscription = repository.getSubscription(subscriptionId) ?: return@launch
                activity?.runOnUiThread {
                    loadView()
                }
            }
        }

        private fun loadView() {
            // Notifications muted until
            val mutedUntilPrefId = context?.getString(R.string.detail_settings_notifications_muted_until_key) ?: return
            val mutedUntil: ListPreference? = findPreference(mutedUntilPrefId)
            mutedUntil?.value = subscription.mutedUntil.toString()
            mutedUntil?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val mutedUntilValue = value?.toLongOrNull() ?:return
                    when (mutedUntilValue) {
                        Repository.MUTED_UNTIL_SHOW_ALL -> save(subscription.copy(mutedUntil = mutedUntilValue))
                        Repository.MUTED_UNTIL_FOREVER -> save(subscription.copy(mutedUntil = mutedUntilValue))
                        Repository.MUTED_UNTIL_TOMORROW -> {
                            val date = Calendar.getInstance()
                            date.add(Calendar.DAY_OF_MONTH, 1)
                            date.set(Calendar.HOUR_OF_DAY, 8)
                            date.set(Calendar.MINUTE, 30)
                            date.set(Calendar.SECOND, 0)
                            date.set(Calendar.MILLISECOND, 0)
                            save(subscription.copy(mutedUntil = date.timeInMillis/1000))
                        }
                        else -> {
                            val mutedUntilTimestamp = System.currentTimeMillis()/1000 + mutedUntilValue * 60
                            save(subscription.copy(mutedUntil = mutedUntilTimestamp))
                        }
                    }
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.mutedUntil.toString()
                }
            }
            mutedUntil?.summaryProvider = Preference.SummaryProvider<ListPreference> { _ ->
                val mutedUntilValue = subscription.mutedUntil
                when (mutedUntilValue) {
                    Repository.MUTED_UNTIL_SHOW_ALL -> getString(R.string.settings_notifications_muted_until_show_all)
                    Repository.MUTED_UNTIL_FOREVER -> getString(R.string.settings_notifications_muted_until_forever)
                    else -> {
                        val formattedDate = formatDateShort(mutedUntilValue)
                        getString(R.string.settings_notifications_muted_until_x, formattedDate)
                    }
                }
            }

            // Minimum priority
            val minPriorityPrefId = context?.getString(R.string.detail_settings_notifications_min_priority_key) ?: return
            val minPriority: ListPreference? = findPreference(minPriorityPrefId)
            minPriority?.value = subscription.minPriority.toString()
            minPriority?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val minPriorityValue = value?.toIntOrNull() ?:return
                    save(subscription.copy(minPriority = minPriorityValue))
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.minPriority.toString()
                }
            }
            minPriority?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                var value = pref.value.toIntOrNull() ?: Repository.MIN_PRIORITY_USE_GLOBAL
                val global = value == Repository.MIN_PRIORITY_USE_GLOBAL
                if (value == Repository.MIN_PRIORITY_USE_GLOBAL) {
                    value = repository.getMinPriority()
                }
                val summary = when (value) {
                    1 -> getString(R.string.settings_notifications_min_priority_summary_any)
                    5 -> getString(R.string.settings_notifications_min_priority_summary_max)
                    else -> {
                        val minPriorityString = toPriorityString(requireContext(), value)
                        getString(R.string.settings_notifications_min_priority_summary_x_or_higher, value, minPriorityString)
                    }
                }
                maybeAppendGlobal(summary, global)
            }

            // Auto delete
            val autoDeletePrefId = context?.getString(R.string.detail_settings_notifications_auto_delete_key) ?: return
            val autoDelete: ListPreference? = findPreference(autoDeletePrefId)
            autoDelete?.value = subscription.autoDelete.toString()
            autoDelete?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val seconds = value?.toLongOrNull() ?:return
                    save(subscription.copy(autoDelete = seconds))
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.autoDelete.toString()
                }
            }
            autoDelete?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                var seconds = pref.value.toLongOrNull() ?: Repository.AUTO_DELETE_USE_GLOBAL
                val global = seconds == Repository.AUTO_DELETE_USE_GLOBAL
                if (seconds == Repository.AUTO_DELETE_USE_GLOBAL) {
                    seconds = repository.getAutoDeleteSeconds()
                }
                val summary = when (seconds) {
                    Repository.AUTO_DELETE_NEVER -> getString(R.string.settings_notifications_auto_delete_summary_never)
                    Repository.AUTO_DELETE_ONE_DAY_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_day)
                    Repository.AUTO_DELETE_THREE_DAYS_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_three_days)
                    Repository.AUTO_DELETE_ONE_WEEK_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_week)
                    Repository.AUTO_DELETE_ONE_MONTH_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_month)
                    Repository.AUTO_DELETE_THREE_MONTHS_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_three_months)
                    else -> getString(R.string.settings_notifications_auto_delete_summary_one_month) // Must match default const
                }
                maybeAppendGlobal(summary, global)
            }
        }

        private fun save(newSubscription: Subscription) {
            subscription = newSubscription
            lifecycleScope.launch(Dispatchers.IO) {
                repository.updateSubscription(newSubscription)
            }
        }

        private fun maybeAppendGlobal(summary: String, global: Boolean): String {
            return if (global) {
                summary + " (" + getString(R.string.settings_global_setting_suffix) + ")"
            } else {
                summary
            }
        }
    }

    companion object {
        private const val TAG = "NtfyDetailSettingsActiv"
    }
}
