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
import androidx.appcompat.app.AppCompatActivity
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.Log

class ShareActivity : AppCompatActivity() {
    private val repository by lazy { (application as Application).repository }
    private val api = ApiService()

    // UI elements
    private lateinit var menu: Menu
    private lateinit var sendItem: MenuItem
    private lateinit var contentImage: ImageView
    private lateinit var contentText: TextView
    private lateinit var topicText: TextView
    private lateinit var progress: ProgressBar

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
        topicText = findViewById(R.id.share_topic_text)
        progress = findViewById(R.id.share_progress)
        progress.visibility = View.GONE

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
        topicText.addTextChangedListener(textWatcher)

        // Incoming intent
        val intent = intent ?: return
        if (intent.action != Intent.ACTION_SEND) return
        if ("text/plain" == intent.type) {
            handleSendText(intent)
        } else if (intent.type?.startsWith("image/") == true) {
            handleSendImage(intent)
        }
    }

    private fun handleSendText(intent: Intent) {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            contentImage.visibility = View.GONE
            contentText.text = text
        }
    }

    private fun handleSendImage(intent: Intent) {
        val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri ?: return
        try {
            val resolver = applicationContext.contentResolver
            val bitmapStream = resolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(bitmapStream)
            contentImage.setImageBitmap(bitmap)
            contentImage.visibility = View.VISIBLE
            contentText.text = getString(R.string.share_content_image_text)
        } catch (_: Exception) {
            contentText.text = getString(R.string.share_content_image_error)
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

    private fun onShareClick() {

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
