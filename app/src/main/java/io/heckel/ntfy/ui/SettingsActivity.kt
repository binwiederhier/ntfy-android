package io.heckel.ntfy.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.preference.Preference.OnPreferenceClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.backup.Backuper
import io.heckel.ntfy.db.CustomHeader
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Main settings
 *
 * The "nested screen" navigation stuff (for user management) has been taken from
 * https://github.com/googlearchive/android-preferences/blob/master/app/src/main/java/com/example/androidx/preference/sample/MainActivity.kt
 */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
    UserFragment.UserDialogListener, CustomHeaderFragment.CustomHeaderDialogListener,
    DefaultServerFragment.DefaultServerDialogListener {
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var userSettingsFragment: UserSettingsFragment
    private lateinit var customHeaderSettingsFragment: CustomHeaderSettingsFragment

    private lateinit var repository: Repository
    private lateinit var serviceManager: SubscriberServiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        Log.d(TAG, "Create $this")

        repository = Repository.getInstance(this)
        serviceManager = SubscriberServiceManager(this)

        val toolbarLayout = findViewById<View>(R.id.app_bar_drawer)
        val dynamicColors = repository.getDynamicColorsEnabled()
        val darkMode = isDarkThemeOn(this)
        val statusBarColor = Colors.statusBarNormal(
            this,
            dynamicColors,
            darkMode
        )
        val toolbarTextColor = Colors.toolbarTextColor(this, dynamicColors, darkMode)
        toolbarLayout.setBackgroundColor(statusBarColor)
        
        val toolbar = toolbarLayout.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(toolbarTextColor)
        toolbar.setNavigationIconTint(toolbarTextColor)
        toolbar.overflowIcon?.setTint(toolbarTextColor)
        setSupportActionBar(toolbar)
        
        // Set system status bar appearance
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            Colors.shouldUseLightStatusBar(dynamicColors, darkMode)

        if (savedInstanceState == null) {
            settingsFragment = SettingsFragment() // Empty constructor!
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_layout, settingsFragment)
                .commit()
            title = getString(R.string.settings_title)
        } else {
            title = savedInstanceState.getCharSequence(TITLE_TAG)
        }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                setTitle(R.string.settings_title)
            }
        }

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save current activity title, so we can set it again after a configuration change
        outState.putCharSequence(TITLE_TAG, title)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        // Instantiate the new Fragment
        val fragmentClass = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass)
        fragment.arguments = pref.extras

        // Replace the existing Fragment with the new Fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_layout, fragment)
            .addToBackStack(null)
            .commit()
        title = pref.title

        // Save user settings fragment for later
        if (fragment is UserSettingsFragment) {
            userSettingsFragment = fragment
        }
        // Save custom header settings fragment for later
        if (fragment is CustomHeaderSettingsFragment) {
            customHeaderSettingsFragment = fragment
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (this::settingsFragment.isInitialized) {
            settingsFragment.updateExactAlarmsPref()
        }
    }

    class SettingsFragment : BasePreferenceFragment() {
        private lateinit var repository: Repository
        private lateinit var serviceManager: SubscriberServiceManager
        private var autoDownloadSelection = AUTO_DOWNLOAD_SELECTION_NOT_SET

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.main_preferences, rootKey)

            // Dependencies (Fragments need a default constructor)
            repository = Repository.getInstance(requireActivity())
            serviceManager = SubscriberServiceManager(requireActivity())
            autoDownloadSelection = repository.getAutoDownloadMaxSize() // Only used for <= Android P, due to permissions request

            // Important note: We do not use the default shared prefs to store settings. Every
            // preferenceDataStore is overridden to use the repository. This is convenient, because
            // everybody has access to the repository.

            // Notifications muted until (global)
            val mutedUntilPrefId = context?.getString(R.string.settings_notifications_muted_until_key) ?: return
            val mutedUntil: ListPreference? = findPreference(mutedUntilPrefId)
            mutedUntil?.value = repository.getGlobalMutedUntil().toString()
            mutedUntil?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val mutedUntilValue = value?.toLongOrNull() ?:return
                    when (mutedUntilValue) {
                        Repository.MUTED_UNTIL_SHOW_ALL -> repository.setGlobalMutedUntil(mutedUntilValue)
                        Repository.MUTED_UNTIL_FOREVER -> repository.setGlobalMutedUntil(mutedUntilValue)
                        Repository.MUTED_UNTIL_TOMORROW -> {
                            val date = Calendar.getInstance()
                            date.add(Calendar.DAY_OF_MONTH, 1)
                            date.set(Calendar.HOUR_OF_DAY, 8)
                            date.set(Calendar.MINUTE, 30)
                            date.set(Calendar.SECOND, 0)
                            date.set(Calendar.MILLISECOND, 0)
                            repository.setGlobalMutedUntil(date.timeInMillis/1000)
                        }
                        else -> {
                            val mutedUntilTimestamp = System.currentTimeMillis()/1000 + mutedUntilValue * 60
                            repository.setGlobalMutedUntil(mutedUntilTimestamp)
                        }
                    }
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getGlobalMutedUntil().toString()
                }
            }
            mutedUntil?.summaryProvider = Preference.SummaryProvider<ListPreference> {
                when (val mutedUntilValue = repository.getGlobalMutedUntil()) {
                    Repository.MUTED_UNTIL_SHOW_ALL -> getString(R.string.settings_notifications_muted_until_show_all)
                    Repository.MUTED_UNTIL_FOREVER -> getString(R.string.settings_notifications_muted_until_forever)
                    else -> {
                        val formattedDate = formatDateShort(mutedUntilValue)
                        getString(R.string.settings_notifications_muted_until_x, formattedDate)
                    }
                }
            }

            // Minimum priority
            val minPriorityPrefId = context?.getString(R.string.settings_notifications_min_priority_key) ?: return
            val minPriority: ListPreference? = findPreference(minPriorityPrefId)
            minPriority?.value = repository.getMinPriority().toString()
            minPriority?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val minPriorityValue = value?.toIntOrNull() ?:return
                    repository.setMinPriority(minPriorityValue)
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getMinPriority().toString()
                }
            }
            minPriority?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                when (val minPriorityValue = pref.value.toIntOrNull() ?: 1) { // 1/low means all priorities
                    PRIORITY_MIN -> getString(R.string.settings_notifications_min_priority_summary_any)
                    PRIORITY_MAX -> getString(R.string.settings_notifications_min_priority_summary_max)
                    else -> {
                        val minPriorityString = toPriorityString(requireContext(), minPriorityValue)
                        getString(R.string.settings_notifications_min_priority_summary_x_or_higher, minPriorityValue, minPriorityString)
                    }
                }
            }

            // Keep alerting for max priority
            val insistentMaxPriorityPrefId = context?.getString(R.string.settings_notifications_insistent_max_priority_key) ?: return
            val insistentMaxPriority: SwitchPreferenceCompat? = findPreference(insistentMaxPriorityPrefId)
            insistentMaxPriority?.isChecked = repository.getInsistentMaxPriorityEnabled()
            insistentMaxPriority?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setInsistentMaxPriorityEnabled(value)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getInsistentMaxPriorityEnabled()
                }
            }
            insistentMaxPriority?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_notifications_insistent_max_priority_summary_enabled)
                } else {
                    getString(R.string.settings_notifications_insistent_max_priority_summary_disabled)
                }
            }

            // Channel settings
            val channelPrefsPrefId = context?.getString(R.string.settings_notifications_channel_prefs_key) ?: return
            val channelPrefs: Preference? = findPreference(channelPrefsPrefId)
            channelPrefs?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            channelPrefs?.onPreferenceClickListener = OnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, BuildConfig.APPLICATION_ID)
                })
                false
            }

            // Auto download
            val autoDownloadPrefId = context?.getString(R.string.settings_notifications_auto_download_key) ?: return
            val autoDownload: ListPreference? = findPreference(autoDownloadPrefId)
            autoDownload?.value = repository.getAutoDownloadMaxSize().toString()
            autoDownload?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val maxSize = value?.toLongOrNull() ?:return
                    repository.setAutoDownloadMaxSize(maxSize)
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getAutoDownloadMaxSize().toString()
                }
            }
            autoDownload?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                when (val maxSize = pref.value.toLongOrNull() ?: repository.getAutoDownloadMaxSize()) {
                    Repository.AUTO_DOWNLOAD_NEVER -> getString(R.string.settings_notifications_auto_download_summary_never)
                    Repository.AUTO_DOWNLOAD_ALWAYS -> getString(R.string.settings_notifications_auto_download_summary_always)
                    else -> getString(R.string.settings_notifications_auto_download_summary_smaller_than_x, formatBytes(maxSize, decimals = 0))
                }
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                autoDownload?.setOnPreferenceChangeListener { _, v ->
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                        ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD)
                        autoDownloadSelection = v.toString().toLongOrNull() ?: repository.getAutoDownloadMaxSize()
                        false // If permission is granted, auto-download will be enabled in onRequestPermissionsResult()
                    } else {
                        true
                    }
                }
            }

            // Auto delete
            val autoDeletePrefId = context?.getString(R.string.settings_notifications_auto_delete_key) ?: return
            val autoDelete: ListPreference? = findPreference(autoDeletePrefId)
            autoDelete?.value = repository.getAutoDeleteSeconds().toString()
            autoDelete?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val seconds = value?.toLongOrNull() ?:return
                    repository.setAutoDeleteSeconds(seconds)
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getAutoDeleteSeconds().toString()
                }
            }
            autoDelete?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                when (pref.value.toLongOrNull() ?: repository.getAutoDeleteSeconds()) {
                    Repository.AUTO_DELETE_NEVER -> getString(R.string.settings_notifications_auto_delete_summary_never)
                    Repository.AUTO_DELETE_ONE_DAY_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_day)
                    Repository.AUTO_DELETE_THREE_DAYS_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_three_days)
                    Repository.AUTO_DELETE_ONE_WEEK_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_week)
                    Repository.AUTO_DELETE_ONE_MONTH_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_month)
                    Repository.AUTO_DELETE_THREE_MONTHS_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_three_months)
                    else -> getString(R.string.settings_notifications_auto_delete_summary_one_month) // Must match default const
                }
            }

            // Dark mode
            val darkModePrefId = context?.getString(R.string.settings_general_dark_mode_key) ?: return
            val darkMode: ListPreference? = findPreference(darkModePrefId)
            darkMode?.value = repository.getDarkMode().toString()
            darkMode?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val darkModeValue = value?.toIntOrNull() ?: return
                    repository.setDarkMode(darkModeValue)
                    AppCompatDelegate.setDefaultNightMode(darkModeValue)

                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getDarkMode().toString()
                }
            }
            darkMode?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                val darkModeValue = pref.value.toIntOrNull() ?: repository.getDarkMode()
                when (darkModeValue) {
                    AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.settings_general_dark_mode_summary_light)
                    AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.settings_general_dark_mode_summary_dark)
                    else -> getString(R.string.settings_general_dark_mode_summary_system)
                }
            }

            // Language
            val languagePrefId = context?.getString(R.string.settings_general_language_key) ?: return
            val language: ListPreference? = findPreference(languagePrefId)
            if (language != null) {
                // We only list languages that have > 80% of strings translated.
                //
                // Please use Hosted Weblate (https://hosted.weblate.org/projects/ntfy/android/)
                // to help translate other languages.
                //
                // IMPORTANT: If a language is added here, also add it to the locales_config.xml file.

                val supportedLocales = listOf(
                    "" to getString(R.string.settings_general_language_system_default),
                    "en" to "English",
                    "bg" to "Български",
                    "ca" to "Català",
                    "cs" to "Čeština",
                    "de" to "Deutsch",
                    "es" to "Español",
                    "et" to "Eesti",
                    "fi" to "Suomi",
                    "fr" to "Français",
                    "gl" to "Galego",
                    "in" to "Bahasa Indonesia",
                    "it" to "Italiano",
                    "iw" to "עברית",
                    "ja" to "日本語",
                    "ko" to "한국어",
                    "nb-NO" to "Norsk bokmål",
                    "nl" to "Nederlands",
                    "pl" to "Polski",
                    "pt" to "Português",
                    "pt-BR" to "Português (Brasil)",
                    "ro" to "Română",
                    "ru" to "Русский",
                    "sk" to "Slovenčina",
                    "sv" to "Svenska",
                    "ta" to "தமிழ்",
                    "tr" to "Türkçe",
                    "uk" to "Українська",
                    "uz" to "Oʻzbekcha",
                    "vi" to "Tiếng Việt",
                    "zh-CN" to "简体中文",
                    "zh-TW" to "繁體中文"
                )
                // Set title with 3 random flags to help users find this preference
                val flags = listOf("🇧🇬", "🇨🇿", "🇩🇪", "🇪🇸", "🇪🇪", "🇫🇮", "🇫🇷", "🇮🇩", "🇮🇱", "🇮🇳", "🇮🇹", "🇯🇵", "🇰🇷", "🇳🇱", "🇳🇴", "🇵🇱", "🇵🇹", "🇧🇷", "🇷🇴", "🇷🇺", "🇸🇪", "🇸🇰", "🇹🇷", "🇹🇼", "🇺🇦", "🇺🇿", "🇻🇳", "🇨🇳")
                val randomFlags = flags.shuffled().take(3).joinToString(" ")
                language.title = "${getString(R.string.settings_general_language_title)} $randomFlags"
                language.entries = supportedLocales.map { it.second }.toTypedArray()
                language.entryValues = supportedLocales.map { it.first }.toTypedArray()

                // Get current locale
                val currentLocales = AppCompatDelegate.getApplicationLocales()
                val currentLocaleTag = if (currentLocales.isEmpty) "" else currentLocales.toLanguageTags()
                language.value = currentLocaleTag

                language.setOnPreferenceChangeListener { _, newValue ->
                    val localeTag = newValue as String
                    if (localeTag.isEmpty()) {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                    } else {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
                    }
                    true
                }

                language.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                    val currentLocalesForSummary = AppCompatDelegate.getApplicationLocales()
                    if (currentLocalesForSummary.isEmpty) {
                        getString(R.string.settings_general_language_summary_system)
                    } else {
                        val locale = currentLocalesForSummary[0]
                        locale?.getDisplayName(locale)?.replaceFirstChar { it.uppercase() } ?: pref.entry?.toString() ?: ""
                    }
                }
            }

            // Dynamic colors
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dynamicColorsEnabledPrefId = context?.getString(R.string.settings_general_dynamic_colors_key) ?: return
                val dynamicColorsEnabled: SwitchPreferenceCompat? = findPreference(dynamicColorsEnabledPrefId)
                dynamicColorsEnabled?.isChecked = repository.getDynamicColorsEnabled()
                dynamicColorsEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                    override fun putBoolean(key: String?, value: Boolean) {
                        repository.setDynamicColorsEnabled(value)

                        // Restart app
                        val packageManager = requireContext().packageManager
                        val packageName = requireContext().packageName
                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                        val componentName = intent!!.component
                        val mainIntent = Intent.makeRestartActivityTask(componentName)
                        startActivity(mainIntent)
                        Runtime.getRuntime().exit(0)
                    }
                    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                        return repository.getDynamicColorsEnabled()
                    }
                }
                dynamicColorsEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
                    if (pref.isChecked) {
                        getString(R.string.settings_general_dynamic_colors_summary_enabled)
                    } else {
                        getString(R.string.settings_general_dynamic_colors_summary_disabled)
                    }
                }
                dynamicColorsEnabled?.isVisible = true
            }

            // Message bar enabled
            val messageBarEnabledPrefId = context?.getString(R.string.settings_general_message_bar_key) ?: return
            val messageBarEnabled: SwitchPreferenceCompat? = findPreference(messageBarEnabledPrefId)
            messageBarEnabled?.isChecked = repository.getMessageBarEnabled()
            messageBarEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setMessageBarEnabled(value)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getMessageBarEnabled()
                }
            }
            messageBarEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_general_message_bar_summary_enabled)
                } else {
                    getString(R.string.settings_general_message_bar_summary_disabled)
                }
            }

            // Default Base URL
            val appBaseUrl = getString(R.string.app_base_url)
            val defaultBaseUrlPrefId = context?.getString(R.string.settings_general_default_base_url_key) ?: return
            val defaultBaseUrl: Preference? = findPreference(defaultBaseUrlPrefId)
            defaultBaseUrl?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            defaultBaseUrl?.onPreferenceClickListener = OnPreferenceClickListener {
                activity?.let { activity ->
                    DefaultServerFragment
                        .newInstance(repository.getDefaultBaseUrl())
                        .show(activity.supportFragmentManager, DefaultServerFragment.TAG)
                }
                true
            }
            defaultBaseUrl?.summaryProvider = Preference.SummaryProvider<Preference> { _ ->
                val currentUrl = repository.getDefaultBaseUrl()
                if (currentUrl.isNullOrEmpty()) {
                    getString(R.string.settings_general_default_base_url_default_summary, appBaseUrl)
                } else {
                    currentUrl
                }
            }

            // Broadcast enabled
            val broadcastEnabledPrefId = context?.getString(R.string.settings_advanced_broadcast_key) ?: return
            val broadcastEnabled: SwitchPreferenceCompat? = findPreference(broadcastEnabledPrefId)
            broadcastEnabled?.isChecked = repository.getBroadcastEnabled()
            broadcastEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setBroadcastEnabled(value)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getBroadcastEnabled()
                }
            }
            broadcastEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_advanced_broadcast_summary_enabled)
                } else {
                    getString(R.string.settings_advanced_broadcast_summary_disabled)
                }
            }

            // Enable UnifiedPush
            val unifiedPushEnabledPrefId = context?.getString(R.string.settings_advanced_unifiedpush_key) ?: return
            val unifiedPushEnabled: SwitchPreferenceCompat? = findPreference(unifiedPushEnabledPrefId)
            unifiedPushEnabled?.isChecked = repository.getUnifiedPushEnabled()
            unifiedPushEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setUnifiedPushEnabled(value)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getUnifiedPushEnabled()
                }
            }
            unifiedPushEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_advanced_unifiedpush_summary_enabled)
                } else {
                    getString(R.string.settings_advanced_unifiedpush_summary_disabled)
                }
            }

            // Export logs
            val exportLogsPrefId = context?.getString(R.string.settings_advanced_export_logs_key) ?: return
            val exportLogs: ListPreference? = findPreference(exportLogsPrefId)
            exportLogs?.isVisible = Log.getRecord()
            exportLogs?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            exportLogs?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
                when (v) {
                    EXPORT_LOGS_COPY_ORIGINAL -> copyLogsToClipboard(scrub = false)
                    EXPORT_LOGS_COPY_SCRUBBED -> copyLogsToClipboard(scrub = true)
                    EXPORT_LOGS_UPLOAD_ORIGINAL -> uploadLogsToNopaste(scrub = false)
                    EXPORT_LOGS_UPLOAD_SCRUBBED -> uploadLogsToNopaste(scrub = true)
                }
                false
            }

            // Clear logs
            val clearLogsPrefId = context?.getString(R.string.settings_advanced_clear_logs_key) ?: return
            val clearLogs: Preference? = findPreference(clearLogsPrefId)
            clearLogs?.isVisible = Log.getRecord()
            clearLogs?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            clearLogs?.onPreferenceClickListener = OnPreferenceClickListener {
                deleteLogs()
                false
            }

            // Record logs
            val recordLogsPrefId = context?.getString(R.string.settings_advanced_record_logs_key) ?: return
            val recordLogsEnabled: SwitchPreferenceCompat? = findPreference(recordLogsPrefId)
            recordLogsEnabled?.isChecked = Log.getRecord()
            recordLogsEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setRecordLogsEnabled(value)
                    Log.setRecord(value)
                    exportLogs?.isVisible = value
                    clearLogs?.isVisible = value
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return Log.getRecord()
                }
            }
            recordLogsEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_advanced_record_logs_summary_enabled)
                } else {
                    getString(R.string.settings_advanced_record_logs_summary_disabled)
                }
            }
            recordLogsEnabled?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.getSubscriptions().forEach { s ->
                        Log.addScrubTerm(shortUrl(s.baseUrl), Log.TermType.Domain)
                        Log.addScrubTerm(s.topic)
                    }
                    repository.getUsers().forEach { u ->
                        Log.addScrubTerm(shortUrl(u.baseUrl), Log.TermType.Domain)
                        Log.addScrubTerm(u.username, Log.TermType.Username)
                        Log.addScrubTerm(u.password, Log.TermType.Password)
                    }
                }
                true
            }

            // Backup
            val backuper = Backuper(requireContext())
            val backupPrefId = context?.getString(R.string.settings_backup_restore_backup_key) ?: return
            val backup: ListPreference? = findPreference(backupPrefId)
            var backupSelection = BACKUP_EVERYTHING
            val backupResultLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
                if (uri == null) {
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        when (backupSelection) {
                            BACKUP_EVERYTHING -> backuper.backup(uri)
                            BACKUP_EVERYTHING_NO_USERS -> backuper.backup(uri, withUsers = false)
                            BACKUP_SETTINGS_ONLY -> backuper.backup(uri, withUsers = false, withSubscriptions = false)
                        }
                        activity?.let { act ->
                            act.runOnUiThread {
                                Toast.makeText(act, getString(R.string.settings_backup_restore_backup_successful), Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Backup failed", e)
                        activity?.let { act ->
                            act.runOnUiThread {
                                Toast.makeText(act, getString(R.string.settings_backup_restore_backup_failed, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            backup?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            backup?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
                backupSelection = v.toString()
                val timestamp = SimpleDateFormat("yyMMdd-HHmm").format(Date())
                val suggestedFilename = when (backupSelection) {
                    BACKUP_EVERYTHING_NO_USERS -> "ntfy-backup-no-users-$timestamp.json"
                    BACKUP_SETTINGS_ONLY -> "ntfy-settings-$timestamp.json"
                    else -> "ntfy-backup-$timestamp.json"
                }
                backupResultLauncher.launch(suggestedFilename)
                false
            }
            backup?.onPreferenceClickListener = OnPreferenceClickListener {
                true
            }

            // Restore
            val restorePrefId = context?.getString(R.string.settings_backup_restore_restore_key) ?: return
            val restore: Preference? = findPreference(restorePrefId)
            val restoreResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) {
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val currentDarkMode = repository.getDarkMode()
                        backuper.restore(uri)
                        activity?.let { act ->
                            act.runOnUiThread {
                                Toast.makeText(act, getString(R.string.settings_backup_restore_restore_successful), Toast.LENGTH_LONG).show()
                                act.recreate()
                                val newDarkMode = repository.getDarkMode()
                                if (newDarkMode != currentDarkMode) {
                                    AppCompatDelegate.setDefaultNightMode(newDarkMode)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Restore failed", e)
                        activity?.let { act ->
                            act.runOnUiThread {
                                Toast.makeText(act, getString(R.string.settings_backup_restore_restore_failed, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            restore?.onPreferenceClickListener = OnPreferenceClickListener {
                // Overly open mime type filter (because of https://github.com/binwiederhier/ntfy/issues/223).
                // This filter could likely be stricter if we'd write the mime type properly in Backuper.backup(),
                // but just in case we want to restore from a file we didn't write outselves, we'll keep this "*/*".
                restoreResultLauncher.launch("*/*")
                true
            }

            // Connection protocol
            val connectionProtocolPrefId = context?.getString(R.string.settings_advanced_connection_protocol_key) ?: return
            val connectionProtocol: ListPreference? = findPreference(connectionProtocolPrefId)
            connectionProtocol?.value = repository.getConnectionProtocol()
            connectionProtocol?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val proto = value ?: repository.getConnectionProtocol()
                    repository.setConnectionProtocol(proto)
                    serviceManager.refresh() // Refresh to switch connections between WS and JSON stream
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getConnectionProtocol()
                }
            }
            connectionProtocol?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                when (pref.value) {
                    Repository.CONNECTION_PROTOCOL_WS -> getString(R.string.settings_advanced_connection_protocol_summary_ws)
                    else -> getString(R.string.settings_advanced_connection_protocol_summary_jsonhttp)
                }
            }

            // Update "Exact alarms" preference to match system setting
            updateExactAlarmsPref()

            // Version
            val versionPrefId = context?.getString(R.string.settings_about_version_key) ?: return
            val versionPref: Preference? = findPreference(versionPrefId)
            val version = getString(R.string.settings_about_version_format, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
            versionPref?.summary = version
            versionPref?.onPreferenceClickListener = OnPreferenceClickListener {
                val context = context ?: return@OnPreferenceClickListener false
                copyToClipboard(context, "ntfy version", version)
                true
            }
        }

        fun setAutoDownload() {
            val autoDownloadSelectionCopy = autoDownloadSelection
            if (autoDownloadSelectionCopy == AUTO_DOWNLOAD_SELECTION_NOT_SET) return
            val autoDownloadPrefId = context?.getString(R.string.settings_notifications_auto_download_key) ?: return
            val autoDownload: ListPreference? = findPreference(autoDownloadPrefId)
            autoDownload?.value = autoDownloadSelectionCopy.toString()
            repository.setAutoDownloadMaxSize(autoDownloadSelectionCopy)
        }

        fun refreshDefaultServerSummary() {
            val appBaseUrl = getString(R.string.app_base_url)
            val defaultBaseUrlPrefId = context?.getString(R.string.settings_general_default_base_url_key) ?: return
            val defaultBaseUrl: Preference? = findPreference(defaultBaseUrlPrefId)
            // Re-set the summary provider to trigger a refresh
            defaultBaseUrl?.summaryProvider = Preference.SummaryProvider<Preference> { _ ->
                val currentUrl = repository.getDefaultBaseUrl()
                if (currentUrl.isNullOrEmpty()) {
                    getString(R.string.settings_general_default_base_url_default_summary, appBaseUrl)
                } else {
                    currentUrl
                }
            }
        }

        private fun copyLogsToClipboard(scrub: Boolean) {
            lifecycleScope.launch(Dispatchers.IO) {
                val context = context ?: return@launch
                val log = Log.getFormatted(context, scrub = scrub)
                activity?.let { act ->
                    act.runOnUiThread {
                        val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("ntfy logs", log)
                        clipboard.setPrimaryClip(clip)
                        if (scrub) {
                            showScrubDialog(getString(R.string.settings_advanced_export_logs_copied_logs))
                        } else {
                            Toast
                                .makeText(context, getString(R.string.settings_advanced_export_logs_copied_logs), Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                }
            }
        }

        private fun uploadLogsToNopaste(scrub: Boolean) {
            lifecycleScope.launch(Dispatchers.IO) {
                Log.d(TAG, "Uploading log to $EXPORT_LOGS_UPLOAD_URL ...")
                val context = context ?: return@launch
                val log = Log.getFormatted(context, scrub = scrub)
                if (log.length > EXPORT_LOGS_UPLOAD_NOTIFY_SIZE_THRESHOLD) {
                    activity?.let { act ->
                        act.runOnUiThread {
                            Toast
                                .makeText(context, getString(R.string.settings_advanced_export_logs_uploading), Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
                val gson = Gson()
                val request = HttpUtil.requestBuilder(EXPORT_LOGS_UPLOAD_URL)
                    .put(log.toRequestBody())
                    .build()
                val client = HttpUtil.longCallClient(context, extractBaseUrl(EXPORT_LOGS_UPLOAD_URL))
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("Unexpected response ${response.code}")
                        }
                        val body = response.body.string().trim()
                        if (body.isEmpty()) throw Exception("Return body is empty")
                        Log.d(TAG, "Logs uploaded successfully: $body")
                        val resp = gson.fromJson(body, NopasteResponse::class.java)
                        activity?.let { act ->
                            act.runOnUiThread {
                                val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("logs URL", resp.url)
                                clipboard.setPrimaryClip(clip)
                                if (scrub) {
                                    showScrubDialog(getString(R.string.settings_advanced_export_logs_copied_url))
                                } else {
                                    Toast
                                        .makeText(context, getString(R.string.settings_advanced_export_logs_copied_url), Toast.LENGTH_LONG)
                                        .show()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error uploading logs", e)
                    activity?.let { act ->
                        act.runOnUiThread {
                            Toast
                                .makeText(context, getString(R.string.settings_advanced_export_logs_error_uploading, e.message), Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                }
            }
        }

        private fun showScrubDialog(title: String) {
            val scrubbed = Log.getScrubTerms()
            val scrubbedText = if (scrubbed.isNotEmpty()) {
                val scrubTerms = scrubbed.map { e -> "${e.key} -> ${e.value}"}.joinToString(separator = "\n")
                getString(R.string.settings_advanced_export_logs_scrub_dialog_text, scrubTerms)
            } else {
                getString(R.string.settings_advanced_export_logs_scrub_dialog_empty)
            }
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(scrubbedText)
                .setPositiveButton(R.string.settings_advanced_export_logs_scrub_dialog_button_ok) { _, _ -> /* Nothing */ }
                .create()
            dialog.show()
        }

        private fun deleteLogs() {
            lifecycleScope.launch(Dispatchers.IO) {
                Log.deleteAll()
                val context = context ?: return@launch
                activity?.let { act ->
                    act.runOnUiThread {
                        Toast
                            .makeText(context, getString(R.string.settings_advanced_clear_logs_deleted_toast), Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
        }

        fun updateExactAlarmsPref() {
            val context = context ?: return
            val exactAlarmsPrefId = context.getString(R.string.settings_advanced_exact_alarms_key)
            val exactAlarmsPref: Preference? = findPreference(exactAlarmsPrefId)
            val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (activity?.getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            } else {
                true
            }
            exactAlarmsPref?.summary = if (canScheduleExactAlarms) getString(R.string.settings_advanced_exact_alarms_true) else getString(R.string.settings_advanced_exact_alarms_false)
            exactAlarmsPref?.onPreferenceClickListener = OnPreferenceClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    startActivity(Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
                true
            }
            // Android doesn't show "ntfy" in the "Alarms & reminders" list if battery optimizations are disabled.
            //
            // In fact, if the user has granted the battery optimization exemption (see battery banner in MainActivity),
            // the alarm manager's canScheduleExactAlarms() method will return true.
            //
            // This is undocumented behavior. See https://github.com/binwiederhier/ntfy/issues/1456#issuecomment-3707174262
            exactAlarmsPref?.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && !isIgnoringBatteryOptimizations(context)
        }

        @Keep
        data class NopasteResponse(val url: String)
    }

    class UserSettingsFragment : BasePreferenceFragment() {
        private lateinit var repository: Repository

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.user_preferences, rootKey)
            repository = Repository.getInstance(requireActivity())
            reload()
        }

        data class UserWithMetadata(
            val user: User,
            val topics: List<String>
        )

        fun reload() {
            preferenceScreen.removeAll()
            lifecycleScope.launch(Dispatchers.IO) {
                val baseUrlsWithTopics = repository.getSubscriptions()
                    .groupBy { it.baseUrl }
                    .mapValues { e -> e.value.map { it.topic } }
                val usersByBaseUrl = repository.getUsers()
                    .map { user ->
                        val topics = baseUrlsWithTopics[user.baseUrl] ?: emptyList()
                        UserWithMetadata(user, topics)
                    }
                    .groupBy { it.user.baseUrl }
                activity?.runOnUiThread {
                    addUserPreferences(usersByBaseUrl)
                }
            }
        }

        private fun addUserPreferences(usersByBaseUrl: Map<String, List<UserWithMetadata>>) {
            val baseUrlsInUse = ArrayList(usersByBaseUrl.keys)
            usersByBaseUrl.forEach { entry ->
                val baseUrl = entry.key
                val users = entry.value

                val preferenceCategory = PreferenceCategory(preferenceScreen.context)
                preferenceCategory.title = shortUrl(baseUrl)
                preferenceScreen.addPreference(preferenceCategory)

                users.forEach { user ->
                    val preference = Preference(preferenceScreen.context)
                    preference.title = user.user.username
                    preference.summary = if (user.topics.isEmpty()) {
                        getString(R.string.settings_general_users_prefs_user_not_used)
                    } else if (user.topics.size == 1) {
                        getString(R.string.settings_general_users_prefs_user_used_by_one, user.topics[0])
                    } else {
                        getString(R.string.settings_general_users_prefs_user_used_by_many, user.topics.joinToString(", "))
                    }
                    preference.onPreferenceClickListener = OnPreferenceClickListener { _ ->
                        activity?.let {
                            UserFragment
                                .newInstance(user.user, baseUrlsInUse)
                                .show(it.supportFragmentManager, UserFragment.TAG)
                        }
                        true
                    }
                    preferenceCategory.addPreference(preference)
                }
            }

            // Add user
            val userAddCategory = PreferenceCategory(preferenceScreen.context)
            userAddCategory.title = getString(R.string.settings_general_users_prefs_user_add)
            preferenceScreen.addPreference(userAddCategory)

            val userAddPref = Preference(preferenceScreen.context)
            userAddPref.title = getString(R.string.settings_general_users_prefs_user_add_title)
            userAddPref.summary = getString(R.string.settings_general_users_prefs_user_add_summary)
            userAddPref.onPreferenceClickListener = OnPreferenceClickListener { _ ->
                activity?.let {
                    UserFragment
                        .newInstance(user = null, baseUrlsInUse = baseUrlsInUse)
                        .show(it.supportFragmentManager, UserFragment.TAG)
                }
                true
            }
            userAddCategory.addPreference(userAddPref)
        }
    }

    class CustomHeaderSettingsFragment : PreferenceFragmentCompat() {
        private lateinit var repository: Repository

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.custom_header_preferences, rootKey)
            repository = Repository.getInstance(requireActivity())
            reload()
        }

        fun reload() {
            preferenceScreen.removeAll()
            lifecycleScope.launch(Dispatchers.IO) {
                val headersByBaseUrl = repository.getCustomHeaders()
                    .groupBy { it.baseUrl }
                    .toSortedMap()
                activity?.runOnUiThread {
                    addCustomHeaderPreferences(headersByBaseUrl)
                }
            }
        }

        private fun addCustomHeaderPreferences(headersByBaseUrl: Map<String, List<CustomHeader>>) {
            headersByBaseUrl.forEach { (baseUrl, headers) ->

                val preferenceCategory = PreferenceCategory(preferenceScreen.context)
                preferenceCategory.title = shortUrl(baseUrl)
                preferenceScreen.addPreference(preferenceCategory)

                headers.forEach { header ->
                    val preference = Preference(preferenceScreen.context)
                    preference.title = header.name
                    preference.summary = "••••••••"
                    preference.onPreferenceClickListener = OnPreferenceClickListener { _ ->
                        activity?.let {
                            CustomHeaderFragment
                                .newInstance(header)
                                .show(it.supportFragmentManager, CustomHeaderFragment.TAG)
                        }
                        true
                    }
                    preferenceCategory.addPreference(preference)
                }
            }

            // Add header
            val headerAddCategory = PreferenceCategory(preferenceScreen.context)
            headerAddCategory.title = getString(R.string.settings_advanced_custom_headers_prefs_header_add)
            preferenceScreen.addPreference(headerAddCategory)

            val headerAddPref = Preference(preferenceScreen.context)
            headerAddPref.title = getString(R.string.settings_advanced_custom_headers_prefs_header_add_title)
            headerAddPref.summary = getString(R.string.settings_advanced_custom_headers_prefs_header_add_summary)
            headerAddPref.onPreferenceClickListener = OnPreferenceClickListener { _ ->
                activity?.let {
                    CustomHeaderFragment
                        .newInstance(header = null)
                        .show(it.supportFragmentManager, CustomHeaderFragment.TAG)
                }
                true
            }
            headerAddCategory.addPreference(headerAddPref)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setAutoDownload()
            }
        }
    }

    override fun onAddUser(dialog: DialogFragment, user: User) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.addUser(user) // New users are not used, so no service refresh required
            runOnUiThread {
                if (this@SettingsActivity::userSettingsFragment.isInitialized) {
                    userSettingsFragment.reload()
                }
            }
        }
    }

    override fun onUpdateUser(dialog: DialogFragment, user: User) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.updateUser(user)
            serviceManager.refresh()
            runOnUiThread {
                if (this@SettingsActivity::userSettingsFragment.isInitialized) {
                    userSettingsFragment.reload()
                }
            }
        }
    }

    override fun onDeleteUser(dialog: DialogFragment, baseUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.deleteUser(baseUrl)
            serviceManager.refresh()
            runOnUiThread {
                if (this@SettingsActivity::userSettingsFragment.isInitialized) {
                    userSettingsFragment.reload()
                }
            }
        }
    }

    override fun onAddCustomHeader(dialog: DialogFragment, header: io.heckel.ntfy.db.CustomHeader) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.addCustomHeader(header)
            serviceManager.refresh() // Refresh to apply new headers
            runOnUiThread {
                if (this@SettingsActivity::customHeaderSettingsFragment.isInitialized) {
                    customHeaderSettingsFragment.reload()
                }
            }
        }
    }

    override fun onUpdateCustomHeader(dialog: DialogFragment, oldHeader: io.heckel.ntfy.db.CustomHeader, newHeader: io.heckel.ntfy.db.CustomHeader) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.updateCustomHeader(oldHeader, newHeader)
            serviceManager.refresh() // Refresh to apply header changes
            runOnUiThread {
                if (this@SettingsActivity::customHeaderSettingsFragment.isInitialized) {
                    customHeaderSettingsFragment.reload()
                }
            }
        }
    }

    override fun onDeleteCustomHeader(dialog: DialogFragment, header: io.heckel.ntfy.db.CustomHeader) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.deleteCustomHeader(header)
            serviceManager.refresh()
            runOnUiThread {
                if (this@SettingsActivity::customHeaderSettingsFragment.isInitialized) {
                    customHeaderSettingsFragment.reload()
                }
            }
        }
    }

    override fun onDefaultServerUpdated(dialog: DialogFragment, url: String) {
        repository.setDefaultBaseUrl(url)
        if (this::settingsFragment.isInitialized) {
            settingsFragment.refreshDefaultServerSummary()
        }
    }

    private fun setAutoDownload() {
        if (!this::settingsFragment.isInitialized) return
        settingsFragment.setAutoDownload()
    }

    companion object {
        private const val TAG = "NtfySettingsActivity"
        private const val TITLE_TAG = "title"
        private const val REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD = 2586
        private const val AUTO_DOWNLOAD_SELECTION_NOT_SET = -99L
        private const val BACKUP_EVERYTHING = "everything"
        private const val BACKUP_EVERYTHING_NO_USERS = "everything_no_users"
        private const val BACKUP_SETTINGS_ONLY = "settings_only"
        private const val EXPORT_LOGS_COPY_ORIGINAL = "copy_original"
        private const val EXPORT_LOGS_COPY_SCRUBBED = "copy_scrubbed"
        private const val EXPORT_LOGS_UPLOAD_ORIGINAL = "upload_original"
        private const val EXPORT_LOGS_UPLOAD_SCRUBBED = "upload_scrubbed"
        private const val EXPORT_LOGS_UPLOAD_URL = "https://nopaste.net/?f=json" // Run by binwiederhier; see https://github.com/binwiederhier/pcopy
        private const val EXPORT_LOGS_UPLOAD_NOTIFY_SIZE_THRESHOLD = 100 * 1024 // Show "Uploading ..." if log larger than X
    }
}
