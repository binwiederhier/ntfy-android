package io.heckel.ntfy.ui

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.allViews
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.stfalcon.imageviewer.StfalconImageViewer
import io.heckel.ntfy.R
import io.heckel.ntfy.db.*
import io.heckel.ntfy.msg.DownloadManager
import io.heckel.ntfy.msg.DownloadAttachmentWorker
import io.heckel.ntfy.msg.DownloadType
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.msg.NotificationService.Companion.ACTION_VIEW
import io.heckel.ntfy.util.*
import kotlinx.coroutines.*


class DetailAdapter(private val activity: Activity, private val lifecycleScope: CoroutineScope, private val repository: Repository, private val onClick: (Notification) -> Unit, private val onLongClick: (Notification) -> Unit) :
    ListAdapter<Notification, DetailAdapter.DetailViewHolder>(TopicDiffCallback) {
    val selected = mutableSetOf<String>() // Notification IDs

    /* Creates and inflates view and return TopicViewHolder. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_detail_item, parent, false)
        return DetailViewHolder(activity, lifecycleScope, repository, view, selected, onClick, onLongClick)
    }

    /* Gets current topic and uses it to bind view. */
    override fun onBindViewHolder(holder: DetailViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun get(position: Int): Notification {
        return getItem(position)
    }

    fun toggleSelection(notificationId: String) {
        if (selected.contains(notificationId)) {
            selected.remove(notificationId)
        } else {
            selected.add(notificationId)
        }

        if (selected.size != 0) {
            val listIds = currentList.map { notification -> notification.id }
            val notificationPosition = listIds.indexOf(notificationId)
            notifyItemChanged(notificationPosition)
        }
    }

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class DetailViewHolder(private val activity: Activity, private val lifecycleScope: CoroutineScope, private val repository: Repository, itemView: View, private val selected: Set<String>, val onClick: (Notification) -> Unit, val onLongClick: (Notification) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private var notification: Notification? = null
        private val layout: View = itemView.findViewById(R.id.detail_item_layout)
        private val cardView: CardView = itemView.findViewById(R.id.detail_item_card)
        private val priorityImageView: ImageView = itemView.findViewById(R.id.detail_item_priority_image)
        private val dateView: TextView = itemView.findViewById(R.id.detail_item_date_text)
        private val titleView: TextView = itemView.findViewById(R.id.detail_item_title_text)
        private val messageView: TextView = itemView.findViewById(R.id.detail_item_message_text)
        private val iconView: ImageView = itemView.findViewById(R.id.detail_item_icon)
        private val newDotImageView: View = itemView.findViewById(R.id.detail_item_new_dot)
        private val tagsView: TextView = itemView.findViewById(R.id.detail_item_tags_text)
        private val menuButton: ImageButton = itemView.findViewById(R.id.detail_item_menu_button)
        private val attachmentImageView: ImageView = itemView.findViewById(R.id.detail_item_attachment_image)
        private val attachmentBoxView: View = itemView.findViewById(R.id.detail_item_attachment_file_box)
        private val attachmentIconView: ImageView = itemView.findViewById(R.id.detail_item_attachment_file_icon)
        private val attachmentInfoView: TextView = itemView.findViewById(R.id.detail_item_attachment_file_info)
        private val actionsWrapperView: ConstraintLayout = itemView.findViewById(R.id.detail_item_actions_wrapper)
        private val actionsFlow: Flow = itemView.findViewById(R.id.detail_item_actions_flow)

        fun bind(notification: Notification) {
            this.notification = notification

            val context = itemView.context
            val unmatchedTags = unmatchedTags(splitTags(notification.tags))

            dateView.text = formatDateShort(notification.timestamp)
            messageView.text = maybeAppendActionErrors(formatMessage(notification), notification)
            messageView.setOnClickListener {
                // Click & Long-click listeners on the text as well, because "autoLink=web" makes them
                // clickable, and so we cannot rely on the underlying card to perform the action.
                // It's weird because "layout" is the ripple-able, but the card is clickable.
                // See https://github.com/binwiederhier/ntfy/issues/226
                layout.ripple(lifecycleScope)
                onClick(notification)
            }
            messageView.setOnLongClickListener {
                onLongClick(notification); true
            }
            newDotImageView.visibility = if (notification.notificationId == 0) View.GONE else View.VISIBLE
            cardView.setOnClickListener { onClick(notification) }
            cardView.setOnLongClickListener { onLongClick(notification); true }
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
                cardView.setCardBackgroundColor(Colors.cardSelectedBackgroundColor(context))
            } else {
                cardView.setCardBackgroundColor(Colors.cardBackgroundColor(context))
            }
            val attachment = notification.attachment
            val attachmentFileStat = maybeFileStat(context, attachment?.contentUri)
            val iconFileStat = maybeFileStat(context, notification.icon?.contentUri)
            renderPriority(context, notification)
            resetCardButtons()
            maybeRenderMenu(context, notification, attachmentFileStat)
            maybeRenderAttachment(context, notification, attachmentFileStat)
            maybeRenderIcon(context, notification, iconFileStat)
            maybeRenderActions(context, notification)
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

        private fun maybeRenderAttachment(context: Context, notification: Notification, attachmentFileStat: FileInfo?) {
            if (notification.attachment == null) {
                attachmentImageView.visibility = View.GONE
                attachmentBoxView.visibility = View.GONE
                return
            }
            val attachment = notification.attachment
            val image = attachment.contentUri != null && supportedImage(attachment.type) && previewableImage(attachmentFileStat)
            maybeRenderAttachmentImage(context, attachment, image)
            maybeRenderAttachmentBox(context, notification, attachment, attachmentFileStat, image)
        }

        private fun maybeRenderIcon(context: Context, notification: Notification, iconStat: FileInfo?) {
            if (notification.icon == null || !previewableImage(iconStat)) {
                iconView.visibility = View.GONE
                return
            }
            try {
                val icon = notification.icon
                val bitmap = icon.contentUri?.readBitmapFromUri(context) ?: throw Exception("uri empty")
                iconView.setImageBitmap(bitmap)
                iconView.visibility = View.VISIBLE
            } catch (_: Exception) {
                iconView.visibility = View.GONE
            }
        }

        private fun maybeRenderMenu(context: Context, notification: Notification, attachmentFileStat: FileInfo?) {
            val menuButtonPopupMenu = maybeCreateMenuPopup(context, menuButton, notification, attachmentFileStat) // Heavy lifting not during on-click
            if (menuButtonPopupMenu != null) {
                menuButton.setOnClickListener { menuButtonPopupMenu.show() }
                menuButton.visibility = View.VISIBLE
            } else {
                menuButton.visibility = View.GONE
            }
        }

        private fun maybeRenderActions(context: Context, notification: Notification) {
            if (notification.actions != null && notification.actions.isNotEmpty()) {
                actionsWrapperView.visibility = View.VISIBLE
                val actionsCount = Math.min(notification.actions.size, 3) // per documentation, only 3 actions are available
                for (i in 0 until actionsCount) {
                    val action = notification.actions[i]
                    val label = formatActionLabel(action)
                    val actionButton = createCardButton(context, label) { runAction(context, notification, action) }
                    addButtonToCard(actionButton)
                }
            } else {
                actionsWrapperView.visibility = View.GONE
            }
        }

        private fun resetCardButtons() {
            // clear any previously created dynamic buttons
            actionsFlow.allViews.forEach { it -> actionsFlow.removeView(it) }
            actionsWrapperView.removeAllViews()
            actionsWrapperView.addView(actionsFlow)
        }

        private fun addButtonToCard(button: View) {
            actionsWrapperView.addView(button)
            actionsFlow.addView(button)
        }

        private fun createCardButton(context: Context, label: String, onClick: () -> Boolean): View {
            // See https://stackoverflow.com/a/41139179/1440785
            val button = LayoutInflater.from(context).inflate(R.layout.button_action, null) as MaterialButton
            button.id = View.generateViewId()
            button.text = label
            button.setOnClickListener { onClick() }
            return button
        }

        private fun maybeRenderAttachmentBox(context: Context, notification: Notification, attachment: Attachment, attachmentFileStat: FileInfo?, image: Boolean) {
            if (image) {
                attachmentBoxView.visibility = View.GONE
                return
            }
            attachmentInfoView.text = formatAttachmentDetails(context, attachment, attachmentFileStat)
            attachmentIconView.setImageResource(mimeTypeToIconResource(attachment.type))
            val attachmentBoxPopupMenu = maybeCreateMenuPopup(context, attachmentBoxView, notification, attachmentFileStat) // Heavy lifting not during on-click
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

        private fun maybeCreateMenuPopup(context: Context, anchor: View?, notification: Notification, attachmentFileStat: FileInfo?): PopupMenu? {
            val popup = PopupMenu(context, anchor)
            popup.menuInflater.inflate(R.menu.menu_detail_attachment, popup.menu)
            val attachment = notification.attachment // May be null
            val hasAttachment = attachment != null
            val attachmentExists = attachmentFileStat != null
            val hasClickLink = notification.click != ""
            val downloadItem = popup.menu.findItem(R.id.detail_item_menu_download)
            val cancelItem = popup.menu.findItem(R.id.detail_item_menu_cancel)
            val openItem = popup.menu.findItem(R.id.detail_item_menu_open)
            val deleteItem = popup.menu.findItem(R.id.detail_item_menu_delete)
            val saveFileItem = popup.menu.findItem(R.id.detail_item_menu_save_file)
            val copyUrlItem = popup.menu.findItem(R.id.detail_item_menu_copy_url)
            val copyContentsItem = popup.menu.findItem(R.id.detail_item_menu_copy_contents)
            val expired = attachment?.expires != null && attachment.expires < System.currentTimeMillis()/1000
            val inProgress = attachment?.progress in 0..99
            if (attachment != null) {
                openItem.setOnMenuItemClickListener { openFile(context, attachment) }
                saveFileItem.setOnMenuItemClickListener { saveFile(context, attachment) }
                deleteItem.setOnMenuItemClickListener { deleteFile(context, notification, attachment) }
                copyUrlItem.setOnMenuItemClickListener { copyUrl(context, attachment) }
                downloadItem.setOnMenuItemClickListener { downloadFile(context, notification) }
                cancelItem.setOnMenuItemClickListener { cancelDownload(context, notification) }
            }
            if (hasClickLink) {
                copyContentsItem.setOnMenuItemClickListener { copyContents(context, notification) }
            }
            openItem.isVisible = hasAttachment && attachmentExists
            downloadItem.isVisible = hasAttachment && !attachmentExists && !expired && !inProgress
            deleteItem.isVisible = hasAttachment && attachmentExists
            saveFileItem.isVisible = hasAttachment && attachmentExists
            copyUrlItem.isVisible = hasAttachment && !expired
            cancelItem.isVisible = hasAttachment && inProgress
            copyContentsItem.isVisible = notification.click != ""
            val noOptions = !openItem.isVisible && !saveFileItem.isVisible && !downloadItem.isVisible
                    && !copyUrlItem.isVisible && !cancelItem.isVisible && !deleteItem.isVisible
                    && !copyContentsItem.isVisible
            if (noOptions) {
                return null
            }
            return popup
        }

        private fun formatAttachmentDetails(context: Context, attachment: Attachment, attachmentFileStat: FileInfo?): String {
            val name = attachment.name
            val exists = attachmentFileStat != null
            val notYetDownloaded = !exists && attachment.progress == ATTACHMENT_PROGRESS_NONE
            val downloading = !exists && attachment.progress in 0..99
            val deleted = !exists && (attachment.progress == ATTACHMENT_PROGRESS_DONE || attachment.progress == ATTACHMENT_PROGRESS_DELETED)
            val failed = !exists && attachment.progress == ATTACHMENT_PROGRESS_FAILED
            val expired = attachment.expires != null && attachment.expires < System.currentTimeMillis()/1000
            val expires = attachment.expires != null && attachment.expires > System.currentTimeMillis()/1000
            val infos = mutableListOf<String>()
            if (attachment.size != null) {
                infos.add(formatBytes(attachment.size))
            }
            if (notYetDownloaded) {
                if (expired) {
                    infos.add(context.getString(R.string.detail_item_download_info_not_downloaded_expired))
                } else if (expires) {
                    infos.add(context.getString(R.string.detail_item_download_info_not_downloaded_expires_x, formatDateShort(attachment.expires!!)))
                } else {
                    infos.add(context.getString(R.string.detail_item_download_info_not_downloaded))
                }
            } else if (downloading) {
                infos.add(context.getString(R.string.detail_item_download_info_downloading_x_percent, attachment.progress))
            } else if (deleted) {
                if (expired) {
                    infos.add(context.getString(R.string.detail_item_download_info_deleted_expired))
                } else if (expires) {
                    infos.add(context.getString(R.string.detail_item_download_info_deleted_expires_x, formatDateShort(attachment.expires!!)))
                } else {
                    infos.add(context.getString(R.string.detail_item_download_info_deleted))
                }
            } else if (failed) {
                if (expired) {
                    infos.add(context.getString(R.string.detail_item_download_info_download_failed_expired))
                } else if (expires) {
                    infos.add(context.getString(R.string.detail_item_download_info_download_failed_expires_x, formatDateShort(attachment.expires!!)))
                } else {
                    infos.add(context.getString(R.string.detail_item_download_info_download_failed))
                }
            }
            return if (infos.size > 0) {
                "$name\n${infos.joinToString(", ")}"
            } else {
                name
            }
        }

        private fun maybeRenderAttachmentImage(context: Context, attachment: Attachment, image: Boolean) {
            if (!image) {
                attachmentImageView.visibility = View.GONE
                return
            }
            try {
                val bitmap = attachment.contentUri?.readBitmapFromUri(context) ?: throw Exception("uri empty")
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

        private fun openFile(context: Context, attachment: Attachment): Boolean {
            Log.d(TAG, "Opening file ${attachment.contentUri}")
            try {
                val contentUri = Uri.parse(attachment.contentUri)
                val intent = Intent(Intent.ACTION_VIEW, contentUri)
                intent.setDataAndType(contentUri, attachment.type ?: "application/octet-stream") // Required for Android <= P
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast
                    .makeText(context, context.getString(R.string.detail_item_cannot_open_not_found), Toast.LENGTH_LONG)
                    .show()
            } catch (e: Exception) {
                Toast
                    .makeText(context, context.getString(R.string.detail_item_cannot_open, e.message), Toast.LENGTH_LONG)
                    .show()
            }
            return true
        }

        private fun saveFile(context: Context, attachment: Attachment): Boolean {
            Log.d(TAG, "Copying file ${attachment.contentUri}")
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, attachment.name)
                    if (attachment.type != null) {
                        put(MediaStore.MediaColumns.MIME_TYPE, attachment.type)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_DOWNLOAD, 1)
                        put(MediaStore.MediaColumns.IS_PENDING, 1) // While downloading
                    }
                }
                val inUri = Uri.parse(attachment.contentUri)
                val inFile = resolver.openInputStream(inUri) ?: throw Exception("Cannot open input stream")
                val outUri = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    val file = ensureSafeNewFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), attachment.name)
                    FileProvider.getUriForFile(context, DownloadAttachmentWorker.FILE_PROVIDER_AUTHORITY, file)
                } else {
                    val contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    resolver.insert(contentUri, values) ?: throw Exception("Cannot insert content")
                }
                val outFile = resolver.openOutputStream(outUri) ?: throw Exception("Cannot open output stream")
                inFile.use { it.copyTo(outFile) }
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    values.clear() // See #116 to avoid "movement" error
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(outUri, values, null, null)
                }
                val actualName = fileName(context, outUri.toString(), attachment.name)
                Toast
                    .makeText(context, context.getString(R.string.detail_item_saved_successfully, actualName), Toast.LENGTH_LONG)
                    .show()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save file: ${e.message}", e)
                Toast
                    .makeText(context, context.getString(R.string.detail_item_cannot_save, e.message), Toast.LENGTH_LONG)
                    .show()
            }
            return true
        }

        private fun deleteFile(context: Context, notification: Notification, attachment: Attachment): Boolean {
            try {
                val contentUri = Uri.parse(attachment.contentUri)
                val resolver = context.applicationContext.contentResolver
                val deleted = resolver.delete(contentUri, null, null) > 0
                if (!deleted) throw Exception("no rows deleted")
                val newAttachment = attachment.copy(
                    contentUri = null,
                    progress = ATTACHMENT_PROGRESS_DELETED
                )
                val newNotification = notification.copy(attachment = newAttachment)
                GlobalScope.launch(Dispatchers.IO) {
                    repository.updateNotification(newNotification)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update notification: ${e.message}", e)
                Toast
                    .makeText(context, context.getString(R.string.detail_item_cannot_delete, e.message), Toast.LENGTH_LONG)
                    .show()
            }
            return true
        }

        private fun downloadFile(context: Context, notification: Notification): Boolean {
            val requiresPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED
            if (requiresPermission) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_WRITE_STORAGE_PERMISSION_FOR_DOWNLOAD)
                return true
            }
            DownloadManager.enqueue(context, notification.id, userAction = true, DownloadType.ATTACHMENT)
            return true
        }

        private fun cancelDownload(context: Context, notification: Notification): Boolean {
            DownloadManager.cancel(context, notification.id)
            return true
        }

        private fun copyUrl(context: Context, attachment: Attachment): Boolean {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("attachment url", attachment.url)
            clipboard.setPrimaryClip(clip)
            Toast
                .makeText(context, context.getString(R.string.detail_item_menu_copy_url_copied), Toast.LENGTH_LONG)
                .show()
            return true
        }

        private fun copyContents(context: Context, notification: Notification): Boolean {
            copyToClipboard(context, notification)
            return true
        }

        private fun runAction(context: Context, notification: Notification, action: Action): Boolean {
            when (action.action) {
                ACTION_VIEW -> runViewAction(context, action)
                else -> runOtherUserAction(context, notification, action)
            }
            return true
        }

        private fun runViewAction(context: Context, action: Action) {
            try {
                val url = action.url ?: return
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start activity from URL ${action.url}", e)
                val message = if (e is ActivityNotFoundException) action.url else e.message
                Toast
                    .makeText(context, context.getString(R.string.detail_item_cannot_open_url, message), Toast.LENGTH_LONG)
                    .show()
            }
        }

        private fun runOtherUserAction(context: Context, notification: Notification, action: Action) {
            val intent = Intent(context, NotificationService.UserActionBroadcastReceiver::class.java).apply {
                putExtra(NotificationService.BROADCAST_EXTRA_TYPE, NotificationService.BROADCAST_TYPE_USER_ACTION)
                putExtra(NotificationService.BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
                putExtra(NotificationService.BROADCAST_EXTRA_ACTION_ID, action.id)
            }
            context.sendBroadcast(intent)
        }

        private fun previewableImage(fileStat: FileInfo?): Boolean {
            return if (fileStat != null) fileStat.size <= IMAGE_PREVIEW_MAX_BYTES else false
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
        const val REQUEST_CODE_WRITE_STORAGE_PERMISSION_FOR_DOWNLOAD = 9876
        const val IMAGE_PREVIEW_MAX_BYTES = 5 * 1024 * 1024 // Too large images crash the app with "Canvas: trying to draw too large(233280000bytes) bitmap."
    }
}
