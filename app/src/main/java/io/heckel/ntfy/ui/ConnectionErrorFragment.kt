package io.heckel.ntfy.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ConnectionDetails
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.copyToClipboard
import io.heckel.ntfy.util.shortUrl

class ConnectionErrorFragment : DialogFragment() {
    private lateinit var repository: Repository
    private var connectionDetails: Map<String, ConnectionDetails> = emptyMap()
    private var selectedBaseUrl: String? = null
    private var filterBaseUrl: String? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var serverLayout: TextInputLayout
    private lateinit var serverDropdown: AutoCompleteTextView
    private lateinit var descriptionTextView: TextView
    private lateinit var errorTextView: TextView
    private lateinit var countdownTextView: TextView
    private lateinit var detailsScrollView: HorizontalScrollView
    private lateinit var stackTraceTextView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Get optional baseUrl filter from arguments
        filterBaseUrl = arguments?.getString(ARG_BASE_URL)

        // Dependencies
        repository = Repository.getInstance(requireContext())
        
        // Get connection details with errors, optionally filtered by baseUrl
        val allDetails = repository.getConnectionDetails()
        connectionDetails = if (filterBaseUrl != null) {
            allDetails.filterKeys { it == filterBaseUrl }.filterValues { it.hasError() }
        } else {
            allDetails.filterValues { it.hasError() }
        }

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_connection_error_dialog, null)

        // Setup toolbar
        toolbar = view.findViewById(R.id.connection_error_dialog_toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.connection_error_dialog_action_retry -> {
                    // Retry all base URLs with errors
                    connectionDetails.filter { it.value.hasError() }.keys.forEach { baseUrl ->
                        repository.incrementConnectionForceReconnectVersion(baseUrl)
                    }
                    SubscriberServiceManager.refresh(requireContext())
                    true
                }
                R.id.connection_error_dialog_action_copy -> {
                    copyErrorToClipboard()
                    true
                }
                else -> false
            }
        }

        // Tint menu icons to match toolbar text color
        val iconColor = MaterialColors.getColor(requireContext(), R.attr.colorOnSurface, Color.BLACK)
        toolbar.menu.findItem(R.id.connection_error_dialog_action_retry)?.icon?.setTint(iconColor)
        toolbar.menu.findItem(R.id.connection_error_dialog_action_copy)?.icon?.setTint(iconColor)

        // Get view references
        serverLayout = view.findViewById(R.id.connection_error_dialog_server_layout)
        serverDropdown = view.findViewById(R.id.connection_error_dialog_server_dropdown)
        descriptionTextView = view.findViewById(R.id.connection_error_dialog_description)
        errorTextView = view.findViewById(R.id.connection_error_dialog_error_text)
        countdownTextView = view.findViewById(R.id.connection_error_dialog_countdown)
        detailsScrollView = view.findViewById(R.id.connection_error_dialog_details_scroll)
        stackTraceTextView = view.findViewById(R.id.connection_error_dialog_stack_trace)

        // Setup server dropdown if multiple errors
        val baseUrls = connectionDetails.keys.toList()
        if (baseUrls.size > 1) {
            serverLayout.visibility = View.VISIBLE
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, baseUrls)
            serverDropdown.setAdapter(adapter)
            serverDropdown.setText(baseUrls.first(), false)
            serverDropdown.setOnItemClickListener { _, _, position, _ ->
                selectedBaseUrl = baseUrls[position]
                updateErrorDisplay()
            }
        } else {
            serverLayout.visibility = View.GONE
        }

        // Select first error by default
        selectedBaseUrl = baseUrls.firstOrNull()
        updateErrorDisplay()

        // Observe connection details to update when errors change
        repository.getConnectionDetailsLiveData().observe(this) { details ->
            connectionDetails = if (filterBaseUrl != null) {
                details.filterKeys { it == filterBaseUrl }.filterValues { it.hasError() }
            } else {
                details.filterValues { it.hasError() }
            }
            
            // Close dialog if no more errors
            if (connectionDetails.isEmpty()) {
                dismiss()
                return@observe
            }
            
            // Update dropdown if the list of errored URLs changed
            val baseUrls = connectionDetails.keys.toList()
            updateServerDropdown(baseUrls)
            
            // If selected URL no longer has an error, switch to first available
            if (selectedBaseUrl == null || !connectionDetails.containsKey(selectedBaseUrl)) {
                selectedBaseUrl = baseUrls.firstOrNull()
                if (baseUrls.size > 1) {
                    serverDropdown.setText(selectedBaseUrl ?: "", false)
                }
            }
            
            updateErrorDisplay()
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
        // Start countdown timer
        handler.post(countdownRunnable)
    }

    override fun onStop() {
        super.onStop()
        // Stop countdown timer
        handler.removeCallbacks(countdownRunnable)
    }

    private fun updateServerDropdown(baseUrls: List<String>) {
        if (baseUrls.size > 1) {
            serverLayout.visibility = View.VISIBLE
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, baseUrls)
            serverDropdown.setAdapter(adapter)
            serverDropdown.setOnItemClickListener { _, _, position, _ ->
                selectedBaseUrl = baseUrls[position]
                updateErrorDisplay()
            }
        } else {
            serverLayout.visibility = View.GONE
        }
    }

    private fun updateErrorDisplay() {
        val baseUrl = selectedBaseUrl ?: return
        descriptionTextView.text = getString(R.string.connection_error_dialog_message, baseUrl)
        
        val details = connectionDetails[baseUrl]
        if (details != null && details.hasError()) {
            errorTextView.text = when {
                details.isConnectionRefused() -> getString(R.string.connection_error_dialog_connection_refused)
                details.isWebSocketNotSupported() -> getString(R.string.connection_error_dialog_websocket_not_supported)
                details.isNotAuthorized() -> getString(R.string.connection_error_dialog_not_authorized)
                else -> getErrorDisplayText(details.error)
            }
            val stackTrace = details.getStackTraceString()
            if (stackTrace.isNotEmpty()) {
                stackTraceTextView.text = stackTrace
                detailsScrollView.visibility = View.VISIBLE
            } else {
                detailsScrollView.visibility = View.GONE
            }
        } else {
            errorTextView.visibility = View.GONE
            detailsScrollView.visibility = View.GONE
        }
        updateCountdown()
    }

    private fun getErrorDisplayText(error: Throwable?): String {
        if (error == null) {
            return "" // This should not happen
        }
        val message = error.message
        if (!message.isNullOrBlank()) {
            return message
        }
        // If no message, return the simple class name (e.g., "IOException")
        return error.javaClass.simpleName
    }

    private fun updateCountdown() {
        val details = selectedBaseUrl?.let { connectionDetails[it] }
        if (details != null && details.nextRetryTime > 0) {
            val remainingMillis = details.nextRetryTime - System.currentTimeMillis()
            if (remainingMillis > 0) {
                val remainingSeconds = (remainingMillis / 1000).toInt()
                countdownTextView.text = getString(R.string.connection_error_dialog_retry_countdown, remainingSeconds)
                countdownTextView.visibility = View.VISIBLE
            } else {
                countdownTextView.text = getString(R.string.connection_error_dialog_retrying)
                countdownTextView.visibility = View.VISIBLE
            }
        } else {
            countdownTextView.visibility = View.GONE
        }
    }

    private fun copyErrorToClipboard() {
        val baseUrl = selectedBaseUrl ?: return
        val details = connectionDetails[baseUrl] ?: return
        val text = buildString {
            appendLine("Server: $baseUrl")
            appendLine("Error: ${getErrorDisplayText(details.error)}")
            appendLine()
            appendLine("Stack trace:")
            append(details.getStackTraceString().ifEmpty { "No stack trace available" })
        }
        copyToClipboard(requireContext(), "connection error", text)
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
