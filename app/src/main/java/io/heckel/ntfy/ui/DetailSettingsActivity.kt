package io.heckel.ntfy.ui

import android.content.ContentResolver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.preference.Preference.OnPreferenceClickListener
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.DownloadWorker
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.*
import kotlinx.coroutines.*
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
        val displayName = intent.getStringExtra(DetailActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME) ?: return
        title = displayName

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish() // Return to previous activity when nav "back" is pressed!
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var resolver: ContentResolver
        private lateinit var repository: Repository
        private lateinit var serviceManager: SubscriberServiceManager
        private lateinit var subscription: Subscription

        private lateinit var iconSetPref: Preference
        private lateinit var iconSetLauncher: ActivityResultLauncher<String>
        private lateinit var iconRemovePref: Preference

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.detail_preferences, rootKey)

            // Dependencies (Fragments need a default constructor)
            repository = Repository.getInstance(requireActivity())
            serviceManager = SubscriberServiceManager(requireActivity())
            resolver = requireContext().applicationContext.contentResolver

            // Create result launcher for custom icon (must be created in onCreatePreferences() directly)
            iconSetLauncher = createIconPickLauncher()

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
            if (subscription.upAppId == null) {
                loadInstantPref()
                loadMutedUntilPref()
                loadMinPriorityPref()
                loadAutoDeletePref()
                loadPasswordPref()
                loadIconSetPref()
                loadIconRemovePref()
            } else {
                val notificationsHeaderId = context?.getString(R.string.detail_settings_notifications_header_key) ?: return
                val notificationsHeader: PreferenceCategory? = findPreference(notificationsHeaderId)
                notificationsHeader?.isVisible = false
            }

            loadDisplayNamePref()
            loadTopicUrlPref()
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

        private fun loadPasswordPref() {
            val prefId = context?.getString(R.string.detail_settings_notifications_password_key) ?: return
            val pref: EditTextPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.text = ""
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val newPassword = value ?: return
                    val encryptionKey = if (newPassword.trim().isEmpty()) null else Encryption.deriveKey(newPassword, topicUrl(subscription))
                    save(subscription.copy(encryptionKey = encryptionKey))
                }
                override fun getString(key: String?, defValue: String?): String {
                    return ""
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                if (TextUtils.isEmpty(pref.text)) {
                    "No password set"
                } else {
                    "Password saved"
                }
            }
        }

        private fun loadIconSetPref() {
            val prefId = context?.getString(R.string.detail_settings_appearance_icon_set_key) ?: return
            iconSetPref = findPreference(prefId) ?: return
            iconSetPref.isVisible = subscription.icon == null
            iconSetPref.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            iconSetPref.onPreferenceClickListener = Preference.OnPreferenceClickListener { _ ->
                iconSetLauncher.launch("image/*")
                true
            }
        }

        private fun loadIconRemovePref() {
            val prefId = context?.getString(R.string.detail_settings_appearance_icon_remove_key) ?: return
            iconRemovePref = findPreference(prefId) ?: return
            iconRemovePref.isVisible = subscription.icon != null
            iconRemovePref.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            iconRemovePref.onPreferenceClickListener = Preference.OnPreferenceClickListener { _ ->
                iconRemovePref.isVisible = false
                iconSetPref.isVisible = true
                deleteIcon(subscription.icon)
                save(subscription.copy(icon = null))
                true
            }

            // Set icon (if it exists)
            if (subscription.icon != null) {
                try {
                    val bitmap = subscription.icon!!.readBitmapFromUri(requireContext())
                    iconRemovePref.icon = bitmap.toDrawable(resources)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to set icon ${subscription.icon}", e)
                }
            }
        }

        private fun loadDisplayNamePref() {
            val prefId = context?.getString(R.string.detail_settings_appearance_display_name_key) ?: return
            val pref: EditTextPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.text = subscription.displayName
            pref?.dialogMessage = getString(R.string.detail_settings_appearance_display_name_message, topicShortUrl(subscription.baseUrl, subscription.topic))
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val displayName: String? = if (value == "") {
                        null
                    } else {
                        value
                    }
                    val newSubscription = subscription.copy(displayName = displayName)
                    save(newSubscription)
                    activity?.runOnUiThread {
                        activity?.title = displayName(newSubscription)
                    }
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.displayName ?: ""
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { provider ->
                if (TextUtils.isEmpty(provider.text)) {
                    getString(
                        R.string.detail_settings_appearance_display_name_default_summary,
                        displayName(subscription)
                    )
                } else {
                    provider.text
                }
            }
        }

        private fun loadTopicUrlPref() {
            // Topic URL
            val topicUrlPrefId = context?.getString(R.string.detail_settings_about_topic_url_key) ?: return
            val topicUrlPref: Preference? = findPreference(topicUrlPrefId)
            val topicUrl = topicShortUrl(subscription.baseUrl, subscription.topic)
            topicUrlPref?.summary = topicUrl
            topicUrlPref?.onPreferenceClickListener = OnPreferenceClickListener {
                val context = context ?: return@OnPreferenceClickListener false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("topic url", topicUrl)
                clipboard.setPrimaryClip(clip)
                Toast
                        .makeText(context, getString(R.string.detail_settings_about_topic_url_copied_to_clipboard_message), Toast.LENGTH_LONG)
                        .show()
                true
            }
        }

        private fun createIconPickLauncher(): ActivityResultLauncher<String> {
            return registerForActivityResult(ActivityResultContracts.GetContent()) { inputUri ->
                if (inputUri == null) {
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val outputUri = createUri() ?: return@launch
                    try {
                        // Early size & mime type check
                        val mimeType = resolver.getType(inputUri)
                        if (!supportedImage(mimeType)) {
                            throw IOException("unknown image type or not supported")
                        }
                        val stat = fileStat(requireContext(), inputUri) // May throw
                        if (stat.size > SUBSCRIPTION_ICON_MAX_SIZE_BYTES) {
                            throw IOException("image too large, max supported is ${SUBSCRIPTION_ICON_MAX_SIZE_BYTES/1024/1024}MB")
                        }

                        // Write to cache storage
                        val inputStream = resolver.openInputStream(inputUri) ?: throw IOException("Couldn't open content URI for reading")
                        val outputStream = resolver.openOutputStream(outputUri) ?: throw IOException("Couldn't open content URI for writing")
                        inputStream.use {
                            it.copyTo(outputStream)
                        }

                        // Read image, check dimensions
                        val bitmap = outputUri.readBitmapFromUri(requireContext())
                        if (bitmap.width > SUBSCRIPTION_ICON_MAX_WIDTH || bitmap.height > SUBSCRIPTION_ICON_MAX_HEIGHT) {
                            throw IOException("image exceeds max dimensions of ${SUBSCRIPTION_ICON_MAX_WIDTH}x${SUBSCRIPTION_ICON_MAX_HEIGHT}")
                        }

                        // Display "remove" preference
                        iconRemovePref.icon = bitmap.toDrawable(resources)
                        iconRemovePref.isVisible = true
                        iconSetPref.isVisible = false

                        // Finally, save (this is last!)
                        save(subscription.copy(icon = outputUri.toString()))
                    } catch (e: Exception) {
                        Log.w(TAG, "Saving icon failed", e)
                        requireActivity().runOnUiThread {
                            Toast.makeText(context, getString(R.string.detail_settings_appearance_icon_error_saving, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        private fun createUri(): Uri? {
            val dir = File(requireContext().cacheDir, SUBSCRIPTION_ICONS)
            if (!dir.exists() && !dir.mkdirs()) {
                return null
            }
            val file =  File(dir, subscription.id.toString())
            return FileProvider.getUriForFile(requireContext(), DownloadWorker.FILE_PROVIDER_AUTHORITY, file)
        }

        private fun deleteIcon(uri: String?) {
            if (uri == null) {
                return
            }
            try {
                resolver.delete(Uri.parse(uri), null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to delete $uri", e)
            }
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
        private const val SUBSCRIPTION_ICON_MAX_SIZE_BYTES = 4194304
        private const val SUBSCRIPTION_ICON_MAX_WIDTH = 2048
        private const val SUBSCRIPTION_ICON_MAX_HEIGHT = 2048
    }
}
