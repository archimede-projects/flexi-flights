package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.network.FlightOptionDto
import com.archimedeprojects.volaflex.data.network.SerpApiService
import java.io.IOException
import kotlinx.serialization.SerializationException

private const val MIN_SEARCHES_LEFT_TO_PROCEED = 5

data class SimpleFlightResult(
    val price: Int,
    val currency: String,
    val airlines: String,
    val departureTime: String,
    val arrivalTime: String,
    val stops: Int,
    val searchesLeftBeforeSearch: Int
)

sealed interface FlightSearchOutcome {
    data class Success(val result: SimpleFlightResult) : FlightSearchOutcome
    data class Blocked(val message: String) : FlightSearchOutcome
    data class Error(
        val message: String,
        val suggestSettings: Boolean = false
    ) : FlightSearchOutcome
}

class FlightSearchRepository(
    private val service: SerpApiService
) {

    suspend fun searchRoundTrip(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        outboundDate: String,
        returnDate: String
    ): FlightSearchOutcome {
        val searchesLeft = when (val quotaResult = readLiveQuota(apiKey)) {
            is QuotaResult.Available -> quotaResult.searchesLeft
            is QuotaResult.Error -> {
                return FlightSearchOutcome.Error(
                    message = quotaResult.message,
                    suggestSettings = quotaResult.suggestSettings
                )
            }
        }

        if (searchesLeft <= MIN_SEARCHES_LEFT_TO_PROCEED) {
            return FlightSearchOutcome.Blocked(
                "Ricerca bloccata: SerpApi ha $searchesLeft query rimaste. " +
                    "VolaFlex richiede almeno 6 query residue prima di avviare una ricerca reale."
            )
        }

        return try {
            val response = service.searchGoogleFlights(
                engine = "google_flights",
                departureId = departureId,
                arrivalId = arrivalId,
                outboundDate = outboundDate,
                returnDate = returnDate,
                type = 1,
                travelClass = 1,
                currency = "EUR",
                language = "it",
                country = "it",
                apiKey = apiKey
            )

            if (!response.isSuccessful) {
                return mapHttpError(response.code(), duringAccountCheck = false)
            }

            val body = response.body()
                ?: return FlightSearchOutcome.Error("SerpApi ha restituito una risposta vuota.")

            if (!body.error.isNullOrBlank()) {
                return FlightSearchOutcome.Error(
                    message = readableSerpApiError(body.error),
                    suggestSettings = looksLikeApiKeyError(body.error)
                )
            }

            val option = (body.bestFlights + body.otherFlights)
                .firstOrNull { it.price != null && it.flights.isNotEmpty() }
                ?: return FlightSearchOutcome.Error(
                    "Nessun volo trovato per questa rotta e queste date. Prova date o aeroporti diversi."
                )

            FlightSearchOutcome.Success(
                result = option.toSimpleResult(
                    currency = body.searchParameters?.currency ?: "EUR",
                    searchesLeftBeforeSearch = searchesLeft
                )
            )
        } catch (_: IOException) {
            FlightSearchOutcome.Error(
                "Errore di rete. Controlla la connessione Internet e riprova."
            )
        } catch (_: SerializationException) {
            FlightSearchOutcome.Error(
                "SerpApi ha restituito dati in un formato non previsto."
            )
        } catch (_: Exception) {
            FlightSearchOutcome.Error(
                "Errore imprevisto durante la ricerca. Riprova."
            )
        }
    }

    private suspend fun readLiveQuota(apiKey: String): QuotaResult {
        return try {
            val response = service.getAccount(apiKey)

            if (!response.isSuccessful) {
                val mapped = mapHttpError(response.code(), duringAccountCheck = true)
                return when (mapped) {
                    is FlightSearchOutcome.Error -> QuotaResult.Error(
                        message = mapped.message,
                        suggestSettings = mapped.suggestSettings
                    )
                    else -> QuotaResult.Error(
                        "Impossibile verificare la quota SerpApi. Ricerca bloccata per sicurezza."
                    )
                }
            }

            val body = response.body()
                ?: return QuotaResult.Error(
                    "Impossibile leggere la quota SerpApi. Ricerca bloccata per sicurezza."
                )

            if (!body.error.isNullOrBlank()) {
                return QuotaResult.Error(
                    message = readableSerpApiError(body.error),
                    suggestSettings = looksLikeApiKeyError(body.error)
                )
            }

            val searchesLeft = body.totalSearchesLeft ?: body.planSearchesLeft
                ?: return QuotaResult.Error(
                    "SerpApi non ha restituito il numero di query rimaste. Ricerca bloccata per sicurezza."
                )

            QuotaResult.Available(searchesLeft)
        } catch (_: IOException) {
            QuotaResult.Error(
                "Impossibile verificare la quota SerpApi per un errore di rete. Ricerca bloccata per sicurezza."
            )
        } catch (_: SerializationException) {
            QuotaResult.Error(
                "Impossibile interpretare la risposta Account API. Ricerca bloccata per sicurezza."
            )
        } catch (_: Exception) {
            QuotaResult.Error(
                "Impossibile verificare la quota SerpApi. Ricerca bloccata per sicurezza."
            )
        }
    }

    private fun mapHttpError(
        code: Int,
        duringAccountCheck: Boolean
    ): FlightSearchOutcome.Error {
        return when (code) {
            401, 403 -> FlightSearchOutcome.Error(
                message = "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni.",
                suggestSettings = true
            )
            429 -> FlightSearchOutcome.Error(
                message = "SerpApi ha bloccato la richiesta per quota o limite orario raggiunto."
            )
            400 -> FlightSearchOutcome.Error(
                message = if (duringAccountCheck) {
                    "SerpApi non ha accettato la chiave salvata. Controllala nelle Impostazioni."
                } else {
                    "SerpApi non ha accettato i parametri della ricerca. Controlla aeroporti e date."
                },
                suggestSettings = duringAccountCheck
            )
            else -> FlightSearchOutcome.Error(
                message = "SerpApi ha risposto con errore HTTP $code. Riprova più tardi."
            )
        }
    }

    private fun readableSerpApiError(rawError: String): String {
        return if (looksLikeApiKeyError(rawError)) {
            "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni."
        } else {
            "SerpApi non ha completato la richiesta: ${rawError.take(180)}"
        }
    }

    private fun looksLikeApiKeyError(message: String): Boolean {
        val normalized = message.lowercase()
        return "api key" in normalized ||
            "api_key" in normalized ||
            "unauthorized" in normalized ||
            "authentication" in normalized
    }

    private fun FlightOptionDto.toSimpleResult(
        currency: String,
        searchesLeftBeforeSearch: Int
    ): SimpleFlightResult {
        val firstSegment = flights.first()
        val lastSegment = flights.last()
        val airlineNames = flights
            .mapNotNull { it.airline?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString(" / ")
            .ifBlank { "Compagnia non disponibile" }

        return SimpleFlightResult(
            price = requireNotNull(price),
            currency = currency,
            airlines = airlineNames,
            departureTime = firstSegment.departureAirport?.time ?: "Orario non disponibile",
            arrivalTime = lastSegment.arrivalAirport?.time ?: "Orario non disponibile",
            stops = layovers.size,
            searchesLeftBeforeSearch = searchesLeftBeforeSearch
        )
    }

    private sealed interface QuotaResult {
        data class Available(val searchesLeft: Int) : QuotaResult
        data class Error(
            val message: String,
            val suggestSettings: Boolean = false
        ) : QuotaResult
    }
}
