package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.topicShortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PublishFragment : DialogFragment() {
    private val api = ApiService()

    private lateinit var repository: Repository

    private lateinit var toolbar: MaterialToolbar
    private lateinit var sendMenuItem: MenuItem
    private lateinit var titleText: TextInputEditText
    private lateinit var messageText: TextInputEditText
    private lateinit var tagsText: TextInputEditText
    private lateinit var priorityDropdown: AutoCompleteTextView
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var errorImage: View

    private var baseUrl: String = ""
    private var topic: String = ""
    private var selectedPriority: Int = 3 // Default priority

    private var initialMessage: String = ""

    interface PublishListener {
        fun onPublished()
    }

    private var publishListener: PublishListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is PublishListener) {
            publishListener = context
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Dependencies
        repository = Repository.getInstance(requireActivity())

        // Get arguments
        baseUrl = arguments?.getString(ARG_BASE_URL) ?: ""
        topic = arguments?.getString(ARG_TOPIC) ?: ""
        initialMessage = arguments?.getString(ARG_MESSAGE) ?: ""

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_publish_dialog, null)

        // Setup toolbar
        toolbar = view.findViewById(R.id.publish_dialog_toolbar)
        toolbar.title = getString(R.string.publish_dialog_title, topicShortUrl(baseUrl, topic))
        toolbar.setNavigationOnClickListener {
            dismiss()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.publish_dialog_send_button) {
                onSendClick()
                true
            } else {
                false
            }
        }
        sendMenuItem = toolbar.menu.findItem(R.id.publish_dialog_send_button)

        // Fields
        titleText = view.findViewById(R.id.publish_dialog_title_text)
        messageText = view.findViewById(R.id.publish_dialog_message_text)
        tagsText = view.findViewById(R.id.publish_dialog_tags_text)
        priorityDropdown = view.findViewById(R.id.publish_dialog_priority_dropdown)
        progress = view.findViewById(R.id.publish_dialog_progress)
        errorText = view.findViewById(R.id.publish_dialog_error_text)
        errorImage = view.findViewById(R.id.publish_dialog_error_image)

        // Set initial message if provided
        if (initialMessage.isNotEmpty()) {
            messageText.setText(initialMessage)
        }

        // Setup priority dropdown
        val priorities = listOf(
            getString(R.string.publish_dialog_priority_min),
            getString(R.string.publish_dialog_priority_low),
            getString(R.string.publish_dialog_priority_default),
            getString(R.string.publish_dialog_priority_high),
            getString(R.string.publish_dialog_priority_max)
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, priorities)
        priorityDropdown.setAdapter(adapter)
        priorityDropdown.setText(priorities[2], false) // Default priority
        priorityDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedPriority = position + 1 // Priority is 1-5
        }

        // Validation on text change
        val textWatcher = AfterChangedTextWatcher {
            validateInput()
        }
        messageText.addTextChangedListener(textWatcher)

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
        messageText.postDelayed({
            messageText.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(messageText, InputMethodManager.SHOW_FORCED)
        }, 200)
    }

    private fun validateInput() {
        if (!this::sendMenuItem.isInitialized) return
        sendMenuItem.isEnabled = messageText.text?.isNotEmpty() == true
    }

    private fun onSendClick() {
        val title = titleText.text.toString()
        val message = messageText.text.toString()
        val tagsString = tagsText.text.toString()
        val tags = if (tagsString.isNotEmpty()) {
            tagsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        progress.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        errorImage.visibility = View.GONE
        enableView(false)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(baseUrl)
                api.publish(
                    baseUrl = baseUrl,
                    topic = topic,
                    user = user,
                    message = message,
                    title = title,
                    priority = selectedPriority,
                    tags = tags,
                    delay = ""
                )
                val activity = activity ?: return@launch
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.publish_dialog_message_published, Toast.LENGTH_SHORT).show()
                    publishListener?.onPublished()
                    dismiss()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish message", e)
                val activity = activity ?: return@launch
                activity.runOnUiThread {
                    progress.visibility = View.GONE
                    val errorMessage = when (e) {
                        is ApiService.UnauthorizedException -> {
                            if (e.user != null) {
                                getString(R.string.detail_test_message_error_unauthorized_user, e.user.username)
                            } else {
                                getString(R.string.detail_test_message_error_unauthorized_anon)
                            }
                        }
                        is ApiService.EntityTooLargeException -> {
                            getString(R.string.detail_test_message_error_too_large)
                        }
                        else -> {
                            getString(R.string.publish_dialog_error_sending, e.message)
                        }
                    }
                    errorText.text = errorMessage
                    errorText.visibility = View.VISIBLE
                    errorImage.visibility = View.VISIBLE
                    enableView(true)
                }
            }
        }
    }

    private fun enableView(enable: Boolean) {
        titleText.isEnabled = enable
        messageText.isEnabled = enable
        tagsText.isEnabled = enable
        priorityDropdown.isEnabled = enable
        sendMenuItem.isEnabled = enable && messageText.text?.isNotEmpty() == true
    }

    companion object {
        const val TAG = "NtfyPublishFragment"
        private const val ARG_BASE_URL = "baseUrl"
        private const val ARG_TOPIC = "topic"
        private const val ARG_MESSAGE = "message"

        fun newInstance(baseUrl: String, topic: String, message: String = ""): PublishFragment {
            val fragment = PublishFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_BASE_URL, baseUrl)
                putString(ARG_TOPIC, topic)
                putString(ARG_MESSAGE, message)
            }
            return fragment
        }
    }
}

