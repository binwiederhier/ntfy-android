package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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
import kotlinx.android.synthetic.main.fragment_add_dialog.*
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
    private lateinit var topicNameText: TextInputEditText
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlText: AutoCompleteTextView
    private lateinit var useAnotherServerCheckbox: CheckBox
    private lateinit var useAnotherServerDescription: TextView
    private lateinit var instantDeliveryBox: View
    private lateinit var instantDeliveryCheckbox: CheckBox
    private lateinit var instantDeliveryDescription: View
    private lateinit var subscribeButton: Button

    // Login page
    private lateinit var users: List<User>
    private lateinit var usersSpinner: Spinner
    private lateinit var usernameText: TextInputEditText
    private lateinit var passwordText: TextInputEditText
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginError: TextView

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
        topicNameText = view.findViewById(R.id.add_dialog_topic_text)
        baseUrlLayout = view.findViewById(R.id.add_dialog_base_url_layout)
        baseUrlText = view.findViewById(R.id.add_dialog_base_url_text)
        instantDeliveryBox = view.findViewById(R.id.add_dialog_instant_delivery_box)
        instantDeliveryCheckbox = view.findViewById(R.id.add_dialog_instant_delivery_checkbox)
        instantDeliveryDescription = view.findViewById(R.id.add_dialog_instant_delivery_description)
        useAnotherServerCheckbox = view.findViewById(R.id.add_dialog_use_another_server_checkbox)
        useAnotherServerDescription = view.findViewById(R.id.add_dialog_use_another_server_description)

        // Fields for "login page"
        usersSpinner = view.findViewById(R.id.add_dialog_login_users_spinner)
        usernameText = view.findViewById(R.id.add_dialog_login_username)
        passwordText = view.findViewById(R.id.add_dialog_login_password)
        loginProgress = view.findViewById(R.id.add_dialog_login_progress)
        loginError = view.findViewById(R.id.add_dialog_login_error)

        // Set "Use another server" description based on flavor
        useAnotherServerDescription.text = if (BuildConfig.FIREBASE_AVAILABLE) {
            getString(R.string.add_dialog_use_another_server_description)
        } else {
            getString(R.string.add_dialog_use_another_server_description_noinstant)
        }

        // Base URL dropdown behavior; Oh my, why is this so complicated?!
        val toggleEndIcon = {
            if (baseUrlText.text.isNotEmpty()) {
                baseUrlLayout.setEndIconDrawable(R.drawable.ic_cancel_gray_24dp)
            } else if (baseUrls.isEmpty()) {
                baseUrlLayout.setEndIconDrawable(0)
            } else {
                baseUrlLayout.setEndIconDrawable(R.drawable.ic_drop_down_gray_24dp)
            }
        }
        baseUrlLayout.setEndIconOnClickListener {
            if (baseUrlText.text.isNotEmpty()) {
                baseUrlText.text.clear()
                if (baseUrls.isEmpty()) {
                    baseUrlLayout.setEndIconDrawable(0)
                } else {
                    baseUrlLayout.setEndIconDrawable(R.drawable.ic_drop_down_gray_24dp)
                }
            } else if (baseUrlText.text.isEmpty() && baseUrls.isNotEmpty()) {
                baseUrlLayout.setEndIconDrawable(R.drawable.ic_drop_up_gray_24dp)
                baseUrlText.showDropDown()
            }
        }
        baseUrlText.setOnDismissListener { toggleEndIcon() }
        baseUrlText.addTextChangedListener(object : TextWatcher {
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
                baseUrlText.threshold = 1
                baseUrlText.setAdapter(adapter)
                if (baseUrls.count() == 1) {
                    baseUrlLayout.setEndIconDrawable(R.drawable.ic_cancel_gray_24dp)
                    baseUrlText.setText(baseUrls.first())
                } else if (baseUrls.count() > 1) {
                    baseUrlLayout.setEndIconDrawable(R.drawable.ic_drop_down_gray_24dp)
                } else {
                    baseUrlLayout.setEndIconDrawable(0)
                }
            }

            // Users dropdown
            users = repository.getUsers()
            if (users.isEmpty()) {
                usersSpinner.visibility = View.GONE
            } else {
                val spinnerEntries = users.toMutableList()
                spinnerEntries.add(0, User(0, "Create new", "")) // FIXME
                usersSpinner.adapter = ArrayAdapter(requireActivity(), R.layout.fragment_add_dialog_dropdown_item, spinnerEntries)
            }
        }

        // Show/hide based on flavor
        instantDeliveryBox.visibility = if (BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE

        // Show/hide spinner and username/password fields
        usersSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    usernameText.visibility = View.VISIBLE
                    passwordText.visibility = View.VISIBLE
                } else {
                    usernameText.visibility = View.GONE
                    passwordText.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // This should not happen, ha!
            }
        }

        // Build dialog
        val alert = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.add_dialog_button_subscribe) { _, _ ->
                // This will be overridden below to avoid closing the dialog immediately
            }
            .setNegativeButton(R.string.add_dialog_button_cancel) { _, _ ->
                dialog?.cancel()
            }
            .create()

        // Add logic to disable "Subscribe" button on invalid input
        alert.setOnShowListener {
            val dialog = it as AlertDialog

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
            topicNameText.addTextChangedListener(textWatcher)
            baseUrlText.addTextChangedListener(textWatcher)
            instantDeliveryCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) instantDeliveryDescription.visibility = View.VISIBLE
                else instantDeliveryDescription.visibility = View.GONE
            }
            useAnotherServerCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    useAnotherServerDescription.visibility = View.VISIBLE
                    baseUrlLayout.visibility = View.VISIBLE
                    instantDeliveryBox.visibility = View.GONE
                    instantDeliveryDescription.visibility = View.GONE
                } else {
                    useAnotherServerDescription.visibility = View.GONE
                    baseUrlLayout.visibility = View.GONE
                    instantDeliveryBox.visibility = if (BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE
                    if (instantDeliveryCheckbox.isChecked) instantDeliveryDescription.visibility = View.VISIBLE
                    else instantDeliveryDescription.visibility = View.GONE
                }
                validateInput()
            }
        }

        return alert
    }

    private fun subscribeButtonClick() {
        val topic = topicNameText.text.toString()
        val baseUrl = getBaseUrl()
        if (subscribeView.visibility == View.VISIBLE) {
            checkAnonReadAndMaybeShowLogin(baseUrl, topic)
        } else if (loginView.visibility == View.VISIBLE) {
            checkAuthAndMaybeDismiss(baseUrl, topic)
        }
    }

    private fun checkAnonReadAndMaybeShowLogin(baseUrl: String, topic: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Checking anonymous read access to topic ${topicUrl(baseUrl, topic)}")
            val authorized = api.checkAnonTopicRead(baseUrl, topic)
            if (authorized) {
                Log.d(TAG, "Anonymous access granted to topic ${topicUrl(baseUrl, topic)}")
                dismiss(authUserId = null)
            } else {
                Log.w(TAG, "Anonymous access not allowed to topic ${topicUrl(baseUrl, topic)}, showing login dialog")
                requireActivity().runOnUiThread {
                    subscribeView.visibility = View.GONE
                    loginError.visibility = View.INVISIBLE
                    loginProgress.visibility = View.INVISIBLE
                    loginView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun checkAuthAndMaybeDismiss(baseUrl: String, topic: String) {
        loginProgress.visibility = View.VISIBLE
        loginError.visibility = View.INVISIBLE
        val existingUser = usersSpinner.selectedItem != null && usersSpinner.selectedItem is User && usersSpinner.selectedItemPosition > 0
        val user = if (existingUser) {
            usersSpinner.selectedItem as User
        } else {
            User(
                id = Random.nextLong(),
                username = usernameText.text.toString(),
                password = passwordText.text.toString()
            )
        }
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Checking read access for user ${user.username} to topic ${topicUrl(baseUrl, topic)}")
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
                    loginError.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun validateInput() = lifecycleScope.launch(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val topic = topicNameText.text.toString()
        val subscription = repository.getSubscription(baseUrl, topic)

        activity?.let {
            it.runOnUiThread {
                if (subscription != null || DISALLOWED_TOPICS.contains(topic)) {
                    subscribeButton.isEnabled = false
                } else if (useAnotherServerCheckbox.isChecked) {
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
            val topic = topicNameText.text.toString()
            val baseUrl = getBaseUrl()
            val instant = if (!BuildConfig.FIREBASE_AVAILABLE || useAnotherServerCheckbox.isChecked) {
                true
            } else {
                instantDeliveryCheckbox.isChecked
            }
            subscribeListener.onSubscribe(topic, baseUrl, instant, authUserId = authUserId)
            dialog?.dismiss()
        }
    }

    private fun getBaseUrl(): String {
        return if (useAnotherServerCheckbox.isChecked) {
            baseUrlText.text.toString()
        } else {
            getString(R.string.app_base_url)
        }
    }

    companion object {
        const val TAG = "NtfyAddFragment"
        private val DISALLOWED_TOPICS = listOf("docs", "static")
    }
}
