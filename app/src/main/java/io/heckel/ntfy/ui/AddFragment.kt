package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AddFragment(private val viewModel: SubscriptionsViewModel, private val onSubscribe: (topic: String, baseUrl: String, instant: Boolean) -> Unit) : DialogFragment() {
    private lateinit var topicNameText: TextInputEditText
    private lateinit var baseUrlText: TextInputEditText
    private lateinit var useAnotherServerCheckbox: CheckBox
    private lateinit var useAnotherServerDescription: View
    private lateinit var instantDeliveryBox: View
    private lateinit var instantDeliveryCheckbox: CheckBox
    private lateinit var instantDeliveryDescription: View
    private lateinit var subscribeButton: Button

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            // Build root view
            val view = requireActivity().layoutInflater.inflate(R.layout.add_dialog_fragment, null)
            topicNameText = view.findViewById(R.id.add_dialog_topic_text) as TextInputEditText
            baseUrlText = view.findViewById(R.id.add_dialog_base_url_text) as TextInputEditText
            instantDeliveryBox = view.findViewById(R.id.add_dialog_instant_delivery_box)
            instantDeliveryCheckbox = view.findViewById(R.id.add_dialog_instant_delivery_checkbox) as CheckBox
            instantDeliveryDescription = view.findViewById(R.id.add_dialog_instant_delivery_description)
            useAnotherServerCheckbox = view.findViewById(R.id.add_dialog_use_another_server_checkbox) as CheckBox
            useAnotherServerDescription = view.findViewById(R.id.add_dialog_use_another_server_description)

            // Build dialog
            val alert = AlertDialog.Builder(it)
                .setView(view)
                .setPositiveButton(R.string.add_dialog_button_subscribe) { _, _ ->
                    val topic = topicNameText.text.toString()
                    val baseUrl = getBaseUrl()
                    val instant = if (useAnotherServerCheckbox.isChecked) true else instantDeliveryCheckbox.isChecked
                    onSubscribe(topic, baseUrl, instant)
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
                        baseUrlText.visibility = View.VISIBLE
                        instantDeliveryBox.visibility = View.GONE
                        instantDeliveryDescription.visibility = View.GONE
                    } else {
                        useAnotherServerDescription.visibility = View.GONE
                        baseUrlText.visibility = View.GONE
                        instantDeliveryBox.visibility = View.VISIBLE
                        if (instantDeliveryCheckbox.isChecked) instantDeliveryDescription.visibility = View.VISIBLE
                        else instantDeliveryDescription.visibility = View.GONE
                    }
                    validateInput()
                }
            }

            alert
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun validateInput() = lifecycleScope.launch(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val topic = topicNameText.text.toString()
        val subscription = viewModel.get(baseUrl, topic)

        activity?.let {
            it.runOnUiThread {
                if (subscription != null) {
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
}
