package io.heckel.ntfy.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.DownloadWorker
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.*
import kotlinx.coroutines.*
import okio.source
import java.io.File
import java.io.IOException
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
        private lateinit var pickIconLauncher: ActivityResultLauncher<String>

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.detail_preferences, rootKey)

            // Dependencies (Fragments need a default constructor)
            repository = Repository.getInstance(requireActivity())
            serviceManager = SubscriberServiceManager(requireActivity())

            // Create result launcher for custom icon (must be created in onCreatePreferences() directly)
            pickIconLauncher = createCustomIconPickLauncher()

            // Load subscription and users
            val subscriptionId = arguments?.getLong(DetailActivity.EXTRA_SUBSCRIPTION_ID) ?: return
            runBlocking {
                withContext(Dispatchers.IO) {
                    subscription = repository.getSubscription(subscriptionId) ?: return@withContext
                    activity?.runOnUiThread {
                        loadView()
                    }
                }
            }
        }

        private fun loadView() {
            loadInstantPref()
            loadMutedUntilPref()
            loadMinPriorityPref()
            loadAutoDeletePref()
            loadCustomIconsPref()
        }

        private fun loadInstantPref() {
            val appBaseUrl = getString(R.string.app_base_url)
            val prefId = context?.getString(R.string.detail_settings_notifications_instant_key) ?: return
            val pref: SwitchPreference? = findPreference(prefId)
            pref?.isVisible = BuildConfig.FIREBASE_AVAILABLE && subscription.baseUrl == appBaseUrl
            pref?.isChecked = subscription.instant
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    save(subscription.copy(instant = value), refresh = true)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return subscription.instant
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<SwitchPreference> { preference ->
                if (preference.isChecked) {
                    getString(R.string.detail_settings_notifications_instant_summary_on)
                } else {
                    getString(R.string.detail_settings_notifications_instant_summary_off)
                }
            }
        }

        private fun loadMutedUntilPref() {
            val prefId = context?.getString(R.string.detail_settings_notifications_muted_until_key) ?: return
            val pref: ListPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.value = subscription.mutedUntil.toString()
            pref?.preferenceDataStore = object : PreferenceDataStore() {
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
            pref?.summaryProvider = Preference.SummaryProvider<ListPreference> { _ ->
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
        }

        private fun loadMinPriorityPref() {
            val prefId = context?.getString(R.string.detail_settings_notifications_min_priority_key) ?: return
            val pref: ListPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.value = subscription.minPriority.toString()
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val minPriorityValue = value?.toIntOrNull() ?:return
                    save(subscription.copy(minPriority = minPriorityValue))
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.minPriority.toString()
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
                var value = preference.value.toIntOrNull() ?: Repository.MIN_PRIORITY_USE_GLOBAL
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
        }

        private fun loadAutoDeletePref() {
            val prefId = context?.getString(R.string.detail_settings_notifications_auto_delete_key) ?: return
            val pref: ListPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.value = subscription.autoDelete.toString()
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val seconds = value?.toLongOrNull() ?:return
                    save(subscription.copy(autoDelete = seconds))
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.autoDelete.toString()
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
                var seconds = preference.value.toLongOrNull() ?: Repository.AUTO_DELETE_USE_GLOBAL
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

        private fun loadCustomIconsPref() {
            val prefId = context?.getString(R.string.detail_settings_general_icon_key) ?: return
            val pref: Preference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            pref?.onPreferenceClickListener = Preference.OnPreferenceClickListener { _ ->
                pickIconLauncher.launch("image/*")
                false
            }
        }

        private fun createCustomIconPickLauncher(): ActivityResultLauncher<String> {
            return registerForActivityResult(ActivityResultContracts.GetContent()) { inputUri ->
                if (inputUri == null) {
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val resolver = requireContext().applicationContext.contentResolver
                        val inputStream = resolver.openInputStream(inputUri) ?: throw IOException("Couldn't open content URI for reading")
                        val outputUri = createUri()
                        val outputStream = resolver.openOutputStream(outputUri) ?: throw IOException("Couldn't open content URI for writing")
                        inputStream.copyTo(outputStream)
                        save(subscription.copy(icon = outputUri.toString()))
                    } catch (e: Exception) {
                        Log.w(TAG, "Saving icon failed", e)
                        requireActivity().runOnUiThread {
                            // FIXME
                        }
                    }
                }
            }
        }


        private fun createUri(): Uri {
            val dir = File(requireContext().cacheDir, SUBSCRIPTION_ICONS)
            if (!dir.exists() && !dir.mkdirs()) {
                throw Exception("Cannot create cache directory for attachments: $dir")
            }
            val file =  File(dir, subscription.id.toString())
            return FileProvider.getUriForFile(requireContext(), DownloadWorker.FILE_PROVIDER_AUTHORITY, file)
        }

        private fun save(newSubscription: Subscription, refresh: Boolean = false) {
            subscription = newSubscription
            lifecycleScope.launch(Dispatchers.IO) {
                repository.updateSubscription(newSubscription)
                if (refresh) {
                    SubscriberServiceManager.refresh(requireContext())
                }
            }
        }

        private fun maybeAppendGlobal(summary: String, global: Boolean): String {
            return if (global) {
                summary + " (" + getString(R.string.detail_settings_global_setting_suffix) + ")"
            } else {
                summary
            }
        }
    }

    companion object {
        private const val TAG = "NtfyDetailSettingsActiv"
        private const val SUBSCRIPTION_ICONS = "subscriptionIcons"
    }
}
