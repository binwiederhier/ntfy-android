package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.CustomHeader
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.validUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomHeaderFragment : DialogFragment() {
    private var header: CustomHeader? = null
    private lateinit var listener: CustomHeaderDialogListener
    private lateinit var repository: Repository

    private lateinit var toolbar: MaterialToolbar
    private lateinit var saveMenuItem: MenuItem
    private lateinit var deleteMenuItem: MenuItem
    private lateinit var descriptionView: TextView
    private lateinit var baseUrlViewLayout: TextInputLayout
    private lateinit var baseUrlView: TextInputEditText
    private lateinit var headerNameView: TextInputEditText
    private lateinit var headerValueView: TextInputEditText
    private lateinit var headerNameLayout: TextInputLayout

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
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Reconstruct header (if it is present in the bundle)
        val baseUrl = arguments?.getString(BUNDLE_BASE_URL)
        val headerName = arguments?.getString(BUNDLE_HEADER_NAME)
        val headerValue = arguments?.getString(BUNDLE_HEADER_VALUE)

        if (baseUrl != null && headerName != null && headerValue != null) {
            header = CustomHeader(baseUrl, headerName, headerValue)
        }

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_custom_header_dialog, null)

        // Setup toolbar
        toolbar = view.findViewById(R.id.custom_header_dialog_toolbar)
        toolbar.setNavigationOnClickListener {
            dismiss()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.custom_header_dialog_action_save -> {
                    saveClicked()
                    true
                }
                R.id.custom_header_dialog_action_delete -> {
                    if (this::listener.isInitialized) {
                        listener.onDeleteCustomHeader(this, header!!)
                    }
                    dismiss()
                    true
                }
                else -> false
            }
        }
        saveMenuItem = toolbar.menu.findItem(R.id.custom_header_dialog_action_save)
        deleteMenuItem = toolbar.menu.findItem(R.id.custom_header_dialog_action_delete)

        // Setup views
        descriptionView = view.findViewById(R.id.custom_header_dialog_description)
        baseUrlViewLayout = view.findViewById(R.id.custom_header_dialog_base_url_layout)
        baseUrlView = view.findViewById(R.id.custom_header_dialog_base_url)
        headerNameView = view.findViewById(R.id.custom_header_dialog_name)
        headerValueView = view.findViewById(R.id.custom_header_dialog_value)
        headerNameLayout = view.findViewById(R.id.custom_header_dialog_name_layout)

        if (header == null) {
            toolbar.setTitle(R.string.custom_headers_dialog_title_add)
            descriptionView.text = getString(R.string.custom_headers_dialog_description_add)
            baseUrlViewLayout.visibility = View.VISIBLE
            saveMenuItem.setTitle(R.string.common_button_add)
            deleteMenuItem.isVisible = false
        } else {
            toolbar.setTitle(R.string.custom_headers_dialog_title_edit)
            descriptionView.text = getString(R.string.custom_headers_dialog_description_edit)
            baseUrlViewLayout.visibility = View.GONE
            baseUrlView.setText(header!!.baseUrl)
            headerNameView.setText(header!!.name)
            headerValueView.setText(header!!.value)
            saveMenuItem.setTitle(R.string.common_button_save)
            deleteMenuItem.isVisible = true
        }

        // Validate input when typing
        val textWatcher = AfterChangedTextWatcher {
            validateInput()
        }
        baseUrlView.addTextChangedListener(textWatcher)
        headerNameView.addTextChangedListener(textWatcher)
        headerValueView.addTextChangedListener(textWatcher)

        // Build dialog
        val dialog = Dialog(requireContext(), R.style.Theme_App_FullScreenDialog)
        dialog.setContentView(view)

        // Initial validation
        validateInput()

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
        val focusView = if (header != null) headerNameView else baseUrlView
        focusView.postDelayed({
            focusView.requestFocus()
            if (header != null && headerNameView.text != null) {
                headerNameView.setSelection(headerNameView.text!!.length)
            }
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(focusView, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
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
        dismiss()
    }

    private fun validateInput() {
        if (!this::saveMenuItem.isInitialized) return // As per crash seen in Google Play

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
                saveMenuItem.isEnabled = validUrl(baseUrl)
                        && headerName.isNotEmpty()
                        && headerValue.isNotEmpty()
                        && isValid
            } else {
                // Editing header: name and value required
                saveMenuItem.isEnabled = headerName.isNotEmpty()
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
            repository.getCustomHeaders(baseUrl)
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
