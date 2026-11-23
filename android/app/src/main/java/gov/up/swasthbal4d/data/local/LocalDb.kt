package gov.up.swasthbal4d.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

/** One complete visit waiting to be synced (encrypted outbox, design 7.2). */
@Entity(tableName = "outbox")
data class OutboxVisit(
    @PrimaryKey val id: String,
    val childId: Int,
    val childName: String,
    val payloadJson: String,
    val provisionalColor: String?,
    val finalColor: String? = null,
    val status: String = "pending", // pending | synced | error
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val resultJson: String? = null,
)

/** Photo / video / audio waiting for upload. Raw media is deleted after a successful upload. */
@Entity(tableName = "pending_media")
data class PendingMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val visitId: String,
    val domain: String,
    val kind: String, // photo | video | audio
    val tag: String,
    val path: String,
    val priority: Int, // upload order: photo 1 -> audio 2 -> video 3
    val status: String = "pending",
)

/** Cached server JSON (offline pack, due list). */
@Entity(tableName = "kv")
data class Kv(@PrimaryKey val key: String, val json: String, val updatedAt: Long = System.currentTimeMillis())

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(v: OutboxVisit)
    @Query("SELECT * FROM outbox WHERE status != 'synced' ORDER BY createdAt") suspend fun pending(): List<OutboxVisit>
    @Query("SELECT COUNT(*) FROM outbox WHERE status != 'synced'") fun pendingCount(): Flow<Int>
    @Query("SELECT * FROM outbox WHERE id = :id") suspend fun get(id: String): OutboxVisit?
    @Query("SELECT * FROM outbox WHERE status = 'synced' AND resultJson IS NOT NULL ORDER BY createdAt DESC LIMIT 20")
    fun recentFinal(): Flow<List<OutboxVisit>>
    @Query("DELETE FROM outbox WHERE status = 'synced' AND createdAt < :before") suspend fun prune(before: Long)
}

@Dao
interface MediaDao {
    @Insert suspend fun add(m: PendingMedia): Long
    @Query("SELECT * FROM pending_media WHERE status = 'pending' ORDER BY priority, id") suspend fun pending(): List<PendingMedia>
    @Query("UPDATE pending_media SET status = :status WHERE id = :id") suspend fun mark(id: Long, status: String)
    @Query("SELECT COUNT(*) FROM pending_media WHERE status = 'pending'") fun pendingCount(): Flow<Int>
}

@Dao
interface KvDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(kv: Kv)
    @Query("SELECT * FROM kv WHERE `key` = :key") suspend fun get(key: String): Kv?
}

@Database(entities = [OutboxVisit::class, PendingMedia::class, Kv::class], version = 1, exportSchema = false)
abstract class LocalDb : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun media(): MediaDao
    abstract fun kv(): KvDao

    companion object {
        fun create(context: Context): LocalDb {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase(context))
            return Room.databaseBuilder(context, LocalDb::class.java, "swasthbal4d.db")
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }

        /** Random DB key kept in EncryptedSharedPreferences (backed by Android Keystore). */
        private fun passphrase(context: Context): ByteArray {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                context, "sb4d_keys", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val existing = prefs.getString("db_key", null)
            if (existing != null) return Base64.decode(existing, Base64.NO_WRAP)
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString("db_key", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
            return bytes
        }
    }
}
