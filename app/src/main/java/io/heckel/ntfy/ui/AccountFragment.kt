package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.account.AccountApi
import io.heckel.ntfy.account.AccountManager
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.validUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountFragment : DialogFragment() {
    private lateinit var accountManager: AccountManager
    private lateinit var listener: AccountDialogListener

    private lateinit var loginView: View
    private lateinit var loggedInView: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var serverUrlView: TextInputEditText
    private lateinit var usernameView: TextInputEditText
    private lateinit var passwordView: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginErrorView: TextView
    private lateinit var loggedInUserView: TextView
    private lateinit var loggedInServerView: TextView

    interface AccountDialogListener {
        fun onAccountChanged()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = activity as AccountDialogListener
        accountManager = AccountManager.getInstance(context)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_account_dialog, null)
        toolbar = view.findViewById(R.id.account_dialog_toolbar)
        toolbar.setNavigationOnClickListener {
            dismiss()
        }
        loginView = view.findViewById(R.id.account_dialog_login_view)
        loggedInView = view.findViewById(R.id.account_dialog_logged_in_view)
        serverUrlView = view.findViewById(R.id.account_dialog_server_url)
        usernameView = view.findViewById(R.id.account_dialog_username)
        passwordView = view.findViewById(R.id.account_dialog_password)
        loginButton = view.findViewById(R.id.account_dialog_login_button)
        loginProgress = view.findViewById(R.id.account_dialog_login_progress)
        loginErrorView = view.findViewById(R.id.account_dialog_login_error)
        loggedInUserView = view.findViewById(R.id.account_dialog_logged_in_user)
        loggedInServerView = view.findViewById(R.id.account_dialog_logged_in_server)

        // Login form, defaulting to the default server
        val repository = Repository.getInstance(requireContext())
        serverUrlView.setText(repository.getDefaultBaseUrl() ?: getString(R.string.app_base_url))
        val textWatcher = AfterChangedTextWatcher {
            validateInput()
        }
        serverUrlView.addTextChangedListener(textWatcher)
        usernameView.addTextChangedListener(textWatcher)
        passwordView.addTextChangedListener(textWatcher)
        loginButton.setOnClickListener {
            login()
        }

        // Account details
        view.findViewById<Button>(R.id.account_dialog_logout_button).setOnClickListener {
            confirmLogout()
        }

        // Build dialog
        val dialog = Dialog(requireContext(), R.style.Theme_App_FullScreenDialog)
        dialog.setContentView(view)
        updateView()
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
        if (!accountManager.loggedIn()) {
            // Show keyboard after the dialog is fully visible
            usernameView.postDelayed({
                usernameView.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(usernameView, InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }
    }

    private fun updateView() {
        if (accountManager.loggedIn()) {
            loginView.visibility = View.GONE
            loggedInView.visibility = View.VISIBLE
            loggedInUserView.text = accountManager.username()
            loggedInServerView.text = accountManager.baseUrl()
        } else {
            loginView.visibility = View.VISIBLE
            loggedInView.visibility = View.GONE
        }
    }

    private fun validateInput() {
        val serverUrl = serverUrlView.text?.toString() ?: ""
        val username = usernameView.text?.toString() ?: ""
        val password = passwordView.text?.toString() ?: ""
        loginButton.isEnabled = validUrl(serverUrl) && username.isNotEmpty() && password.isNotEmpty()
    }

    private fun login() {
        val serverUrl = (serverUrlView.text?.toString() ?: "").trimEnd('/')
        val username = usernameView.text?.toString() ?: ""
        val password = passwordView.text?.toString() ?: ""
        enableLoginForm(false)
        loginProgress.visibility = View.VISIBLE
        loginErrorView.visibility = View.GONE
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    accountManager.login(serverUrl, username, password)
                }
                listener.onAccountChanged()
                dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "Login failed", e)
                val message = if (e is AccountApi.UnauthorizedException) {
                    getString(R.string.account_dialog_login_error_unauthorized)
                } else {
                    getString(R.string.account_dialog_login_error, e.message)
                }
                loginProgress.visibility = View.GONE
                loginErrorView.text = message
                loginErrorView.visibility = View.VISIBLE
                enableLoginForm(true)
            }
        }
    }

    private fun enableLoginForm(enable: Boolean) {
        serverUrlView.isEnabled = enable
        usernameView.isEnabled = enable
        passwordView.isEnabled = enable
        if (enable) {
            validateInput()
        } else {
            loginButton.isEnabled = false
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.account_dialog_logout_confirm_message)
            .setPositiveButton(R.string.account_dialog_logout_button) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        accountManager.logout()
                    }
                    listener.onAccountChanged()
                    updateView()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val TAG = "NtfyAccountFragment"
    }
}
