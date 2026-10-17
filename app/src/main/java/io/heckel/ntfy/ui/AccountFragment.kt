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
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.account.AccountApi
import io.heckel.ntfy.account.AccountManager
import io.heckel.ntfy.account.FirstSyncMode
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
    private lateinit var expiredView: TextView
    private lateinit var serverUrlView: TextInputEditText
    private lateinit var usernameView: TextInputEditText
    private lateinit var passwordView: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginErrorView: TextView
    private lateinit var loggedInUserView: TextView
    private lateinit var loggedInServerView: TextView
    private lateinit var syncButton: Button

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
        expiredView = view.findViewById(R.id.account_dialog_expired)
        loggedInView = view.findViewById(R.id.account_dialog_logged_in_view)
        serverUrlView = view.findViewById(R.id.account_dialog_server_url)
        usernameView = view.findViewById(R.id.account_dialog_username)
        passwordView = view.findViewById(R.id.account_dialog_password)
        loginButton = view.findViewById(R.id.account_dialog_login_button)
        loginProgress = view.findViewById(R.id.account_dialog_login_progress)
        loginErrorView = view.findViewById(R.id.account_dialog_login_error)
        loggedInUserView = view.findViewById(R.id.account_dialog_logged_in_user)
        loggedInServerView = view.findViewById(R.id.account_dialog_logged_in_server)
        syncButton = view.findViewById(R.id.account_dialog_sync_button)

        // Login form, defaulting to the default server
        val repository = Repository.getInstance(requireContext())
        serverUrlView.setText(accountManager.baseUrl() ?: repository.getDefaultBaseUrl() ?: getString(R.string.app_base_url))
        usernameView.setText(accountManager.username() ?: "") // Set after an expired login
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
        syncButton.setOnClickListener {
            syncButton.isEnabled = false
            sync(firstSyncMode = null, afterLogin = false)
        }
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
            val focusView = if (usernameView.text.isNullOrEmpty()) usernameView else passwordView
            focusView.postDelayed({
                focusView.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(focusView, InputMethodManager.SHOW_IMPLICIT)
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
            expiredView.visibility = if (accountManager.expired()) View.VISIBLE else View.GONE
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
                sync(firstSyncMode = null, afterLogin = true)
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

    /**
     * Syncs, and asks how to reconcile the phone and the account if this is the first sync and they
     * have different topics. Backing out of that question right after logging in logs out again.
     */
    private fun sync(firstSyncMode: FirstSyncMode?, afterLogin: Boolean) {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    accountManager.sync(firstSyncMode)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), getString(R.string.account_dialog_sync_error, e.message), Toast.LENGTH_LONG).show()
                if (afterLogin) dismiss() else syncButton.isEnabled = true // Retried in the background
                return@launch
            }
            if (!isAdded) return@launch // Dialog closed meanwhile
            when (result) {
                is AccountManager.SyncResult.NeedsFirstSyncChoice -> askFirstSyncMode(result, afterLogin)
                is AccountManager.SyncResult.Done -> {
                    Toast.makeText(requireContext(), R.string.account_dialog_sync_done, Toast.LENGTH_SHORT).show()
                    if (afterLogin) dismiss() else syncButton.isEnabled = true
                }
                else -> { // Expired or logged out meanwhile
                    listener.onAccountChanged()
                    loginProgress.visibility = View.GONE
                    enableLoginForm(true)
                    updateView()
                }
            }
        }
    }

    private fun askFirstSyncMode(choice: AccountManager.SyncResult.NeedsFirstSyncChoice, afterLogin: Boolean) {
        val modes = arrayOf(FirstSyncMode.MERGE, FirstSyncMode.USE_ACCOUNT, FirstSyncMode.USE_PHONE)
        val labels = arrayOf(
            getString(R.string.account_dialog_first_sync_merge),
            getString(R.string.account_dialog_first_sync_use_account, choice.phoneOnly),
            getString(R.string.account_dialog_first_sync_use_phone, choice.accountOnly)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_dialog_first_sync_title)
            .setItems(labels) { _, which ->
                sync(modes[which], afterLogin)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancelFirstSync(afterLogin)
            }
            .setOnCancelListener {
                cancelFirstSync(afterLogin)
            }
            .show()
    }

    private fun cancelFirstSync(afterLogin: Boolean) {
        if (!afterLogin) {
            syncButton.isEnabled = true
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                accountManager.logout()
            }
            listener.onAccountChanged()
            loginProgress.visibility = View.GONE
            enableLoginForm(true)
            updateView()
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
