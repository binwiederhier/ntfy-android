package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.db.User

class UserFragment : DialogFragment() {
    private var user: User? = null

    private lateinit var baseUrlView: TextInputEditText
    private lateinit var usernameView: TextInputEditText
    private lateinit var passwordView: TextInputEditText
    private lateinit var positiveButton: Button

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Reconstruct user (if it is present in the bundle)
        val userId = arguments?.getLong(BUNDLE_USER_ID)
        val baseUrl = arguments?.getString(BUNDLE_BASE_URL)
        val username = arguments?.getString(BUNDLE_USERNAME)
        val password = arguments?.getString(BUNDLE_PASSWORD)

        if (userId != null && baseUrl != null && username != null && password != null) {
            user = User(userId, baseUrl, username, password)
        }

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_user_dialog, null)

        val positiveButtonTextResId = if (user == null) R.string.user_dialog_button_add else R.string.user_dialog_button_save
        val titleView = view.findViewById(R.id.user_dialog_title) as TextView
        val descriptionView = view.findViewById(R.id.user_dialog_description) as TextView

        baseUrlView = view.findViewById(R.id.user_dialog_base_url)
        usernameView = view.findViewById(R.id.user_dialog_username)
        passwordView = view.findViewById(R.id.user_dialog_password)

        if (user == null) {
            titleView.text = getString(R.string.user_dialog_title_add)
            descriptionView.text = getString(R.string.user_dialog_description_add)
            baseUrlView.visibility = View.VISIBLE
            passwordView.hint = getString(R.string.user_dialog_password_hint_add)
        } else {
            titleView.text = getString(R.string.user_dialog_title_edit)
            descriptionView.text = getString(R.string.user_dialog_description_edit)
            baseUrlView.visibility = View.GONE
            usernameView.setText(user!!.username)
            passwordView.hint = getString(R.string.user_dialog_password_hint_edit)
        }

        // Build dialog
        val builder = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(positiveButtonTextResId) { _, _ ->
                // This will be overridden below to avoid closing the dialog immediately
            }
            .setNegativeButton(R.string.user_dialog_button_cancel) { _, _ ->
                // This will be overridden below
            }
        if (user != null) {
            builder.setNeutralButton(R.string.user_dialog_button_delete)  { _, _ ->
                // This will be overridden below
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            // Delete button should be red
            if (user != null) {
                dialog
                    .getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setTextColor(ContextCompat.getColor(requireContext(), R.color.primaryDangerButtonColor))
            }

            // Validate input when typing
            val textWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    validateInput()
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Nothing
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    // Nothing
                }
            }
            baseUrlView.addTextChangedListener(textWatcher)
            usernameView.addTextChangedListener(textWatcher)
            passwordView.addTextChangedListener(textWatcher)

            // Validate now!
            validateInput()
        }

        // Show keyboard when the dialog is shown (see https://stackoverflow.com/a/19573049/1440785)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        return dialog
    }

    private fun validateInput() {
        val baseUrl = baseUrlView.text?.toString() ?: ""
        val username = usernameView.text?.toString() ?: ""
        val password = passwordView.text?.toString() ?: ""
        if (user == null) {
            positiveButton.isEnabled = (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                    && username.isNotEmpty() && password.isNotEmpty()
        } else {
            positiveButton.isEnabled = username.isNotEmpty() // Unchanged if left blank
        }
    }

    companion object {
        const val TAG = "NtfyUserFragment"
        private const val BUNDLE_USER_ID = "userId"
        private const val BUNDLE_BASE_URL = "baseUrl"
        private const val BUNDLE_USERNAME = "username"
        private const val BUNDLE_PASSWORD = "password"

        fun newInstance(user: User?): UserFragment {
            val fragment = UserFragment()
            val args = Bundle()
            if (user != null) {
                args.putLong(BUNDLE_USER_ID, user.id)
                args.putString(BUNDLE_BASE_URL, user.baseUrl)
                args.putString(BUNDLE_USERNAME, user.username)
                args.putString(BUNDLE_PASSWORD, user.password)
                fragment.arguments = args
            }
            return fragment
        }
    }
}
