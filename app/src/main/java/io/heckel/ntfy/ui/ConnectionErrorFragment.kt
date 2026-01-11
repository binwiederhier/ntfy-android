package io.heckel.ntfy.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ConnectionError
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.copyToClipboard

class ConnectionErrorFragment : DialogFragment() {
    private lateinit var repository: Repository
    private var connectionErrors: Map<String, ConnectionError> = emptyMap()
    private var selectedBaseUrl: String? = null
    private var detailsVisible = false
    private var filterBaseUrl: String? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var serverLabel: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var errorTextView: TextView
    private lateinit var showDetailsTextView: TextView
    private lateinit var detailsScrollView: HorizontalScrollView
    private lateinit var stackTraceTextView: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Get optional baseUrl filter from arguments
        filterBaseUrl = arguments?.getString(ARG_BASE_URL)

        // Dependencies
        repository = Repository.getInstance(requireContext())
        
        // Get connection errors, optionally filtered by baseUrl
        val allErrors = repository.getConnectionErrors()
        connectionErrors = if (filterBaseUrl != null) {
            allErrors.filterKeys { it == filterBaseUrl }
        } else {
            allErrors
        }

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_connection_error_dialog, null)

        // Setup toolbar
        toolbar = view.findViewById(R.id.connection_error_dialog_toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.connection_error_dialog_action_copy -> {
                    copyErrorToClipboard()
                    true
                }
                else -> false
            }
        }

        // Tint menu icons to match toolbar text color
        val iconColor = MaterialColors.getColor(requireContext(), R.attr.colorOnSurface, Color.BLACK)
        val copyMenuItem = toolbar.menu.findItem(R.id.connection_error_dialog_action_copy)
        copyMenuItem?.icon?.setTint(iconColor)

        // Get view references
        serverLabel = view.findViewById(R.id.connection_error_dialog_server_label)
        serverSpinner = view.findViewById(R.id.connection_error_dialog_server_spinner)
        errorTextView = view.findViewById(R.id.connection_error_dialog_error_text)
        showDetailsTextView = view.findViewById(R.id.connection_error_dialog_show_details)
        detailsScrollView = view.findViewById(R.id.connection_error_dialog_details_scroll)
        stackTraceTextView = view.findViewById(R.id.connection_error_dialog_stack_trace)

        // Setup server spinner if multiple errors
        val baseUrls = connectionErrors.keys.toList()
        if (baseUrls.size > 1) {
            serverLabel.visibility = View.VISIBLE
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
            serverLabel.visibility = View.GONE
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

        // Build dialog
        val dialog = Dialog(requireContext(), R.style.Theme_App_FullScreenDialog)
        dialog.setContentView(view)

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

    private fun copyErrorToClipboard() {
        val error = selectedBaseUrl?.let { connectionErrors[it] } ?: return
        val text = buildString {
            appendLine("Server: ${error.baseUrl}")
            appendLine("Error: ${error.message}")
            appendLine()
            appendLine("Stack trace:")
            append(error.getStackTraceString().ifEmpty { "No stack trace available" })
        }
        copyToClipboard(requireContext(), "connection error", text)
        Toast.makeText(context, R.string.connection_error_dialog_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "NtfyConnectionErrorFragment"
        private const val ARG_BASE_URL = "base_url"

        fun newInstance(baseUrl: String? = null): ConnectionErrorFragment {
            val fragment = ConnectionErrorFragment()
            if (baseUrl != null) {
                val args = Bundle()
                args.putString(ARG_BASE_URL, baseUrl)
                fragment.arguments = args
            }
            return fragment
        }
    }
}
