package io.heckel.ntfy.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.heckel.ntfy.util.shortUrl
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["baseUrl", "topic"], unique = true), Index(value = ["upConnectorToken"], unique = true)])
data class Subscription(
    @PrimaryKey val id: Long, // Internal ID, only used in Repository and activities
    @ColumnInfo(name = "baseUrl") val baseUrl: String,
    @ColumnInfo(name = "topic") val topic: String,
    @ColumnInfo(name = "instant") val instant: Boolean,
    @ColumnInfo(name = "mutedUntil") val mutedUntil: Long, // TODO notificationSound, notificationSchedule
    @ColumnInfo(name = "upAppId") val upAppId: String?, // UnifiedPush application package name
    @ColumnInfo(name = "upConnectorToken") val upConnectorToken: String?, // UnifiedPush connector token
    // TODO autoDownloadAttachments, minPriority
    @Ignore val totalCount: Int = 0, // Total notifications
    @Ignore val newCount: Int = 0, // New notifications
    @Ignore val lastActive: Long = 0, // Unix timestamp
    @Ignore val state: ConnectionState = ConnectionState.NOT_APPLICABLE
) {
    constructor(id: Long, baseUrl: String, topic: String, instant: Boolean, mutedUntil: Long, upAppId: String, upConnectorToken: String) :
            this(id, baseUrl, topic, instant, mutedUntil, upAppId, upConnectorToken, 0, 0, 0, ConnectionState.NOT_APPLICABLE)
}

enum class ConnectionState {
    NOT_APPLICABLE, CONNECTING, CONNECTED
}

data class SubscriptionWithMetadata(
    val id: Long,
    val baseUrl: String,
    val topic: String,
    val instant: Boolean,
    val mutedUntil: Long,
    val upAppId: String?,
    val upConnectorToken: String?,
    val totalCount: Int,
    val newCount: Int,
    val lastActive: Long
)

@Entity(primaryKeys = ["id", "subscriptionId"])
data class Notification(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "subscriptionId") val subscriptionId: Long,
    @ColumnInfo(name = "timestamp") val timestamp: Long, // Unix timestamp
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "notificationId") val notificationId: Int, // Android notification popup ID
    @ColumnInfo(name = "priority", defaultValue = "3") val priority: Int, // 1=min, 3=default, 5=max
    @ColumnInfo(name = "tags") val tags: String,
    @ColumnInfo(name = "click") val click: String, // URL/intent to open on notification click
    @Embedded(prefix = "attachment_") val attachment: Attachment?,
    @ColumnInfo(name = "deleted") val deleted: Boolean,
)

@Entity
data class Attachment(
    @ColumnInfo(name = "name") val name: String, // Filename
    @ColumnInfo(name = "type") val type: String?, // MIME type
    @ColumnInfo(name = "size") val size: Long?, // Size in bytes
    @ColumnInfo(name = "expires") val expires: Long?, // Unix timestamp
    @ColumnInfo(name = "url") val url: String, // URL (mandatory, see ntfy server)
    @ColumnInfo(name = "contentUri") val contentUri: String?, // After it's downloaded, the content:// location
    @ColumnInfo(name = "progress") val progress: Int, // Progress during download, -1 if not downloaded
) {
    constructor(name: String, type: String?, size: Long?, expires: Long?, url: String) :
            this(name, type, size, expires, url, null, PROGRESS_NONE)
}

const val PROGRESS_NONE = -1
const val PROGRESS_INDETERMINATE = -2
const val PROGRESS_FAILED = -3
const val PROGRESS_DELETED = -4
const val PROGRESS_DONE = 100

@Entity
data class User(
    @PrimaryKey @ColumnInfo(name = "baseUrl") val baseUrl: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password") val password: String
) {
    override fun toString(): String = username
}

@Entity(tableName = "Log")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long, // Internal ID, only used in Repository and activities
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "tag") val tag: String,
    @ColumnInfo(name = "level") val level: Int,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "exception") val exception: String?
) {
    constructor(timestamp: Long, tag: String, level: Int, message: String, exception: String?) :
            this(0, timestamp, tag, level, message, exception)
}

@androidx.room.Database(entities = [Subscription::class, Notification::class, User::class, LogEntry::class], version = 8)
abstract class Database : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun notificationDao(): NotificationDao
    abstract fun userDao(): UserDao
    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var instance: Database? = null

        fun getInstance(context: Context): Database {
            return instance ?: synchronized(this) {
                val instance = Room
                    .databaseBuilder(context.applicationContext, Database::class.java,"AppDatabase")
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
                    .addMigrations(MIGRATION_6_7)
                    .addMigrations(MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                this.instance = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop "notifications" & "lastActive" columns (SQLite does not support dropping columns, ...)
                db.execSQL("CREATE TABLE Subscription_New (id INTEGER NOT NULL, baseUrl TEXT NOT NULL, topic TEXT NOT NULL, instant INTEGER NOT NULL DEFAULT('0'), PRIMARY KEY(id))")
                db.execSQL("INSERT INTO Subscription_New SELECT id, baseUrl, topic, 0 FROM Subscription")
                db.execSQL("DROP TABLE Subscription")
                db.execSQL("ALTER TABLE Subscription_New RENAME TO Subscription")
                db.execSQL("CREATE UNIQUE INDEX index_Subscription_baseUrl_topic ON Subscription (baseUrl, topic)")

                // Add "notificationId" & "deleted" columns
                db.execSQL("ALTER TABLE Notification ADD COLUMN notificationId INTEGER NOT NULL DEFAULT('0')")
                db.execSQL("ALTER TABLE Notification ADD COLUMN deleted INTEGER NOT NULL DEFAULT('0')")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Subscription ADD COLUMN mutedUntil INTEGER NOT NULL DEFAULT('0')")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE Notification_New (id TEXT NOT NULL, subscriptionId INTEGER NOT NULL, timestamp INTEGER NOT NULL, title TEXT NOT NULL, message TEXT NOT NULL, notificationId INTEGER NOT NULL, priority INTEGER NOT NULL DEFAULT(3), tags TEXT NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(id, subscriptionId))")
                db.execSQL("INSERT INTO Notification_New SELECT id, subscriptionId, timestamp, '', message, notificationId, 3, '', deleted FROM Notification")
                db.execSQL("DROP TABLE Notification")
                db.execSQL("ALTER TABLE Notification_New RENAME TO Notification")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Subscription ADD COLUMN upAppId TEXT")
                db.execSQL("ALTER TABLE Subscription ADD COLUMN upConnectorToken TEXT")
                db.execSQL("CREATE UNIQUE INDEX index_Subscription_upConnectorToken ON Subscription (upConnectorToken)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Notification ADD COLUMN click TEXT NOT NULL DEFAULT('')")
                db.execSQL("ALTER TABLE Notification ADD COLUMN attachment_name TEXT") // Room limitation: Has to be nullable for @Embedded
                db.execSQL("ALTER TABLE Notification ADD COLUMN attachment_type TEXT")
                db.execSQL("ALTER TABLE Notification ADD COLUMN attachment_size INT")
                db.execSQL("ALTER TABLE Notification ADD COLUMN attachment_expires INT")
                db.execSQL("ALTER TABLE Notification ADD COLUMN attachment_url TEXT") // Room limitation: Has to be nullable for @Embedded
                db.execSQL("ALTER TABLE Notification ADD COLUMN attachment_contentUri TEXT")
                db.execSQL("ALTER TABLE Notification ADD COLUMN attachment_progress INT") // Room limitation: Has to be nullable for @Embedded
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE Log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INT NOT NULL, tag TEXT NOT NULL, level INT NOT NULL, message TEXT NOT NULL, exception TEXT)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE User (baseUrl TEXT NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL, PRIMARY KEY(baseUrl))")
            }
        }
    }
}

@Dao
interface SubscriptionDao {
    @Query("""
        SELECT 
          s.id, s.baseUrl, s.topic, s.instant, s.mutedUntil, s.upAppId, s.upConnectorToken,
          COUNT(n.id) totalCount, 
          COUNT(CASE n.notificationId WHEN 0 THEN NULL ELSE n.id END) newCount, 
          IFNULL(MAX(n.timestamp),0) AS lastActive
        FROM Subscription AS s
        LEFT JOIN Notification AS n ON s.id=n.subscriptionId AND n.deleted != 1
        GROUP BY s.id
        ORDER BY s.upAppId ASC, MAX(n.timestamp) DESC
    """)
    fun listFlow(): Flow<List<SubscriptionWithMetadata>>

    @Query("""
        SELECT 
          s.id, s.baseUrl, s.topic, s.instant, s.mutedUntil, s.upAppId, s.upConnectorToken,
          COUNT(n.id) totalCount, 
          COUNT(CASE n.notificationId WHEN 0 THEN NULL ELSE n.id END) newCount, 
          IFNULL(MAX(n.timestamp),0) AS lastActive
        FROM Subscription AS s
        LEFT JOIN Notification AS n ON s.id=n.subscriptionId AND n.deleted != 1
        GROUP BY s.id
        ORDER BY s.upAppId ASC, MAX(n.timestamp) DESC
    """)
    fun list(): List<SubscriptionWithMetadata>

    @Query("""
        SELECT 
          s.id, s.baseUrl, s.topic, s.instant, s.mutedUntil, s.upAppId, s.upConnectorToken,
          COUNT(n.id) totalCount, 
          COUNT(CASE n.notificationId WHEN 0 THEN NULL ELSE n.id END) newCount, 
          IFNULL(MAX(n.timestamp),0) AS lastActive
        FROM Subscription AS s
        LEFT JOIN Notification AS n ON s.id=n.subscriptionId AND n.deleted != 1
        WHERE s.baseUrl = :baseUrl AND s.topic = :topic
        GROUP BY s.id
    """)
    fun get(baseUrl: String, topic: String): SubscriptionWithMetadata?

    @Query("""
        SELECT 
          s.id, s.baseUrl, s.topic, s.instant, s.mutedUntil, s.upAppId, s.upConnectorToken,
          COUNT(n.id) totalCount, 
          COUNT(CASE n.notificationId WHEN 0 THEN NULL ELSE n.id END) newCount, 
          IFNULL(MAX(n.timestamp),0) AS lastActive
        FROM Subscription AS s
        LEFT JOIN Notification AS n ON s.id=n.subscriptionId AND n.deleted != 1
        WHERE s.id = :subscriptionId
        GROUP BY s.id
    """)
    fun get(subscriptionId: Long): SubscriptionWithMetadata?

    @Query("""
        SELECT 
          s.id, s.baseUrl, s.topic, s.instant, s.mutedUntil, s.upAppId, s.upConnectorToken,
          COUNT(n.id) totalCount, 
          COUNT(CASE n.notificationId WHEN 0 THEN NULL ELSE n.id END) newCount, 
          IFNULL(MAX(n.timestamp),0) AS lastActive
        FROM Subscription AS s
        LEFT JOIN Notification AS n ON s.id=n.subscriptionId AND n.deleted != 1
        WHERE s.upConnectorToken = :connectorToken
        GROUP BY s.id
    """)
    fun getByConnectorToken(connectorToken: String): SubscriptionWithMetadata?

    @Insert
    fun add(subscription: Subscription)

    @Update
    fun update(subscription: Subscription)

    @Query("DELETE FROM subscription WHERE id = :subscriptionId")
    fun remove(subscriptionId: Long)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notification WHERE subscriptionId = :subscriptionId AND deleted != 1 ORDER BY timestamp DESC")
    fun listFlow(subscriptionId: Long): Flow<List<Notification>>

    @Query("SELECT id FROM notification WHERE subscriptionId = :subscriptionId") // Includes deleted
    fun listIds(subscriptionId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun add(notification: Notification)

    @Update(onConflict = OnConflictStrategy.IGNORE)
    fun update(notification: Notification)

    @Query("SELECT * FROM notification WHERE id = :notificationId")
    fun get(notificationId: String): Notification?

    @Query("UPDATE notification SET notificationId = 0 WHERE subscriptionId = :subscriptionId")
    fun clearAllNotificationIds(subscriptionId: Long)

    @Query("UPDATE notification SET deleted = 1 WHERE id = :notificationId")
    fun markAsDeleted(notificationId: String)

    @Query("UPDATE notification SET deleted = 1 WHERE subscriptionId = :subscriptionId")
    fun markAllAsDeleted(subscriptionId: Long)

    @Query("UPDATE notification SET deleted = 1 WHERE timestamp < :olderThanTimestamp")
    fun markAsDeletedIfOlderThan(olderThanTimestamp: Long)

    @Query("UPDATE notification SET deleted = 0 WHERE id = :notificationId")
    fun undelete(notificationId: String)

    @Query("DELETE FROM notification WHERE timestamp < :olderThanTimestamp")
    fun removeIfOlderThan(olderThanTimestamp: Long)

    @Query("DELETE FROM notification WHERE subscriptionId = :subscriptionId")
    fun removeAll(subscriptionId: Long)
}

@Dao
interface UserDao {
    @Insert
    suspend fun insert(user: User)

    @Query("SELECT * FROM user ORDER BY username")
    suspend fun list(): List<User>

    @Query("SELECT * FROM user WHERE baseUrl = :baseUrl")
    suspend fun get(baseUrl: String): User?

    @Update
    suspend fun update(user: User)

    @Query("DELETE FROM user WHERE baseUrl = :baseUrl")
    suspend fun delete(baseUrl: String)
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry)

    @Query("DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY timestamp DESC, id DESC LIMIT :keepCount)")
    suspend fun prune(keepCount: Int)

    @Query("SELECT * FROM log ORDER BY timestamp ASC, id ASC")
    fun getAll(): List<LogEntry>

    @Query("DELETE FROM log")
    fun deleteAll()
}
