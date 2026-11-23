package com.example.appdev.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.appdev.domain.model.ChargeStatus
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.perf.SpeedTestRepository
import com.example.appdev.domain.perf.SpeedTestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "speed_tests", indices = [Index("source", "timestampMillis")])
data class SpeedTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val timestampMillis: Long,
    val temperatureC: Float,
    val score: Double,
    val chargeStatus: String,
    val thermalStatus: String?,
)

@Dao
interface SpeedTestDao {
    @Insert
    suspend fun insert(entity: SpeedTestEntity)

    @Query("SELECT * FROM speed_tests WHERE source = :source ORDER BY timestampMillis DESC LIMIT 300")
    fun observe(source: String): Flow<List<SpeedTestEntity>>

    @Query("DELETE FROM speed_tests WHERE source = :source")
    suspend fun deleteAll(source: String)
}

class RoomSpeedTestRepository(private val dao: SpeedTestDao) : SpeedTestRepository {

    override suspend fun add(result: SpeedTestResult) = dao.insert(
        SpeedTestEntity(
            source = result.source.name,
            timestampMillis = result.timestampMillis,
            temperatureC = result.temperatureC,
            score = result.score,
            chargeStatus = result.chargeStatus.name,
            thermalStatus = result.thermalStatus,
        ),
    )

    override fun observe(source: ReadingSource): Flow<List<SpeedTestResult>> =
        dao.observe(source.name).map { list -> list.map { it.toDomain() } }

    override suspend fun deleteAll(source: ReadingSource) = dao.deleteAll(source.name)
}

internal fun SpeedTestEntity.toDomain() = SpeedTestResult(
    id = id,
    source = ReadingSource.entries.firstOrNull { it.name == source } ?: ReadingSource.DEVICE,
    timestampMillis = timestampMillis,
    temperatureC = temperatureC,
    score = score,
    chargeStatus = ChargeStatus.entries.firstOrNull { it.name == chargeStatus } ?: ChargeStatus.UNKNOWN,
    thermalStatus = thermalStatus,
)
