package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.validUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserFragment : DialogFragment() {
    private var user: User? = null
    private lateinit var baseUrlsInUse: ArrayList<String>
    private lateinit var listener: UserDialogListener
    private lateinit var repository: Repository

    private lateinit var toolbar: MaterialToolbar
    private lateinit var saveMenuItem: MenuItem
    private lateinit var deleteMenuItem: MenuItem
    private lateinit var descriptionView: TextView
    private lateinit var baseUrlViewLayout: TextInputLayout
    private lateinit var baseUrlView: TextInputEditText
    private lateinit var usernameView: TextInputEditText
    private lateinit var passwordView: TextInputEditText

    interface UserDialogListener {
        fun onAddUser(dialog: DialogFragment, user: User)
        fun onUpdateUser(dialog: DialogFragment, user: User)
        fun onDeleteUser(dialog: DialogFragment, baseUrl: String)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = activity as UserDialogListener
        repository = Repository.getInstance(context)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

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

        // Setup toolbar
        toolbar = view.findViewById(R.id.user_dialog_toolbar)
        toolbar.setNavigationOnClickListener {
            dismiss()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.user_dialog_action_save -> {
                    saveClicked()
                    true
                }
                R.id.user_dialog_action_delete -> {
                    if (this::listener.isInitialized) {
                        listener.onDeleteUser(this, user!!.baseUrl)
                    }
                    dismiss()
                    true
                }
                else -> false
            }
        }
        saveMenuItem = toolbar.menu.findItem(R.id.user_dialog_action_save)
        deleteMenuItem = toolbar.menu.findItem(R.id.user_dialog_action_delete)

        // Setup views
        descriptionView = view.findViewById(R.id.user_dialog_description)
        baseUrlViewLayout = view.findViewById(R.id.user_dialog_base_url_layout)
        baseUrlView = view.findViewById(R.id.user_dialog_base_url)
        usernameView = view.findViewById(R.id.user_dialog_username)
        passwordView = view.findViewById(R.id.user_dialog_password)

        if (user == null) {
            toolbar.setTitle(R.string.user_dialog_title_add)
            descriptionView.text = getString(R.string.user_dialog_description_add)
            baseUrlViewLayout.visibility = View.VISIBLE
            passwordView.hint = getString(R.string.user_dialog_password_hint_add)
            saveMenuItem.setTitle(R.string.user_dialog_button_add)
            deleteMenuItem.isVisible = false
        } else {
            toolbar.setTitle(R.string.user_dialog_title_edit)
            descriptionView.text = getString(R.string.user_dialog_description_edit)
            baseUrlViewLayout.visibility = View.GONE
            usernameView.setText(user!!.username)
            passwordView.hint = getString(R.string.user_dialog_password_hint_edit)
            saveMenuItem.setTitle(R.string.common_button_save)
            deleteMenuItem.isVisible = true
        }

        // Validate input when typing
        val textWatcher = AfterChangedTextWatcher {
            validateInput()
        }
        baseUrlView.addTextChangedListener(textWatcher)
        usernameView.addTextChangedListener(textWatcher)
        passwordView.addTextChangedListener(textWatcher)

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
        val focusView = if (user != null) usernameView else baseUrlView
        focusView.postDelayed({
            focusView.requestFocus()
            if (user != null && usernameView.text != null) {
                usernameView.setSelection(usernameView.text!!.length)
            }
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(focusView, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
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
        dismiss()
    }

    private fun validateInput() {
        if (!this::saveMenuItem.isInitialized) return // As per crash seen in Google Play

        val baseUrl = baseUrlView.text?.toString() ?: ""
        val username = usernameView.text?.toString() ?: ""
        val password = passwordView.text?.toString() ?: ""
        
        // Clear previous errors
        baseUrlViewLayout.error = null
        
        if (user == null) {
            CoroutineScope(Dispatchers.Main).launch {
                val hasAuthorizationHeader = hasAuthorizationHeader(baseUrl)
                if (hasAuthorizationHeader) {
                    baseUrlViewLayout.error = getString(R.string.user_dialog_base_url_error_authorization_header_exists)
                }
                saveMenuItem.isEnabled = validUrl(baseUrl)
                        && !baseUrlsInUse.contains(baseUrl)
                        && !hasAuthorizationHeader
                        && username.isNotEmpty() && password.isNotEmpty()
            }
        } else {
            saveMenuItem.isEnabled = username.isNotEmpty() // Unchanged if left blank
        }
    }

    private suspend fun hasAuthorizationHeader(baseUrl: String): Boolean {
        if (!this::repository.isInitialized || !validUrl(baseUrl)) {
            return false
        }
        return withContext(Dispatchers.IO) {
            repository.getCustomHeaders(baseUrl)
                .any { it.name.equals("Authorization", ignoreCase = true) }
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
