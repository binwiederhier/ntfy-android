package io.heckel.ntfy.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    @ColumnInfo(name = "name") val name: String?, // Filename
    @ColumnInfo(name = "type") val type: String?, // MIME type
    @ColumnInfo(name = "size") val size: Long?, // Size in bytes
    @ColumnInfo(name = "expires") val expires: Long?, // Unix timestamp
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "contentUri") val contentUri: String?,
    @ColumnInfo(name = "progress") val progress: Int,
) {
    constructor(name: String?, type: String?, size: Long?, expires: Long?, url: String) :
            this(name, type, size, expires, url, null, 0)
}

@androidx.room.Database(entities = [Subscription::class, Notification::class], version = 6)
abstract class Database : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun notificationDao(): NotificationDao

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

    @Query("DELETE FROM notification WHERE subscriptionId = :subscriptionId")
    fun removeAll(subscriptionId: Long)
}
