package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.DiagnosticEventDao
import com.archimedeprojects.volaflex.data.local.DiagnosticEventEntity
import kotlinx.coroutines.flow.Flow

class DiagnosticRepository(
    private val dao: DiagnosticEventDao
) {
    val latestEvents: Flow<List<DiagnosticEventEntity>> = dao.observeLatest20()

    suspend fun log(
        requestType: String,
        outcome: String,
        httpStatus: Int? = null,
        message: String? = null
    ) {
        runCatching {
            dao.insert(
                DiagnosticEventEntity(
                    timestampEpochMillis = System.currentTimeMillis(),
                    requestType = requestType,
                    outcome = outcome,
                    httpStatus = httpStatus,
                    message = message?.take(300)
                )
            )
            dao.trimToLatest20()
        }
    }
}
