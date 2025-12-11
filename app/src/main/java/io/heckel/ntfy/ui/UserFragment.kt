package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.User
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.dangerButton
import io.heckel.ntfy.util.validUrl

class UserFragment : DialogFragment() {
    private var user: User? = null
    private lateinit var baseUrlsInUse: ArrayList<String>
    private lateinit var listener: UserDialogListener

    private lateinit var baseUrlViewLayout: TextInputLayout
    private lateinit var baseUrlView: TextInputEditText
    private lateinit var usernameView: TextInputEditText
    private lateinit var passwordView: TextInputEditText
    private lateinit var positiveButton: Button

    interface UserDialogListener {
        fun onAddUser(dialog: DialogFragment, user: User)
        fun onUpdateUser(dialog: DialogFragment, user: User)
        fun onDeleteUser(dialog: DialogFragment, baseUrl: String)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = activity as UserDialogListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Reconstruct user (if it is present in the bundle)
        val baseUrl = arguments?.getString(BUNDLE_BASE_URL)
        val username = arguments?.getString(BUNDLE_USERNAME)
        val password = arguments?.getString(BUNDLE_PASSWORD)

        if (baseUrl != null && username != null && password != null) {
            user = User(baseUrl, username, password)
        }

        // Required for validation
        baseUrlsInUse = arguments?.getStringArrayList(BUNDLE_BASE_URLS_IN_USE) ?: arrayListOf()

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_user_dialog, null)

        val positiveButtonTextResId = if (user == null) R.string.user_dialog_button_add else R.string.user_dialog_button_save
        val titleView = view.findViewById<TextView>(R.id.user_dialog_title)
        val descriptionView = view.findViewById<TextView>(R.id.user_dialog_description)

        baseUrlViewLayout = view.findViewById(R.id.user_dialog_base_url_layout)
        baseUrlView = view.findViewById(R.id.user_dialog_base_url)
        usernameView = view.findViewById(R.id.user_dialog_username)
        passwordView = view.findViewById(R.id.user_dialog_password)

        if (user == null) {
            titleView.text = getString(R.string.user_dialog_title_add)
            descriptionView.text = getString(R.string.user_dialog_description_add)
            baseUrlViewLayout.visibility = View.VISIBLE
            passwordView.hint = getString(R.string.user_dialog_password_hint_add)
        } else {
            titleView.text = getString(R.string.user_dialog_title_edit)
            descriptionView.text = getString(R.string.user_dialog_description_edit)
            baseUrlViewLayout.visibility = View.GONE
            usernameView.setText(user!!.username)
            passwordView.hint = getString(R.string.user_dialog_password_hint_edit)
        }

        // Build dialog
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setPositiveButton(positiveButtonTextResId) { _, _ ->
                saveClicked()
            }
            .setNegativeButton(R.string.user_dialog_button_cancel) { _, _ ->
                // Do nothing
            }
        if (user != null) {
            builder.setNeutralButton(R.string.user_dialog_button_delete)  { _, _ ->
                if (this::listener.isInitialized) {
                    listener.onDeleteUser(this, user!!.baseUrl)
                }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            // Delete button should be red
            if (user != null) {
                dialog
                    .getButton(AlertDialog.BUTTON_NEUTRAL)
                    .dangerButton(requireContext())
            }

            // Validate input when typing
            val textWatcher = AfterChangedTextWatcher {
                validateInput()
            }
            baseUrlView.addTextChangedListener(textWatcher)
            usernameView.addTextChangedListener(textWatcher)
            passwordView.addTextChangedListener(textWatcher)

            // Focus
            if (user != null) {
                usernameView.requestFocus()
                if (usernameView.text != null) {
                    usernameView.setSelection(usernameView.text!!.length)
                }
            } else {
                baseUrlView.requestFocus()
            }

            // Validate now!
            validateInput()
        }

        // Show keyboard when the dialog is shown (see https://stackoverflow.com/a/19573049/1440785)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        return dialog
    }

    private fun saveClicked() {
        if (!this::listener.isInitialized) return
        val baseUrl = baseUrlView.text?.toString() ?: ""
        val username = usernameView.text?.toString() ?: ""
        val password = passwordView.text?.toString() ?: ""
        if (user == null) {
            user = User(baseUrl, username, password)
            listener.onAddUser(this, user!!)
        } else {
            user = if (password.isNotEmpty()) {
                user!!.copy(username = username, password = password)
            } else {
                user!!.copy(username = username)
            }
            listener.onUpdateUser(this, user!!)
        }
    }

    private fun validateInput() {
        val baseUrl = baseUrlView.text?.toString() ?: ""
        val username = usernameView.text?.toString() ?: ""
        val password = passwordView.text?.toString() ?: ""
        if (user == null) {
            positiveButton.isEnabled = validUrl(baseUrl)
                    && !baseUrlsInUse.contains(baseUrl)
                    && username.isNotEmpty() && password.isNotEmpty()
        } else {
            positiveButton.isEnabled = username.isNotEmpty() // Unchanged if left blank
        }
    }

    companion object {
        const val TAG = "NtfyUserFragment"
        private const val BUNDLE_BASE_URL = "baseUrl"
        private const val BUNDLE_USERNAME = "username"
        private const val BUNDLE_PASSWORD = "password"
        private const val BUNDLE_BASE_URLS_IN_USE = "baseUrlsInUse"

        fun newInstance(user: User?, baseUrlsInUse: ArrayList<String>): UserFragment {
            val fragment = UserFragment()
            val args = Bundle()
            args.putStringArrayList(BUNDLE_BASE_URLS_IN_USE, baseUrlsInUse)
            if (user != null) {
                args.putString(BUNDLE_BASE_URL, user.baseUrl)
                args.putString(BUNDLE_USERNAME, user.username)
                args.putString(BUNDLE_PASSWORD, user.password)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
