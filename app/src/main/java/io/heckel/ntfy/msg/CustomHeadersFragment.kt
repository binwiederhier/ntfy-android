package io.heckel.ntfy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log

class CustomHeadersFragment : PreferenceFragmentCompat() {
    private lateinit var repository: Repository

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.custom_headers_preferences, rootKey)
        repository = Repository.getInstance(requireContext())

        setupAddHeaderPreference()
        loadCustomHeaders()
    }

    private fun setupAddHeaderPreference() {
        findPreference<Preference>("add_custom_header")?.setOnPreferenceClickListener {
            showAddHeaderDialog()
            true
        }
    }

    private fun loadCustomHeaders() {
        val customHeaders = repository.getCustomHeaders()
        val preferenceScreen = preferenceScreen

        // Remove existing header preferences (keep only the "Add Header" preference)
        val preferencesToRemove = mutableListOf<Preference>()
        for (i in 0 until preferenceScreen.preferenceCount) {
            val preference = preferenceScreen.getPreference(i)
            if (preference.key != "add_custom_header") {
                preferencesToRemove.add(preference)
            }
        }
        preferencesToRemove.forEach { preferenceScreen.removePreference(it) }

        // Add preferences for each custom header
        customHeaders.forEach { (name, value) ->
            val headerPreference = Preference(requireContext()).apply {
                key = "header_$name"
                title = name
                summary = redactHeaderValue(value) // Show redacted value
                setOnPreferenceClickListener {
                    showEditHeaderDialog(name, value)
                    true
                }
            }
            preferenceScreen.addPreference(headerPreference)
        }
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
                    addCustomHeader(name, value)
                } else {
                    showInvalidHeaderDialog()
                }
            }
            .setNegativeButton(R.string.user_dialog_button_cancel, null)
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
                    updateCustomHeader(originalName, name, value)
                } else {
                    showInvalidHeaderDialog()
                }
            }
            .setNeutralButton(R.string.custom_headers_delete) { _, _ ->
                showDeleteHeaderDialog(originalName)
            }
            .setNegativeButton(R.string.user_dialog_button_cancel, null)
            .show()
    }

    private fun showDeleteHeaderDialog(headerName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_headers_delete_title)
            .setMessage(getString(R.string.custom_headers_delete_message, headerName))
            .setPositiveButton(R.string.custom_headers_delete) { _, _ ->
                deleteCustomHeader(headerName)
            }
            .setNegativeButton(R.string.user_dialog_button_cancel, null)
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

        // HTTP header names should only contain ASCII letters, digits, and hyphens
        // and must not start or end with hyphens
        val regex = Regex("^[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9]$|^[A-Za-z0-9]$")
        return regex.matches(name)
    }

    private fun addCustomHeader(name: String, value: String) {
        try {
            val currentHeaders = repository.getCustomHeaders().toMutableMap()
            currentHeaders[name] = value
            repository.setCustomHeaders(currentHeaders)
            loadCustomHeaders() // Refresh the UI
            Log.d(TAG, "Added custom header: $name")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add custom header", e)
        }
    }

    private fun updateCustomHeader(originalName: String, newName: String, newValue: String) {
        try {
            val currentHeaders = repository.getCustomHeaders().toMutableMap()

            // Remove the old header if name changed
            if (originalName != newName) {
                currentHeaders.remove(originalName)
            }

            // Add/update the header
            currentHeaders[newName] = newValue

            repository.setCustomHeaders(currentHeaders)
            loadCustomHeaders() // Refresh the UI
            Log.d(TAG, "Updated custom header: $originalName -> $newName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update custom header", e)
        }
    }

    private fun deleteCustomHeader(name: String) {
        try {
            val currentHeaders = repository.getCustomHeaders().toMutableMap()
            currentHeaders.remove(name)
            repository.setCustomHeaders(currentHeaders)
            loadCustomHeaders() // Refresh the UI
            Log.d(TAG, "Deleted custom header: $name")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete custom header", e)
        }
    }

    private fun redactHeaderValue(value: String): String {
        return when {
            value.isEmpty() -> "(empty)"
            value.length <= 3 -> "•".repeat(value.length)
            else -> "•".repeat(8) // Always show 8 dots for longer values
        }
    }

    companion object {
        private const val TAG = "CustomHeadersFragment"
    }
}