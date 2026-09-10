package com.archimedeprojects.volaflex.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.archimedeprojects.volaflex.data.ApiKeyStatus
import com.archimedeprojects.volaflex.data.ApiKeyStore
import com.archimedeprojects.volaflex.data.NightsSearchOutcome
import com.archimedeprojects.volaflex.data.NightsSearchRepository
import com.archimedeprojects.volaflex.data.NightsSearchResult
import com.archimedeprojects.volaflex.data.local.AirportDirectory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val nightsDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val nightsTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private sealed interface NightsUiState {
    data object Idle : NightsUiState
    data object Loading : NightsUiState
    data class IataWarning(val text: String) : NightsUiState
    data class Success(val result: NightsSearchResult) : NightsUiState
    data class Message(
        val text: String,
        val showSettingsButton: Boolean = false
    ) : NightsUiState
}

@Composable
fun NightsSearchScreen(
    apiKeyStore: ApiKeyStore,
    repository: NightsSearchRepository,
    onOpenFixedDates: () -> Unit,
    onOpenWeekend: () -> Unit,
    onOpenSettings: () -> Unit,
    onBackHome: () -> Unit
) {
    val apiStatus by apiKeyStore.status.collectAsState(initial = ApiKeyStatus())
    var departureInput by remember { mutableStateOf("") }
    var arrivalInput by remember { mutableStateOf("") }
    var nightsInput by remember { mutableStateOf("3") }
    var targetDate by remember { mutableStateOf(LocalDate.now().plusDays(45)) }
    var flexibilityInput by remember { mutableStateOf("5") }
    var uiState by remember { mutableStateOf<NightsUiState>(NightsUiState.Idle) }
    val scope = rememberCoroutineScope()

    val executeSearch: (Boolean) -> Unit = { forceRefresh ->
        val departure = departureInput.trim().uppercase(Locale.ROOT)
        val arrival = arrivalInput.trim().uppercase(Locale.ROOT)
        val nights = nightsInput.toIntOrNull()
        val flexibility = flexibilityInput.toIntOrNull()

        scope.launch {
            val serpApiKey = runCatching { apiKeyStore.getSerpApiKey() }.getOrNull()
            if (serpApiKey.isNullOrBlank()) {
                uiState = NightsUiState.Message(
                    text = "SerpApi non è configurata. Salva prima la API key nelle Impostazioni.",
                    showSettingsButton = true
                )
                return@launch
            }

            val searchApiKey = runCatching { apiKeyStore.getSearchApiKey() }.getOrNull()
            uiState = NightsUiState.Loading

            uiState = when (
                val outcome = repository.search(
                    serpApiKey = serpApiKey,
                    searchApiKey = searchApiKey,
                    departureId = departure,
                    arrivalId = arrival,
                    nights = requireNotNull(nights),
                    targetDate = targetDate,
                    flexibilityDays = requireNotNull(flexibility),
                    forceRefresh = forceRefresh
                )
            ) {
                is NightsSearchOutcome.Success -> NightsUiState.Success(outcome.result)
                is NightsSearchOutcome.Blocked -> NightsUiState.Message(outcome.message)
                is NightsSearchOutcome.Error -> NightsUiState.Message(
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
        Text("Ricerca", style = MaterialTheme.typography.headlineMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onOpenFixedDates,
                modifier = Modifier.weight(1f)
            ) {
                Text("Date fisse")
            }
            OutlinedButton(
                onClick = onOpenWeekend,
                modifier = Modifier.weight(1f)
            ) {
                Text("Weekend")
            }
            Button(
                onClick = {},
                modifier = Modifier.weight(1f)
            ) {
                Text("N notti")
            }
        }

        Text(
            text = "Scegli quante notti vuoi restare e una data target. VolaFlex cerca partenze entro ±X giorni e mantiene sempre il ritorno esattamente N giorni dopo.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = departureInput,
            onValueChange = {
                departureInput = it
                if (uiState is NightsUiState.IataWarning) uiState = NightsUiState.Idle
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
                if (uiState is NightsUiState.IataWarning) uiState = NightsUiState.Idle
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Destinazione (es. MAD)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii
            )
        )

        OutlinedTextField(
            value = nightsInput,
            onValueChange = { nightsInput = it.filter(Char::isDigit).take(2) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Numero di notti (1–30)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        NightsDateSelector(
            label = "Data target di partenza",
            date = targetDate,
            onDateSelected = { targetDate = it }
        )

        OutlinedTextField(
            value = flexibilityInput,
            onValueChange = { flexibilityInput = it.filter(Char::isDigit).take(2) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Flessibilità ± giorni (0–60)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        StrategyPreview(
            flexibilityDays = flexibilityInput.toIntOrNull(),
            searchApiConfigured = apiStatus.searchApiConfigured
        )

        Button(
            onClick = {
                val departure = departureInput.trim().uppercase(Locale.ROOT)
                val arrival = arrivalInput.trim().uppercase(Locale.ROOT)
                val nights = nightsInput.toIntOrNull()
                val flexibility = flexibilityInput.toIntOrNull()

                when {
                    departure.isBlank() || arrival.isBlank() -> {
                        uiState = NightsUiState.Message("Inserisci sia la partenza sia la destinazione.")
                    }
                    departure == arrival -> {
                        uiState = NightsUiState.Message("Partenza e destinazione devono essere diverse.")
                    }
                    nights == null || nights !in 1..30 -> {
                        uiState = NightsUiState.Message("Inserisci un numero di notti tra 1 e 30.")
                    }
                    flexibility == null || flexibility !in 0..60 -> {
                        uiState = NightsUiState.Message("Inserisci una flessibilità tra 0 e 60 giorni.")
                    }
                    targetDate.isBefore(LocalDate.now()) -> {
                        uiState = NightsUiState.Message("La data target non può essere nel passato.")
                    }
                    else -> {
                        val unknownCodes = listOf(departure, arrival)
                            .distinct()
                            .filterNot(AirportDirectory::isKnown)

                        if (unknownCodes.isNotEmpty()) {
                            val codes = unknownCodes.joinToString("', '")
                            uiState = NightsUiState.IataWarning(
                                "Codice '$codes' non riconosciuto nella lista locale — controlla che sia corretto prima di continuare."
                            )
                        } else {
                            executeSearch(false)
                        }
                    }
                }
            },
            enabled = uiState !is NightsUiState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cerca periodo più economico")
        }

        when (val state = uiState) {
            NightsUiState.Idle -> Unit
            NightsUiState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text("Discovery date e verifica Google Flights in corso…")
                }
            }
            is NightsUiState.IataWarning -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Controllo codice aeroporto", style = MaterialTheme.typography.titleMedium)
                        Text(state.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { uiState = NightsUiState.Idle },
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
            is NightsUiState.Message -> {
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
            is NightsUiState.Success -> {
                NightsResultCard(
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
private fun StrategyPreview(
    flexibilityDays: Int?,
    searchApiConfigured: Boolean
) {
    val x = flexibilityDays ?: return
    if (x !in 0..60) return
    val candidateCount = 2 * x + 1
    val text = when {
        candidateCount <= 10 -> {
            "Strategia prevista: SerpApi diretto ed esaustivo su $candidateCount date. Account API gratuita; massimo $candidateCount query voli."
        }
        searchApiConfigured -> {
            val blocks = (candidateCount + 13) / 14
            "Strategia prevista: SearchAPI.io Calendar in $blocks blocchi (max 14×14=196 combinazioni ciascuno), poi 1 verifica SerpApi precisa."
        }
        else -> {
            "SearchAPI.io non configurata: modalità risparmio quota. VolaFlex campionerà 5 date + fino a 2 vicine alla migliore: massimo 7 query SerpApi."
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun NightsResultCard(
    result: NightsSearchResult,
    onForceRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("N notti verificato ✓", style = MaterialTheme.typography.headlineSmall)

            if (result.fromCache && result.cachedAtEpochMillis != null) {
                val cacheTime = Instant.ofEpochMilli(result.cachedAtEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(nightsTimeFormatter)
                Text(
                    text = "Discovery + verifica da cache — aggiornata alle $cacheTime. 0 nuove query.",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text("Strategia: ${result.strategyLabel}")
            Text("Andata: ${formatNightsDate(result.outboundDate)}")
            Text("Ritorno: ${formatNightsDate(result.returnDate)} — ${result.nights} notti")
            Text("Compagnia (andata): ${result.airlines}")
            Text("Partenza (andata): ${result.departureTime}")
            Text("Arrivo (andata): ${result.arrivalTime}")
            Text("Scali (andata): ${result.stops}")
            Text(
                text = "Prezzo round-trip verificato: ${result.price} ${result.currency}",
                style = MaterialTheme.typography.titleLarge
            )

            if (result.indicativePrice != null && result.indicativePrice != result.price) {
                Text("Prezzo Discovery indicativo: ${result.indicativePrice} ${result.currency}")
            }

            Text(
                "Date candidate nel range: ${result.totalCandidateDates}; " +
                    "date valutate: ${result.evaluatedCandidateDates}."
            )
            if (!result.fromCache) {
                Text(
                    "Richieste di questo run: SearchAPI Calendar ${result.calendarRequests}; " +
                        "SerpApi Google Flights ${result.serpApiSearchRequests}."
                )
                Text("Quota SerpApi live prima della ricerca: ${result.searchesLeftBeforeSearch} rimaste")
            }

            Text(
                text = "Il dettaglio esatto del ritorno resta on-demand per non aggiungere query inutili.",
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightsDateSelector(
    label: String,
    date: LocalDate,
    onDateSelected: (LocalDate) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Button(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(date.format(nightsDateFormatter))
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val selected = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            onDateSelected(selected)
                        }
                        showPicker = false
                    }
                ) {
                    Text("Conferma")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Annulla")
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun formatNightsDate(raw: String): String {
    return runCatching { LocalDate.parse(raw).format(nightsDateFormatter) }.getOrDefault(raw)
}
