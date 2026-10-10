package com.example.hamkit.data.qso

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(
    tableName = "qso_records",
    indices = [androidx.room.Index("qso_time")]
)
data class QsoRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "callsign")
    val callsign: String,
    @ColumnInfo(name = "grid")
    val grid: String? = null,
    @ColumnInfo(name = "report_sent")
    val reportSent: String = "-99",
    @ColumnInfo(name = "report_received")
    val reportReceived: String = "-99",
    @ColumnInfo(name = "band")
    val band: String = Ft8Band.BAND_40M.name,
    @ColumnInfo(name = "freq_hz")
    val freqHz: Long = Ft8Band.BAND_40M.freqHz,
    @ColumnInfo(name = "mode")
    val mode: String = "FT8",
    @ColumnInfo(name = "qso_time")
    val qsoTime: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_complete")
    val isComplete: Boolean = false,
    @ColumnInfo(name = "operator")
    val operator: String? = null,
    @ColumnInfo(name = "my_grid")
    val myGrid: String? = null,
    @ColumnInfo(name = "comment")
    val comment: String? = null,
)

@Dao
interface QsoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: QsoRecordEntity): Long

    @Update
    suspend fun update(record: QsoRecordEntity)

    @Query("DELETE FROM qso_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM qso_records ORDER BY qso_time DESC")
    fun getAll(): Flow<List<QsoRecordEntity>>

    /** 自动记录去重：同呼号+频段、未完成且时间在窗口内的最近一条 */
    @Query(
        "SELECT * FROM qso_records WHERE callsign = :callsign AND band = :band " +
            "AND is_complete = 0 AND qso_time >= :since ORDER BY qso_time DESC LIMIT 1"
    )
    suspend fun findRecentIncomplete(callsign: String, band: String, since: Long): QsoRecordEntity?
}

@Database(
    entities = [QsoRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class QsoDatabase : RoomDatabase() {
    abstract fun qsoDao(): QsoDao

    companion object {
        @Volatile
        private var INSTANCE: QsoDatabase? = null

        fun getDatabase(context: Context): QsoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QsoDatabase::class.java,
                    "qso_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * QSO 通联日志仓库：FT8 通联记录的持久化与增删改查。
 *
 * 与 APRS 消息存储（AprsMessageStore）完全独立，二者数据互不合并。
 */
class QsoStore private constructor(
    private val dao: QsoDao
) {
    constructor(context: Context) : this(QsoDatabase.getDatabase(context).qsoDao())

    /** 全部记录，按时间倒序 */
    val records: Flow<List<Ft8QsoRecord>> = dao.getAll().map { list ->
        list.map { it.toModel() }
    }

    suspend fun insert(record: Ft8QsoRecord): Long = dao.insert(record.toEntity())

    suspend fun update(record: Ft8QsoRecord) = dao.update(record.toEntity())

    suspend fun upsert(record: Ft8QsoRecord): Long {
        return if (record.id == 0L) dao.insert(record.toEntity()) else {
            dao.update(record.toEntity())
            record.id
        }
    }

    suspend fun delete(record: Ft8QsoRecord) = dao.deleteById(record.id)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun findRecentIncomplete(callsign: String, band: Ft8Band, since: Long): Ft8QsoRecord? =
        dao.findRecentIncomplete(callsign.trim().uppercase(), band.name, since)?.toModel()

    private fun QsoRecordEntity.toModel() = Ft8QsoRecord(
        id = id,
        callsign = callsign,
        grid = grid,
        reportSent = reportSent,
        reportReceived = reportReceived,
        band = Ft8Band.entries.find { it.name == band } ?: Ft8Band.BAND_40M,
        freqHz = freqHz,
        mode = mode,
        qsoTime = qsoTime,
        isComplete = isComplete,
        operator = operator,
        myGrid = myGrid,
        comment = comment,
    )

    private fun Ft8QsoRecord.toEntity() = QsoRecordEntity(
        id = id,
        callsign = callsign.trim().uppercase(),
        grid = grid?.trim()?.uppercase()?.ifEmpty { null },
        reportSent = reportSent.ifBlank { "-99" },
        reportReceived = reportReceived.ifBlank { "-99" },
        band = band.name,
        freqHz = freqHz,
        mode = mode.trim().uppercase().ifEmpty { "FT8" },
        qsoTime = qsoTime,
        isComplete = isComplete,
        operator = operator?.trim()?.uppercase()?.ifEmpty { null },
        myGrid = myGrid?.trim()?.uppercase()?.ifEmpty { null },
        comment = comment?.trim()?.ifEmpty { null },
    )
}
