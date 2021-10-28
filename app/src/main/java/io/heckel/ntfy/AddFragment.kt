package io.heckel.ntfy

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText

class AddFragment(private val listener: Listener) : DialogFragment() {
    interface Listener {
        fun onAddClicked(topic: String, baseUrl: String)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            // Build root view
            val view = requireActivity().layoutInflater.inflate(R.layout.fragment_add, null)
            val topicNameText = view.findViewById(R.id.add_dialog_topic_text) as TextInputEditText
            val baseUrlText = view.findViewById(R.id.add_dialog_base_url_text) as TextInputEditText
            val useAnotherServerCheckbox = view.findViewById(R.id.add_dialog_use_another_server_checkbox) as CheckBox
            useAnotherServerCheckbox.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) baseUrlText.visibility = View.VISIBLE
                else baseUrlText.visibility = View.GONE
            }

            // Build dialog
            val alert = AlertDialog.Builder(it)
                .setView(view)
                .setPositiveButton(R.string.add_dialog_button_subscribe) { _, _ ->
                    val topic = topicNameText.text.toString()
                    val baseUrl = if (useAnotherServerCheckbox.isChecked) {
                        baseUrlText.text.toString()
                    } else {
                        getString(R.string.add_dialog_base_url_default)
                    }
                    listener.onAddClicked(topic, baseUrl)
                }
                .setNegativeButton(R.string.add_dialog_button_cancel) { _, _ ->
                    dialog?.cancel()
                }
                .create()

            // Add logic to disable "Subscribe" button on invalid input
            alert.setOnShowListener {
                val dialog = it as AlertDialog

                val subscribeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                subscribeButton.isEnabled = false

                val textWatcher = object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        if (useAnotherServerCheckbox.isChecked) {
                            subscribeButton.isEnabled = topicNameText.text.toString().isNotBlank()
                                    && "[-_A-Za-z0-9]+".toRegex().matches(topicNameText.text.toString())
                                    && baseUrlText.text.toString().isNotBlank()
                                    && "^https?://.+".toRegex().matches(baseUrlText.text.toString())
                        } else {
                            subscribeButton.isEnabled = topicNameText.text.toString().isNotBlank()
                                    && "[-_A-Za-z0-9]+".toRegex().matches(topicNameText.text.toString())
                        }
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
            }

            alert
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}
