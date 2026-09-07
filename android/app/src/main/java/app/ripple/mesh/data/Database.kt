package app.ripple.mesh.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class MessageStatus { PENDING, SENT, DELIVERED, RECEIVED, FAILED }

/**
 * One chat message. `conversation` is "broadcast" for the public channel, or the
 * hex NodeId of the other party for a direct conversation.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,          // hex
    val conversation: String,
    val fromNodeId: String,                     // hex
    val fromName: String?,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val status: MessageStatus,
    val verified: Boolean,
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,             // hex
    val publicKeyWire: ByteArray,
    val name: String,
    val lastSeen: Long,
    val hops: Int,
)

/** Relay-store persistence so store-and-forward survives process death. */
@Entity(tableName = "relay_packets")
data class RelayPacketEntity(
    @PrimaryKey val messageId: String,
    val bytes: ByteArray,
    val expiresAt: Long,
)

/** Received SOS beacon history (PROTOCOL.md §2.2), retained for ~90 days. */
@Entity(tableName = "sos_beacons")
data class SosBeaconEntity(
    @PrimaryKey val messageId: String,           // hex
    val fromNodeId: String,                      // hex
    val fromName: String?,
    val text: String,
    val latE7: Int?,
    val lngE7: Int?,
    val accuracyMeters: Int?,
    val verified: Boolean,
    val timestamp: Long,
)

data class ConversationSummary(val conversation: String, val lastText: String, val lastTimestamp: Long, val unread: Int)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun setStatus(messageId: String, status: MessageStatus)

    @Query("SELECT * FROM messages WHERE conversation = :conversation ORDER BY timestamp ASC")
    fun observeConversation(conversation: String): Flow<List<MessageEntity>>

    @Query(
        """SELECT conversation,
                  (SELECT text FROM messages m2 WHERE m2.conversation = m.conversation ORDER BY timestamp DESC LIMIT 1) AS lastText,
                  MAX(timestamp) AS lastTimestamp,
                  SUM(CASE WHEN status = 'RECEIVED' AND outgoing = 0 THEN 1 ELSE 0 END) AS unread
           FROM messages m GROUP BY conversation ORDER BY lastTimestamp DESC"""
    )
    fun observeConversations(): Flow<List<ConversationSummary>>

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE conversation = :conversation AND outgoing = 0 AND status = 'RECEIVED'")
    suspend fun markRead(conversation: String)

    @Query("SELECT COUNT(*) FROM messages WHERE messageId = :messageId")
    suspend fun exists(messageId: String): Int
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(peers: List<PeerEntity>)

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers")
    suspend fun all(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    fun observe(nodeId: String): Flow<PeerEntity?>
}

@Dao
interface RelayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(packets: List<RelayPacketEntity>)

    @Query("SELECT * FROM relay_packets WHERE expiresAt > :now")
    suspend fun live(now: Long): List<RelayPacketEntity>

    @Query("DELETE FROM relay_packets WHERE expiresAt <= :now")
    suspend fun purge(now: Long)
}

@Dao
interface SosBeaconDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(beacon: SosBeaconEntity)

    @Query("SELECT * FROM sos_beacons ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<SosBeaconEntity>>

    @Query("DELETE FROM sos_beacons WHERE timestamp < :cutoff")
    suspend fun prune(cutoff: Long)
}

/** v1 → v2: add the `sos_beacons` history table. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sos_beacons` (" +
                "`messageId` TEXT NOT NULL, " +
                "`fromNodeId` TEXT NOT NULL, " +
                "`fromName` TEXT, " +
                "`text` TEXT NOT NULL, " +
                "`latE7` INTEGER, " +
                "`lngE7` INTEGER, " +
                "`accuracyMeters` INTEGER, " +
                "`verified` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`messageId`))"
        )
    }
}

@Database(entities = [MessageEntity::class, PeerEntity::class, RelayPacketEntity::class, SosBeaconEntity::class], version = 2, exportSchema = false)
abstract class RippleDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun peers(): PeerDao
    abstract fun relay(): RelayDao
    abstract fun sos(): SosBeaconDao

    companion object {
        /** Received SOS beacons are retained for ~90 days before being pruned. */
        const val SOS_RETENTION_MS = 90L * 24 * 3600 * 1000

        @Volatile private var instance: RippleDatabase? = null
        fun get(context: Context): RippleDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RippleDatabase::class.java, "ripple.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
