package io.heckel.ntfy.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["baseUrl", "topic"], unique = true)])
data class Subscription(
    @PrimaryKey val id: Long, // Internal ID, only used in Repository and activities
    @ColumnInfo(name = "baseUrl") val baseUrl: String,
    @ColumnInfo(name = "topic") val topic: String,
    @ColumnInfo(name = "notifications") val notifications: Int,
    @ColumnInfo(name = "lastActive") val lastActive: Long, // Unix timestamp
)

@Entity
data class Notification(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "subscriptionId") val subscriptionId: Long,
    @ColumnInfo(name = "timestamp") val timestamp: Long, // Unix timestamp
    @ColumnInfo(name = "message")  val message: String
)

@androidx.room.Database(entities = [Subscription::class, Notification::class], version = 1)
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
                    .fallbackToDestructiveMigration()
                    .build()
                this.instance = instance
                instance
            }
        }
    }
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription ORDER BY lastActive DESC")
    fun list(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscription WHERE baseUrl = :baseUrl AND topic = :topic")
    fun get(baseUrl: String, topic: String): Subscription?

    @Insert
    fun add(subscription: Subscription)

    @Update
    fun update(subscription: Subscription)

    @Query("DELETE FROM subscription WHERE id = :subscriptionId")
    fun remove(subscriptionId: Long)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notification WHERE subscriptionId = :subscriptionId ORDER BY timestamp DESC")
    fun list(subscriptionId: Long): Flow<List<Notification>>

    @Query("SELECT id FROM notification WHERE subscriptionId = :subscriptionId")
    fun listIds(subscriptionId: Long): List<String>

    @Insert
    fun add(notification: Notification)

    @Query("DELETE FROM notification WHERE id = :notificationId")
    fun remove(notificationId: String)

    @Query("DELETE FROM notification WHERE subscriptionId = :subscriptionId")
    fun removeAll(subscriptionId: Long)
}
