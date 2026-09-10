package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheDao
import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheEntity
import com.archimedeprojects.volaflex.data.network.SerpApiService
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val ANYWHERE_WEEKEND_CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L
private const val ANYWHERE_WEEKEND_QUOTA_RESERVE = 5
private const val ANYWHERE_CACHE_MARKER = "ANYWHERE"

@Serializable
data class AnywhereWeekendCandidate(
    val city: String,
    val country: String,
    val airportIata: String,
    val airportName: String,
    val outboundDate: String,
    val returnDate: String,
    val price: Int,
    val currency: String,
    val monthLabel: String,
    val monthKey: String
)

data class AnywhereWeekendSearchResult(
    val candidates: List<AnywhereWeekendCandidate>,
    val searchesLeftBeforeSearch: Int,
    val exploreRequests: Int,
    val fromCache: Boolean = false,
    val cachedAtEpochMillis: Long? = null
)

sealed interface AnywhereWeekendSearchOutcome {
    data class Success(val result: AnywhereWeekendSearchResult) : AnywhereWeekendSearchOutcome
    data class Blocked(val message: String) : AnywhereWeekendSearchOutcome
    data class Error(
        val message: String,
        val suggestSettings: Boolean = false
    ) : AnywhereWeekendSearchOutcome
}

class AnywhereWeekendSearchRepository(
    private val service: SerpApiService,
    private val cacheDao: WeekendSearchCacheDao,
    private val diagnostics: DiagnosticRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun search(
        apiKey: String,
        departureIds: List<String>,
        months: List<WeekendMonthRequest>,
        forceRefresh: Boolean = false
    ): AnywhereWeekendSearchOutcome {
        if (months.isEmpty()) {
            return AnywhereWeekendSearchOutcome.Error("Seleziona almeno un mese da cercare.")
        }

        val origins = departureIds
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        if (origins.isEmpty()) {
            return AnywhereWeekendSearchOutcome.Error("Inserisci almeno un aeroporto di partenza.")
        }
        if (origins.size > 3) {
            return AnywhereWeekendSearchOutcome.Error("Per ora puoi usare al massimo 3 aeroporti di partenza.")
        }

        val departure = origins.joinToString(",")
        val periodKey = months.joinToString(",") { it.yearMonthKey }
        val cacheKey = "WEEKEND|$departure|$ANYWHERE_CACHE_MARKER|$periodKey"
        val now = System.currentTimeMillis()
        val minimumFreshTimestamp = now - ANYWHERE_WEEKEND_CACHE_TTL_MILLIS

        if (!forceRefresh) {
            val cached = runCatching {
                cacheDao.findFresh(cacheKey, minimumFreshTimestamp)
            }.getOrNull()

            if (cached != null) {
                val cachedCandidates = runCatching {
                    json.decodeFromString<List<AnywhereWeekendCandidate>>(cached.candidatesJson)
                }.getOrNull()

                if (!cachedCandidates.isNullOrEmpty()) {
                    diagnostics.log(
                        requestType = "CACHE",
                        outcome = "HIT",
                        message = "Weekend Anywhere origins=$departure period=$periodKey"
                    )
                    return AnywhereWeekendSearchOutcome.Success(
                        AnywhereWeekendSearchResult(
                            candidates = cachedCandidates,
                            searchesLeftBeforeSearch = cached.searchesLeftBeforeSearch,
                            exploreRequests = 0,
                            fromCache = true,
                            cachedAtEpochMillis = cached.cachedAtEpochMillis
                        )
                    )
                }
            }
        }

        val searchesLeft = when (val quota = readLiveQuota(apiKey)) {
            is QuotaResult.Available -> quota.searchesLeft
            is QuotaResult.Error -> {
                return AnywhereWeekendSearchOutcome.Error(
                    message = quota.message,
                    suggestSettings = quota.suggestSettings
                )
            }
        }

        val estimatedExploreQueries = months.size
        val minimumRequired = ANYWHERE_WEEKEND_QUOTA_RESERVE + estimatedExploreQueries
        if (searchesLeft < minimumRequired) {
            val message = "Ricerca Ovunque bloccata: servono almeno $minimumRequired query SerpApi residue " +
                "per eseguire $estimatedExploreQueries query Explore e conservare la riserva di " +
                "$ANYWHERE_WEEKEND_QUOTA_RESERVE. Quota attuale: $searchesLeft."
            diagnostics.log(
                requestType = "QUOTA_GUARD",
                outcome = "BLOCKED",
                message = message
            )
            return AnywhereWeekendSearchOutcome.Blocked(message)
        }

        val allCandidates = mutableListOf<AnywhereWeekendCandidate>()
        var exploreRequests = 0
        var monthsWithoutOpportunities = 0

        for (month in months) {
            when (
                val outcome = searchSingleMonth(
                    apiKey = apiKey,
                    departureId = departure,
                    month = month
                )
            ) {
                is SingleMonthOutcome.Success -> {
                    exploreRequests += 1
                    allCandidates += outcome.candidates
                }
                SingleMonthOutcome.NoOpportunities -> {
                    exploreRequests += 1
                    monthsWithoutOpportunities += 1
                }
                is SingleMonthOutcome.Error -> {
                    exploreRequests += 1
                    return AnywhereWeekendSearchOutcome.Error(
                        message = outcome.message,
                        suggestSettings = outcome.suggestSettings
                    )
                }
            }
        }

        if (allCandidates.isEmpty()) {
            val message = if (monthsWithoutOpportunities == months.size) {
                "Travel Explore ha risposto con una struttura valida, ma non ha segnalato opportunità weekend prezzate nel periodo selezionato."
            } else {
                "Travel Explore non ha prodotto candidati utilizzabili per la ricerca Ovunque."
            }
            return AnywhereWeekendSearchOutcome.Error(message)
        }

        val sortedCandidates = allCandidates
            .distinctBy { candidate ->
                "${candidate.monthKey}|${candidate.airportIata}|${candidate.outboundDate}|${candidate.returnDate}"
            }
            .sortedBy { it.price }

        runCatching {
            cacheDao.upsert(
                WeekendSearchCacheEntity(
                    cacheKey = cacheKey,
                    departureId = departure,
                    arrivalId = ANYWHERE_CACHE_MARKER,
                    periodKey = periodKey,
                    candidatesJson = json.encodeToString(sortedCandidates),
                    searchesLeftBeforeSearch = searchesLeft,
                    cachedAtEpochMillis = now
                )
            )
            cacheDao.deleteOlderThan(minimumFreshTimestamp)
        }

        return AnywhereWeekendSearchOutcome.Success(
            AnywhereWeekendSearchResult(
                candidates = sortedCandidates,
                searchesLeftBeforeSearch = searchesLeft,
                exploreRequests = exploreRequests
            )
        )
    }

    private suspend fun searchSingleMonth(
        apiKey: String,
        departureId: String,
        month: WeekendMonthRequest
    ): SingleMonthOutcome {
        return try {
            val response = service.searchTravelExploreAnywhere(
                engine = "google_travel_explore",
                departureId = departureId,
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
                val mapped = mapHttpError(response.code())
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "HTTP_ERROR",
                    httpStatus = response.code(),
                    message = "Anywhere origins=$departureId ${month.label}: ${mapped.message}"
                )
                return SingleMonthOutcome.Error(
                    message = mapped.message,
                    suggestSettings = mapped.suggestSettings
                )
            }

            val body = response.body()
            if (body == null) {
                val message = "Travel Explore ha restituito un body mancante per ${month.label}. Non viene interpretato come assenza di voli."
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "BODY_MISSING",
                    httpStatus = response.code(),
                    message = "Anywhere origins=$departureId: $message"
                )
                return SingleMonthOutcome.Error(message)
            }

            val providerError = body.stringOrNull("error")
            if (!providerError.isNullOrBlank()) {
                val message = readableSerpApiError(providerError)
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "PROVIDER_ERROR",
                    httpStatus = response.code(),
                    message = "Anywhere origins=$departureId ${month.label}: $message"
                )
                return SingleMonthOutcome.Error(
                    message = message,
                    suggestSettings = looksLikeApiKeyError(providerError)
                )
            }

            val destinationsElement = body["destinations"]
            if (destinationsElement == null) {
                val message = "Travel Explore non ha incluso il campo 'destinations' nella risposta Ovunque per ${month.label}. Struttura anomala: nessuna conclusione sui voli."
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "STRUCTURE_ANOMALY",
                    httpStatus = response.code(),
                    message = "Anywhere origins=$departureId: $message"
                )
                return SingleMonthOutcome.Error(message)
            }

            val destinations = destinationsElement as? JsonArray
            if (destinations == null) {
                val message = "Il campo 'destinations' di Travel Explore non è una lista per ${month.label}. Struttura JSON anomala."
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "STRUCTURE_ANOMALY",
                    httpStatus = response.code(),
                    message = "Anywhere origins=$departureId: $message"
                )
                return SingleMonthOutcome.Error(message)
            }

            if (destinations.isEmpty()) {
                val message = "Travel Explore ha restituito una lista 'destinations' presente ma vuota per una ricerca Ovunque ampia (${month.label}). Vista la storia recente del provider, il caso è trattato come risposta sorprendentemente vuota e non come prova di assenza voli."
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "SURPRISING_EMPTY",
                    httpStatus = response.code(),
                    message = "Anywhere origins=$departureId: $message"
                )
                return SingleMonthOutcome.Error(message)
            }

            val currency = (body["search_parameters"] as? JsonObject)
                ?.stringOrNull("currency")
                ?: "EUR"
            var malformedPricedEntries = 0

            val candidates = destinations.mapNotNull { element ->
                val destination = element as? JsonObject
                if (destination == null) {
                    malformedPricedEntries += 1
                    return@mapNotNull null
                }

                val airport = destination["destination_airport"] as? JsonObject
                val city = destination.stringOrNull("name")
                    ?: airport?.stringOrNull("location")
                val country = destination.stringOrNull("country")
                val airportIata = airport?.stringOrNull("code")
                    ?.trim()
                    ?.uppercase(Locale.ROOT)
                val airportName = airport?.stringOrNull("name")
                val outboundDate = destination.stringOrNull("start_date")
                val returnDate = destination.stringOrNull("end_date")
                val price = destination.intOrNull("flight_price")

                val hasOfferSignal = price != null || outboundDate != null || returnDate != null
                val datesValid = outboundDate?.let { runCatching { LocalDate.parse(it) }.isSuccess } == true &&
                    returnDate?.let { runCatching { LocalDate.parse(it) }.isSuccess } == true
                val iataValid = airportIata?.let { code ->
                    code.length == 3 && code.all(Char::isLetter)
                } == true

                if (
                    city.isNullOrBlank() ||
                    country.isNullOrBlank() ||
                    !iataValid ||
                    !datesValid ||
                    price == null
                ) {
                    if (hasOfferSignal) malformedPricedEntries += 1
                    return@mapNotNull null
                }

                val resolvedIata = requireNotNull(airportIata)
                AnywhereWeekendCandidate(
                    city = city,
                    country = country,
                    airportIata = resolvedIata,
                    airportName = airportName ?: resolvedIata,
                    outboundDate = requireNotNull(outboundDate),
                    returnDate = requireNotNull(returnDate),
                    price = price,
                    currency = currency,
                    monthLabel = month.label,
                    monthKey = month.yearMonthKey
                )
            }

            if (candidates.isEmpty()) {
                if (malformedPricedEntries > 0) {
                    val message = "Travel Explore ha restituito destinazioni con segnali di offerta ma campi essenziali mancanti/non validi per ${month.label}. Struttura anomala: nessun candidato viene mostrato."
                    diagnostics.log(
                        requestType = "TRAVEL_EXPLORE",
                        outcome = "STRUCTURE_ANOMALY",
                        httpStatus = response.code(),
                        message = "Anywhere origins=$departureId: $message"
                    )
                    SingleMonthOutcome.Error(message)
                } else {
                    diagnostics.log(
                        requestType = "TRAVEL_EXPLORE",
                        outcome = "NO_OPPORTUNITIES",
                        httpStatus = response.code(),
                        message = "Anywhere origins=$departureId ${month.label}: lista destinazioni valida, nessuna opportunità weekend prezzata"
                    )
                    SingleMonthOutcome.NoOpportunities
                }
            } else {
                val sorted = candidates.sortedBy { it.price }
                diagnostics.log(
                    requestType = "TRAVEL_EXPLORE",
                    outcome = "SUCCESS",
                    httpStatus = response.code(),
                    message = "Anywhere origins=$departureId ${month.label}: ${sorted.size} destinazioni utilizzabili, migliore ${sorted.first().airportIata} ${sorted.first().price} ${sorted.first().currency}"
                )
                SingleMonthOutcome.Success(sorted)
            }
        } catch (_: IOException) {
            val message = "Errore di rete durante Travel Explore Ovunque. Controlla la connessione e riprova."
            diagnostics.log(
                requestType = "TRAVEL_EXPLORE",
                outcome = "NETWORK_ERROR",
                message = "Anywhere origins=$departureId ${month.label}: $message"
            )
            SingleMonthOutcome.Error(message)
        } catch (_: Exception) {
            val message = "Travel Explore Ovunque ha restituito dati che VolaFlex non è riuscita a interpretare in sicurezza."
            diagnostics.log(
                requestType = "TRAVEL_EXPLORE",
                outcome = "STRUCTURE_ANOMALY",
                message = "Anywhere origins=$departureId ${month.label}: $message"
            )
            SingleMonthOutcome.Error(message)
        }
    }

    private suspend fun readLiveQuota(apiKey: String): QuotaResult {
        return try {
            val response = service.getAccount(apiKey)
            if (!response.isSuccessful) {
                val mapped = mapHttpError(response.code())
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
                diagnostics.log("SERPAPI_ACCOUNT", "ERROR", response.code(), message)
                return QuotaResult.Error(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = readableSerpApiError(body.error)
                diagnostics.log("SERPAPI_ACCOUNT", "ERROR", response.code(), message)
                return QuotaResult.Error(message, looksLikeApiKeyError(body.error))
            }

            val searchesLeft = body.totalSearchesLeft ?: body.planSearchesLeft
            if (searchesLeft == null) {
                val message = "SerpApi non ha restituito il numero di query rimaste. Ricerca bloccata per sicurezza."
                diagnostics.log("SERPAPI_ACCOUNT", "ERROR", response.code(), message)
                return QuotaResult.Error(message)
            }

            diagnostics.log(
                requestType = "SERPAPI_ACCOUNT",
                outcome = "SUCCESS",
                httpStatus = response.code(),
                message = "Quota live: $searchesLeft query rimaste"
            )
            QuotaResult.Available(searchesLeft)
        } catch (_: Exception) {
            val message = "Impossibile verificare la quota SerpApi. Ricerca bloccata per sicurezza."
            diagnostics.log("SERPAPI_ACCOUNT", "ERROR", message = message)
            QuotaResult.Error(message)
        }
    }

    private fun mapHttpError(code: Int): AnywhereWeekendSearchOutcome.Error {
        return when (code) {
            401, 403 -> AnywhereWeekendSearchOutcome.Error(
                "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni.",
                suggestSettings = true
            )
            429 -> AnywhereWeekendSearchOutcome.Error(
                "SerpApi ha bloccato la richiesta per quota o limite orario raggiunto."
            )
            400 -> AnywhereWeekendSearchOutcome.Error(
                "SerpApi non ha accettato i parametri della ricerca Ovunque."
            )
            else -> AnywhereWeekendSearchOutcome.Error(
                "SerpApi ha risposto con errore HTTP $code durante la ricerca Ovunque."
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

    private fun JsonObject.stringOrNull(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        return (this[name] as? JsonPrimitive)?.intOrNull
    }

    private sealed interface SingleMonthOutcome {
        data class Success(val candidates: List<AnywhereWeekendCandidate>) : SingleMonthOutcome
        data object NoOpportunities : SingleMonthOutcome
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
