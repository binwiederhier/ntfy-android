package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.heckel.ntfy.R

class HeaderFragment : DialogFragment() {
    private var baseUrl: String? = null
    private var customHeaders: MutableMap<String, String> = mutableMapOf()
    private lateinit var listener: HeaderDialogListener

    interface HeaderDialogListener {
        fun onHeadersChanged(dialog: DialogFragment, baseUrl: String, headers: Map<String, String>)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = activity as HeaderDialogListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Reconstruct headers
        baseUrl = arguments?.getString(BUNDLE_BASE_URL)
        val headersJson = arguments?.getString(BUNDLE_HEADERS)

        // Parse headers from JSON
        if (headersJson != null) {
            try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                customHeaders = Gson().fromJson<Map<String, String>>(headersJson, type)?.toMutableMap() ?: mutableMapOf()
            } catch (e: Exception) {
                customHeaders = mutableMapOf()
            }
        }

        return showHeadersListDialog()
    }

    private fun showHeadersListDialog(): Dialog {
        val headerNames = customHeaders.keys.toList()
        val items = headerNames.map { name ->
            "$name: ${redactHeaderValue(customHeaders[name] ?: "")}"
        }.toTypedArray()

        val dialogItems = items + getString(R.string.custom_headers_add_header)

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.header_dialog_title, baseUrl ?: ""))
            .setItems(dialogItems) { _, which ->
                if (which < headerNames.size) {
                    // Edit existing header
                    val headerName = headerNames[which]
                    showEditHeaderDialog(headerName, customHeaders[headerName] ?: "")
                } else {
                    // Add new header
                    showAddHeaderDialog()
                }
            }
            .setPositiveButton(R.string.header_dialog_button_save) { _, _ ->
                saveClicked()
            }
            .setNegativeButton(R.string.user_dialog_button_cancel, null)
            .create()
    }

    private fun saveClicked() {
        if (!this::listener.isInitialized || baseUrl == null) return
        listener.onHeadersChanged(this, baseUrl!!, customHeaders)
    }

    private fun showAddHeaderDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_custom_header, null)

        val headerNameEdit = dialogView.findViewById<TextInputEditText>(R.id.header_name)
        val headerValueEdit = dialogView.findViewById<TextInputEditText>(R.id.header_value)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_headers_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.custom_headers_add) { _, _ ->
                val name = headerNameEdit.text.toString().trim()
                val value = headerValueEdit.text.toString().trim()

                if (validateHeaderName(name)) {
                    customHeaders[name] = value
                    // Refresh the list dialog
                    dismiss()
                    showHeadersListDialog().show()
                } else {
                    showInvalidHeaderDialog()
                }
            }
            .setNegativeButton(R.string.user_dialog_button_cancel) { _, _ ->
                // Go back to list
                dismiss()
                showHeadersListDialog().show()
            }
            .show()
    }

    private fun showEditHeaderDialog(originalName: String, originalValue: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_custom_header, null)

        val headerNameEdit = dialogView.findViewById<TextInputEditText>(R.id.header_name)
        val headerValueEdit = dialogView.findViewById<TextInputEditText>(R.id.header_value)

        headerNameEdit.setText(originalName)
        headerValueEdit.setText(originalValue)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_headers_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.custom_headers_save) { _, _ ->
                val name = headerNameEdit.text.toString().trim()
                val value = headerValueEdit.text.toString().trim()

                if (validateHeaderName(name)) {
                    if (originalName != name) {
                        customHeaders.remove(originalName)
                    }
                    customHeaders[name] = value
                    // Refresh the list dialog
                    dismiss()
                    showHeadersListDialog().show()
                } else {
                    showInvalidHeaderDialog()
                }
            }
            .setNeutralButton(R.string.custom_headers_delete) { _, _ ->
                customHeaders.remove(originalName)
                // Refresh the list dialog
                dismiss()
                showHeadersListDialog().show()
            }
            .setNegativeButton(R.string.user_dialog_button_cancel) { _, _ ->
                // Go back to list
                dismiss()
                showHeadersListDialog().show()
            }
            .show()
    }

    private fun showInvalidHeaderDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_headers_error_title)
            .setMessage(R.string.custom_headers_invalid_name)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun validateHeaderName(name: String): Boolean {
        if (name.isEmpty()) return false
        val regex = Regex("^[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9]$|^[A-Za-z0-9]$")
        return regex.matches(name)
    }

    private fun redactHeaderValue(value: String): String {
        return when {
            value.isEmpty() -> "(empty)"
            value.length <= 3 -> "•".repeat(value.length)
            else -> "•".repeat(8)
        }
    }

    companion object {
        const val TAG = "NtfyHeaderFragment"
        private const val BUNDLE_BASE_URL = "baseUrl"
        private const val BUNDLE_HEADERS = "headers"

        fun newInstance(baseUrl: String, headers: String?): HeaderFragment {
            val fragment = HeaderFragment()
            val args = Bundle()
            args.putString(BUNDLE_BASE_URL, baseUrl)
            args.putString(BUNDLE_HEADERS, headers)
            fragment.arguments = args
            return fragment
        }
    }
}
