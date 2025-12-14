package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.db.CustomHeader
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.dangerButton
import io.heckel.ntfy.util.validUrl

class CustomHeaderFragment : DialogFragment() {
    private var header: CustomHeader? = null
    private lateinit var baseUrlsInUse: ArrayList<String>
    private lateinit var listener: CustomHeaderDialogListener

    private lateinit var baseUrlView: TextInputEditText
    private lateinit var headerNameView: TextInputEditText
    private lateinit var headerValueView: TextInputEditText
    private lateinit var positiveButton: Button

    interface CustomHeaderDialogListener {
        fun onAddCustomHeader(dialog: DialogFragment, header: CustomHeader)
        fun onUpdateCustomHeader(dialog: DialogFragment, oldHeader: CustomHeader, newHeader: CustomHeader)
        fun onDeleteCustomHeader(dialog: DialogFragment, header: CustomHeader)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = activity as CustomHeaderDialogListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Reconstruct header (if it is present in the bundle)
        val baseUrl = arguments?.getString(BUNDLE_BASE_URL)
        val headerName = arguments?.getString(BUNDLE_HEADER_NAME)
        val headerValue = arguments?.getString(BUNDLE_HEADER_VALUE)

        if (baseUrl != null && headerName != null && headerValue != null) {
            header = CustomHeader(baseUrl, headerName, headerValue)
        }

        // Required for validation
        baseUrlsInUse = arguments?.getStringArrayList(BUNDLE_BASE_URLS_IN_USE) ?: arrayListOf()

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_custom_header_dialog, null)

        val positiveButtonTextResId = if (header == null) R.string.custom_headers_add else R.string.custom_headers_save
        val titleView = view.findViewById(R.id.custom_header_dialog_title) as TextView
        val descriptionView = view.findViewById(R.id.custom_header_dialog_description) as TextView

        baseUrlView = view.findViewById(R.id.custom_header_dialog_base_url)
        headerNameView = view.findViewById(R.id.custom_header_dialog_name)
        headerValueView = view.findViewById(R.id.custom_header_dialog_value)

        if (header == null) {
            titleView.text = getString(R.string.custom_headers_add_title)
            descriptionView.text = getString(R.string.custom_header_dialog_description_add)
            baseUrlView.visibility = View.VISIBLE
        } else {
            titleView.text = getString(R.string.custom_headers_edit_title)
            descriptionView.text = getString(R.string.custom_header_dialog_description_edit)
            baseUrlView.visibility = View.GONE
            baseUrlView.setText(header!!.baseUrl)
            headerNameView.setText(header!!.name)
            headerValueView.setText(header!!.value)
        }

        // Build dialog
        val builder = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(positiveButtonTextResId) { _, _ ->
                saveClicked()
            }
            .setNegativeButton(R.string.user_dialog_button_cancel) { _, _ ->
                // Do nothing
            }
        if (header != null) {
            builder.setNeutralButton(R.string.custom_headers_delete)  { _, _ ->
                if (this::listener.isInitialized) {
                    listener.onDeleteCustomHeader(this, header!!)
                }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            // Delete button should be red
            if (header != null) {
                dialog
                    .getButton(AlertDialog.BUTTON_NEUTRAL)
                    .dangerButton(requireContext())
            }

            // Validate input when typing
            val textWatcher = AfterChangedTextWatcher {
                validateInput()
            }
            baseUrlView.addTextChangedListener(textWatcher)
            headerNameView.addTextChangedListener(textWatcher)
            headerValueView.addTextChangedListener(textWatcher)

            // Focus
            if (header != null) {
                headerNameView.requestFocus()
                if (headerNameView.text != null) {
                    headerNameView.setSelection(headerNameView.text!!.length)
                }
            } else {
                baseUrlView.requestFocus()
            }

            // Validate now!
            validateInput()
        }

        // Show keyboard when the dialog is shown (see https://stackoverflow.com/a/19573049/1440785)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        return dialog
    }

    private fun saveClicked() {
        if (!this::listener.isInitialized) return
        val baseUrl = baseUrlView.text?.toString() ?: ""
        val headerName = headerNameView.text?.toString()?.trim() ?: ""
        val headerValue = headerValueView.text?.toString()?.trim() ?: ""

        if (header == null) {
            val newHeader = CustomHeader(baseUrl, headerName, headerValue)
            listener.onAddCustomHeader(this, newHeader)
        } else {
            val newHeader = CustomHeader(
                if (baseUrl.isEmpty()) header!!.baseUrl else baseUrl,
                headerName,
                headerValue
            )
            listener.onUpdateCustomHeader(this, header!!, newHeader)
        }
    }

    private fun validateInput() {
        val baseUrl = baseUrlView.text?.toString() ?: ""
        val headerName = headerNameView.text?.toString()?.trim() ?: ""
        val headerValue = headerValueView.text?.toString()?.trim() ?: ""

        if (header == null) {
            // New header: baseUrl, name, and value required
            positiveButton.isEnabled = validUrl(baseUrl)
                && headerName.isNotEmpty()
                && validateHeaderName(headerName)
                && headerValue.isNotEmpty()
        } else {
            // Editing header: name and value required
            positiveButton.isEnabled = headerName.isNotEmpty()
                && validateHeaderName(headerName)
                && headerValue.isNotEmpty()
        }
    }

    private fun validateHeaderName(name: String): Boolean {
        if (name.isEmpty()) return false

        // HTTP header names should only contain ASCII letters, digits, and hyphens
        // and must not start or end with hyphens
        val regex = Regex("^[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9]$|^[A-Za-z0-9]$")
        return regex.matches(name)
    }

    companion object {
        const val TAG = "NtfyCustomHeaderFragment"
        private const val BUNDLE_BASE_URL = "baseUrl"
        private const val BUNDLE_HEADER_NAME = "headerName"
        private const val BUNDLE_HEADER_VALUE = "headerValue"
        private const val BUNDLE_BASE_URLS_IN_USE = "baseUrlsInUse"

        fun newInstance(header: CustomHeader?, baseUrlsInUse: List<String>): CustomHeaderFragment {
            val fragment = CustomHeaderFragment()
            val args = Bundle()
            args.putStringArrayList(BUNDLE_BASE_URLS_IN_USE, ArrayList(baseUrlsInUse))
            if (header != null) {
                args.putString(BUNDLE_BASE_URL, header.baseUrl)
                args.putString(BUNDLE_HEADER_NAME, header.name)
                args.putString(BUNDLE_HEADER_VALUE, header.value)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
