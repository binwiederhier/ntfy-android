package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.text.method.LinkMovementMethod
import android.widget.AutoCompleteTextView
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.BuildConfig
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import android.widget.ImageView
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.formatBytes
import io.heckel.ntfy.util.mimeTypeToIconResource
import io.heckel.ntfy.util.topicShortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.heckel.ntfy.util.ProgressRequestBody
import okhttp3.MediaType.Companion.toMediaType

class PublishFragment : DialogFragment() {
    private val api = ApiService()

    private lateinit var repository: Repository

    // Toolbar
    private lateinit var toolbar: MaterialToolbar
    private lateinit var sendMenuItem: MenuItem

    // Main fields
    private lateinit var titleText: TextInputEditText
    private lateinit var messageText: TextInputEditText
    private lateinit var tagsText: TextInputEditText
    private lateinit var priorityDropdown: AutoCompleteTextView

    // Chips
    private lateinit var chipGroup: ChipGroup
    private lateinit var chipTitle: Chip
    private lateinit var chipTags: Chip
    private lateinit var chipPriority: Chip
    private lateinit var chipMarkdown: Chip
    private lateinit var chipClickUrl: Chip
    private lateinit var chipEmail: Chip
    private lateinit var chipDelay: Chip
    private lateinit var chipAttachUrl: Chip
    private lateinit var chipAttachFile: Chip
    private lateinit var chipPhoneCall: Chip

    // Toggleable field layouts
    private lateinit var titleLayout: View
    private lateinit var tagsLayout: View
    private lateinit var priorityLayout: View

    // Optional field layouts
    private lateinit var clickUrlLayout: View
    private lateinit var emailLayout: View
    private lateinit var delayLayout: View
    private lateinit var attachUrlLayout: View
    private lateinit var phoneCallLayout: View

    // Optional field inputs
    private lateinit var clickUrlText: TextInputEditText
    private lateinit var emailText: TextInputEditText
    private lateinit var delayText: TextInputEditText
    private lateinit var attachUrlText: TextInputEditText
    private lateinit var attachFilenameText: TextInputEditText
    private lateinit var attachFilenameLayout: TextInputLayout
    private lateinit var phoneCallText: TextInputEditText

    // Attachment box (shown after file is selected)
    private lateinit var attachmentBox: View
    private lateinit var attachmentBoxIcon: ImageView
    private lateinit var attachmentBoxFilenameText: TextInputEditText
    private lateinit var attachmentBoxSize: TextView

    // Progress/Error
    private lateinit var uploadProgress: LinearProgressIndicator
    private lateinit var uploadProgressText: TextView
    private lateinit var errorText: TextView
    private lateinit var errorImage: View
    private lateinit var docsLink: TextView
    
    // Job and cancel function (represents active publish HTTP call)
    private var job: Job? = null
    private var cancel: (() -> Unit)? = null
    private var sending: Boolean = false

    // State
    private var baseUrl: String = ""
    private var topic: String = ""
    private var selectedPriority: Int = 3 // Default priority
    private var initialMessage: String = ""
    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""
    private var selectedFileSize: Long = 0
    private var selectedFileMimeType: String = "application/octet-stream"

    // File picker
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleSelectedFile(uri)
                }
            } else {
                // User cancelled file picker, uncheck the chip
                chipAttachFile.isChecked = false
            }
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
            if (sending) {
                cancelUpload()
            } else {
                dismiss()
            }
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

        // Main fields
        titleText = view.findViewById(R.id.publish_dialog_title_text)
        messageText = view.findViewById(R.id.publish_dialog_message_text)
        tagsText = view.findViewById(R.id.publish_dialog_tags_text)
        priorityDropdown = view.findViewById(R.id.publish_dialog_priority_dropdown)
        uploadProgress = view.findViewById(R.id.publish_dialog_upload_progress)
        uploadProgressText = view.findViewById(R.id.publish_dialog_upload_progress_text)
        errorText = view.findViewById(R.id.publish_dialog_error_text)
        errorImage = view.findViewById(R.id.publish_dialog_error_image)
        docsLink = view.findViewById(R.id.publish_dialog_docs_text)
        docsLink.movementMethod = LinkMovementMethod.getInstance()
        docsLink.isVisible = BuildConfig.PAYMENT_LINKS_AVAILABLE

        // Set initial message if provided
        if (initialMessage.isNotEmpty()) {
            messageText.setText(initialMessage)
        }

        // Setup priority dropdown with custom adapter
        val priorityItems = PriorityAdapter.createPriorityItems(requireContext())
        val priorityAdapter = PriorityAdapter(requireContext(), priorityItems)
        priorityDropdown.setAdapter(priorityAdapter)
        // Set default priority (index 2 = priority 3, since list is now max-first)
        priorityDropdown.setText(priorityItems[2].label, false)
        updatePriorityIcon(priorityItems[2].iconResId)
        priorityDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedPriority = priorityItems[position].priority
            priorityDropdown.setText(priorityItems[position].label, false)
            updatePriorityIcon(priorityItems[position].iconResId)
        }

        // Setup chips
        chipGroup = view.findViewById(R.id.publish_dialog_chip_group)
        chipTitle = view.findViewById(R.id.publish_dialog_chip_title)
        chipTags = view.findViewById(R.id.publish_dialog_chip_tags)
        chipPriority = view.findViewById(R.id.publish_dialog_chip_priority)
        chipMarkdown = view.findViewById(R.id.publish_dialog_chip_markdown)
        chipClickUrl = view.findViewById(R.id.publish_dialog_chip_click_url)
        chipEmail = view.findViewById(R.id.publish_dialog_chip_email)
        chipDelay = view.findViewById(R.id.publish_dialog_chip_delay)
        chipAttachUrl = view.findViewById(R.id.publish_dialog_chip_attach_url)
        chipAttachFile = view.findViewById(R.id.publish_dialog_chip_attach_file)
        chipPhoneCall = view.findViewById(R.id.publish_dialog_chip_phone_call)

        // Setup toggleable field layouts
        titleLayout = view.findViewById(R.id.publish_dialog_title_layout)
        tagsLayout = view.findViewById(R.id.publish_dialog_tags_layout)
        priorityLayout = view.findViewById(R.id.publish_dialog_priority_layout)

        // Setup optional field layouts
        clickUrlLayout = view.findViewById(R.id.publish_dialog_click_url_layout)
        emailLayout = view.findViewById(R.id.publish_dialog_email_layout)
        delayLayout = view.findViewById(R.id.publish_dialog_delay_layout)
        attachUrlLayout = view.findViewById(R.id.publish_dialog_attach_url_layout)
        phoneCallLayout = view.findViewById(R.id.publish_dialog_phone_call_layout)

        // Setup optional field inputs
        clickUrlText = view.findViewById(R.id.publish_dialog_click_url_text)
        emailText = view.findViewById(R.id.publish_dialog_email_text)
        delayText = view.findViewById(R.id.publish_dialog_delay_text)
        attachUrlText = view.findViewById(R.id.publish_dialog_attach_url_text)
        attachFilenameText = view.findViewById(R.id.publish_dialog_attach_filename_text)
        attachFilenameLayout = view.findViewById(R.id.publish_dialog_attach_filename_layout)
        phoneCallText = view.findViewById(R.id.publish_dialog_phone_call_text)

        // Attachment box (shown after file is selected)
        attachmentBox = view.findViewById(R.id.publish_dialog_attachment_box)
        attachmentBoxIcon = attachmentBox.findViewById(R.id.attachment_box_icon)
        attachmentBoxFilenameText = attachmentBox.findViewById(R.id.attachment_box_filename)
        attachmentBoxSize = attachmentBox.findViewById(R.id.attachment_box_size)

        // Setup chip click listeners
        setupChipListeners()

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

    private fun setupChipListeners() {
        // Markdown chip - no field to show, just toggle state
        // (no listener needed, we just check isChecked when sending)

        chipTitle.setOnCheckedChangeListener { _, isChecked ->
            titleLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                titleText.requestFocus()
                showKeyboard(titleText)
            } else {
                titleText.setText("")
            }
        }

        chipTags.setOnCheckedChangeListener { _, isChecked ->
            tagsLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                tagsText.requestFocus()
                showKeyboard(tagsText)
            } else {
                tagsText.setText("")
            }
        }

        chipPriority.setOnCheckedChangeListener { _, isChecked ->
            priorityLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                hideKeyboard()
                priorityDropdown.requestFocus()
                priorityDropdown.showDropDown()
            } else {
                // Reset to default priority
                selectedPriority = 3
                val priorityItems = PriorityAdapter.createPriorityItems(requireContext())
                priorityDropdown.setText(priorityItems[2].label, false)
                updatePriorityIcon(priorityItems[2].iconResId)
            }
        }

        chipClickUrl.setOnCheckedChangeListener { _, isChecked ->
            clickUrlLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                clickUrlText.requestFocus()
                showKeyboard(clickUrlText)
            } else {
                clickUrlText.setText("")
            }
        }

        chipEmail.setOnCheckedChangeListener { _, isChecked ->
            emailLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                emailText.requestFocus()
                showKeyboard(emailText)
            } else {
                emailText.setText("")
            }
        }

        chipDelay.setOnCheckedChangeListener { _, isChecked ->
            delayLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                delayText.requestFocus()
                showKeyboard(delayText)
            } else {
                delayText.setText("")
            }
        }

        chipAttachUrl.setOnCheckedChangeListener { _, isChecked ->
            attachUrlLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            attachFilenameLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                // Mutually exclusive with attach file
                chipAttachFile.isChecked = false
                attachUrlText.requestFocus()
                showKeyboard(attachUrlText)
            } else {
                attachUrlText.setText("")
                attachFilenameText.setText("")
            }
        }

        chipAttachFile.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Mutually exclusive with attach URL
                chipAttachUrl.isChecked = false
                // Open file picker immediately (don't show any UI yet)
                openFilePicker()
            } else {
                selectedFileUri = null
                selectedFileName = ""
                selectedFileSize = 0
                attachmentBox.visibility = View.GONE
                attachmentBoxFilenameText.setText("")
            }
        }

        chipPhoneCall.setOnCheckedChangeListener { _, isChecked ->
            phoneCallLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                phoneCallText.requestFocus()
                showKeyboard(phoneCallText)
            } else {
                phoneCallText.setText("")
            }
        }
    }

    private fun showKeyboard(view: View) {
        view.postDelayed({
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view?.windowToken, 0)
    }

    private fun updatePriorityIcon(iconResId: Int) {
        val drawable = ContextCompat.getDrawable(requireContext(), iconResId)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        priorityDropdown.setCompoundDrawablesRelative(drawable, null, null, null)
        priorityDropdown.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    private fun handleSelectedFile(uri: Uri) {
        selectedFileUri = uri
        
        // Get file name, size and mime type
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            cursor.moveToFirst()
            selectedFileName = if (nameIndex >= 0) cursor.getString(nameIndex) else "file"
            selectedFileSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0
        }
        
        selectedFileMimeType = requireContext().contentResolver.getType(uri) ?: "application/octet-stream"
        
        // Show the attachment box with icon, size, and filename field
        attachmentBox.visibility = View.VISIBLE
        attachmentBoxIcon.setImageResource(mimeTypeToIconResource(selectedFileMimeType))
        attachmentBoxSize.text = formatBytes(selectedFileSize)
        attachmentBoxFilenameText.setText(selectedFileName)
        
        attachmentBoxFilenameText.requestFocus()
        showKeyboard(attachmentBoxFilenameText)
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
            imm?.showSoftInput(messageText, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun validateInput() {
        if (!this::sendMenuItem.isInitialized) return
        sendMenuItem.isEnabled = true
    }

    private fun onSendClick() {
        val title = if (chipTitle.isChecked) titleText.text.toString() else ""
        val message = messageText.text.toString()
        val markdown = chipMarkdown.isChecked
        val priority = if (chipPriority.isChecked) selectedPriority else 3 // Default priority if not shown
        val tagsString = if (chipTags.isChecked) tagsText.text.toString() else ""
        val tags = if (tagsString.isNotEmpty()) {
            tagsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        // Optional fields
        val clickUrl = if (chipClickUrl.isChecked) clickUrlText.text.toString() else ""
        val email = if (chipEmail.isChecked) emailText.text.toString() else ""
        val delay = if (chipDelay.isChecked) delayText.text.toString() else ""
        val attachUrl = if (chipAttachUrl.isChecked) attachUrlText.text.toString() else ""
        val attachFilename = if (chipAttachUrl.isChecked) attachFilenameText.text.toString() else ""
        val phoneCall = if (chipPhoneCall.isChecked) phoneCallText.text.toString() else ""

        // Show progress UI
        val hasFileAttachment = chipAttachFile.isChecked && selectedFileUri != null
        if (hasFileAttachment) {
            uploadProgress.visibility = View.VISIBLE
            uploadProgress.progress = 0
            uploadProgressText.visibility = View.VISIBLE
            uploadProgressText.text = getString(R.string.publish_dialog_uploading, "0%", "0 B", formatBytes(selectedFileSize))
        }
        errorText.visibility = View.GONE
        errorImage.visibility = View.GONE
        enableView(false)

        // Kick off HTTP request
        sending = true
        job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(baseUrl)
                
                // Handle file attachment
                if (hasFileAttachment) {
                    // Create streaming RequestBody to avoid loading entire file into memory
                    val fileUri = selectedFileUri!!
                    val mimeType = selectedFileMimeType.toMediaType()
                    val fileSize = selectedFileSize
                    val context = requireContext()
                    
                    val baseBody = object : RequestBody() {
                        override fun contentType(): MediaType = mimeType
                        
                        override fun contentLength(): Long = fileSize
                        
                        override fun writeTo(sink: BufferedSink) {
                            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                sink.writeAll(inputStream.source())
                            }
                        }
                    }
                    
                    // Wrap with progress tracking
                    val body = ProgressRequestBody(baseBody) { bytesWritten, totalBytes ->
                        val percent = if (totalBytes > 0) (bytesWritten * 100 / totalBytes).toInt() else 0
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            uploadProgress.progress = percent
                            uploadProgressText.text = getString(
                                R.string.publish_dialog_uploading,
                                "$percent%",
                                formatBytes(bytesWritten),
                                formatBytes(totalBytes)
                            )
                        }
                    }
                    
                    api.publish(
                        baseUrl = baseUrl,
                        topic = topic,
                        user = user,
                        message = message,
                        title = title,
                        priority = priority,
                        tags = tags,
                        delay = delay,
                        body = body,
                        filename = attachmentBoxFilenameText.text.toString(),
                        click = clickUrl,
                        email = email,
                        call = phoneCall,
                        markdown = markdown,
                        onCancelAvailable = { cancel -> this@PublishFragment.cancel = cancel }
                    )
                } else {
                    // No file attachment
                    api.publish(
                        baseUrl = baseUrl,
                        topic = topic,
                        user = user,
                        message = message,
                        title = title,
                        priority = priority,
                        tags = tags,
                        delay = delay,
                        click = clickUrl,
                        attach = attachUrl,
                        email = email,
                        call = phoneCall,
                        markdown = markdown,
                        filename = attachFilename,
                        onCancelAvailable = { cancel -> this@PublishFragment.cancel = cancel }
                    )
                }
                
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    sending = false
                    cancel = null
                    Toast.makeText(requireContext(), R.string.publish_dialog_message_published, Toast.LENGTH_SHORT).show()
                    publishListener?.onPublished()
                    dismiss()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish message", e)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    sending = false
                    cancel = null
                    uploadProgress.visibility = View.GONE
                    uploadProgressText.visibility = View.GONE
                    
                    // Don't show error if cancelled (coroutine or OkHttp call)
                    if (e is kotlinx.coroutines.CancellationException || 
                        (e is java.io.IOException && e.message?.contains("Canceled") == true)) {
                        enableView(true)
                        return@withContext
                    }
                    
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
    
    private fun cancelUpload() {
        // Cancel both the HTTP request and the coroutine job
        cancel?.invoke()
        job?.cancel()
        cancel = null
        sending = false
        uploadProgress.visibility = View.GONE
        uploadProgressText.visibility = View.GONE
        enableView(true)
        if (isAdded) {
            Toast.makeText(requireContext(), R.string.publish_dialog_upload_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableView(enable: Boolean) {
        titleText.isEnabled = enable
        messageText.isEnabled = enable
        tagsText.isEnabled = enable
        priorityDropdown.isEnabled = enable
        
        // Chips
        chipMarkdown.isEnabled = enable
        chipTitle.isEnabled = enable
        chipTags.isEnabled = enable
        chipPriority.isEnabled = enable
        chipClickUrl.isEnabled = enable
        chipEmail.isEnabled = enable
        chipDelay.isEnabled = enable
        chipAttachUrl.isEnabled = enable
        chipAttachFile.isEnabled = enable
        chipPhoneCall.isEnabled = enable
        
        // Optional fields
        clickUrlText.isEnabled = enable
        emailText.isEnabled = enable
        delayText.isEnabled = enable
        attachUrlText.isEnabled = enable
        attachFilenameText.isEnabled = enable
        attachmentBoxFilenameText.isEnabled = enable
        phoneCallText.isEnabled = enable
        
        sendMenuItem.isEnabled = enable
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
