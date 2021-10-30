package io.heckel.ntfy.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["baseUrl", "topic"], unique = true)])
data class Subscription(
    @PrimaryKey val id: Long, // Internal ID, only used in Repository and activities
    @ColumnInfo(name = "baseUrl") val baseUrl: String,
    @ColumnInfo(name = "topic") val topic: String,
    @ColumnInfo(name = "messages")  val messages: Int
)

@androidx.room.Database(entities = [Subscription::class], version = 1)
abstract class Database : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao

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
    @Query("SELECT * FROM subscription")
    fun list(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscription WHERE baseUrl = :baseUrl AND topic = :topic")
    fun get(baseUrl: String, topic: String): Subscription?

    @Insert
    fun add(subscription: Subscription)

    @Update
    fun update(subscription: Subscription)

    @Delete
    fun remove(subscription: Subscription)
}
