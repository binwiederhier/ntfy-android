package io.heckel.ntfy.ui

import android.app.DownloadManager
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stfalcon.imageviewer.StfalconImageViewer
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Attachment
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.PROGRESS_DONE
import io.heckel.ntfy.data.PROGRESS_NONE
import io.heckel.ntfy.msg.AttachmentDownloadWorker
import io.heckel.ntfy.util.*
import java.util.*


class DetailAdapter(private val onClick: (Notification) -> Unit, private val onLongClick: (Notification) -> Unit) :
    ListAdapter<Notification, DetailAdapter.DetailViewHolder>(TopicDiffCallback) {
    val selected = mutableSetOf<String>() // Notification IDs

    /* Creates and inflates view and return TopicViewHolder. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_detail_item, parent, false)
        return DetailViewHolder(view, selected, onClick, onLongClick)
    }

    /* Gets current topic and uses it to bind view. */
    override fun onBindViewHolder(holder: DetailViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun toggleSelection(notificationId: String) {
        if (selected.contains(notificationId)) {
            selected.remove(notificationId)
        } else {
            selected.add(notificationId)
        }
    }

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class DetailViewHolder(itemView: View, private val selected: Set<String>, val onClick: (Notification) -> Unit, val onLongClick: (Notification) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private var notification: Notification? = null
        private val priorityImageView: ImageView = itemView.findViewById(R.id.detail_item_priority_image)
        private val dateView: TextView = itemView.findViewById(R.id.detail_item_date_text)
        private val titleView: TextView = itemView.findViewById(R.id.detail_item_title_text)
        private val messageView: TextView = itemView.findViewById(R.id.detail_item_message_text)
        private val newDotImageView: View = itemView.findViewById(R.id.detail_item_new_dot)
        private val tagsView: TextView = itemView.findViewById(R.id.detail_item_tags_text)
        private val menuButton: ImageButton = itemView.findViewById(R.id.detail_item_menu_button)
        private val attachmentImageView: ImageView = itemView.findViewById(R.id.detail_item_attachment_image)
        private val attachmentBoxView: View = itemView.findViewById(R.id.detail_item_attachment_box)
        private val attachmentIconView: ImageView = itemView.findViewById(R.id.detail_item_attachment_icon)
        private val attachmentInfoView: TextView = itemView.findViewById(R.id.detail_item_attachment_info)

        fun bind(notification: Notification) {
            this.notification = notification

            val context = itemView.context
            val unmatchedTags = unmatchedTags(splitTags(notification.tags))

            dateView.text = Date(notification.timestamp * 1000).toString()
            messageView.text = formatMessage(notification)
            newDotImageView.visibility = if (notification.notificationId == 0) View.GONE else View.VISIBLE
            itemView.setOnClickListener { onClick(notification) }
            itemView.setOnLongClickListener { onLongClick(notification); true }
            if (notification.title != "") {
                titleView.visibility = View.VISIBLE
                titleView.text = formatTitle(notification)
            } else {
                titleView.visibility = View.GONE
            }
            if (unmatchedTags.isNotEmpty()) {
                tagsView.visibility = View.VISIBLE
                tagsView.text = context.getString(R.string.detail_item_tags, unmatchedTags.joinToString(", "))
            } else {
                tagsView.visibility = View.GONE
            }
            if (selected.contains(notification.id)) {
                itemView.setBackgroundResource(R.color.primarySelectedRowColor);
            }
            renderPriority(context, notification)
            maybeRenderAttachment(context, notification)
        }

        private fun renderPriority(context: Context, notification: Notification) {
            when (notification.priority) {
                1 -> {
                    priorityImageView.visibility = View.VISIBLE
                    priorityImageView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_priority_1_24dp))
                }
                2 -> {
                    priorityImageView.visibility = View.VISIBLE
                    priorityImageView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_priority_2_24dp))
                }
                3 -> {
                    priorityImageView.visibility = View.GONE
                }
                4 -> {
                    priorityImageView.visibility = View.VISIBLE
                    priorityImageView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_priority_4_24dp))
                }
                5 -> {
                    priorityImageView.visibility = View.VISIBLE
                    priorityImageView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_priority_5_24dp))
                }
            }
        }

        private fun maybeRenderAttachment(context: Context, notification: Notification) {
            if (notification.attachment == null) {
                menuButton.visibility = View.GONE
                attachmentImageView.visibility = View.GONE
                attachmentBoxView.visibility = View.GONE
                return
            }
            val attachment = notification.attachment
            val exists = if (attachment.contentUri != null) fileExists(context, attachment.contentUri) else false
            val image = attachment.contentUri != null && exists && supportedImage(attachment.type)
            maybeRenderMenu(context, notification, attachment, exists)
            maybeRenderAttachmentImage(context, attachment, image)
            maybeRenderAttachmentBox(context, notification, attachment, exists, image)
        }

        private fun maybeRenderMenu(context: Context, notification: Notification, attachment: Attachment, exists: Boolean) {
            val menuButtonPopupMenu = createAttachmentPopup(context, menuButton, notification, attachment, exists) // Heavy lifting not during on-click
            if (menuButtonPopupMenu != null) {
                menuButton.setOnClickListener { menuButtonPopupMenu.show() }
                menuButton.visibility = View.VISIBLE
            } else {
                menuButton.visibility = View.GONE
            }
        }

        private fun maybeRenderAttachmentBox(context: Context, notification: Notification, attachment: Attachment, exists: Boolean, image: Boolean) {
            if (image) {
                attachmentBoxView.visibility = View.GONE
                return
            }
            attachmentInfoView.text = formatAttachmentDetails(context, attachment, exists)
            attachmentIconView.setImageResource(if (attachment.type?.startsWith("image/") == true) {
                R.drawable.ic_file_image_red_24dp
            } else if (attachment.type?.startsWith("video/") == true) {
                R.drawable.ic_file_video_orange_24dp
            } else if (attachment.type?.startsWith("audio/") == true) {
                R.drawable.ic_file_audio_purple_24dp
            } else if ("application/vnd.android.package-archive" == attachment.type) {
                R.drawable.ic_file_app_gray_24dp
            } else {
                R.drawable.ic_file_document_blue_24dp
            })
            val attachmentBoxPopupMenu = createAttachmentPopup(context, attachmentBoxView, notification, attachment, exists) // Heavy lifting not during on-click
            if (attachmentBoxPopupMenu != null) {
                attachmentBoxView.setOnClickListener { attachmentBoxPopupMenu.show() }
            } else {
                attachmentBoxView.setOnClickListener {
                    Toast
                        .makeText(context, context.getString(R.string.detail_item_cannot_download), Toast.LENGTH_LONG)
                        .show()
                }
            }
            attachmentBoxView.visibility = View.VISIBLE
        }

        private fun createAttachmentPopup(context: Context, anchor: View?, notification: Notification, attachment: Attachment, exists: Boolean): PopupMenu? {
            val popup = PopupMenu(context, anchor)
            popup.menuInflater.inflate(R.menu.menu_detail_attachment, popup.menu)
            val downloadItem = popup.menu.findItem(R.id.detail_item_menu_download)
            val openItem = popup.menu.findItem(R.id.detail_item_menu_open)
            val browseItem = popup.menu.findItem(R.id.detail_item_menu_browse)
            val copyUrlItem = popup.menu.findItem(R.id.detail_item_menu_copy_url)
            val expired = attachment.expires != null && attachment.expires < System.currentTimeMillis()/1000
            if (attachment.contentUri != null) {
                openItem.setOnMenuItemClickListener {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attachment.contentUri)))
                    } catch (e: ActivityNotFoundException) {
                        Toast
                            .makeText(context, context.getString(R.string.detail_item_cannot_open), Toast.LENGTH_LONG)
                            .show()
                    } catch (_: Exception) {
                        // URI parse exception and others; we don't care!
                    }
                    true
                }
            }
            browseItem.setOnMenuItemClickListener {
                context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                true
            }
            copyUrlItem.setOnMenuItemClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("attachment url", attachment.url)
                clipboard.setPrimaryClip(clip)
                Toast
                    .makeText(context, context.getString(R.string.detail_item_menu_copy_url_copied), Toast.LENGTH_LONG)
                    .show()
                true
            }
            downloadItem.setOnMenuItemClickListener {
                scheduleAttachmentDownload(context, notification)
                true
            }
            openItem.isVisible = exists
            browseItem.isVisible = exists
            downloadItem.isVisible = !exists && !expired
            copyUrlItem.isVisible = !expired
            val noOptions = !openItem.isVisible && !browseItem.isVisible && !downloadItem.isVisible && !copyUrlItem.isVisible
            if (noOptions) {
                return null
            }
            return popup
        }

        private fun formatAttachmentDetails(context: Context, attachment: Attachment, exists: Boolean): String {
            val name = queryAttachmentFilename(context, attachment)
            val notYetDownloaded = !exists && attachment.progress == PROGRESS_NONE
            val downloading = !exists && attachment.progress in 0..99
            val deleted = !exists && attachment.progress == PROGRESS_DONE
            val expired = attachment.expires != null && attachment.expires < System.currentTimeMillis()/1000
            val expires = attachment.expires != null && attachment.expires > System.currentTimeMillis()/1000
            val infos = mutableListOf<String>()
            if (attachment.size != null) {
                infos.add(formatBytes(attachment.size))
            }
            if (notYetDownloaded) {
                if (expired) {
                    infos.add("not downloaded, link expired")
                } else if (expires) {
                    infos.add("not downloaded, expires ${formatDateShort(attachment.expires!!)}")
                } else {
                    infos.add("not downloaded")
                }
            } else if (downloading) {
                infos.add("${attachment.progress}% downloaded")
            } else if (deleted) {
                if (expired) {
                    infos.add("deleted, link expired")
                } else if (expires) {
                    infos.add("deleted, link expires ${formatDateShort(attachment.expires!!)}")
                } else {
                    infos.add("deleted")
                }
            }
            return if (infos.size > 0) {
                "$name\n${infos.joinToString(", ")}"
            } else {
                name
            }
        }

        private fun queryAttachmentFilename(context: Context, attachment: Attachment): String {
            if (attachment.contentUri == null) {
                return attachment.name
            }
            try {
                val resolver = context.applicationContext.contentResolver
                val cursor = resolver.query(Uri.parse(attachment.contentUri), null, null, null, null) ?: return attachment.name
                return cursor.use { c ->
                    val nameIndex = c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                    c.moveToFirst()
                    c.getString(nameIndex)
                }
            } catch (_: Exception) {
                return attachment.name
            }
        }

        private fun maybeRenderAttachmentImage(context: Context, attachment: Attachment, image: Boolean) {
            if (!image) {
                attachmentImageView.visibility = View.GONE
                return
            }
            try {
                val resolver = context.applicationContext.contentResolver
                val bitmapStream = resolver.openInputStream(Uri.parse(attachment.contentUri))
                val bitmap = BitmapFactory.decodeStream(bitmapStream)
                attachmentImageView.setImageBitmap(bitmap)
                attachmentImageView.setOnClickListener {
                    val loadImage = { view: ImageView, image: Bitmap -> view.setImageBitmap(image) }
                    StfalconImageViewer.Builder(context, listOf(bitmap), loadImage)
                        .allowZooming(true)
                        .withTransitionFrom(attachmentImageView)
                        .withHiddenStatusBar(false)
                        .show()
                }
                attachmentImageView.visibility = View.VISIBLE
            } catch (_: Exception) {
                attachmentImageView.visibility = View.GONE
            }
        }

        private fun scheduleAttachmentDownload(context: Context, notification: Notification) {
            Log.d(TAG, "Enqueuing work to download attachment")
            val workManager = WorkManager.getInstance(context)
            val workRequest = OneTimeWorkRequest.Builder(AttachmentDownloadWorker::class.java)
                .setInputData(workDataOf("id" to notification.id))
                .build()
            workManager.enqueue(workRequest)
        }
    }

    object TopicDiffCallback : DiffUtil.ItemCallback<Notification>() {
        override fun areItemsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val TAG = "NtfyDetailAdapter"
    }
}
