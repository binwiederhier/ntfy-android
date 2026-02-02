package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.normalizeBaseUrl
import io.heckel.ntfy.util.validBaseUrl

class DefaultServerFragment : DialogFragment() {
    private var currentUrl: String? = null
    private lateinit var listener: DefaultServerDialogListener

    private lateinit var toolbar: MaterialToolbar
    private lateinit var saveMenuItem: MenuItem
    private lateinit var resetMenuItem: MenuItem
    private lateinit var urlViewLayout: TextInputLayout
    private lateinit var urlView: TextInputEditText

    interface DefaultServerDialogListener {
        fun onDefaultServerUpdated(dialog: DialogFragment, url: String)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = activity as DefaultServerDialogListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Get current URL from arguments
        currentUrl = arguments?.getString(BUNDLE_CURRENT_URL)

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_default_server_dialog, null)

        // Setup toolbar
        toolbar = view.findViewById(R.id.default_server_dialog_toolbar)
        toolbar.setNavigationOnClickListener {
            dismiss()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.default_server_dialog_action_save -> {
                    saveClicked()
                    true
                }
                R.id.default_server_dialog_action_reset -> {
                    resetClicked()
                    true
                }
                else -> false
            }
        }
        saveMenuItem = toolbar.menu.findItem(R.id.default_server_dialog_action_save)
        resetMenuItem = toolbar.menu.findItem(R.id.default_server_dialog_action_reset)

        // Setup views
        urlViewLayout = view.findViewById(R.id.default_server_dialog_url_layout)
        urlView = view.findViewById(R.id.default_server_dialog_url)

        // Set current URL
        urlView.setText(currentUrl ?: "")

        // Show reset option if there's a current URL
        resetMenuItem.isVisible = !currentUrl.isNullOrEmpty()

        // Validate input when typing
        urlView.addTextChangedListener(AfterChangedTextWatcher {
            validateInput()
        })

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
        urlView.postDelayed({
            urlView.requestFocus()
            urlView.text?.let { text ->
                urlView.setSelection(text.length)
            }
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(urlView, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun saveClicked() {
        if (!this::listener.isInitialized) return
        val url = urlView.text?.toString() ?: ""
        val normalizedUrl = if (url.isEmpty()) "" else normalizeBaseUrl(url)
        listener.onDefaultServerUpdated(this, normalizedUrl)
        dismiss()
    }

    private fun resetClicked() {
        if (!this::listener.isInitialized) return
        listener.onDefaultServerUpdated(this, "")
        dismiss()
    }

    private fun validateInput() {
        if (!this::saveMenuItem.isInitialized) return

        val url = urlView.text?.toString() ?: ""

        // Clear previous errors
        urlViewLayout.error = null

        if (url.isEmpty()) {
            // Empty is allowed (means use default)
            saveMenuItem.isEnabled = true
        } else if (!validBaseUrl(url)) {
            // Show error for invalid URL
            urlViewLayout.error = getString(R.string.default_server_dialog_url_error_invalid)
            saveMenuItem.isEnabled = false
        } else {
            saveMenuItem.isEnabled = true
        }
    }

    companion object {
        const val TAG = "NtfyDefaultServerFragment"
        private const val BUNDLE_CURRENT_URL = "currentUrl"

        fun newInstance(currentUrl: String?): DefaultServerFragment {
            val fragment = DefaultServerFragment()
            val args = Bundle()
            args.putString(BUNDLE_CURRENT_URL, currentUrl ?: "")
            fragment.arguments = args
            return fragment
        }
    }
}
