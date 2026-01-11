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
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ConnectionError
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.copyToClipboard

class ConnectionErrorFragment : DialogFragment() {
    private lateinit var repository: Repository
    private var connectionErrors: Map<String, ConnectionError> = emptyMap()
    private var selectedBaseUrl: String? = null
    private var filterBaseUrl: String? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var serverLayout: TextInputLayout
    private lateinit var serverDropdown: AutoCompleteTextView
    private lateinit var errorTextView: TextView
    private lateinit var countdownTextView: TextView
    private lateinit var retryChip: Chip
    private lateinit var detailsChip: Chip
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
        serverLayout = view.findViewById(R.id.connection_error_dialog_server_layout)
        serverDropdown = view.findViewById(R.id.connection_error_dialog_server_dropdown)
        errorTextView = view.findViewById(R.id.connection_error_dialog_error_text)
        countdownTextView = view.findViewById(R.id.connection_error_dialog_countdown)
        retryChip = view.findViewById(R.id.connection_error_dialog_retry_chip)
        detailsChip = view.findViewById(R.id.connection_error_dialog_details_chip)
        detailsScrollView = view.findViewById(R.id.connection_error_dialog_details_scroll)
        stackTraceTextView = view.findViewById(R.id.connection_error_dialog_stack_trace)

        // Setup server dropdown if multiple errors
        val baseUrls = connectionErrors.keys.toList()
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

        // Toggle details visibility using chip checked state
        detailsChip.setOnCheckedChangeListener { _, isChecked ->
            updateDetailsVisibility(isChecked)
        }

        // Retry now button
        retryChip.setOnClickListener {
            SubscriberServiceManager.refresh(requireContext())
            dismiss()
        }

        // Observe connection errors to update countdown when it changes
        repository.getConnectionErrorsLiveData().observe(this) { errors ->
            connectionErrors = if (filterBaseUrl != null) {
                errors.filterKeys { it == filterBaseUrl }
            } else {
                errors
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
        updateDetailsVisibility(detailsChip.isChecked)
        updateCountdown()
    }

    private fun updateCountdown() {
        val error = selectedBaseUrl?.let { connectionErrors[it] }
        if (error != null && error.nextRetryTime > 0) {
            val remainingMillis = error.nextRetryTime - System.currentTimeMillis()
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

    private fun updateDetailsVisibility(visible: Boolean) {
        detailsScrollView.visibility = if (visible) View.VISIBLE else View.GONE
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
