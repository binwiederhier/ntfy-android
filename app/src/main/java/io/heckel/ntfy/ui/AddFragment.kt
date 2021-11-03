package io.heckel.ntfy.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import androidx.activity.viewModels
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AddFragment(private val viewModel: SubscriptionsViewModel, private val onSubscribe: (topic: String, baseUrl: String) -> Unit) : DialogFragment() {
    private lateinit var topicNameText: TextInputEditText
    private lateinit var baseUrlText: TextInputEditText
    private lateinit var useAnotherServerCheckbox: CheckBox
    private lateinit var subscribeButton: Button

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            // Build root view
            val view = requireActivity().layoutInflater.inflate(R.layout.add_dialog_fragment, null)
            topicNameText = view.findViewById(R.id.add_dialog_topic_text) as TextInputEditText
            baseUrlText = view.findViewById(R.id.add_dialog_base_url_text) as TextInputEditText
            useAnotherServerCheckbox = view.findViewById(R.id.add_dialog_use_another_server_checkbox) as CheckBox

            // FIXME For now, other servers are disabled
            useAnotherServerCheckbox.visibility = View.GONE

            // Build dialog
            val alert = AlertDialog.Builder(it)
                .setView(view)
                .setPositiveButton(R.string.add_dialog_button_subscribe) { _, _ ->
                    val topic = topicNameText.text.toString()
                    val baseUrl = getBaseUrl()
                    onSubscribe(topic, baseUrl)
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
                useAnotherServerCheckbox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) baseUrlText.visibility = View.VISIBLE
                    else baseUrlText.visibility = View.GONE
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
                            && "[-_A-Za-z0-9]+".toRegex().matches(topic)
                            && baseUrl.isNotBlank()
                            && "^https?://.+".toRegex().matches(baseUrl)
                } else {
                    subscribeButton.isEnabled = topic.isNotBlank()
                            && "[-_A-Za-z0-9]+".toRegex().matches(topic)
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
