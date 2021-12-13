package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class AddFragment : DialogFragment() {
    private lateinit var repository: Repository
    private lateinit var subscribeListener: SubscribeListener

    private lateinit var topicNameText: TextInputEditText
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlText: AutoCompleteTextView
    private lateinit var useAnotherServerCheckbox: CheckBox
    private lateinit var useAnotherServerDescription: View
    private lateinit var instantDeliveryBox: View
    private lateinit var instantDeliveryCheckbox: CheckBox
    private lateinit var instantDeliveryDescription: View
    private lateinit var subscribeButton: Button

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

        // Dependencies
        val database = Database.getInstance(requireActivity().applicationContext)
        val sharedPrefs = requireActivity().getSharedPreferences(Repository.SHARED_PREFS_ID, Context.MODE_PRIVATE)
        repository = Repository.getInstance(sharedPrefs, database.subscriptionDao(), database.notificationDao())

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_add_dialog, null)
        topicNameText = view.findViewById(R.id.add_dialog_topic_text)
        baseUrlLayout = view.findViewById(R.id.add_dialog_base_url_layout)
        baseUrlText = view.findViewById(R.id.add_dialog_base_url_text)
        instantDeliveryBox = view.findViewById(R.id.add_dialog_instant_delivery_box)
        instantDeliveryCheckbox = view.findViewById(R.id.add_dialog_instant_delivery_checkbox)
        instantDeliveryDescription = view.findViewById(R.id.add_dialog_instant_delivery_description)
        useAnotherServerCheckbox = view.findViewById(R.id.add_dialog_use_another_server_checkbox)
        useAnotherServerDescription = view.findViewById(R.id.add_dialog_use_another_server_description)

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

        // Fill autocomplete for base URL
        lifecycleScope.launch(Dispatchers.IO) {
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
        }

        // Show/hide based on flavor
        instantDeliveryBox.visibility = if (BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE

        // Build dialog
        val alert = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.add_dialog_button_subscribe) { _, _ ->
                val topic = topicNameText.text.toString()
                val baseUrl = getBaseUrl()
                val instant = if (!BuildConfig.FIREBASE_AVAILABLE || useAnotherServerCheckbox.isChecked) {
                    true
                } else {
                    instantDeliveryCheckbox.isChecked
                }
                subscribeListener.onSubscribe(topic, baseUrl, instant)
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
