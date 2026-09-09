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
import com.archimedeprojects.volaflex.data.FlightSearchOutcome
import com.archimedeprojects.volaflex.data.FlightSearchRepository
import com.archimedeprojects.volaflex.data.SimpleFlightResult
import com.archimedeprojects.volaflex.data.local.AirportDirectory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val displayDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val displayTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class IataWarning(val text: String) : SearchUiState
    data class Success(val result: SimpleFlightResult) : SearchUiState
    data class Message(
        val text: String,
        val showSettingsButton: Boolean = false
    ) : SearchUiState
}

@Composable
fun SearchScreen(
    apiKeyStore: ApiKeyStore,
    repository: FlightSearchRepository,
    onOpenSettings: () -> Unit,
    onBackHome: () -> Unit
) {
    var departureInput by remember { mutableStateOf("") }
    var arrivalInput by remember { mutableStateOf("") }
    var outboundDate by remember { mutableStateOf(LocalDate.now().plusDays(30)) }
    var returnDate by remember { mutableStateOf(LocalDate.now().plusDays(33)) }
    var uiState by remember { mutableStateOf<SearchUiState>(SearchUiState.Idle) }
    val scope = rememberCoroutineScope()

    val executeSearch: (Boolean) -> Unit = { forceRefresh ->
        val departure = departureInput.trim().uppercase(Locale.ROOT)
        val arrival = arrivalInput.trim().uppercase(Locale.ROOT)

        scope.launch {
            val apiKey = runCatching {
                apiKeyStore.getSerpApiKey()
            }.getOrNull()

            if (apiKey.isNullOrBlank()) {
                uiState = SearchUiState.Message(
                    text = "SerpApi non è configurata. Salva prima la API key nelle Impostazioni.",
                    showSettingsButton = true
                )
                return@launch
            }

            uiState = SearchUiState.Loading

            uiState = when (
                val outcome = repository.searchRoundTrip(
                    apiKey = apiKey,
                    departureId = departure,
                    arrivalId = arrival,
                    outboundDate = outboundDate.toString(),
                    returnDate = returnDate.toString(),
                    forceRefresh = forceRefresh
                )
            ) {
                is FlightSearchOutcome.Success -> SearchUiState.Success(outcome.result)
                is FlightSearchOutcome.Blocked -> SearchUiState.Message(outcome.message)
                is FlightSearchOutcome.Error -> SearchUiState.Message(
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

        Text(
            text = "Una rotta, date fisse, classe Economy. Usa codici IATA come FCO e MAD.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = departureInput,
            onValueChange = {
                departureInput = it
                if (uiState is SearchUiState.IataWarning) {
                    uiState = SearchUiState.Idle
                }
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
                if (uiState is SearchUiState.IataWarning) {
                    uiState = SearchUiState.Idle
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Arrivo (es. MAD)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii
            )
        )

        DateSelector(
            label = "Andata",
            date = outboundDate,
            onDateSelected = { selected ->
                outboundDate = selected
                if (returnDate.isBefore(selected)) {
                    returnDate = selected.plusDays(1)
                }
            }
        )

        DateSelector(
            label = "Ritorno",
            date = returnDate,
            onDateSelected = { returnDate = it }
        )

        Button(
            onClick = {
                val departure = departureInput.trim().uppercase(Locale.ROOT)
                val arrival = arrivalInput.trim().uppercase(Locale.ROOT)

                when {
                    departure.isBlank() || arrival.isBlank() -> {
                        uiState = SearchUiState.Message("Inserisci sia la partenza sia l'arrivo.")
                    }
                    departure == arrival -> {
                        uiState = SearchUiState.Message("Partenza e arrivo devono essere diversi.")
                    }
                    returnDate.isBefore(outboundDate) -> {
                        uiState = SearchUiState.Message(
                            "La data di ritorno non può essere precedente all'andata."
                        )
                    }
                    else -> {
                        val unknownCodes = listOf(departure, arrival)
                            .distinct()
                            .filterNot(AirportDirectory::isKnown)

                        if (unknownCodes.isNotEmpty()) {
                            val codes = unknownCodes.joinToString("', '")
                            uiState = SearchUiState.IataWarning(
                                "Codice '$codes' non riconosciuto nella lista locale — " +
                                    "controlla che sia corretto prima di continuare."
                            )
                        } else {
                            executeSearch(false)
                        }
                    }
                }
            },
            enabled = uiState !is SearchUiState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cerca")
        }

        when (val state = uiState) {
            SearchUiState.Idle -> Unit

            SearchUiState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text("Controllo cache/quota e ricerca in corso…")
                }
            }

            is SearchUiState.IataWarning -> {
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { uiState = SearchUiState.Idle },
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

            is SearchUiState.Message -> {
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

            is SearchUiState.Success -> {
                FlightResultCard(
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
private fun FlightResultCard(
    result: SimpleFlightResult,
    onForceRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Primo risultato trovato",
                style = MaterialTheme.typography.titleMedium
            )

            if (result.fromCache && result.cachedAtEpochMillis != null) {
                val cacheTime = Instant.ofEpochMilli(result.cachedAtEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(displayTimeFormatter)
                Text(
                    text = "Risultato da cache — aggiornato alle $cacheTime",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text("Prezzo round-trip: ${result.price} ${result.currency}")
            Text("Compagnia (andata): ${result.airlines}")
            Text("Partenza (andata): ${result.departureTime}")
            Text("Arrivo (andata): ${result.arrivalTime}")
            Text("Scali (andata): ${result.stops}")

            if (result.fromCache) {
                Text(
                    "Quota registrata quando il risultato è stato ottenuto: " +
                        "${result.searchesLeftBeforeSearch} rimaste"
                )
                OutlinedButton(
                    onClick = onForceRefresh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Aggiorna comunque")
                }
            } else {
                Text("Quota verificata prima della ricerca: ${result.searchesLeftBeforeSearch} rimaste")
            }

            Text(
                text = "I dettagli del ritorno richiedono una seconda query SerpApi e non vengono ancora richiesti.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelector(
    label: String,
    date: LocalDate,
    onDateSelected: (LocalDate) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
        Button(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(date.format(displayDateFormatter))
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toUtcMillis()
        )

        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDateSelected(millis.toLocalDateUtc())
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

private fun LocalDate.toUtcMillis(): Long {
    return atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun Long.toLocalDateUtc(): LocalDate {
    return Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
}
