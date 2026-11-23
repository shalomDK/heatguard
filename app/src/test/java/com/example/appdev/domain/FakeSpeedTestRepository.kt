package com.example.appdev.domain

import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.perf.SpeedTestRepository
import com.example.appdev.domain.perf.SpeedTestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeSpeedTestRepository : SpeedTestRepository {
    private val results = MutableStateFlow<List<SpeedTestResult>>(emptyList())
    val all: List<SpeedTestResult> get() = results.value

    override suspend fun add(result: SpeedTestResult) {
        results.value = results.value + result.copy(id = results.value.size + 1L)
    }

    override fun observe(source: ReadingSource): Flow<List<SpeedTestResult>> =
        results.map { list -> list.filter { it.source == source }.sortedByDescending { it.timestampMillis } }

    override suspend fun deleteAll(source: ReadingSource) {
        results.value = results.value.filterNot { it.source == source }
    }
}
