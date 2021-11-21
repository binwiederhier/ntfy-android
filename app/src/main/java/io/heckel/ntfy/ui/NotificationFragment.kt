package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Database
import io.heckel.ntfy.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationFragment : DialogFragment() {
    private lateinit var repository: Repository
    private lateinit var settingsListener: NotificationSettingsListener

    interface NotificationSettingsListener {
        fun onNotificationSettingsChanged(mutedUntil: Long)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        settingsListener = activity as NotificationSettingsListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Dependencies
        val database = Database.getInstance(activity!!.applicationContext)
        repository = Repository.getInstance(database.subscriptionDao(), database.notificationDao())

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.notification_dialog_fragment, null)
        // topicNameText = view.findViewById(R.id.add_dialog_topic_text) as TextInputEditText

        // Build dialog
        val alert = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.notification_dialog_save) { _, _ ->
                ///
                settingsListener.onNotificationSettingsChanged(0L)
            }
            .setNegativeButton(R.string.notification_dialog_cancel) { _, _ ->
                dialog?.cancel()
            }
            .create()

        // Add logic to disable "Subscribe" button on invalid input
        alert.setOnShowListener {
            val dialog = it as AlertDialog
            ///
        }

        return alert
    }


    companion object {
        const val TAG = "NtfyNotificationFragment"
    }
}
