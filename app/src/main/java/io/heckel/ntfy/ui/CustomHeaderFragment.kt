package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.CustomHeader
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.dangerButton
import io.heckel.ntfy.util.validUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomHeaderFragment : DialogFragment() {
    private var header: CustomHeader? = null
    private lateinit var listener: CustomHeaderDialogListener
    private lateinit var repository: Repository

    private lateinit var baseUrlView: TextInputEditText
    private lateinit var headerNameView: TextInputEditText
    private lateinit var headerValueView: TextInputEditText
    private lateinit var headerNameLayout: TextInputLayout
    private lateinit var positiveButton: Button

    interface CustomHeaderDialogListener {
        fun onAddCustomHeader(dialog: DialogFragment, header: CustomHeader)
        fun onUpdateCustomHeader(dialog: DialogFragment, oldHeader: CustomHeader, newHeader: CustomHeader)
        fun onDeleteCustomHeader(dialog: DialogFragment, header: CustomHeader)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = activity as CustomHeaderDialogListener
        repository = Repository.getInstance(context)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Reconstruct header (if it is present in the bundle)
        val baseUrl = arguments?.getString(BUNDLE_BASE_URL)
        val headerName = arguments?.getString(BUNDLE_HEADER_NAME)
        val headerValue = arguments?.getString(BUNDLE_HEADER_VALUE)

        if (baseUrl != null && headerName != null && headerValue != null) {
            header = CustomHeader(baseUrl, headerName, headerValue)
        }

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_custom_header_dialog, null)

        val positiveButtonTextResId = if (header == null) R.string.custom_headers_dialog_button_add else R.string.custom_headers_dialog_button_save
        val descriptionView: TextView = view.findViewById(R.id.custom_header_dialog_description)

        baseUrlView = view.findViewById(R.id.custom_header_dialog_base_url)
        headerNameView = view.findViewById(R.id.custom_header_dialog_name)
        headerValueView = view.findViewById(R.id.custom_header_dialog_value)
        headerNameLayout = view.findViewById(R.id.custom_header_dialog_name_layout)

        var title: String
        if (header == null) {
            title = getString(R.string.custom_headers_dialog_title_add)
            descriptionView.text = getString(R.string.custom_headers_dialog_description_add)
            baseUrlView.visibility = View.VISIBLE
        } else {
            title = getString(R.string.custom_headers_dialog_title_edit)
            descriptionView.text = getString(R.string.custom_headers_dialog_description_edit)
            baseUrlView.visibility = View.GONE
            baseUrlView.setText(header!!.baseUrl)
            headerNameView.setText(header!!.name)
            headerValueView.setText(header!!.value)
        }

        // Build dialog
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(view)
            .setPositiveButton(positiveButtonTextResId) { _, _ ->
                saveClicked()
            }
            .setNegativeButton(R.string.user_dialog_button_cancel) { _, _ ->
                // Do nothing
            }
        if (header != null) {
            builder.setNeutralButton(R.string.custom_headers_dialog_button_delete)  { _, _ ->
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
                    .dangerButton()
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
                baseUrl.ifEmpty { header!!.baseUrl },
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

        // Clear previous errors
        headerNameLayout.error = null

        // Validate header name and check if a user already exists for this server
        CoroutineScope(Dispatchers.Main).launch {
            var isValid = true
            val targetBaseUrl = if (header != null) header!!.baseUrl else baseUrl
            
            if (headerName.isNotEmpty()) {
                if (!validateHeaderName(headerName)) {
                    headerNameLayout.error = getString(R.string.custom_headers_dialog_error_invalid_name)
                    isValid = false
                } else if (isReservedHeader(headerName)) {
                    headerNameLayout.error = getString(R.string.custom_headers_dialog_error_reserved_name)
                    isValid = false
                } else if (isDuplicateHeader(targetBaseUrl, headerName)) {
                    headerNameLayout.error = getString(R.string.custom_headers_dialog_error_duplicate)
                    isValid = false
                } else if (headerName.equals("Authorization", ignoreCase = true) && hasUserForServer(targetBaseUrl)) {
                    headerNameLayout.error = getString(R.string.custom_headers_dialog_error_user_exists)
                    isValid = false
                }
            }
            if (header == null) {
                // New header: baseUrl, name, and value required
                positiveButton.isEnabled = validUrl(baseUrl)
                        && headerName.isNotEmpty()
                        && headerValue.isNotEmpty()
                        && isValid
            } else {
                // Editing header: name and value required
                positiveButton.isEnabled = headerName.isNotEmpty()
                        && headerValue.isNotEmpty()
                        && isValid
            }
        }
    }

    private fun validateHeaderName(name: String): Boolean {
        if (name.isEmpty()) return false

        // HTTP header names should only contain ASCII letters, digits, and hyphens
        // and must not start or end with hyphens
        val regex = Regex("^[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9]$|^[A-Za-z0-9]$")
        return regex.matches(name)
    }

    private fun isReservedHeader(name: String): Boolean {
        // These headers are already set by ntfy and cannot be overridden
        val nameLower = name.lowercase()
        val reservedHeaders = setOf(
            "user-agent",
            "host",
            "connection",
            "upgrade",
            "accept-encoding"
        )
        // Also block all WebSocket-related headers
        return reservedHeaders.contains(nameLower) || nameLower.startsWith("sec-websocket-")
    }

    private suspend fun isDuplicateHeader(baseUrl: String, headerName: String): Boolean {
        if (!this::repository.isInitialized || !validUrl(baseUrl)) {
            return false
        }
        val existingHeaders = withContext(Dispatchers.IO) {
            repository.getCustomHeadersForServer(baseUrl)
        }
        return existingHeaders.any { existingHeader ->
            // When editing, exclude the current header being edited
            val isCurrentHeader = header != null && 
                existingHeader.baseUrl == header!!.baseUrl && 
                existingHeader.name == header!!.name
            !isCurrentHeader && existingHeader.name.equals(headerName, ignoreCase = true)
        }
    }

    private suspend fun hasUserForServer(baseUrl: String): Boolean {
        if (!this::repository.isInitialized || !validUrl(baseUrl)) {
            return false
        }
        return withContext(Dispatchers.IO) {
            repository.getUser(baseUrl) != null
        }
    }

    companion object {
        const val TAG = "NtfyCustomHeaderFragment"
        private const val BUNDLE_BASE_URL = "baseUrl"
        private const val BUNDLE_HEADER_NAME = "headerName"
        private const val BUNDLE_HEADER_VALUE = "headerValue"

        fun newInstance(header: CustomHeader?): CustomHeaderFragment {
            val fragment = CustomHeaderFragment()
            val args = Bundle()
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
