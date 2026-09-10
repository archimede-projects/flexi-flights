package com.archimedeprojects.volaflex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.archimedeprojects.volaflex.data.ApiKeyStore
import com.archimedeprojects.volaflex.data.VerifiedWeekendResult
import com.archimedeprojects.volaflex.data.WeekendCandidate
import com.archimedeprojects.volaflex.data.WeekendMonthRequest
import com.archimedeprojects.volaflex.data.WeekendSearchOutcome
import com.archimedeprojects.volaflex.data.WeekendSearchRepository
import com.archimedeprojects.volaflex.data.WeekendSearchResult
import com.archimedeprojects.volaflex.data.local.AirportDirectory
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val weekendDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val weekendTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val weekendMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)

private data class WeekendPeriodOption(
    val label: String,
    val months: List<WeekendMonthRequest>
)

private sealed interface WeekendUiState {
    data object Idle : WeekendUiState
    data object Loading : WeekendUiState
    data class IataWarning(val text: String) : WeekendUiState
    data class Success(val result: WeekendSearchResult) : WeekendUiState
    data class Message(
        val text: String,
        val showSettingsButton: Boolean = false
    ) : WeekendUiState
}

@Composable
fun WeekendSearchScreen(
    apiKeyStore: ApiKeyStore,
    repository: WeekendSearchRepository,
    onOpenFixedDates: () -> Unit,
    onOpenSettings: () -> Unit,
    onBackHome: () -> Unit
) {
    val periodOptions = remember { buildWeekendPeriodOptions(LocalDate.now()) }
    var selectedPeriod by remember {
        mutableStateOf(periodOptions.getOrElse(1) { periodOptions.first() })
    }
    var periodMenuExpanded by remember { mutableStateOf(false) }
    var departureInput by remember { mutableStateOf("") }
    var arrivalInput by remember { mutableStateOf("") }
    var uiState by remember { mutableStateOf<WeekendUiState>(WeekendUiState.Idle) }
    val scope = rememberCoroutineScope()

    val executeSearch: (Boolean) -> Unit = { forceRefresh ->
        val departure = departureInput.trim().uppercase(Locale.ROOT)
        val arrival = arrivalInput.trim().uppercase(Locale.ROOT)

        scope.launch {
            val apiKey = runCatching {
                apiKeyStore.getSerpApiKey()
            }.getOrNull()

            if (apiKey.isNullOrBlank()) {
                uiState = WeekendUiState.Message(
                    text = "SerpApi non è configurata. Salva prima la API key nelle Impostazioni.",
                    showSettingsButton = true
                )
                return@launch
            }

            uiState = WeekendUiState.Loading

            uiState = when (
                val outcome = repository.searchWeekendCandidates(
                    apiKey = apiKey,
                    departureId = departure,
                    arrivalId = arrival,
                    months = selectedPeriod.months,
                    forceRefresh = forceRefresh
                )
            ) {
                is WeekendSearchOutcome.Success -> WeekendUiState.Success(outcome.result)
                is WeekendSearchOutcome.Blocked -> WeekendUiState.Message(outcome.message)
                is WeekendSearchOutcome.Error -> WeekendUiState.Message(
                    text = outcome.message,
                    showSettingsButton = outcome.suggestSettings
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Ricerca",
            style = MaterialTheme.typography.headlineMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onOpenFixedDates,
                modifier = Modifier.weight(1f)
            ) {
                Text("Date fisse")
            }
            Button(
                onClick = {},
                modifier = Modifier.weight(1f)
            ) {
                Text("Weekend")
            }
        }

        Text(
            text = "Travel Explore trova il candidato economico; Google Flights verifica poi solo quel weekend con le fasce venerdì sera/sabato mattina → domenica sera/lunedì.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = departureInput,
            onValueChange = {
                departureInput = it
                if (uiState is WeekendUiState.IataWarning) uiState = WeekendUiState.Idle
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Partenza (es. FCO)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii
            )
        )

        OutlinedTextField(
            value = arrivalInput,
            onValueChange = {
                arrivalInput = it
                if (uiState is WeekendUiState.IataWarning) uiState = WeekendUiState.Idle
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Destinazione (es. MAD)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii
            )
        )

        Text(
            text = "Periodo",
            style = MaterialTheme.typography.labelLarge
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { periodMenuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedPeriod.label)
            }

            DropdownMenu(
                expanded = periodMenuExpanded,
                onDismissRequest = { periodMenuExpanded = false }
            ) {
                periodOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            selectedPeriod = option
                            periodMenuExpanded = false
                            uiState = WeekendUiState.Idle
                        }
                    )
                }
            }
        }

        Text(
            text = "Costo massimo su cache miss: ${selectedPeriod.months.size} query Explore + fino a 2 query Google Flights. I controlli Account API sono gratuiti.",
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            onClick = {
                val departure = departureInput.trim().uppercase(Locale.ROOT)
                val arrival = arrivalInput.trim().uppercase(Locale.ROOT)

                when {
                    departure.isBlank() || arrival.isBlank() -> {
                        uiState = WeekendUiState.Message("Inserisci sia la partenza sia la destinazione.")
                    }
                    departure == arrival -> {
                        uiState = WeekendUiState.Message("Partenza e destinazione devono essere diverse.")
                    }
                    else -> {
                        val unknownCodes = listOf(departure, arrival)
                            .distinct()
                            .filterNot(AirportDirectory::isKnown)

                        if (unknownCodes.isNotEmpty()) {
                            val codes = unknownCodes.joinToString("', '")
                            uiState = WeekendUiState.IataWarning(
                                "Codice '$codes' non riconosciuto nella lista locale — " +
                                    "controlla che sia corretto prima di continuare."
                            )
                        } else {
                            executeSearch(false)
                        }
                    }
                }
            },
            enabled = uiState !is WeekendUiState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cerca weekend economici")
        }

        when (val state = uiState) {
            WeekendUiState.Idle -> Unit

            WeekendUiState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text("Discovery Explore e verifica Google Flights in corso…")
                }
            }

            is WeekendUiState.IataWarning -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Controllo codice aeroporto",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(state.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { uiState = WeekendUiState.Idle },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Correggi")
                            }
                            Button(
                                onClick = { executeSearch(false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cerca comunque")
                            }
                        }
                    }
                }
            }

            is WeekendUiState.Message -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(state.text)
                        if (state.showSettingsButton) {
                            Button(onClick = onOpenSettings) {
                                Text("Vai a Impostazioni")
                            }
                        }
                    }
                }
            }

            is WeekendUiState.Success -> {
                WeekendResultsCard(
                    result = state.result,
                    onForceRefresh = { executeSearch(true) }
                )
            }
        }

        TextButton(
            onClick = onBackHome,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Torna alla Home")
        }
    }
}

@Composable
private fun WeekendResultsCard(
    result: WeekendSearchResult,
    onForceRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        result.verifiedWeekend?.let { verified ->
            VerifiedWeekendCard(
                result = verified,
                fromCache = result.verificationFromCache
            )
        }

        if (result.verificationMessage != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Verifica non completata",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(result.verificationMessage)
                    Text(
                        text = "I candidati Explore sotto restano solo indicativi.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Discovery Travel Explore",
                    style = MaterialTheme.typography.titleMedium
                )

                if (result.fromCache && result.cachedAtEpochMillis != null) {
                    val cacheTime = Instant.ofEpochMilli(result.cachedAtEpochMillis)
                        .atZone(ZoneId.systemDefault())
                        .format(weekendTimeFormatter)
                    Text(
                        text = "Discovery da cache — aggiornata alle $cacheTime",
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    Text("Quota verificata prima della Discovery: ${result.searchesLeftBeforeSearch} rimaste")
                }

                if (result.verificationFromCache) {
                    Text(
                        text = "Anche la verifica Google Flights proviene dalla cache locale.",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Text(
                    text = "Explore è usato solo per scegliere il weekend promettente: le sue date possono essere più lunghe del weekend breve desiderato.",
                    style = MaterialTheme.typography.bodySmall
                )

                if (result.fromCache) {
                    OutlinedButton(
                        onClick = onForceRefresh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Aggiorna comunque (usa nuove query)")
                    }
                }
            }
        }

        result.candidates.forEach { candidate ->
            WeekendCandidateCard(candidate)
        }
    }
}

@Composable
private fun VerifiedWeekendCard(
    result: VerifiedWeekendResult,
    fromCache: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "Weekend verificato ✓",
                style = MaterialTheme.typography.headlineSmall
            )
            if (fromCache) {
                Text(
                    text = "Verifica riutilizzata dalla cache locale — 0 nuove query.",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Text(result.patternLabel, style = MaterialTheme.typography.titleMedium)
            Text("Andata: ${formatWeekendDate(result.outboundDate)}")
            Text("Partenza esatta: ${result.outboundDepartureTime}")
            Text("Arrivo esatto: ${result.outboundArrivalTime}")
            Text("Ritorno: ${formatWeekendDate(result.returnDate)}")
            Text("Fascia ritorno verificata: ${result.returnWindowLabel}")
            Text("Compagnia (andata): ${result.airlines}")
            Text("Scali (andata): ${result.outboundStops}")
            Text(
                text = "Prezzo round-trip verificato: ${result.price} ${result.currency}",
                style = MaterialTheme.typography.titleLarge
            )
            Text("Quota live prima della fase di verifica: ${result.searchesLeftBeforeVerification} rimaste")
            Text(
                text = "Nota: la query round-trip applica anche la fascia del ritorno, ma il dettaglio esatto del volo di ritorno richiede departure_token e non viene ancora scaricato per risparmiare quota.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun WeekendCandidateCard(candidate: WeekendCandidate) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = candidate.monthLabel,
                style = MaterialTheme.typography.titleMedium
            )
            Text("Destinazione: ${candidate.destinationName} (${candidate.destinationIata})")
            Text("Range indicativo Explore: ${formatWeekendDate(candidate.outboundDate)} → ${formatWeekendDate(candidate.returnDate)}")
            Text(
                text = "Prezzo indicativo Explore: ${candidate.price} ${candidate.currency}",
                style = MaterialTheme.typography.titleMedium
            )
            if (candidate.verification != null) {
                Text(
                    text = "Candidato scelto per la verifica precisa ✓",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

private fun buildWeekendPeriodOptions(today: LocalDate): List<WeekendPeriodOption> {
    val currentMonth = YearMonth.from(today)
    val monthRequests = (0L..5L).map { offset ->
        currentMonth.plusMonths(offset).toWeekendMonthRequest()
    }

    val singleMonths = monthRequests.map { month ->
        WeekendPeriodOption(
            label = month.label,
            months = listOf(month)
        )
    }

    return singleMonths + listOf(
        WeekendPeriodOption(
            label = "Prossimi 2 mesi",
            months = monthRequests.take(2)
        ),
        WeekendPeriodOption(
            label = "Prossimi 3 mesi",
            months = monthRequests.take(3)
        )
    )
}

private fun YearMonth.toWeekendMonthRequest(): WeekendMonthRequest {
    return WeekendMonthRequest(
        yearMonthKey = toString(),
        monthNumber = monthValue,
        label = format(weekendMonthFormatter)
    )
}

private fun formatWeekendDate(rawDate: String): String {
    return runCatching {
        LocalDate.parse(rawDate).format(weekendDateFormatter)
    }.getOrDefault(rawDate)
}
