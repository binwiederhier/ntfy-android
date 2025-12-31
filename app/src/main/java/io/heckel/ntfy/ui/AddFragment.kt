package io.heckel.ntfy.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.tls.SSLManager
import io.heckel.ntfy.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.view.isVisible
import androidx.core.view.isGone
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class AddFragment : DialogFragment(), CertificateTrustFragment.CertificateTrustListener {
    private lateinit var repository: Repository
    private lateinit var api: ApiService
    private lateinit var subscribeListener: SubscribeListener
    private lateinit var appBaseUrl: String
    private var defaultBaseUrl: String? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var actionMenuItem: MenuItem
    private lateinit var subscribeView: View
    private lateinit var loginView: View

    // Subscribe page
    private lateinit var subscribeTopicText: TextInputEditText
    private lateinit var subscribeBaseUrlLayout: TextInputLayout
    private lateinit var subscribeBaseUrlText: AutoCompleteTextView
    private lateinit var subscribeUseAnotherServerCheckbox: CheckBox
    private lateinit var subscribeUseAnotherServerDescription: TextView
    private lateinit var subscribeInstantDeliveryBox: View
    private lateinit var subscribeInstantDeliveryCheckbox: CheckBox
    private lateinit var subscribeInstantDeliveryDescription: View
    private lateinit var subscribeForegroundDescription: TextView
    private lateinit var subscribeProgress: ProgressBar
    private lateinit var subscribeErrorText: TextView
    private lateinit var subscribeErrorTextImage: View

    // Login page
    private lateinit var loginUsernameText: TextInputEditText
    private lateinit var loginPasswordText: TextInputEditText
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginErrorText: TextView
    private lateinit var loginErrorTextImage: View

    interface SubscribeListener {
        fun onSubscribe(topic: String, baseUrl: String, instant: Boolean)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        subscribeListener = activity as SubscribeListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Dependencies (Fragments need a default constructor)
        repository = Repository.getInstance(requireActivity())
        api = ApiService(requireContext())
        appBaseUrl = getString(R.string.app_base_url)
        defaultBaseUrl = repository.getDefaultBaseUrl()

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_add_dialog, null)

        // Setup toolbar
        toolbar = view.findViewById(R.id.add_dialog_toolbar)
        toolbar.setNavigationOnClickListener {
            dismiss()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.add_dialog_action_button) {
                onActionButtonClick()
                true
            } else {
                false
            }
        }
        actionMenuItem = toolbar.menu.findItem(R.id.add_dialog_action_button)

        // Main "pages"
        subscribeView = view.findViewById(R.id.add_dialog_subscribe_view)
        subscribeView.visibility = View.VISIBLE
        loginView = view.findViewById(R.id.add_dialog_login_view)
        loginView.visibility = View.GONE

        // Fields for "subscribe page"
        subscribeTopicText = view.findViewById(R.id.add_dialog_subscribe_topic_text)
        subscribeBaseUrlLayout = view.findViewById(R.id.add_dialog_subscribe_base_url_layout)
        subscribeBaseUrlLayout.background = view.background
        subscribeBaseUrlLayout.makeEndIconSmaller(resources) // Hack!
        subscribeBaseUrlText = view.findViewById(R.id.add_dialog_subscribe_base_url_text)
        subscribeBaseUrlText.background = view.background
        subscribeBaseUrlText.hint = defaultBaseUrl ?: appBaseUrl
        subscribeInstantDeliveryBox = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_box)
        subscribeInstantDeliveryCheckbox = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_checkbox)
        subscribeInstantDeliveryDescription = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_description)
        subscribeUseAnotherServerCheckbox = view.findViewById(R.id.add_dialog_subscribe_use_another_server_checkbox)
        subscribeUseAnotherServerDescription = view.findViewById(R.id.add_dialog_subscribe_use_another_server_description)
        subscribeForegroundDescription = view.findViewById(R.id.add_dialog_subscribe_foreground_description)
        subscribeProgress = view.findViewById(R.id.add_dialog_subscribe_progress)
        subscribeErrorText = view.findViewById(R.id.add_dialog_subscribe_error_text)
        subscribeErrorText.visibility = View.GONE
        subscribeErrorTextImage = view.findViewById(R.id.add_dialog_subscribe_error_text_image)
        subscribeErrorTextImage.visibility = View.GONE

        // Fields for "login page"
        loginUsernameText = view.findViewById(R.id.add_dialog_login_username)
        loginPasswordText = view.findViewById(R.id.add_dialog_login_password)
        loginProgress = view.findViewById(R.id.add_dialog_login_progress)
        loginErrorText = view.findViewById(R.id.add_dialog_login_error_text)
        loginErrorTextImage = view.findViewById(R.id.add_dialog_login_error_text_image)

        // Set foreground description text
        subscribeForegroundDescription.text = getString(R.string.add_dialog_foreground_description, shortUrl(appBaseUrl))

        // Show/hide based on flavor (faster shortcut for validateInputSubscribeView, which can only run onShow)
        if (!BuildConfig.FIREBASE_AVAILABLE) {
            subscribeInstantDeliveryBox.visibility = View.GONE
        }

        // Add baseUrl auto-complete behavior
        lifecycleScope.launch(Dispatchers.IO) {
            val baseUrlsRaw = repository.getSubscriptions()
                .groupBy { it.baseUrl }
                .map { it.key }
                .filterNot { it == appBaseUrl }
            val baseUrls = if (defaultBaseUrl != null) {
                (baseUrlsRaw.filterNot { it == defaultBaseUrl } + appBaseUrl).sorted()
            } else {
                baseUrlsRaw.sorted()
            }
            val activity = activity ?: return@launch // We may have pressed "Cancel"
            activity.runOnUiThread {
                initBaseUrlDropdown(baseUrls, subscribeBaseUrlText, subscribeBaseUrlLayout)
            }
        }

        // Subscribe view validation
        val subscribeTextWatcher = AfterChangedTextWatcher {
            validateInputSubscribeView()
        }
        subscribeTopicText.addTextChangedListener(subscribeTextWatcher)
        subscribeBaseUrlText.addTextChangedListener(subscribeTextWatcher)
        subscribeInstantDeliveryCheckbox.setOnCheckedChangeListener { _, _ ->
            validateInputSubscribeView()
        }
        subscribeUseAnotherServerCheckbox.setOnCheckedChangeListener { _, _ ->
            validateInputSubscribeView()
        }

        // Username/password validation on type
        val loginTextWatcher = AfterChangedTextWatcher {
            validateInputLoginView()
        }
        loginUsernameText.addTextChangedListener(loginTextWatcher)
        loginPasswordText.addTextChangedListener(loginTextWatcher)

        // Build dialog
        val dialog = Dialog(requireContext(), R.style.Theme_App_FullScreenDialog)
        dialog.setContentView(view)

        // Initial validation
        validateInputSubscribeView()

        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Show keyboard after the dialog is fully visible
        subscribeTopicText.postDelayed({
            subscribeTopicText.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(subscribeTopicText, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun onActionButtonClick() {
        val topic = subscribeTopicText.text.toString()
        val baseUrl = getBaseUrl()
        if (subscribeView.isVisible) {
            checkReadAndMaybeShowLogin(baseUrl, topic)
        } else if (loginView.isVisible) {
            loginAndMaybeDismiss(baseUrl, topic)
        }
    }

    private fun checkReadAndMaybeShowLogin(baseUrl: String, topic: String) {
        subscribeProgress.visibility = View.VISIBLE
        subscribeErrorText.visibility = View.GONE
        subscribeErrorTextImage.visibility = View.GONE
        enableSubscribeView(false)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(baseUrl) // May be null
                val authorized = api.checkAuth(baseUrl, topic, user)
                if (authorized) {
                    Log.d(TAG, "Access granted to topic ${topicUrl(baseUrl, topic)}")
                    dismissDialog()
                } else {
                    if (user != null) {
                        Log.w(TAG, "Access not allowed to topic ${topicUrl(baseUrl, topic)}, but user already exists")
                        showErrorAndReenableSubscribeView(getString(R.string.add_dialog_login_error_not_authorized, user.username))
                    } else {
                        Log.w(TAG, "Access not allowed to topic ${topicUrl(baseUrl, topic)}, showing login dialog")
                        val activity = activity ?: return@launch // We may have pressed "Cancel"
                        activity.runOnUiThread {
                            showLoginView(activity)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection to topic failed: ${e.message}", e)
                
                // Check if this is an SSL certificate error
                if (isSSLException(e)) {
                    Log.d(TAG, "SSL certificate error detected, attempting to fetch certificate for user review")
                    handleSSLException(baseUrl, topic, e)
                } else {
                    showErrorAndReenableSubscribeView(e.message)
                }
            }
        }
    }
    
    private fun isSSLException(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SSLHandshakeException || 
                cause is SSLPeerUnverifiedException ||
                cause is java.security.cert.CertificateException) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
    
    private fun handleSSLException(baseUrl: String, topic: String, e: Exception) {
        // Try to fetch the server's certificate
        val sslManager = SSLManager.getInstance(requireContext())
        val certificate = sslManager.fetchServerCertificate(baseUrl)
        
        val activity = activity ?: return
        activity.runOnUiThread {
            if (certificate != null) {
                // Show the certificate trust dialog
                showCertificateTrustDialog(baseUrl, certificate)
            } else {
                // Could not fetch certificate, show generic SSL error
                showErrorAndReenableSubscribeView(getString(R.string.add_dialog_error_ssl_untrusted))
            }
        }
    }
    
    private fun showCertificateTrustDialog(baseUrl: String, certificate: X509Certificate) {
        subscribeProgress.visibility = View.GONE
        enableSubscribeView(true)
        
        CertificateTrustFragment
            .newInstance(baseUrl, certificate)
            .show(childFragmentManager, CertificateTrustFragment.TAG)
    }
    
    // CertificateTrustFragment.CertificateTrustListener implementation
    override fun onCertificateTrusted(baseUrl: String, certificate: X509Certificate) {
        Log.d(TAG, "Certificate trusted for $baseUrl, retrying connection")
        // Retry the connection now that the certificate is trusted
        val topic = subscribeTopicText.text.toString()
        checkReadAndMaybeShowLogin(baseUrl, topic)
    }
    
    override fun onCertificateRejected() {
        Log.d(TAG, "Certificate rejected by user")
        showErrorAndReenableSubscribeView(getString(R.string.add_dialog_error_ssl_untrusted))
    }

    private fun showErrorAndReenableSubscribeView(message: String?) {
        val activity = activity ?: return // We may have pressed "Cancel"
        activity.runOnUiThread {
            subscribeProgress.visibility = View.GONE
            subscribeErrorText.visibility = View.VISIBLE
            subscribeErrorText.text = message
            subscribeErrorTextImage.visibility = View.VISIBLE
            enableSubscribeView(true)
        }
    }

    private fun loginAndMaybeDismiss(baseUrl: String, topic: String) {
        loginProgress.visibility = View.VISIBLE
        loginErrorText.visibility = View.GONE
        loginErrorTextImage.visibility = View.GONE
        enableLoginView(false)
        val user = User(
            baseUrl = baseUrl,
            username = loginUsernameText.text.toString(),
            password = loginPasswordText.text.toString()
        )
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Checking read access for user ${user.username} to topic ${topicUrl(baseUrl, topic)}")
            try {
                val authorized = api.checkAuth(baseUrl, topic, user)
                if (authorized) {
                    Log.d(TAG, "Access granted for user ${user.username} to topic ${topicUrl(baseUrl, topic)}, adding to database")
                    repository.addUser(user)
                    dismissDialog()
                } else {
                    Log.w(TAG, "Access not allowed for user ${user.username} to topic ${topicUrl(baseUrl, topic)}")
                    showErrorAndReenableLoginView(getString(R.string.add_dialog_login_error_not_authorized, user.username))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection to topic failed during login: ${e.message}", e)
                showErrorAndReenableLoginView(e.message)
            }
        }
    }

    private fun showErrorAndReenableLoginView(message: String?) {
        val activity = activity ?: return // We may have pressed "Cancel"
        activity.runOnUiThread {
            loginProgress.visibility = View.GONE
            loginErrorText.visibility = View.VISIBLE
            loginErrorText.text = message
            loginErrorTextImage.visibility = View.VISIBLE
            enableLoginView(true)
        }
    }

    private fun validateInputSubscribeView() {
        if (!this::actionMenuItem.isInitialized) return // As per crash seen in Google Play

        // Show/hide things: This logic is intentionally kept simple. Do not simplify "just because it's pretty".
        val instantToggleAllowed = if (!BuildConfig.FIREBASE_AVAILABLE) {
            false
        } else if (subscribeUseAnotherServerCheckbox.isChecked && subscribeBaseUrlText.text.toString() == appBaseUrl) {
            true
        } else if (!subscribeUseAnotherServerCheckbox.isChecked && defaultBaseUrl == null) {
            true
        } else {
            false
        }
        if (subscribeUseAnotherServerCheckbox.isChecked) {
            subscribeUseAnotherServerDescription.visibility = View.VISIBLE
            subscribeBaseUrlLayout.visibility = View.VISIBLE
        } else {
            subscribeUseAnotherServerDescription.visibility = View.GONE
            subscribeBaseUrlLayout.visibility = View.GONE
        }
        if (instantToggleAllowed) {
            subscribeInstantDeliveryBox.visibility = View.VISIBLE
            subscribeInstantDeliveryDescription.visibility = if (subscribeInstantDeliveryCheckbox.isChecked) View.VISIBLE else View.GONE
            subscribeForegroundDescription.visibility = View.GONE
        } else {
            subscribeInstantDeliveryBox.visibility = View.GONE
            subscribeInstantDeliveryDescription.visibility = View.GONE
            subscribeForegroundDescription.visibility = if (BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE
        }

        // Enable/disable "Subscribe" button
        lifecycleScope.launch(Dispatchers.IO) {
            val baseUrl = getBaseUrl()
            val topic = subscribeTopicText.text.toString()
            val subscription = repository.getSubscription(baseUrl, topic)

            activity?.let {
                it.runOnUiThread {
                    if (subscription != null || DISALLOWED_TOPICS.contains(topic)) {
                        actionMenuItem.isEnabled = false
                    } else if (subscribeUseAnotherServerCheckbox.isChecked) {
                        actionMenuItem.isEnabled = validTopic(topic) && validUrl(baseUrl)
                    } else {
                        actionMenuItem.isEnabled = validTopic(topic)
                    }
                }
            }
        }
    }

    private fun validateInputLoginView() {
        if (!this::actionMenuItem.isInitialized || !this::loginUsernameText.isInitialized || !this::loginPasswordText.isInitialized) {
            return // As per crash seen in Google Play
        }
        if (loginUsernameText.isGone) {
            actionMenuItem.isEnabled = true
        } else {
            actionMenuItem.isEnabled = (loginUsernameText.text?.isNotEmpty() ?: false)
                    && (loginPasswordText.text?.isNotEmpty() ?: false)
        }
    }

    private fun dismissDialog() {
        Log.d(TAG, "Closing dialog and calling onSubscribe handler")
        val activity = activity?: return // We may have pressed "Cancel"
        activity.runOnUiThread {
            val topic = subscribeTopicText.text.toString()
            val baseUrl = getBaseUrl()
            val instant = !BuildConfig.FIREBASE_AVAILABLE || baseUrl != appBaseUrl || subscribeInstantDeliveryCheckbox.isChecked
            subscribeListener.onSubscribe(topic, baseUrl, instant)
            dialog?.dismiss()
        }
    }

    private fun getBaseUrl(): String {
        return if (subscribeUseAnotherServerCheckbox.isChecked) {
            subscribeBaseUrlText.text.toString()
        } else {
            return defaultBaseUrl ?: appBaseUrl
        }
    }

    private fun showSubscribeView() {
        resetSubscribeView()
        toolbar.setTitle(R.string.add_dialog_title)
        actionMenuItem.setTitle(R.string.add_dialog_button_subscribe)
        toolbar.setNavigationOnClickListener {
            dismiss()
        }
        loginView.visibility = View.GONE
        subscribeView.visibility = View.VISIBLE
        if (subscribeTopicText.requestFocus()) {
            val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(subscribeTopicText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showLoginView(activity: Activity) {
        resetLoginView()
        loginProgress.visibility = View.INVISIBLE
        toolbar.setTitle(R.string.add_dialog_login_title)
        actionMenuItem.setTitle(R.string.add_dialog_button_login)
        toolbar.setNavigationOnClickListener {
            showSubscribeView()
        }
        subscribeView.visibility = View.GONE
        loginView.visibility = View.VISIBLE
        if (loginUsernameText.requestFocus()) {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(loginUsernameText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun enableSubscribeView(enable: Boolean) {
        subscribeTopicText.isEnabled = enable
        subscribeBaseUrlText.isEnabled = enable
        subscribeInstantDeliveryCheckbox.isEnabled = enable
        subscribeUseAnotherServerCheckbox.isEnabled = enable
        actionMenuItem.isEnabled = enable
    }

    private fun resetSubscribeView() {
        subscribeProgress.visibility = View.GONE
        subscribeErrorText.visibility = View.GONE
        subscribeErrorTextImage.visibility = View.GONE
        enableSubscribeView(true)
    }

    private fun enableLoginView(enable: Boolean) {
        loginUsernameText.isEnabled = enable
        loginPasswordText.isEnabled = enable
        actionMenuItem.isEnabled = enable
        if (enable && loginUsernameText.requestFocus()) {
            val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(loginUsernameText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun resetLoginView() {
        loginProgress.visibility = View.GONE
        loginErrorText.visibility = View.GONE
        loginErrorTextImage.visibility = View.GONE
        loginUsernameText.visibility = View.VISIBLE
        loginUsernameText.text?.clear()
        loginPasswordText.visibility = View.VISIBLE
        loginPasswordText.text?.clear()
        enableLoginView(true)
    }

    companion object {
        const val TAG = "NtfyAddFragment"
        private val DISALLOWED_TOPICS = listOf("docs", "static", "file") // If updated, also update in server
    }
}
