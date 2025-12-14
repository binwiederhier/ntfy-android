package io.heckel.ntfy.ui

import android.widget.TextView
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R

abstract class BasePreferenceFragment : PreferenceFragmentCompat() {
    /**
     * Show [ListPreference] and [EditTextPreference] dialog by [MaterialAlertDialogBuilder]
     */
    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference) {
            is ListPreference -> {
                val prefIndex = preference.entryValues.indexOf(preference.value)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(preference.title)
                    .setSingleChoiceItems(preference.entries, prefIndex) { dialog, index ->
                        val newValue = preference.entryValues[index].toString()
                        if (preference.callChangeListener(newValue)) {
                            preference.value = newValue
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            is EditTextPreference -> {
                val view = layoutInflater.inflate(R.layout.preference_dialog_edittext_edited, null)
                var message = ""
                var hint = ""
                if (preference.extras.getString("message") != null) {
                    message = preference.extras.getString("message")!!
                }
                if (preference.extras.getString("hint") != null) {
                    hint = preference.extras.getString("hint")!!
                }
                val messageView = view.findViewById<TextView>(android.R.id.message)
                messageView.text = message
                val editText = view.findViewById<TextInputEditText>(android.R.id.edit)
                editText.setText(preference.text.toString())
                editText.hint = hint
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(preference.title)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val newValue = editText.text.toString()
                        if (preference.callChangeListener(newValue)) {
                            preference.text = newValue
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }
}
