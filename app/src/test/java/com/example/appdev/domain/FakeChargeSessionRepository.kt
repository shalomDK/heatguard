package com.example.appdev.domain

import com.example.appdev.domain.charging.ChargeSession
import com.example.appdev.domain.charging.ChargeSessionRepository
import com.example.appdev.domain.model.ReadingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeChargeSessionRepository : ChargeSessionRepository {
    private val sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
    val all: List<ChargeSession> get() = sessions.value
    private var nextId = 1L

    override suspend fun start(session: ChargeSession): Long {
        val id = nextId++
        sessions.value = sessions.value + session.copy(id = id)
        return id
    }

    override suspend fun update(session: ChargeSession) {
        sessions.value = sessions.value.map { if (it.id == session.id) session else it }
    }

    override suspend fun getActive(source: ReadingSource): ChargeSession? =
        sessions.value.lastOrNull { it.source == source && it.endMillis == null }

    override fun observe(source: ReadingSource): Flow<List<ChargeSession>> =
        sessions.map { list -> list.filter { it.source == source }.sortedByDescending { it.startMillis } }

    override suspend fun deleteAll(source: ReadingSource) {
        sessions.value = sessions.value.filterNot { it.source == source }
    }
}
