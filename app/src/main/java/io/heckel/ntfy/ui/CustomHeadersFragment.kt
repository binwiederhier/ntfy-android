package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CustomHeadersFragment : PreferenceFragmentCompat() {
    private lateinit var repository: Repository
    private lateinit var serviceManager: SubscriberServiceManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.custom_headers_preferences, rootKey)

        repository = Repository.getInstance(requireActivity())
        serviceManager = SubscriberServiceManager(requireActivity())

        loadCustomHeaders()

        // Add "Add Header" button
        val addHeaderPref: Preference? = findPreference("add_custom_header")
        addHeaderPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showAddHeaderDialog()
            true
        }
    }

    private fun loadCustomHeaders() {
        // Clear existing headers (except the add button)
        val addHeaderPref = findPreference<Preference>("add_custom_header")
        preferenceScreen.removeAll()
        addHeaderPref?.let { preferenceScreen.addPreference(it) }

        // Add current headers
        val headers = repository.getCustomHeaders()
        headers.forEach { (name, value) ->
            addHeaderPreference(name, value)
        }
    }

    private fun addHeaderPreference(name: String, value: String) {
        val preference = Preference(requireContext())
        preference.key = "header_$name"
        preference.title = name
        preference.summary = "••••••••" // Always hide all values

        preference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showEditHeaderDialog(name, value)
            true
        }

        // Insert before the "Add Header" button
        val addHeaderIndex = preferenceScreen.preferenceCount - 1
        preferenceScreen.addPreference(preference)
        if (addHeaderIndex >= 0) {
            preference.order = addHeaderIndex
        }
    }

    private fun showAddHeaderDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_header, null)
        val nameEdit = dialogView.findViewById<EditText>(R.id.header_name)
        val valueEdit = dialogView.findViewById<EditText>(R.id.header_value)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_headers_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.custom_headers_add) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val value = valueEdit.text.toString().trim()

                if (name.isNotEmpty() && value.isNotEmpty()) {
                    if (isValidHeaderName(name)) {
                        addHeader(name, value)
                    } else {
                        showErrorDialog(getString(R.string.custom_headers_invalid_name))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditHeaderDialog(currentName: String, currentValue: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_header, null)
        val nameEdit = dialogView.findViewById<EditText>(R.id.header_name)
        val valueEdit = dialogView.findViewById<EditText>(R.id.header_value)

        nameEdit.setText(currentName)
        valueEdit.setText(currentValue)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_headers_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.custom_headers_save) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val value = valueEdit.text.toString().trim()

                if (name.isNotEmpty() && value.isNotEmpty()) {
                    if (isValidHeaderName(name)) {
                        updateHeader(currentName, name, value)
                    } else {
                        showErrorDialog(getString(R.string.custom_headers_invalid_name))
                    }
                }
            }
            .setNeutralButton(R.string.custom_headers_delete) { _, _ ->
                showDeleteConfirmDialog(currentName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_headers_delete_title)
            .setMessage(getString(R.string.custom_headers_delete_message, name))
            .setPositiveButton(R.string.custom_headers_delete) { _, _ ->
                deleteHeader(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_headers_error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun addHeader(name: String, value: String) {
        val headers = repository.getCustomHeaders().toMutableMap()
        headers[name] = value
        repository.setCustomHeaders(headers)
        loadCustomHeaders()
        restartService()
    }

    private fun updateHeader(oldName: String, newName: String, value: String) {
        val headers = repository.getCustomHeaders().toMutableMap()
        headers.remove(oldName)
        headers[newName] = value
        repository.setCustomHeaders(headers)
        loadCustomHeaders()
        restartService()
    }

    private fun deleteHeader(name: String) {
        val headers = repository.getCustomHeaders().toMutableMap()
        headers.remove(name)
        repository.setCustomHeaders(headers)
        loadCustomHeaders()
        restartService()
    }

    private fun isValidHeaderName(name: String): Boolean {
        // Basic header name validation according to RFC 7230
        return name.matches(Regex("^[!#$%&'*+\\-.0-9A-Z^_`a-z|~]+$"))
    }

    private fun restartService() {
        serviceManager.restart()
    }
}
