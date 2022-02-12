package io.heckel.ntfy.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.topicUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AddFragment : DialogFragment() {
    private val api = ApiService()

    private lateinit var repository: Repository
    private lateinit var subscribeListener: SubscribeListener

    private lateinit var subscribeView: View
    private lateinit var loginView: View
    private lateinit var positiveButton: Button
    private lateinit var negativeButton: Button

    // Subscribe page
    private lateinit var subscribeTopicText: TextInputEditText
    private lateinit var subscribeBaseUrlLayout: TextInputLayout
    private lateinit var subscribeBaseUrlText: AutoCompleteTextView
    private lateinit var subscribeUseAnotherServerCheckbox: CheckBox
    private lateinit var subscribeUseAnotherServerDescription: TextView
    private lateinit var subscribeInstantDeliveryBox: View
    private lateinit var subscribeInstantDeliveryCheckbox: CheckBox
    private lateinit var subscribeInstantDeliveryDescription: View
    private lateinit var subscribeProgress: ProgressBar
    private lateinit var subscribeErrorText: TextView
    private lateinit var subscribeErrorTextImage: View

    // Login page
    private lateinit var users: List<User>
    private lateinit var loginUsernameText: TextInputEditText
    private lateinit var loginPasswordText: TextInputEditText
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginErrorText: TextView
    private lateinit var loginErrorTextImage: View

    private lateinit var baseUrls: List<String> // List of base URLs already used, excluding app_base_url

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

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_add_dialog, null)

        // Main "pages"
        subscribeView = view.findViewById(R.id.add_dialog_subscribe_view)
        subscribeView.visibility = View.VISIBLE
        loginView = view.findViewById(R.id.add_dialog_login_view)
        loginView.visibility = View.GONE

        // Fields for "subscribe page"
        subscribeTopicText = view.findViewById(R.id.add_dialog_subscribe_topic_text)
        subscribeBaseUrlLayout = view.findViewById(R.id.add_dialog_subscribe_base_url_layout)
        subscribeBaseUrlLayout.background = view.background
        subscribeBaseUrlText = view.findViewById(R.id.add_dialog_subscribe_base_url_text)
        subscribeBaseUrlText.background = view.background
        subscribeInstantDeliveryBox = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_box)
        subscribeInstantDeliveryCheckbox = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_checkbox)
        subscribeInstantDeliveryDescription = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_description)
        subscribeUseAnotherServerCheckbox = view.findViewById(R.id.add_dialog_subscribe_use_another_server_checkbox)
        subscribeUseAnotherServerDescription = view.findViewById(R.id.add_dialog_subscribe_use_another_server_description)
        subscribeProgress = view.findViewById(R.id.add_dialog_subscribe_progress)
        subscribeErrorText = view.findViewById(R.id.add_dialog_subscribe_error_text)
        subscribeErrorText.visibility = View.GONE
        subscribeErrorTextImage = view.findViewById(R.id.add_dialog_subscribe_error_text_image)
        subscribeErrorTextImage.visibility = View.GONE

        // Hack: Make end icon smaller, see https://stackoverflow.com/a/57098715/1440785
        val dimension = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30f, resources.displayMetrics)
        val endIconImageView = subscribeBaseUrlLayout.findViewById<ImageView>(R.id.text_input_end_icon)
        endIconImageView.minimumHeight = dimension.toInt()
        endIconImageView.minimumWidth = dimension.toInt()
        subscribeBaseUrlLayout.requestLayout()

        // Fields for "login page"
        loginUsernameText = view.findViewById(R.id.add_dialog_login_username)
        loginPasswordText = view.findViewById(R.id.add_dialog_login_password)
        loginProgress = view.findViewById(R.id.add_dialog_login_progress)
        loginErrorText = view.findViewById(R.id.add_dialog_login_error_text)
        loginErrorTextImage = view.findViewById(R.id.add_dialog_login_error_text_image)

        // Set "Use another server" description based on flavor
        subscribeUseAnotherServerDescription.text = if (BuildConfig.FIREBASE_AVAILABLE) {
            getString(R.string.add_dialog_use_another_server_description)
        } else {
            getString(R.string.add_dialog_use_another_server_description_noinstant)
        }

        // Base URL dropdown behavior; Oh my, why is this so complicated?!
        val toggleEndIcon = {
            if (subscribeBaseUrlText.text.isNotEmpty()) {
                subscribeBaseUrlLayout.setEndIconDrawable(R.drawable.ic_cancel_gray_24dp)
            } else if (baseUrls.isEmpty()) {
                subscribeBaseUrlLayout.setEndIconDrawable(0)
            } else {
                subscribeBaseUrlLayout.setEndIconDrawable(R.drawable.ic_drop_down_gray_24dp)
            }
        }
        subscribeBaseUrlLayout.setEndIconOnClickListener {
            if (subscribeBaseUrlText.text.isNotEmpty()) {
                subscribeBaseUrlText.text.clear()
                if (baseUrls.isEmpty()) {
                    subscribeBaseUrlLayout.setEndIconDrawable(0)
                } else {
                    subscribeBaseUrlLayout.setEndIconDrawable(R.drawable.ic_drop_down_gray_24dp)
                }
            } else if (subscribeBaseUrlText.text.isEmpty() && baseUrls.isNotEmpty()) {
                subscribeBaseUrlLayout.setEndIconDrawable(R.drawable.ic_drop_up_gray_24dp)
                subscribeBaseUrlText.showDropDown()
            }
        }
        subscribeBaseUrlText.setOnDismissListener { toggleEndIcon() }
        subscribeBaseUrlText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                toggleEndIcon()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Nothing
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // Nothing
            }
        })

        // Fill autocomplete for base URL & users drop-down
        lifecycleScope.launch(Dispatchers.IO) {
            // Auto-complete
            val appBaseUrl = getString(R.string.app_base_url)
            baseUrls = repository.getSubscriptions()
                .groupBy { it.baseUrl }
                .map { it.key }
                .filterNot { it == appBaseUrl }
                .sorted()
            val activity = activity ?: return@launch // We may have pressed "Cancel"
            val adapter = ArrayAdapter(activity, R.layout.fragment_add_dialog_dropdown_item, baseUrls)
            activity.runOnUiThread {
                subscribeBaseUrlText.threshold = 1
                subscribeBaseUrlText.setAdapter(adapter)
                if (baseUrls.count() == 1) {
                    subscribeBaseUrlLayout.setEndIconDrawable(R.drawable.ic_cancel_gray_24dp)
                    subscribeBaseUrlText.setText(baseUrls.first())
                } else if (baseUrls.count() > 1) {
                    subscribeBaseUrlLayout.setEndIconDrawable(R.drawable.ic_drop_down_gray_24dp)
                } else {
                    subscribeBaseUrlLayout.setEndIconDrawable(0)
                }
            }

            // Users dropdown
            users = repository.getUsers()
        }

        // Show/hide based on flavor
        subscribeInstantDeliveryBox.visibility = if (BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE

        // Username/password validation on type
        val loginTextWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                validateInputLoginView()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Nothing
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // Nothing
            }
        }
        loginUsernameText.addTextChangedListener(loginTextWatcher)
        loginPasswordText.addTextChangedListener(loginTextWatcher)

        // Build dialog
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.add_dialog_button_subscribe) { _, _ ->
                // This will be overridden below to avoid closing the dialog immediately
            }
            .setNegativeButton(R.string.add_dialog_button_cancel) { _, _ ->
                // This will be overridden below
            }
            .create()

        // Show keyboard when the dialog is shown (see https://stackoverflow.com/a/19573049/1440785)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        // Add logic to disable "Subscribe" button on invalid input
        dialog.setOnShowListener {
            positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false
            positiveButton.setOnClickListener {
                positiveButtonClick()
            }
            negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            negativeButton.setOnClickListener {
                negativeButtonClick()
            }
            val subscribeTextWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    validateInputSubscribeView()
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Nothing
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    // Nothing
                }
            }
            subscribeTopicText.addTextChangedListener(subscribeTextWatcher)
            subscribeBaseUrlText.addTextChangedListener(subscribeTextWatcher)
            subscribeInstantDeliveryCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) subscribeInstantDeliveryDescription.visibility = View.VISIBLE
                else subscribeInstantDeliveryDescription.visibility = View.GONE
            }
            subscribeUseAnotherServerCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    subscribeUseAnotherServerDescription.visibility = View.VISIBLE
                    subscribeBaseUrlLayout.visibility = View.VISIBLE
                    subscribeInstantDeliveryBox.visibility = View.GONE
                    subscribeInstantDeliveryDescription.visibility = View.GONE
                } else {
                    subscribeUseAnotherServerDescription.visibility = View.GONE
                    subscribeBaseUrlLayout.visibility = View.GONE
                    subscribeInstantDeliveryBox.visibility = if (BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE
                    if (subscribeInstantDeliveryCheckbox.isChecked) subscribeInstantDeliveryDescription.visibility = View.VISIBLE
                    else subscribeInstantDeliveryDescription.visibility = View.GONE
                }
                validateInputSubscribeView()
            }
            subscribeUseAnotherServerCheckbox.isChecked = this::baseUrls.isInitialized && baseUrls.count() == 1

            // Focus topic text (keyboard is shown too, see above)
            subscribeTopicText.requestFocus()
        }

        return dialog
    }

    private fun positiveButtonClick() {
        val topic = subscribeTopicText.text.toString()
        val baseUrl = getBaseUrl()
        if (subscribeView.visibility == View.VISIBLE) {
            checkReadAndMaybeShowLogin(baseUrl, topic)
        } else if (loginView.visibility == View.VISIBLE) {
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
                showErrorAndReenableSubscribeView(e.message)
            }
        }
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

    private fun negativeButtonClick() {
        if (subscribeView.visibility == View.VISIBLE) {
            dialog?.cancel()
        } else if (loginView.visibility == View.VISIBLE) {
            showSubscribeView()
        }
    }

    private fun validateInputSubscribeView() {
        if (!this::positiveButton.isInitialized) return // As per crash seen in Google Play
        lifecycleScope.launch(Dispatchers.IO) {
            val baseUrl = getBaseUrl()
            val topic = subscribeTopicText.text.toString()
            val subscription = repository.getSubscription(baseUrl, topic)

            activity?.let {
                it.runOnUiThread {
                    if (subscription != null || DISALLOWED_TOPICS.contains(topic)) {
                        positiveButton.isEnabled = false
                    } else if (subscribeUseAnotherServerCheckbox.isChecked) {
                        positiveButton.isEnabled = topic.isNotBlank()
                                && "[-_A-Za-z0-9]{1,64}".toRegex().matches(topic)
                                && baseUrl.isNotBlank()
                                && "^https?://.+".toRegex().matches(baseUrl)
                    } else {
                        positiveButton.isEnabled = topic.isNotBlank()
                                && "[-_A-Za-z0-9]{1,64}".toRegex().matches(topic)
                    }
                }
            }
        }
    }

    private fun validateInputLoginView() {
        if (!this::positiveButton.isInitialized) return // As per crash seen in Google Play
        if (loginUsernameText.visibility == View.GONE) {
            positiveButton.isEnabled = true
        } else {
            positiveButton.isEnabled = (loginUsernameText.text?.isNotEmpty() ?: false)
                    && (loginPasswordText.text?.isNotEmpty() ?: false)
        }
    }

    private fun dismissDialog() {
        Log.d(TAG, "Closing dialog and calling onSubscribe handler")
        val activity = activity?: return // We may have pressed "Cancel"
        activity.runOnUiThread {
            val topic = subscribeTopicText.text.toString()
            val baseUrl = getBaseUrl()
            val instant = if (!BuildConfig.FIREBASE_AVAILABLE || subscribeUseAnotherServerCheckbox.isChecked) {
                true
            } else {
                subscribeInstantDeliveryCheckbox.isChecked
            }
            subscribeListener.onSubscribe(topic, baseUrl, instant)
            dialog?.dismiss()
        }
    }

    private fun getBaseUrl(): String {
        return if (subscribeUseAnotherServerCheckbox.isChecked) {
            subscribeBaseUrlText.text.toString()
        } else {
            getString(R.string.app_base_url)
        }
    }

    private fun showSubscribeView() {
        resetSubscribeView()
        positiveButton.text = getString(R.string.add_dialog_button_subscribe)
        negativeButton.text = getString(R.string.add_dialog_button_cancel)
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
        positiveButton.text = getString(R.string.add_dialog_button_login)
        negativeButton.text = getString(R.string.add_dialog_button_back)
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
        positiveButton.isEnabled = enable
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
        positiveButton.isEnabled = enable
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
