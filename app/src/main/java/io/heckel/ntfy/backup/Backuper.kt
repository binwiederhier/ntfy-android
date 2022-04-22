package io.heckel.ntfy.backup

import android.content.Context
import android.net.Uri
import androidx.room.ColumnInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.topicUrl
import java.io.InputStreamReader

class Backuper(val context: Context) {
    private val gson = Gson()
    private val resolver = context.applicationContext.contentResolver
    private val repository = (context.applicationContext as Application).repository

    suspend fun backup(uri: Uri, withSettings: Boolean = true, withSubscriptions: Boolean = true, withUsers: Boolean = true) {
        Log.d(TAG, "Backing up settings to file $uri")
        val json = gson.toJson(createBackupFile(withSettings, withSubscriptions, withUsers))
        val outputStream = resolver.openOutputStream(uri) ?: throw Exception("Cannot open output stream")
        outputStream.use { it.write(json.toByteArray()) }
        Log.d(TAG, "Backup done")
    }

    suspend fun restore(uri: Uri) {
        Log.d(TAG, "Restoring settings from file $uri")
        val reader = JsonReader(InputStreamReader(resolver.openInputStream(uri)))
        val backupFile = gson.fromJson<BackupFile>(reader, BackupFile::class.java)
        applyBackupFile(backupFile)
        Log.d(TAG, "Restoring done")
    }

    fun settingsAsString(): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(createSettings())
    }

    private suspend fun applyBackupFile(backupFile: BackupFile) {
        if (backupFile.magic != FILE_MAGIC) {
            throw InvalidBackupFileException()
        }
        applySettings(backupFile.settings)
        applySubscriptions(backupFile.subscriptions)
        applyNotifications(backupFile.notifications)
        applyUsers(backupFile.users)
    }

    private fun applySettings(settings: Settings?) {
        if (settings == null) {
            return
        }
        if (settings.minPriority != null) {
            repository.setMinPriority(settings.minPriority)
        }
        if (settings.autoDownloadMaxSize != null) {
            repository.setAutoDownloadMaxSize(settings.autoDownloadMaxSize)
        }
        if (settings.autoDeleteSeconds != null) {
            repository.setAutoDeleteSeconds(settings.autoDeleteSeconds)
        }
        if (settings.darkMode != null) {
            repository.setDarkMode(settings.darkMode)
        }
        if (settings.connectionProtocol != null) {
            repository.setConnectionProtocol(settings.connectionProtocol)
        }
        if (settings.broadcastEnabled != null) {
            repository.setBroadcastEnabled(settings.broadcastEnabled)
        }
        if (settings.recordLogs != null) {
            repository.setRecordLogsEnabled(settings.recordLogs)
        }
        if (settings.defaultBaseUrl != null) {
            repository.setDefaultBaseUrl(settings.defaultBaseUrl)
        }
        if (settings.mutedUntil != null) {
            repository.setGlobalMutedUntil(settings.mutedUntil)
        }
        if (settings.lastSharedTopics != null) {
            settings.lastSharedTopics.forEach { repository.addLastShareTopic(it) }
        }
    }

    private suspend fun applySubscriptions(subscriptions: List<Subscription>?) {
        if (subscriptions == null) {
            return;
        }
        subscriptions.forEach { s ->
            try {
                repository.addSubscription(io.heckel.ntfy.db.Subscription(
                    id = s.id,
                    baseUrl = s.baseUrl,
                    topic = s.topic,
                    instant = s.instant,
                    mutedUntil = s.mutedUntil,
                    upAppId = s.upAppId,
                    upConnectorToken = s.upConnectorToken
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to restore subscription ${s.id} (${topicUrl(s.baseUrl, s.topic)}): ${e.message}. Ignoring.", e)
            }
        }
    }

    private suspend fun applyNotifications(notifications: List<Notification>?) {
        if (notifications == null) {
            return;
        }
        notifications.forEach { n ->
            try {
                val actions = if (n.actions != null) {
                    n.actions.map { a ->
                        io.heckel.ntfy.db.Action(
                            id = a.id,
                            action = a.action,
                            label = a.label,
                            url = a.url,
                            method = a.method,
                            headers = a.headers,
                            body = a.body,
                            intent = a.intent,
                            extras = a.extras,
                            progress = a.progress,
                            error = a.error
                        )
                    }
                } else {
                    null
                }
                val attachment = if (n.attachment != null) {
                    io.heckel.ntfy.db.Attachment(
                        name = n.attachment.name,
                        type = n.attachment.type,
                        size = n.attachment.size,
                        expires = n.attachment.expires,
                        url = n.attachment.url,
                        contentUri = n.attachment.contentUri,
                        progress = n.attachment.progress,
                    )
                } else {
                    null
                }
                repository.addNotification(io.heckel.ntfy.db.Notification(
                    id = n.id,
                    subscriptionId = n.subscriptionId,
                    timestamp = n.timestamp,
                    title = n.title,
                    message = n.message,
                    encoding = n.encoding,
                    notificationId = 0,
                    priority = n.priority,
                    tags = n.tags,
                    click = n.click,
                    actions = actions,
                    attachment = attachment,
                    deleted = n.deleted
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to restore notification ${n.id}: ${e.message}. Ignoring.", e)
            }
        }
    }

    private suspend fun applyUsers(users: List<User>?) {
        if (users == null) {
            return;
        }
        users.forEach { u ->
            try {
                repository.addUser(io.heckel.ntfy.db.User(
                    baseUrl = u.baseUrl,
                    username = u.username,
                    password = u.password
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to restore user ${u.baseUrl} / ${u.username}: ${e.message}. Ignoring.", e)
            }
        }
    }

    private suspend fun createBackupFile(withSettings: Boolean, withSubscriptions: Boolean, withUsers: Boolean): BackupFile {
        return BackupFile(
            magic = FILE_MAGIC,
            version = FILE_VERSION,
            settings = if (withSettings) createSettings() else null,
            subscriptions = if (withSubscriptions) createSubscriptionList() else null,
            notifications = if (withSubscriptions) createNotificationList() else null,
            users = if (withUsers) createUserList() else null
        )
    }

    private fun createSettings(): Settings {
        return Settings(
            minPriority = repository.getMinPriority(),
            autoDownloadMaxSize = repository.getAutoDownloadMaxSize(),
            autoDeleteSeconds = repository.getAutoDeleteSeconds(),
            darkMode = repository.getDarkMode(),
            connectionProtocol = repository.getConnectionProtocol(),
            broadcastEnabled = repository.getBroadcastEnabled(),
            recordLogs = repository.getRecordLogs(),
            defaultBaseUrl = repository.getDefaultBaseUrl() ?: "",
            mutedUntil = repository.getGlobalMutedUntil(),
            lastSharedTopics = repository.getLastShareTopics()
        )
    }

    private suspend fun createSubscriptionList(): List<Subscription> {
        return repository.getSubscriptions().map { s ->
            Subscription(
                id = s.id,
                baseUrl = s.baseUrl,
                topic = s.topic,
                instant = s.instant,
                mutedUntil = s.mutedUntil,
                upAppId = s.upAppId,
                upConnectorToken = s.upConnectorToken
            )
        }
    }

    private suspend fun createNotificationList(): List<Notification> {
        return repository.getNotifications().map { n ->
            val actions = if (n.actions != null) {
                n.actions.map { a ->
                    Action(
                        id = a.id,
                        action = a.action,
                        label = a.label,
                        url = a.url,
                        method = a.method,
                        headers = a.headers,
                        body = a.body,
                        intent = a.intent,
                        extras = a.extras,
                        progress = a.progress,
                        error = a.error
                    )
                }
            } else {
                null
            }
            val attachment = if (n.attachment != null) {
                Attachment(
                    name = n.attachment.name,
                    type = n.attachment.type,
                    size = n.attachment.size,
                    expires = n.attachment.expires,
                    url = n.attachment.url,
                    contentUri = n.attachment.contentUri,
                    progress = n.attachment.progress,
                )
            } else {
                null
            }
            Notification(
                id = n.id,
                subscriptionId = n.subscriptionId,
                timestamp = n.timestamp,
                title = n.title,
                message = n.message,
                encoding = n.encoding,
                priority = n.priority,
                tags = n.tags,
                click = n.click,
                actions = actions,
                attachment = attachment,
                deleted = n.deleted
            )
        }
    }

    private suspend fun createUserList(): List<User> {
        return repository.getUsers().map { u ->
            User(
                baseUrl = u.baseUrl,
                username = u.username,
                password = u.password
            )
        }
    }

    companion object {
        const val MIME_TYPE = "application/json"
        private const val FILE_MAGIC = "ntfy2586"
        private const val FILE_VERSION = 1
        private const val TAG = "NtfyExporter"
    }
}

data class BackupFile(
    val magic: String,
    val version: Int,
    val settings: Settings?,
    val subscriptions: List<Subscription>?,
    val notifications: List<Notification>?,
    val users: List<User>?
)

data class Settings(
    val minPriority: Int?,
    val autoDownloadMaxSize: Long?,
    val autoDeleteSeconds: Long?,
    val darkMode: Int?,
    val connectionProtocol: String?,
    val broadcastEnabled: Boolean?,
    val recordLogs: Boolean?,
    val defaultBaseUrl: String?,
    val mutedUntil: Long?,
    val lastSharedTopics: List<String>?,
)

data class Subscription(
    val id: Long,
    val baseUrl: String,
    val topic: String,
    val instant: Boolean,
    val mutedUntil: Long,
    val upAppId: String?,
    val upConnectorToken: String?
)

data class Notification(
    val id: String,
    val subscriptionId: Long,
    val timestamp: Long,
    val title: String,
    val message: String,
    val encoding: String, // "base64" or ""
    val priority: Int, // 1=min, 3=default, 5=max
    val tags: String,
    val click: String, // URL/intent to open on notification click
    val actions: List<Action>?,
    val attachment: Attachment?,
    val deleted: Boolean
)

data class Action(
    val id: String, // Synthetic ID to identify result, and easily pass via Broadcast and WorkManager
    val action: String, // "view", "http" or "broadcast"
    val label: String,
    val url: String?, // used in "view" and "http" actions
    val method: String?, // used in "http" action
    val headers: Map<String,String>?, // used in "http" action
    val body: String?, // used in "http" action
    val intent: String?, // used in "broadcast" action
    val extras: Map<String,String>?, // used in "broadcast" action
    val progress: Int?, // used to indicate progress in popup
    val error: String? // used to indicate errors in popup
)

data class Attachment(
    val name: String, // Filename
    val type: String?, // MIME type
    val size: Long?, // Size in bytes
    val expires: Long?, // Unix timestamp
    val url: String, // URL (mandatory, see ntfy server)
    val contentUri: String?, // After it's downloaded, the content:// location
    val progress: Int, // Progress during download, -1 if not downloaded
)

data class User(
    val baseUrl: String,
    val username: String,
    val password: String
)

class InvalidBackupFileException : Exception("Invalid backup file format")
