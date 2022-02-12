package io.heckel.ntfy.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShareActivity : AppCompatActivity() {
    private val repository by lazy { (application as Application).repository }
    private val api = ApiService()

    // File to share
    private var fileUri: Uri? = null

    // List of base URLs used, excluding app_base_url
    private lateinit var baseUrls: List<String>

    // UI elements
    private lateinit var menu: Menu
    private lateinit var sendItem: MenuItem
    private lateinit var contentImage: ImageView
    private lateinit var contentFileBox: View
    private lateinit var contentFileInfo: TextView
    private lateinit var contentFileIcon: ImageView
    private lateinit var contentText: TextView
    private lateinit var topicText: TextView
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlText: AutoCompleteTextView
    private lateinit var useAnotherServerCheckbox: CheckBox
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var errorImage: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        Log.init(this) // Init logs in all entry points
        Log.d(TAG, "Create $this with intent $intent")

        // Action bar
        title = getString(R.string.share_title)

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // UI elements
        contentText = findViewById(R.id.share_content_text)
        contentImage = findViewById(R.id.share_content_image)
        contentFileBox = findViewById(R.id.share_content_file_box)
        contentFileInfo = findViewById(R.id.share_content_file_info)
        contentFileIcon = findViewById(R.id.share_content_file_icon)
        topicText = findViewById(R.id.share_topic_text)
        baseUrlLayout = findViewById(R.id.share_base_url_layout)
        //baseUrlLayout.background = window.background
        baseUrlLayout.makeEndIconSmaller(resources) // Hack!
        baseUrlText = findViewById(R.id.share_base_url_text)
        //baseUrlText.background = topicText.background
        useAnotherServerCheckbox = findViewById(R.id.share_use_another_server_checkbox)
        progress = findViewById(R.id.share_progress)
        progress.visibility = View.GONE
        errorText = findViewById(R.id.share_error_text)
        errorText.visibility = View.GONE
        errorImage = findViewById(R.id.share_error_image)
        errorImage.visibility = View.GONE

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
        contentText.addTextChangedListener(textWatcher)
        topicText.addTextChangedListener(textWatcher)

        // Add behavior to "use another" checkbox
        useAnotherServerCheckbox.setOnCheckedChangeListener { _, isChecked ->
            baseUrlLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            validateInput()
        }

        // Add baseUrl auto-complete behavior
        lifecycleScope.launch(Dispatchers.IO) {
            val appBaseUrl = getString(R.string.app_base_url)
            baseUrls = repository.getSubscriptions()
                .groupBy { it.baseUrl }
                .map { it.key }
                .filterNot { it == appBaseUrl }
                .sorted()
            val activity = this@ShareActivity
            activity.runOnUiThread {
                initBaseUrlDropdown(baseUrls, baseUrlText, baseUrlLayout)
                useAnotherServerCheckbox.isChecked = baseUrls.count() == 1
            }
        }

        // Incoming intent
        val intent = intent ?: return
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type == "text/plain") {
            handleSendText(intent)
        } else if (supportedImage(intent.type)) {
            handleSendImage(intent)
        } else {
            handleSendFile(intent)
        }
    }

    private fun handleSendText(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "(no text)"
        Log.d(TAG, "Shared content is text: $text")
        contentText.text = text
        show()
    }

    private fun handleSendImage(intent: Intent) {
        fileUri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
        Log.d(TAG, "Shared content is an image with URI $fileUri")
        if (fileUri == null) {
            Log.w(TAG, "Null URI is not allowed. Aborting.")
            return
        }
        try {
            val resolver = applicationContext.contentResolver
            val bitmapStream = resolver.openInputStream(fileUri!!)
            val bitmap = BitmapFactory.decodeStream(bitmapStream)
            contentImage.setImageBitmap(bitmap)
            contentText.text = getString(R.string.share_content_image_text)
            show(image = true)
        } catch (e: Exception) {
            fileUri = null
            contentText.text = ""
            errorText.text = getString(R.string.share_content_image_error, e.message)
            show(error = true)
        }
    }

    private fun handleSendFile(intent: Intent) {
        fileUri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
        Log.d(TAG, "Shared content is a file with URI $fileUri")
        if (fileUri == null) {
            Log.w(TAG, "Null URI is not allowed. Aborting.")
            return
        }
        try {
            val resolver = applicationContext.contentResolver
            val info = fileStat(this, fileUri)
            val mimeType = resolver.getType(fileUri!!)
            contentText.text = getString(R.string.share_content_file_text)
            contentFileInfo.text = "${info.filename}\n${formatBytes(info.size)}"
            contentFileIcon.setImageResource(mimeTypeToIconResource(mimeType))
            show(file = true)
        } catch (e: Exception) {
            fileUri = null
            contentText.text = ""
            errorText.text = getString(R.string.share_content_file_error, e.message)
            show(error = true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_share_action_bar, menu)
        this.menu = menu
        sendItem = menu.findItem(R.id.share_menu_send)
        validateInput() // Disable icon
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.share_menu_send -> {
                onShareClick()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun show(image: Boolean = false, file: Boolean = false, error: Boolean = false) {
        contentImage.visibility = if (image) View.VISIBLE else View.GONE
        contentFileBox.visibility = if (file) View.VISIBLE else View.GONE
        errorImage.visibility = if (error) View.VISIBLE else View.GONE
        errorText.visibility = if (error) View.VISIBLE else View.GONE
    }

    private fun onShareClick() {
        val baseUrl = getBaseUrl()
        val topic = topicText.text.toString()
        val message = contentText.text.toString()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val user = repository.getUser(baseUrl)
            try {
                val (filename, body) = if (fileUri != null) {
                    val stat = fileStat(this@ShareActivity, fileUri)
                    val body = ContentUriRequestBody(applicationContext.contentResolver, fileUri!!, stat.size)
                    Pair(stat.filename, body)
                } else {
                    Pair("", null)
                }
                api.publish(
                    baseUrl = baseUrl,
                    topic = topic,
                    user = user,
                    message = message,
                    title = "",
                    priority = 3,
                    tags = emptyList(),
                    delay = "",
                    body = body, // May be null
                    filename = filename, // May be empty
                )
                runOnUiThread {
                    finish()
                    Toast
                        .makeText(this@ShareActivity, getString(R.string.share_successful), Toast.LENGTH_LONG)
                        .show()
                }
            } catch (e: Exception) {
                val message = if (e is ApiService.UnauthorizedException) {
                    if (e.user != null) {
                        getString(R.string.detail_test_message_error_unauthorized_user, e.user.username)
                    }  else {
                        getString(R.string.detail_test_message_error_unauthorized_anon)
                    }
                } else if (e is ApiService.EntityTooLargeException) {
                    getString(R.string.detail_test_message_error_too_large)
                } else {
                    getString(R.string.detail_test_message_error, e.message)
                }
                runOnUiThread {
                    progress.visibility = View.GONE
                    errorText.text = message
                    errorImage.visibility = View.VISIBLE
                    errorText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun validateInput() {
        if (!this::sendItem.isInitialized) return // Initialized late in onCreateOptionsMenu
        val enabled = if (useAnotherServerCheckbox.isChecked) {
            contentText.text.isNotEmpty() && validTopic(topicText.text.toString()) && validUrl(baseUrlText.text.toString())
        } else {
            contentText.text.isNotEmpty() && topicText.text.isNotEmpty()
        }
        sendItem.isEnabled = enabled
        sendItem.icon.alpha = if (enabled) 255 else 130
    }

    private fun getBaseUrl(): String {
        return if (useAnotherServerCheckbox.isChecked) {
            baseUrlText.text.toString()
        } else {
            getString(R.string.app_base_url)
        }
    }

    companion object {
        const val TAG = "NtfyShareActivity"
    }
}
