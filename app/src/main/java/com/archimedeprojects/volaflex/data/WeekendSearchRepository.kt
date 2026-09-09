package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheDao
import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheEntity
import com.archimedeprojects.volaflex.data.network.SerpApiService
import com.archimedeprojects.volaflex.data.network.TravelExploreResponseDto
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val WEEKEND_CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L
private const val WEEKEND_QUOTA_RESERVE = 5

@Serializable
data class WeekendCandidate(
    val outboundDate: String,
    val returnDate: String,
    val price: Int,
    val currency: String,
    val destinationIata: String,
    val destinationName: String,
    val monthLabel: String
)

data class WeekendMonthRequest(
    val yearMonthKey: String,
    val monthNumber: Int,
    val label: String
)

data class WeekendSearchResult(
    val candidates: List<WeekendCandidate>,
    val searchesLeftBeforeSearch: Int,
    val fromCache: Boolean = false,
    val cachedAtEpochMillis: Long? = null
)

sealed interface WeekendSearchOutcome {
    data class Success(val result: WeekendSearchResult) : WeekendSearchOutcome
    data class Blocked(val message: String) : WeekendSearchOutcome
    data class Error(
        val message: String,
        val suggestSettings: Boolean = false
    ) : WeekendSearchOutcome
}

class WeekendSearchRepository(
    private val service: SerpApiService,
    private val cacheDao: WeekendSearchCacheDao,
    private val diagnostics: DiagnosticRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun searchWeekendCandidates(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        months: List<WeekendMonthRequest>,
        forceRefresh: Boolean = false
    ): WeekendSearchOutcome {
        if (months.isEmpty()) {
            return WeekendSearchOutcome.Error("Seleziona almeno un mese da cercare.")
        }

        val departure = departureId.trim().uppercase(Locale.ROOT)
        val arrival = arrivalId.trim().uppercase(Locale.ROOT)
        val periodKey = months.joinToString(",") { it.yearMonthKey }
        val cacheKey = "WEEKEND|$departure|$arrival|$periodKey"
        val now = System.currentTimeMillis()
        val minimumFreshTimestamp = now - WEEKEND_CACHE_TTL_MILLIS

        if (!forceRefresh) {
            val cached = runCatching {
                cacheDao.findFresh(cacheKey, minimumFreshTimestamp)
            }.getOrNull()

            if (cached != null) {
                val candidates = runCatching {
                    json.decodeFromString<List<WeekendCandidate>>(cached.candidatesJson)
                }.getOrNull()

                if (!candidates.isNullOrEmpty()) {
                    diagnostics.log(
                        requestType = "CACHE",
                        outcome = "HIT",
                        message = "Weekend $departure→$arrival $periodKey"
                    )
                    return WeekendSearchOutcome.Success(
                        WeekendSearchResult(
                            candidates = candidates,
                            searchesLeftBeforeSearch = cached.searchesLeftBeforeSearch,
                            fromCache = true,
                            cachedAtEpochMillis = cached.cachedAtEpochMillis
                        )
                    )
                }
            }
        }

        val searchesLeft = when (val quotaResult = readLiveQuota(apiKey)) {
            is QuotaResult.Available -> quotaResult.searchesLeft
            is QuotaResult.Error -> {
                return WeekendSearchOutcome.Error(
                    message = quotaResult.message,
                    suggestSettings = quotaResult.suggestSettings
                )
            }
        }

        val estimatedQueries = months.size
        val minimumRequired = WEEKEND_QUOTA_RESERVE + estimatedQueries
        if (searchesLeft < minimumRequired) {
            val message = "Ricerca weekend bloccata: servono almeno $minimumRequired query residue " +
                "per eseguire $estimatedQueries query Explore e conservare una riserva di " +
                "$WEEKEND_QUOTA_RESERVE. Quota attuale: $searchesLeft."
            diagnostics.log(
                requestType = "QUOTA_GUARD",
                outcome = "BLOCKED",
                message = message
            )
            return WeekendSearchOutcome.Blocked(message)
        }

        val candidates = mutableListOf<WeekendCandidate>()

        for (month in months) {
            val outcome = searchSingleMonth(
                apiKey = apiKey,
                departureId = departure,
                arrivalId = arrival,
                month = month
            )

            when (outcome) {
                is SingleMonthOutcome.Success -> candidates += outcome.candidate
                SingleMonthOutcome.Empty -> Unit
                is SingleMonthOutcome.Error -> {
                    return WeekendSearchOutcome.Error(
                        message = outcome.message,
                        suggestSettings = outcome.suggestSettings
                    )
                }
            }
        }

        if (candidates.isEmpty()) {
            return WeekendSearchOutcome.Error(
                "Google Travel Explore non ha trovato weekend per questa rotta nel periodo selezionato."
            )
        }

        val sortedCandidates = candidates.sortedBy { it.price }

        runCatching {
            cacheDao.upsert(
                WeekendSearchCacheEntity(
                    cacheKey = cacheKey,
                    departureId = departure,
                    arrivalId = arrival,
                    periodKey = periodKey,
                    candidatesJson = json.encodeToString(sortedCandidates),
                    searchesLeftBeforeSearch = searchesLeft,
                    cachedAtEpochMillis = now
                )
            )
            cacheDao.deleteOlderThan(minimumFreshTimestamp)
        }

        return WeekendSearchOutcome.Success(
            WeekendSearchResult(
                candidates = sortedCandidates,
                searchesLeftBeforeSearch = searchesLeft
            )
        )
    }

    private suspend fun searchSingleMonth(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        month: WeekendMonthRequest
    ): SingleMonthOutcome {
        return try {
            val response = service.searchTravelExplore(
                engine = "google_travel_explore",
                departureId = departureId,
                arrivalId = arrivalId,
                month = month.monthNumber,
                travelDuration = 1,
                travelClass = 1,
                travelMode = 1,
                currency = "EUR",
                language = "it",
                country = "it",
                apiKey = apiKey
            )

            if (!response.isSuccessful) {
                val mapped = mapHttpError(response.code(), duringAccountCheck = false)
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = "${month.label}: ${mapped.message}"
                )
                return SingleMonthOutcome.Error(
                    message = mapped.message,
                    suggestSettings = mapped.suggestSettings
                )
            }

            val body = response.body()
            if (body == null) {
                val message = "Travel Explore ha restituito una risposta vuota per ${month.label}."
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = message
                )
                return SingleMonthOutcome.Error(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = readableSerpApiError(body.error)
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = "${month.label}: $message"
                )
                return SingleMonthOutcome.Error(
                    message = message,
                    suggestSettings = looksLikeApiKeyError(body.error)
                )
            }

            val candidate = body.toWeekendCandidate(
                fallbackArrivalId = arrivalId,
                monthLabel = month.label
            )

            if (candidate == null) {
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "EMPTY",
                    httpStatus = response.code(),
                    message = "$departureId→$arrivalId ${month.label}: nessun weekend"
                )
                SingleMonthOutcome.Empty
            } else {
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "SUCCESS",
                    httpStatus = response.code(),
                    message = "$departureId→$arrivalId ${month.label}: ${candidate.price} ${candidate.currency}"
                )
                SingleMonthOutcome.Success(candidate)
            }
        } catch (_: IOException) {
            val message = "Errore di rete durante Travel Explore. Controlla la connessione e riprova."
            diagnostics.log(
                requestType = "TRAVEL_EXPLORE",
                outcome = "ERROR",
                message = "${month.label}: $message"
            )
            SingleMonthOutcome.Error(message)
        } catch (_: SerializationException) {
            val message = "Travel Explore ha restituito dati in un formato non previsto."
            diagnostics.log(
                requestType = "TRAVEL_EXPLORE",
                outcome = "ERROR",
                message = "${month.label}: $message"
            )
            SingleMonthOutcome.Error(message)
        } catch (_: Exception) {
            val message = "Errore imprevisto durante la ricerca weekend. Riprova."
            diagnostics.log(
                requestType = "TRAVEL_EXPLORE",
                outcome = "ERROR",
                message = "${month.label}: $message"
            )
            SingleMonthOutcome.Error(message)
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
                return QuotaResult.Error(mapped.message, mapped.suggestSettings)
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
            diagnostics.log("SERPAPI_ACCOUNT", "ERROR", message = message)
            QuotaResult.Error(message)
        } catch (_: SerializationException) {
            val message = "Impossibile interpretare la risposta Account API. Ricerca bloccata per sicurezza."
            diagnostics.log("SERPAPI_ACCOUNT", "ERROR", message = message)
            QuotaResult.Error(message)
        } catch (_: Exception) {
            val message = "Impossibile verificare la quota SerpApi. Ricerca bloccata per sicurezza."
            diagnostics.log("SERPAPI_ACCOUNT", "ERROR", message = message)
            QuotaResult.Error(message)
        }
    }

    private fun TravelExploreResponseDto.toWeekendCandidate(
        fallbackArrivalId: String,
        monthLabel: String
    ): WeekendCandidate? {
        val directFlight = flights
            .filter { it.price != null }
            .let { priced ->
                priced.firstOrNull { it.cheapestFlight == true }
                    ?: priced.minByOrNull { it.price ?: Int.MAX_VALUE }
            }

        if (startDate != null && endDate != null && directFlight?.price != null) {
            return WeekendCandidate(
                outboundDate = startDate,
                returnDate = endDate,
                price = directFlight.price,
                currency = searchParameters?.currency ?: "EUR",
                destinationIata = directFlight.arrivalAirport?.id ?: fallbackArrivalId,
                destinationName = directFlight.arrivalAirport?.name ?: fallbackArrivalId,
                monthLabel = monthLabel
            )
        }

        val destination = destinations.firstOrNull {
            it.destinationAirport?.code.equals(fallbackArrivalId, ignoreCase = true) &&
                it.startDate != null && it.endDate != null && it.flightPrice != null
        } ?: destinations.firstOrNull {
            it.startDate != null && it.endDate != null && it.flightPrice != null
        }

        if (destination?.startDate == null || destination.endDate == null || destination.flightPrice == null) {
            return null
        }

        return WeekendCandidate(
            outboundDate = destination.startDate,
            returnDate = destination.endDate,
            price = destination.flightPrice,
            currency = searchParameters?.currency ?: "EUR",
            destinationIata = destination.destinationAirport?.code ?: fallbackArrivalId,
            destinationName = destination.name
                ?: destination.destinationAirport?.location
                ?: fallbackArrivalId,
            monthLabel = monthLabel
        )
    }

    private fun mapHttpError(
        code: Int,
        duringAccountCheck: Boolean
    ): WeekendSearchOutcome.Error {
        return when (code) {
            401, 403 -> WeekendSearchOutcome.Error(
                message = "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni.",
                suggestSettings = true
            )
            429 -> WeekendSearchOutcome.Error(
                message = "SerpApi ha bloccato la richiesta per quota o limite orario raggiunto."
            )
            400 -> WeekendSearchOutcome.Error(
                message = if (duringAccountCheck) {
                    "SerpApi non ha accettato la chiave salvata. Controllala nelle Impostazioni."
                } else {
                    "Travel Explore non ha accettato i parametri della ricerca weekend."
                },
                suggestSettings = duringAccountCheck
            )
            else -> WeekendSearchOutcome.Error(
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

    private sealed interface SingleMonthOutcome {
        data class Success(val candidate: WeekendCandidate) : SingleMonthOutcome
        data object Empty : SingleMonthOutcome
        data class Error(
            val message: String,
            val suggestSettings: Boolean = false
        ) : SingleMonthOutcome
    }

    private sealed interface QuotaResult {
        data class Available(val searchesLeft: Int) : QuotaResult
        data class Error(
            val message: String,
            val suggestSettings: Boolean = false
        ) : QuotaResult
    }
}
