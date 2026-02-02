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
import io.heckel.ntfy.msg.AccountManager
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AccountFragment : DialogFragment() {
    private lateinit var accountManager: AccountManager
    private lateinit var listener: AccountDialogListener
    private lateinit var appBaseUrl: String

    private lateinit var toolbar: MaterialToolbar
    private lateinit var loginView: View
    private lateinit var loggedInView: View

    // Login view
    private lateinit var serverUrlText: TextInputEditText
    private lateinit var usernameText: TextInputEditText
    private lateinit var passwordText: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginErrorText: TextView

    // Logged in view
    private lateinit var loggedInUserText: TextView
    private lateinit var loggedInServerText: TextView
    private lateinit var syncButton: Button
    private lateinit var logoutButton: Button
    private lateinit var syncProgress: ProgressBar

    interface AccountDialogListener {
        fun onAccountChanged()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = activity as AccountDialogListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        accountManager = AccountManager.getInstance(requireContext())
        appBaseUrl = getString(R.string.app_base_url)

        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_account_dialog, null)

        // Setup toolbar
        toolbar = view.findViewById(R.id.account_dialog_toolbar)
        toolbar.setNavigationOnClickListener {
            dismiss()
        }

        // Main views
        loginView = view.findViewById(R.id.account_dialog_login_view)
        loggedInView = view.findViewById(R.id.account_dialog_logged_in_view)

        // Login view elements
        serverUrlText = view.findViewById(R.id.account_dialog_server_url)
        usernameText = view.findViewById(R.id.account_dialog_username)
        passwordText = view.findViewById(R.id.account_dialog_password)
        loginButton = view.findViewById(R.id.account_dialog_login_button)
        loginProgress = view.findViewById(R.id.account_dialog_login_progress)
        loginErrorText = view.findViewById(R.id.account_dialog_login_error)

        // Logged in view elements
        loggedInUserText = view.findViewById(R.id.account_dialog_logged_in_user)
        loggedInServerText = view.findViewById(R.id.account_dialog_logged_in_server)
        syncButton = view.findViewById(R.id.account_dialog_sync_button)
        logoutButton = view.findViewById(R.id.account_dialog_logout_button)
        syncProgress = view.findViewById(R.id.account_dialog_sync_progress)

        // Set default server URL
        serverUrlText.setText(appBaseUrl)

        // Login button validation
        val textWatcher = AfterChangedTextWatcher {
            validateLoginForm()
        }
        serverUrlText.addTextChangedListener(textWatcher)
        usernameText.addTextChangedListener(textWatcher)
        passwordText.addTextChangedListener(textWatcher)

        // Login button click
        loginButton.setOnClickListener {
            performLogin()
        }

        // Sync button click
        syncButton.setOnClickListener {
            performSync()
        }

        // Logout button click
        logoutButton.setOnClickListener {
            showLogoutConfirmation()
        }

        // Build dialog
        val dialog = Dialog(requireContext(), R.style.Theme_App_FullScreenDialog)
        dialog.setContentView(view)

        // Show appropriate view
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
        if (!accountManager.isLoggedIn()) {
            usernameText.postDelayed({
                usernameText.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(usernameText, InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }
    }

    private fun updateView() {
        if (accountManager.isLoggedIn()) {
            toolbar.setTitle(R.string.settings_account_title)
            loginView.visibility = View.GONE
            loggedInView.visibility = View.VISIBLE
            loggedInUserText.text = accountManager.getUsername()
            loggedInServerText.text = accountManager.getBaseUrl()
        } else {
            toolbar.setTitle(R.string.settings_account_login_title)
            loginView.visibility = View.VISIBLE
            loggedInView.visibility = View.GONE
        }
    }

    private fun validateLoginForm() {
        val serverUrl = serverUrlText.text.toString()
        val username = usernameText.text.toString()
        val password = passwordText.text.toString()
        loginButton.isEnabled = serverUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()
    }

    private fun performLogin() {
        val serverUrl = serverUrlText.text.toString().trimEnd('/')
        val username = usernameText.text.toString()
        val password = passwordText.text.toString()

        loginProgress.visibility = View.VISIBLE
        loginErrorText.visibility = View.GONE
        enableLoginForm(false)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                accountManager.login(serverUrl, username, password)
                activity?.runOnUiThread {
                    Log.d(TAG, "Login successful")
                    listener.onAccountChanged()
                    dismiss()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Login failed", e)
                activity?.runOnUiThread {
                    loginProgress.visibility = View.GONE
                    loginErrorText.visibility = View.VISIBLE
                    loginErrorText.text = getString(R.string.settings_account_login_failed, e.message)
                    enableLoginForm(true)
                }
            }
        }
    }

    private fun enableLoginForm(enable: Boolean) {
        serverUrlText.isEnabled = enable
        usernameText.isEnabled = enable
        passwordText.isEnabled = enable
        loginButton.isEnabled = enable
    }

    private fun performSync() {
        syncProgress.visibility = View.VISIBLE
        syncButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                accountManager.syncFromRemote()
                activity?.runOnUiThread {
                    syncProgress.visibility = View.GONE
                    syncButton.isEnabled = true
                    listener.onAccountChanged()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
                activity?.runOnUiThread {
                    syncProgress.visibility = View.GONE
                    syncButton.isEnabled = true
                }
            }
        }
    }

    private fun showLogoutConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_account_logout_title)
            .setMessage(R.string.settings_account_logout_confirmation)
            .setPositiveButton(R.string.settings_account_logout_button) { _, _ ->
                performLogout()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performLogout() {
        lifecycleScope.launch(Dispatchers.IO) {
            accountManager.logout()
            activity?.runOnUiThread {
                Log.d(TAG, "Logout successful")
                listener.onAccountChanged()
                updateView()
            }
        }
    }

    companion object {
        const val TAG = "NtfyAccountFragment"

        fun newInstance(): AccountFragment {
            return AccountFragment()
        }
    }
}

