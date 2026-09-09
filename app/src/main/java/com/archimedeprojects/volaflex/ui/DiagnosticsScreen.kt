package com.archimedeprojects.volaflex.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.archimedeprojects.volaflex.data.DiagnosticRepository
import com.archimedeprojects.volaflex.data.local.DiagnosticEventEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val diagnosticTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

@Composable
fun DiagnosticsScreen(
    repository: DiagnosticRepository,
    onBackSettings: () -> Unit
) {
    val events by repository.latestEvents.collectAsState(initial = emptyList())
    val context = LocalContext.current
    var copyMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Diagnostica",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Ultimi 20 eventi di rete/cache. Le API key non vengono incluse.",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = {
                val text = buildDiagnosticsText(events)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("VolaFlex diagnostica", text)
                )
                copyMessage = "Diagnostica copiata ✓"
            },
            enabled = events.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copia diagnostica")
        }

        copyMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }

        if (events.isEmpty()) {
            Text(
                text = "Nessun evento registrato. Esegui una ricerca per popolare la diagnostica.",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = events,
                    key = { event -> event.id }
                ) { event ->
                    DiagnosticEventCard(event)
                }
            }
        }

        OutlinedButton(
            onClick = onBackSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Torna a Impostazioni")
        }
    }
}

@Composable
private fun DiagnosticEventCard(event: DiagnosticEventEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = formatDiagnosticTimestamp(event.timestampEpochMillis),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "${event.requestType} — ${event.outcome}",
                style = MaterialTheme.typography.titleSmall
            )
            event.httpStatus?.let { status ->
                Text("HTTP: $status")
            }
            event.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(message)
            }
        }
    }
}

private fun buildDiagnosticsText(events: List<DiagnosticEventEntity>): String {
    if (events.isEmpty()) {
        return "VolaFlex diagnostica: nessun evento registrato."
    }

    return buildString {
        appendLine("VolaFlex diagnostica — ultimi ${events.size} eventi")
        appendLine()
        events.forEach { event ->
            append(formatDiagnosticTimestamp(event.timestampEpochMillis))
            append(" | ")
            append(event.requestType)
            append(" | ")
            append(event.outcome)
            event.httpStatus?.let { status ->
                append(" | HTTP ")
                append(status)
            }
            event.message?.takeIf { it.isNotBlank() }?.let { message ->
                append(" | ")
                append(message)
            }
            appendLine()
        }
    }.trimEnd()
}

private fun formatDiagnosticTimestamp(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(diagnosticTimestampFormatter)
}
