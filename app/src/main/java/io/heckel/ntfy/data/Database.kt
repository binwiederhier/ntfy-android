package io.heckel.ntfy.data

import android.content.Context
import androidx.annotation.NonNull
import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["baseUrl", "topic"], unique = true)])
data class Subscription(
    @PrimaryKey val id: Long, // Internal ID, only used in Repository and activities
    @ColumnInfo(name = "baseUrl") val baseUrl: String,
    @ColumnInfo(name = "topic") val topic: String,
    @ColumnInfo(name = "instant") val instant: Boolean,
    @Ignore val notifications: Int,
    @Ignore val lastActive: Long = 0 // Unix timestamp
) {
    constructor(id: Long, baseUrl: String, topic: String, instant: Boolean) : this(id, baseUrl, topic, instant, 0, 0)
}

data class SubscriptionWithMetadata(
    val id: Long,
    val baseUrl: String,
    val topic: String,
    val instant: Boolean,
    val notifications: Int,
    val lastActive: Long
)

@Entity
data class Notification(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "subscriptionId") val subscriptionId: Long,
    @ColumnInfo(name = "timestamp") val timestamp: Long, // Unix timestamp
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "deleted") val deleted: Boolean
)

@androidx.room.Database(entities = [Subscription::class, Notification::class], version = 2)
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
                db.execSQL("INSERT INTO Subscription_New SELECT id, baseUrl, topic FROM Subscription")
                db.execSQL("DROP TABLE Subscription")
                db.execSQL("ALTER TABLE Subscription_New RENAME TO Subscription")
                db.execSQL("CREATE UNIQUE INDEX index_Subscription_baseUrl_topic ON Subscription (baseUrl, topic)")

                // Add "deleted" column
                db.execSQL("ALTER TABLE Notification ADD COLUMN deleted INTEGER NOT NULL DEFAULT('0')")
            }
        }
    }
}

@Dao
interface SubscriptionDao {
    @Query(
        "SELECT s.id, s.baseUrl, s.topic, s.instant, COUNT(n.id) notifications, IFNULL(MAX(n.timestamp),0) AS lastActive " +
        "FROM subscription AS s " +
        "LEFT JOIN notification AS n ON s.id=n.subscriptionId AND n.deleted != 1 " +
        "GROUP BY s.id " +
        "ORDER BY MAX(n.timestamp) DESC"
    )
    fun listFlow(): Flow<List<SubscriptionWithMetadata>>

    @Query(
        "SELECT s.id, s.baseUrl, s.topic, s.instant, COUNT(n.id) notifications, IFNULL(MAX(n.timestamp),0) AS lastActive " +
        "FROM subscription AS s " +
        "LEFT JOIN notification AS n ON s.id=n.subscriptionId AND n.deleted != 1 " +
        "GROUP BY s.id " +
        "ORDER BY MAX(n.timestamp) DESC"
    )
    fun list(): List<SubscriptionWithMetadata>

    @Query(
        "SELECT s.id, s.baseUrl, s.topic, s.instant, COUNT(n.id) notifications, IFNULL(MAX(n.timestamp),0) AS lastActive " +
        "FROM subscription AS s " +
        "LEFT JOIN notification AS n ON s.id=n.subscriptionId AND n.deleted != 1 " +
        "WHERE s.baseUrl = :baseUrl AND s.topic = :topic " +
        "GROUP BY s.id "
    )
    fun get(baseUrl: String, topic: String): SubscriptionWithMetadata?

    @Query(
        "SELECT s.id, s.baseUrl, s.topic, s.instant, COUNT(n.id) notifications, IFNULL(MAX(n.timestamp),0) AS lastActive " +
        "FROM subscription AS s " +
        "LEFT JOIN notification AS n ON s.id=n.subscriptionId AND n.deleted != 1 " +
        "WHERE s.id = :subscriptionId " +
        "GROUP BY s.id "
    )
    fun get(subscriptionId: Long): SubscriptionWithMetadata?

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
    fun list(subscriptionId: Long): Flow<List<Notification>>

    @Query("SELECT id FROM notification WHERE subscriptionId = :subscriptionId") // Includes deleted
    fun listIds(subscriptionId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun add(notification: Notification)

    @Query("SELECT * FROM notification WHERE id = :notificationId")
    fun get(notificationId: String): Notification?

    @Query("UPDATE notification SET deleted = 1 WHERE id = :notificationId")
    fun remove(notificationId: String)

    @Query("DELETE FROM notification WHERE subscriptionId = :subscriptionId")
    fun removeAll(subscriptionId: Long)
}
