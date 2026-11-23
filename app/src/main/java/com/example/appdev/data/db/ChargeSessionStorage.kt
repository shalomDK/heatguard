package com.example.appdev.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.example.appdev.domain.charging.ChargeSession
import com.example.appdev.domain.charging.ChargeSessionRepository
import com.example.appdev.domain.model.PowerSource
import com.example.appdev.domain.model.ReadingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "charge_sessions", indices = [Index("source", "startMillis")])
data class ChargeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val startMillis: Long,
    val endMillis: Long?,
    val lastSeenMillis: Long,
    val powerSource: String,
    val startTempC: Float,
    val peakTempC: Float,
    val tempSumC: Double,
    val readingCount: Int,
    val startPercent: Int?,
    val lastPercent: Int?,
)

@Dao
interface ChargeSessionDao {
    @Insert
    suspend fun insert(entity: ChargeSessionEntity): Long

    @Update
    suspend fun update(entity: ChargeSessionEntity)

    @Query("SELECT * FROM charge_sessions WHERE source = :source AND endMillis IS NULL ORDER BY startMillis DESC LIMIT 1")
    suspend fun getActive(source: String): ChargeSessionEntity?

    @Query("SELECT * FROM charge_sessions WHERE source = :source ORDER BY startMillis DESC LIMIT 300")
    fun observe(source: String): Flow<List<ChargeSessionEntity>>

    @Query("DELETE FROM charge_sessions WHERE source = :source")
    suspend fun deleteAll(source: String)
}

class RoomChargeSessionRepository(private val dao: ChargeSessionDao) : ChargeSessionRepository {
    override suspend fun start(session: ChargeSession): Long = dao.insert(session.toEntity())
    override suspend fun update(session: ChargeSession) = dao.update(session.toEntity())
    override suspend fun getActive(source: ReadingSource): ChargeSession? = dao.getActive(source.name)?.toDomain()
    override fun observe(source: ReadingSource): Flow<List<ChargeSession>> =
        dao.observe(source.name).map { list -> list.map { it.toDomain() } }

    override suspend fun deleteAll(source: ReadingSource) = dao.deleteAll(source.name)
}

internal fun ChargeSession.toEntity() = ChargeSessionEntity(
    id = id, source = source.name, startMillis = startMillis, endMillis = endMillis, lastSeenMillis = lastSeenMillis,
    powerSource = powerSource.name, startTempC = startTempC, peakTempC = peakTempC, tempSumC = tempSumC,
    readingCount = readingCount, startPercent = startPercent, lastPercent = lastPercent,
)

internal fun ChargeSessionEntity.toDomain() = ChargeSession(
    id = id,
    source = ReadingSource.entries.firstOrNull { it.name == source } ?: ReadingSource.DEVICE,
    startMillis = startMillis, endMillis = endMillis, lastSeenMillis = lastSeenMillis,
    powerSource = PowerSource.entries.firstOrNull { it.name == powerSource } ?: PowerSource.UNKNOWN,
    startTempC = startTempC, peakTempC = peakTempC, tempSumC = tempSumC, readingCount = readingCount,
    startPercent = startPercent, lastPercent = lastPercent,
)
