package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.RadioButton
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

class NotificationFragment : DialogFragment() {
    var settingsListener: NotificationSettingsListener? = null

    private lateinit var repository: Repository
    private lateinit var muteFor30minButton: RadioButton
    private lateinit var muteFor1hButton: RadioButton
    private lateinit var muteFor2hButton: RadioButton
    private lateinit var muteFor8hButton: RadioButton
    private lateinit var muteUntilTomorrowButton: RadioButton
    private lateinit var muteForeverButton: RadioButton

    interface NotificationSettingsListener {
        fun onNotificationMutedUntilChanged(mutedUntilTimestamp: Long)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (settingsListener == null) {
            settingsListener = activity as NotificationSettingsListener
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Dependencies
        repository = Repository.getInstance(requireContext())

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_notification_dialog, null)

        muteFor30minButton = view.findViewById(R.id.notification_dialog_30min)
        muteFor30minButton.setOnClickListener { onClickMinutes(30) }

        muteFor1hButton = view.findViewById(R.id.notification_dialog_1h)
        muteFor1hButton.setOnClickListener { onClickMinutes(60) }

        muteFor2hButton = view.findViewById(R.id.notification_dialog_2h)
        muteFor2hButton.setOnClickListener { onClickMinutes(2 * 60) }

        muteFor8hButton = view.findViewById(R.id.notification_dialog_8h)
        muteFor8hButton.setOnClickListener{ onClickMinutes(8 * 60) }

        muteUntilTomorrowButton = view.findViewById(R.id.notification_dialog_tomorrow)
        muteUntilTomorrowButton.setOnClickListener {
            // Duplicate code in SettingsActivity, :shrug: ...
            val date = Calendar.getInstance()
            date.add(Calendar.DAY_OF_MONTH, 1)
            date.set(Calendar.HOUR_OF_DAY, 8)
            date.set(Calendar.MINUTE, 30)
            date.set(Calendar.SECOND, 0)
            date.set(Calendar.MILLISECOND, 0)
            onClick(date.timeInMillis/1000)
        }

        muteForeverButton = view.findViewById(R.id.notification_dialog_forever)
        muteForeverButton.setOnClickListener{ onClick(Repository.MUTED_UNTIL_FOREVER) }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
    }

    private fun onClickMinutes(minutes: Int) {
        onClick(System.currentTimeMillis()/1000 + minutes * 60)
    }

    private fun onClick(mutedUntilTimestamp: Long) {
        lifecycleScope.launch(Dispatchers.Main) {
            delay(150) // Another hack: Let the animation finish before dismissing the window
            settingsListener?.onNotificationMutedUntilChanged(mutedUntilTimestamp)
            dismiss()
        }
    }

    companion object {
        const val TAG = "NtfyNotificationFragment"
    }
}
