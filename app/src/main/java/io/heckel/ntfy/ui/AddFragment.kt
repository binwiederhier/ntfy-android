package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import io.heckel.ntfy.log.Log
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.topicUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

class AddFragment : DialogFragment() {
    private val api = ApiService()

    private lateinit var repository: Repository
    private lateinit var subscribeListener: SubscribeListener

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
    private lateinit var subscribeProgress: ProgressBar
    private lateinit var subscribeErrorImage: View
    private lateinit var subscribeButton: Button

    // Login page
    private lateinit var users: List<User>
    private lateinit var loginUsersSpinner: Spinner
    private lateinit var loginUsernameText: TextInputEditText
    private lateinit var loginPasswordText: TextInputEditText
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginErrorImage: View

    private lateinit var baseUrls: List<String> // List of base URLs already used, excluding app_base_url

    interface SubscribeListener {
        fun onSubscribe(topic: String, baseUrl: String, instant: Boolean, authUserId: Long?)
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
        loginView = view.findViewById(R.id.add_dialog_login_view)
        loginView.visibility = View.GONE

        // Fields for "subscribe page"
        subscribeTopicText = view.findViewById(R.id.add_dialog_topic_text)
        subscribeBaseUrlLayout = view.findViewById(R.id.add_dialog_base_url_layout)
        subscribeBaseUrlText = view.findViewById(R.id.add_dialog_base_url_text)
        subscribeInstantDeliveryBox = view.findViewById(R.id.add_dialog_instant_delivery_box)
        subscribeInstantDeliveryCheckbox = view.findViewById(R.id.add_dialog_instant_delivery_checkbox)
        subscribeInstantDeliveryDescription = view.findViewById(R.id.add_dialog_instant_delivery_description)
        subscribeUseAnotherServerCheckbox = view.findViewById(R.id.add_dialog_use_another_server_checkbox)
        subscribeUseAnotherServerDescription = view.findViewById(R.id.add_dialog_use_another_server_description)
        subscribeProgress = view.findViewById(R.id.add_dialog_progress)
        subscribeErrorImage = view.findViewById(R.id.add_dialog_error_image)

        // Fields for "login page"
        loginUsersSpinner = view.findViewById(R.id.add_dialog_login_users_spinner)
        loginUsernameText = view.findViewById(R.id.add_dialog_login_username)
        loginPasswordText = view.findViewById(R.id.add_dialog_login_password)
        loginProgress = view.findViewById(R.id.add_dialog_login_progress)
        loginErrorImage = view.findViewById(R.id.add_dialog_login_error_image)

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
            val adapter = ArrayAdapter(requireActivity(), R.layout.fragment_add_dialog_dropdown_item, baseUrls)
            requireActivity().runOnUiThread {
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

        // Show/hide drop-down and username/password fields
        loginUsersSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    loginUsernameText.visibility = View.VISIBLE
                    loginPasswordText.visibility = View.VISIBLE
                } else {
                    loginUsernameText.visibility = View.GONE
                    loginPasswordText.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // This should not happen, ha!
            }
        }

        // Build dialog
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.add_dialog_button_subscribe) { _, _ ->
                // This will be overridden below to avoid closing the dialog immediately
            }
            .setNegativeButton(R.string.add_dialog_button_cancel) { _, _ ->
                dialog?.cancel()
            }
            .create()

        // Show keyboard when the dialog is shown (see https://stackoverflow.com/a/19573049/1440785)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        // Add logic to disable "Subscribe" button on invalid input
        dialog.setOnShowListener {
            subscribeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            subscribeButton.isEnabled = false
            subscribeButton.setOnClickListener {
                subscribeButtonClick()
            }

            val textWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    validateInput()
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Nothing
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    // Nothing
                }
            }
            subscribeTopicText.addTextChangedListener(textWatcher)
            subscribeBaseUrlText.addTextChangedListener(textWatcher)
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
                validateInput()
            }
            subscribeUseAnotherServerCheckbox.isChecked = this::baseUrls.isInitialized && baseUrls.count() == 1

            // Focus topic text (keyboard is shown too, see above)
            subscribeTopicText.requestFocus()
        }

        return dialog
    }

    private fun subscribeButtonClick() {
        val topic = subscribeTopicText.text.toString()
        val baseUrl = getBaseUrl()
        if (subscribeView.visibility == View.VISIBLE) {
            checkAnonReadAndMaybeShowLogin(baseUrl, topic)
        } else if (loginView.visibility == View.VISIBLE) {
            checkAuthAndMaybeDismiss(baseUrl, topic)
        }
    }

    private fun checkAnonReadAndMaybeShowLogin(baseUrl: String, topic: String) {
        subscribeProgress.visibility = View.VISIBLE
        subscribeErrorImage.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Checking anonymous read access to topic ${topicUrl(baseUrl, topic)}")
            try {
                val authorized = api.checkAnonTopicRead(baseUrl, topic)
                if (authorized) {
                    Log.d(TAG, "Anonymous access granted to topic ${topicUrl(baseUrl, topic)}")
                    dismiss(authUserId = null)
                } else {
                    Log.w(TAG, "Anonymous access not allowed to topic ${topicUrl(baseUrl, topic)}, showing login dialog")
                    requireActivity().runOnUiThread {
                        // Show/hide users dropdown
                        val relevantUsers = users.filter { it.baseUrl == baseUrl }
                        if (relevantUsers.isEmpty()) {
                            loginUsersSpinner.visibility = View.GONE
                        } else {
                            val spinnerEntries = relevantUsers.toMutableList()
                            spinnerEntries.add(0, User(0, "", getString(R.string.add_dialog_login_new_user), ""))
                            loginUsersSpinner.adapter = ArrayAdapter(requireActivity(), R.layout.fragment_add_dialog_dropdown_item, spinnerEntries)
                            loginUsersSpinner.setSelection(1)
                        }

                        // Show login page
                        subscribeView.visibility = View.GONE
                        loginProgress.visibility = View.INVISIBLE
                        loginView.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection to topic failed: ${e.message}", e)
                requireActivity().runOnUiThread {
                    subscribeProgress.visibility = View.GONE
                    subscribeErrorImage.visibility = View.VISIBLE
                    Toast
                        .makeText(context, getString(R.string.add_dialog_error_connection_failed, e.message), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun checkAuthAndMaybeDismiss(baseUrl: String, topic: String) {
        loginProgress.visibility = View.VISIBLE
        loginErrorImage.visibility = View.GONE
        val existingUser = loginUsersSpinner.selectedItem != null && loginUsersSpinner.selectedItem is User && loginUsersSpinner.selectedItemPosition > 0
        val user = if (existingUser) {
            loginUsersSpinner.selectedItem as User
        } else {
            User(
                id = Random.nextLong(),
                baseUrl = baseUrl,
                username = loginUsernameText.text.toString(),
                password = loginPasswordText.text.toString()
            )
        }
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Checking read access for user ${user.username} to topic ${topicUrl(baseUrl, topic)}")
            try {
                val authorized = api.checkUserTopicRead(baseUrl, topic, user.username, user.password)
                if (authorized) {
                    Log.d(TAG, "Access granted for user ${user.username} to topic ${topicUrl(baseUrl, topic)}")
                    if (!existingUser) {
                        Log.d(TAG, "Adding new user ${user.username} to database")
                        repository.addUser(user)
                    }
                    dismiss(authUserId = user.id)
                } else {
                    Log.w(TAG, "Access not allowed for user ${user.username} to topic ${topicUrl(baseUrl, topic)}")
                    requireActivity().runOnUiThread {
                        loginProgress.visibility = View.GONE
                        loginErrorImage.visibility = View.VISIBLE
                        Toast
                            .makeText(context, getString(R.string.add_dialog_login_error_not_authorized), Toast.LENGTH_LONG)
                            .show()
                    }
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    loginProgress.visibility = View.GONE
                    loginErrorImage.visibility = View.VISIBLE
                    Toast
                        .makeText(context, getString(R.string.add_dialog_error_connection_failed, e.message), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun validateInput() = lifecycleScope.launch(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val topic = subscribeTopicText.text.toString()
        val subscription = repository.getSubscription(baseUrl, topic)

        activity?.let {
            it.runOnUiThread {
                if (subscription != null || DISALLOWED_TOPICS.contains(topic)) {
                    subscribeButton.isEnabled = false
                } else if (subscribeUseAnotherServerCheckbox.isChecked) {
                    subscribeButton.isEnabled = topic.isNotBlank()
                            && "[-_A-Za-z0-9]{1,64}".toRegex().matches(topic)
                            && baseUrl.isNotBlank()
                            && "^https?://.+".toRegex().matches(baseUrl)
                } else {
                    subscribeButton.isEnabled = topic.isNotBlank()
                            && "[-_A-Za-z0-9]{1,64}".toRegex().matches(topic)
                }
            }
        }
    }

    private fun dismiss(authUserId: Long?) {
        Log.d(TAG, "Closing dialog and calling onSubscribe handler")
        requireActivity().runOnUiThread {
            val topic = subscribeTopicText.text.toString()
            val baseUrl = getBaseUrl()
            val instant = if (!BuildConfig.FIREBASE_AVAILABLE || subscribeUseAnotherServerCheckbox.isChecked) {
                true
            } else {
                subscribeInstantDeliveryCheckbox.isChecked
            }
            subscribeListener.onSubscribe(topic, baseUrl, instant, authUserId = authUserId)
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

    companion object {
        const val TAG = "NtfyAddFragment"
        private val DISALLOWED_TOPICS = listOf("docs", "static")
    }
}
