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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

    // UI elements
    private lateinit var menu: Menu
    private lateinit var sendItem: MenuItem
    private lateinit var contentImage: ImageView
    private lateinit var contentFileBox: View
    private lateinit var contentFileInfo: TextView
    private lateinit var contentFileIcon: ImageView
    private lateinit var contentText: TextView
    private lateinit var topicText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var errorImage: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        Log.init(this) // Init logs in all entry points
        Log.d(TAG, "Create $this")

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

        // Incoming intent
        val intent = intent ?: return
        if (intent.action != Intent.ACTION_SEND) return
        if ("text/plain" == intent.type) {
            handleSendText(intent)
        } else if (supportedImage(intent.type)) {
            handleSendImage(intent)
        } else {
            handleSendFile(intent)
        }
    }

    private fun handleSendText(intent: Intent) {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            contentText.text = text
            show()
        }
    }

    private fun handleSendImage(intent: Intent) {
        fileUri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri ?: return
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
        fileUri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri ?: return
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
        val baseUrl = "https://ntfy.sh" // FIXME
        val topic = topicText.text.toString()
        val message = contentText.text.toString()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val user = repository.getUser(baseUrl)
            try {
                val filename = if (fileUri != null) {
                    fileStat(this@ShareActivity, fileUri).filename
                } else {
                    ""
                }
                val body = if (fileUri != null) {
                    val resolver = applicationContext.contentResolver
                    ContentUriRequestBody(resolver, fileUri!!)
                } else {
                    null
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
                    filename = filename // May be empty
                )
                runOnUiThread {
                    finish()
                    Toast
                        .makeText(this@ShareActivity, getString(R.string.share_successful), Toast.LENGTH_LONG)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    errorText.text = e.message
                    errorImage.visibility = View.VISIBLE
                    errorText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun validateInput() {
        if (!this::sendItem.isInitialized) return // Initialized late in onCreateOptionsMenu
        sendItem.isEnabled = contentText.text.isNotEmpty() && topicText.text.isNotEmpty()
        sendItem.icon.alpha = if (sendItem.isEnabled) 255 else 130
    }

    companion object {
        const val TAG = "NtfyShareActivity"
    }
}
