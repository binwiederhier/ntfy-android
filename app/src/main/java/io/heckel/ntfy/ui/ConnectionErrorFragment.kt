package io.heckel.ntfy.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ConnectionError
import io.heckel.ntfy.db.Repository

class ConnectionErrorFragment : DialogFragment() {
    private lateinit var repository: Repository
    private var connectionErrors: Map<String, ConnectionError> = emptyMap()
    private var selectedBaseUrl: String? = null
    private var detailsVisible = false

    private lateinit var serverSpinner: Spinner
    private lateinit var errorTextView: TextView
    private lateinit var showDetailsTextView: TextView
    private lateinit var detailsScrollView: ScrollView
    private lateinit var stackTraceTextView: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Dependencies
        repository = Repository.getInstance(requireContext())
        connectionErrors = repository.getConnectionErrors()

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_connection_error_dialog, null)

        // Get view references
        serverSpinner = view.findViewById(R.id.connection_error_dialog_server_spinner)
        errorTextView = view.findViewById(R.id.connection_error_dialog_error_text)
        showDetailsTextView = view.findViewById(R.id.connection_error_dialog_show_details)
        detailsScrollView = view.findViewById(R.id.connection_error_dialog_details_scroll)
        stackTraceTextView = view.findViewById(R.id.connection_error_dialog_stack_trace)

        // Setup server spinner if multiple errors
        val baseUrls = connectionErrors.keys.toList()
        if (baseUrls.size > 1) {
            serverSpinner.visibility = View.VISIBLE
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, baseUrls)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            serverSpinner.adapter = adapter
            serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedBaseUrl = baseUrls[position]
                    updateErrorDisplay()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            serverSpinner.visibility = View.GONE
        }

        // Select first error by default
        selectedBaseUrl = baseUrls.firstOrNull()
        updateErrorDisplay()

        // Toggle details visibility
        showDetailsTextView.setOnClickListener {
            detailsVisible = !detailsVisible
            updateDetailsVisibility()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setPositiveButton(R.string.connection_error_dialog_dismiss) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
    }

    private fun updateErrorDisplay() {
        val error = selectedBaseUrl?.let { connectionErrors[it] }
        if (error != null) {
            errorTextView.text = error.message
            stackTraceTextView.text = error.getStackTraceString().ifEmpty { 
                getString(R.string.connection_error_dialog_no_stack_trace)
            }
        } else {
            errorTextView.text = getString(R.string.connection_error_dialog_no_error)
            stackTraceTextView.text = ""
        }
        updateDetailsVisibility()
    }

    private fun updateDetailsVisibility() {
        if (detailsVisible) {
            detailsScrollView.visibility = View.VISIBLE
            showDetailsTextView.text = getString(R.string.connection_error_dialog_hide_details)
        } else {
            detailsScrollView.visibility = View.GONE
            showDetailsTextView.text = getString(R.string.connection_error_dialog_show_details)
        }
    }

    companion object {
        const val TAG = "NtfyConnectionErrorFragment"
    }
}
