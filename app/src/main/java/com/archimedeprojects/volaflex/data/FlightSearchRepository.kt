package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.FlightSearchCacheDao
import com.archimedeprojects.volaflex.data.local.FlightSearchCacheEntity
import com.archimedeprojects.volaflex.data.network.FlightOptionDto
import com.archimedeprojects.volaflex.data.network.SerpApiService
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.SerializationException

private const val MIN_SEARCHES_LEFT_TO_PROCEED = 5
private const val CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L
private const val MAX_FIXED_DATE_ORIGINS = 3
private const val MAX_FIXED_DATE_DESTINATIONS = 3

data class SimpleFlightResult(
    val price: Int,
    val currency: String,
    val airlines: String,
    val departureAirportId: String,
    val arrivalAirportId: String,
    val departureTime: String,
    val arrivalTime: String,
    val stops: Int,
    val searchesLeftBeforeSearch: Int,
    val fromCache: Boolean = false,
    val cachedAtEpochMillis: Long? = null
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
    private val service: SerpApiService,
    private val cacheDao: FlightSearchCacheDao,
    private val diagnostics: DiagnosticRepository
) {

    suspend fun searchRoundTrip(
        apiKey: String,
        departureIds: List<String>,
        arrivalIds: List<String>,
        outboundDate: String,
        returnDate: String,
        forceRefresh: Boolean = false
    ): FlightSearchOutcome {
        val normalizedDepartures = canonicalizeAirportIds(departureIds)
        val normalizedArrivals = canonicalizeAirportIds(arrivalIds)

        if (normalizedDepartures.isEmpty()) {
            return FlightSearchOutcome.Error("Inserisci almeno un aeroporto di partenza.")
        }
        if (normalizedDepartures.size > MAX_FIXED_DATE_ORIGINS) {
            return FlightSearchOutcome.Error("Per ora puoi usare al massimo 3 aeroporti di partenza.")
        }
        if (normalizedArrivals.isEmpty()) {
            return FlightSearchOutcome.Error("Inserisci almeno un aeroporto di destinazione.")
        }
        if (normalizedArrivals.size > MAX_FIXED_DATE_DESTINATIONS) {
            return FlightSearchOutcome.Error("Per ora puoi usare al massimo 3 aeroporti di destinazione.")
        }
        if (normalizedDepartures.any { it in normalizedArrivals }) {
            return FlightSearchOutcome.Error("Nessuna destinazione può coincidere con uno degli aeroporti di partenza.")
        }

        val normalizedDepartureQuery = normalizedDepartures.joinToString(",")
        val normalizedArrivalQuery = normalizedArrivals.joinToString(",")
        val cacheKey = buildCacheKey(
            canonicalDepartureIds = normalizedDepartureQuery,
            canonicalArrivalIds = normalizedArrivalQuery,
            outboundDate = outboundDate,
            returnDate = returnDate
        )
        val now = System.currentTimeMillis()
        val minimumFreshTimestamp = now - CACHE_TTL_MILLIS

        if (!forceRefresh) {
            val cached = runCatching {
                cacheDao.findFresh(cacheKey, minimumFreshTimestamp)
            }.getOrNull()

            if (cached != null) {
                diagnostics.log(
                    requestType = "CACHE",
                    outcome = "HIT",
                    message = "origins=$normalizedDepartureQuery destinations=$normalizedArrivalQuery $outboundDate/$returnDate"
                )
                return FlightSearchOutcome.Success(cached.toSimpleResult())
            }
        }

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
            val message = "Ricerca bloccata: SerpApi ha $searchesLeft query rimaste. " +
                "VolaFlex richiede almeno 6 query residue prima di avviare una ricerca reale."
            diagnostics.log(
                requestType = "QUOTA_GUARD",
                outcome = "BLOCKED",
                message = message
            )
            return FlightSearchOutcome.Blocked(message)
        }

        return try {
            val response = service.searchGoogleFlights(
                engine = "google_flights",
                departureId = normalizedDepartureQuery,
                arrivalId = normalizedArrivalQuery,
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
                val mapped = mapHttpError(response.code(), duringAccountCheck = false)
                diagnostics.log(
                    requestType = "GOOGLE_FLIGHTS",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = mapped.message
                )
                return mapped
            }

            val body = response.body()
            if (body == null) {
                val message = "SerpApi ha restituito una risposta vuota."
                diagnostics.log(
                    requestType = "GOOGLE_FLIGHTS",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = message
                )
                return FlightSearchOutcome.Error(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = readableSerpApiError(body.error)
                diagnostics.log(
                    requestType = "GOOGLE_FLIGHTS",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = message
                )
                return FlightSearchOutcome.Error(
                    message = message,
                    suggestSettings = looksLikeApiKeyError(body.error)
                )
            }

            val option = (body.bestFlights + body.otherFlights)
                .firstOrNull { it.price != null && it.flights.isNotEmpty() }

            if (option == null) {
                val message = "Nessun volo trovato per queste origini, destinazioni e date. Prova una combinazione diversa."
                diagnostics.log(
                    requestType = "GOOGLE_FLIGHTS",
                    outcome = "EMPTY",
                    httpStatus = response.code(),
                    message = message
                )
                return FlightSearchOutcome.Error(message)
            }

            val result = option.toSimpleResult(
                currency = body.searchParameters?.currency ?: "EUR",
                searchesLeftBeforeSearch = searchesLeft
            )

            runCatching {
                cacheDao.upsert(
                    result.toCacheEntity(
                        cacheKey = cacheKey,
                        outboundDate = outboundDate,
                        returnDate = returnDate,
                        cachedAtEpochMillis = now
                    )
                )
                cacheDao.deleteOlderThan(minimumFreshTimestamp)
            }

            diagnostics.log(
                requestType = "GOOGLE_FLIGHTS",
                outcome = "SUCCESS",
                httpStatus = response.code(),
                message = "origins=$normalizedDepartureQuery destinations=$normalizedArrivalQuery winner=${result.departureAirportId}→${result.arrivalAirportId}, ${result.price} ${result.currency}"
            )

            FlightSearchOutcome.Success(result)
        } catch (_: IOException) {
            val message = "Errore di rete. Controlla la connessione Internet e riprova."
            diagnostics.log(
                requestType = "GOOGLE_FLIGHTS",
                outcome = "ERROR",
                message = message
            )
            FlightSearchOutcome.Error(message)
        } catch (_: SerializationException) {
            val message = "SerpApi ha restituito dati in un formato non previsto."
            diagnostics.log(
                requestType = "GOOGLE_FLIGHTS",
                outcome = "ERROR",
                message = message
            )
            FlightSearchOutcome.Error(message)
        } catch (_: Exception) {
            val message = "Errore imprevisto durante la ricerca. Riprova."
            diagnostics.log(
                requestType = "GOOGLE_FLIGHTS",
                outcome = "ERROR",
                message = message
            )
            FlightSearchOutcome.Error(message)
        }
    }

    private suspend fun readLiveQuota(apiKey: String): QuotaResult {
        return try {
            val response = service.getAccount(apiKey)

            if (!response.isSuccessful) {
                val mapped = mapHttpError(response.code(), duringAccountCheck = true)
                diagnostics.log(
                    requestType = "SERPAPI_ACCOUNT",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = mapped.message
                )
                return QuotaResult.Error(
                    message = mapped.message,
                    suggestSettings = mapped.suggestSettings
                )
            }

            val body = response.body()
            if (body == null) {
                val message = "Impossibile leggere la quota SerpApi. Ricerca bloccata per sicurezza."
                diagnostics.log(
                    requestType = "SERPAPI_ACCOUNT",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = message
                )
                return QuotaResult.Error(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = readableSerpApiError(body.error)
                diagnostics.log(
                    requestType = "SERPAPI_ACCOUNT",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = message
                )
                return QuotaResult.Error(
                    message = message,
                    suggestSettings = looksLikeApiKeyError(body.error)
                )
            }

            val searchesLeft = body.totalSearchesLeft ?: body.planSearchesLeft
            if (searchesLeft == null) {
                val message = "SerpApi non ha restituito il numero di query rimaste. Ricerca bloccata per sicurezza."
                diagnostics.log(
                    requestType = "SERPAPI_ACCOUNT",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = message
                )
                return QuotaResult.Error(message)
            }

            diagnostics.log(
                requestType = "SERPAPI_ACCOUNT",
                outcome = "SUCCESS",
                httpStatus = response.code(),
                message = "Quota live: $searchesLeft query rimaste"
            )
            QuotaResult.Available(searchesLeft)
        } catch (_: IOException) {
            val message = "Impossibile verificare la quota SerpApi per un errore di rete. Ricerca bloccata per sicurezza."
            diagnostics.log(
                requestType = "SERPAPI_ACCOUNT",
                outcome = "ERROR",
                message = message
            )
            QuotaResult.Error(message)
        } catch (_: SerializationException) {
            val message = "Impossibile interpretare la risposta Account API. Ricerca bloccata per sicurezza."
            diagnostics.log(
                requestType = "SERPAPI_ACCOUNT",
                outcome = "ERROR",
                message = message
            )
            QuotaResult.Error(message)
        } catch (_: Exception) {
            val message = "Impossibile verificare la quota SerpApi. Ricerca bloccata per sicurezza."
            diagnostics.log(
                requestType = "SERPAPI_ACCOUNT",
                outcome = "ERROR",
                message = message
            )
            QuotaResult.Error(message)
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

    private fun canonicalizeAirportIds(ids: List<String>): List<String> {
        return ids
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
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
            departureAirportId = firstSegment.departureAirport?.id ?: "Non disponibile",
            arrivalAirportId = lastSegment.arrivalAirport?.id ?: "Non disponibile",
            departureTime = firstSegment.departureAirport?.time ?: "Orario non disponibile",
            arrivalTime = lastSegment.arrivalAirport?.time ?: "Orario non disponibile",
            stops = layovers.size,
            searchesLeftBeforeSearch = searchesLeftBeforeSearch
        )
    }

    private fun SimpleFlightResult.toCacheEntity(
        cacheKey: String,
        outboundDate: String,
        returnDate: String,
        cachedAtEpochMillis: Long
    ): FlightSearchCacheEntity {
        return FlightSearchCacheEntity(
            cacheKey = cacheKey,
            departureId = departureAirportId,
            arrivalId = arrivalAirportId,
            outboundDate = outboundDate,
            returnDate = returnDate,
            price = price,
            currency = currency,
            airlines = airlines,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            stops = stops,
            searchesLeftBeforeSearch = searchesLeftBeforeSearch,
            cachedAtEpochMillis = cachedAtEpochMillis
        )
    }

    private fun FlightSearchCacheEntity.toSimpleResult(): SimpleFlightResult {
        return SimpleFlightResult(
            price = price,
            currency = currency,
            airlines = airlines,
            departureAirportId = departureId,
            arrivalAirportId = arrivalId,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            stops = stops,
            searchesLeftBeforeSearch = searchesLeftBeforeSearch,
            fromCache = true,
            cachedAtEpochMillis = cachedAtEpochMillis
        )
    }

    private fun buildCacheKey(
        canonicalDepartureIds: String,
        canonicalArrivalIds: String,
        outboundDate: String,
        returnDate: String
    ): String {
        return "$canonicalDepartureIds|$canonicalArrivalIds|$outboundDate|$returnDate"
    }

    private sealed interface QuotaResult {
        data class Available(val searchesLeft: Int) : QuotaResult
        data class Error(
            val message: String,
            val suggestSettings: Boolean = false
        ) : QuotaResult
    }
}
