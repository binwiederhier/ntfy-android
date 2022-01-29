package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import io.heckel.ntfy.R

class UserFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_user_dialog, null)

        val addMode = false // FIXME
        val positiveButtonTextResId = if (addMode) R.string.user_dialog_button_add else R.string.user_dialog_button_save
        val titleText = view.findViewById(R.id.user_dialog_title) as TextView
        titleText.text = if (addMode) {
            getString(R.string.user_dialog_title_add)
        } else {
            getString(R.string.user_dialog_title_edit)
        }

        // Build dialog
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(positiveButtonTextResId) { _, _ ->
                // This will be overridden below to avoid closing the dialog immediately
            }
            .setNegativeButton(R.string.user_dialog_button_cancel) { _, _ ->
                // This will be overridden below
            }
            .setNeutralButton(R.string.user_dialog_button_delete)  { _, _ ->
                // This will be overridden below
            }
            .create()

        // Show keyboard when the dialog is shown (see https://stackoverflow.com/a/19573049/1440785)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        // Add logic to disable "Subscribe" button on invalid input
        dialog.setOnShowListener {

        }

        return dialog
    }

    companion object {
        const val TAG = "NtfyUserFragment"
    }
}
