package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheDao
import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheEntity
import com.archimedeprojects.volaflex.data.network.SerpApiService
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ANYWHERE_WEEKEND_CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L
private const val ANYWHERE_WEEKEND_QUOTA_RESERVE = 5
private const val ANYWHERE_CACHE_MARKER = "ANYWHERE"
private val IATA_PATTERN = Regex("^[A-Z]{3}$")

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
    val cachedAtEpochMillis: Long? = null,
    val verifiedWeekend: VerifiedWeekendResult? = null,
    val verificationFromCache: Boolean = false,
    val verificationAttemptedIata: String? = null,
    val verificationMessage: String? = null
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
    private val verificationEngine = WeekendVerificationEngine(service, cacheDao, diagnostics)

    suspend fun search(
        apiKey: String,
        departureIds: List<String>,
        months: List<WeekendMonthRequest>,
        forceRefresh: Boolean = false
    ): AnywhereWeekendSearchOutcome {
        if (months.isEmpty()) return AnywhereWeekendSearchOutcome.Error("Seleziona almeno un mese da cercare.")

        val origins = departureIds
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        if (origins.isEmpty()) return AnywhereWeekendSearchOutcome.Error("Inserisci almeno un aeroporto di partenza.")
        if (origins.size > 3) return AnywhereWeekendSearchOutcome.Error("Per ora puoi usare al massimo 3 aeroporti di partenza.")

        val departure = origins.joinToString(",")
        val periodKey = months.joinToString(",") { it.yearMonthKey }
        val cacheKey = "WEEKEND|$departure|$ANYWHERE_CACHE_MARKER|$periodKey"
        val now = System.currentTimeMillis()
        val minimumFreshTimestamp = now - ANYWHERE_WEEKEND_CACHE_TTL_MILLIS

        if (!forceRefresh) {
            val cached = runCatching { cacheDao.findFresh(cacheKey, minimumFreshTimestamp) }.getOrNull()
            if (cached != null) {
                val cachedCandidates = runCatching {
                    json.decodeFromString<List<AnywhereWeekendCandidate>>(cached.candidatesJson)
                }.getOrNull()
                if (!cachedCandidates.isNullOrEmpty()) {
                    diagnostics.log("CACHE", "HIT", message = "Weekend Anywhere origins=$departure period=$periodKey")
                    val verification = verifyBestEligibleCandidate(
                        apiKey = apiKey,
                        departureId = departure,
                        periodKey = periodKey,
                        candidates = cachedCandidates,
                        forceRefresh = false
                    )
                    return AnywhereWeekendSearchOutcome.Success(
                        AnywhereWeekendSearchResult(
                            candidates = cachedCandidates,
                            searchesLeftBeforeSearch = cached.searchesLeftBeforeSearch,
                            exploreRequests = 0,
                            fromCache = true,
                            cachedAtEpochMillis = cached.cachedAtEpochMillis,
                            verifiedWeekend = verification.verifiedWeekend,
                            verificationFromCache = verification.fromCache,
                            verificationAttemptedIata = verification.attemptedIata,
                            verificationMessage = verification.message
                        )
                    )
                }
            }
        }

        val searchesLeft = when (val quota = readLiveQuota(apiKey)) {
            is QuotaResult.Available -> quota.searchesLeft
            is QuotaResult.Error -> return AnywhereWeekendSearchOutcome.Error(quota.message, quota.suggestSettings)
        }

        val estimatedExploreQueries = months.size
        val minimumRequired = ANYWHERE_WEEKEND_QUOTA_RESERVE + estimatedExploreQueries
        if (searchesLeft < minimumRequired) {
            val message = "Ricerca Ovunque bloccata: servono almeno $minimumRequired query SerpApi residue per eseguire $estimatedExploreQueries query Explore e conservare la riserva di $ANYWHERE_WEEKEND_QUOTA_RESERVE. Quota attuale: $searchesLeft."
            diagnostics.log("QUOTA_GUARD", "BLOCKED", message = message)
            return AnywhereWeekendSearchOutcome.Blocked(message)
        }

        val allCandidates = mutableListOf<AnywhereWeekendCandidate>()
        var exploreRequests = 0
        var monthsWithoutOpportunities = 0

        for (month in months) {
            when (val outcome = searchSingleMonth(apiKey, departure, month)) {
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
                    return AnywhereWeekendSearchOutcome.Error(outcome.message, outcome.suggestSettings)
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
            .distinctBy { "${it.monthKey}|${it.airportIata}|${it.outboundDate}|${it.returnDate}" }
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

        val verification = verifyBestEligibleCandidate(
            apiKey = apiKey,
            departureId = departure,
            periodKey = periodKey,
            candidates = sortedCandidates,
            forceRefresh = forceRefresh
        )

        return AnywhereWeekendSearchOutcome.Success(
            AnywhereWeekendSearchResult(
                candidates = sortedCandidates,
                searchesLeftBeforeSearch = searchesLeft,
                exploreRequests = exploreRequests,
                verifiedWeekend = verification.verifiedWeekend,
                verificationFromCache = verification.fromCache,
                verificationAttemptedIata = verification.attemptedIata,
                verificationMessage = verification.message
            )
        )
    }

    private suspend fun verifyBestEligibleCandidate(
        apiKey: String,
        departureId: String,
        periodKey: String,
        candidates: List<AnywhereWeekendCandidate>,
        forceRefresh: Boolean
    ): VerificationAttachment {
        val candidate = candidates
            .asSequence()
            .filter { IATA_PATTERN.matches(it.airportIata.trim().uppercase(Locale.ROOT)) }
            .map { it to it.toVerificationSeed() }
            .firstOrNull { (_, seed) -> verificationEngine.hasValidPattern(seed) }

        if (candidate == null) {
            val message = "Discovery disponibile, ma nessun candidato Ovunque ha IATA e date sufficienti per una verifica weekend sicura."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "NO_OPPORTUNITIES",
                message = "ANYWHERE origins=$departureId period=$periodKey: $message"
            )
            return VerificationAttachment(message = message)
        }

        val (selected, seed) = candidate
        diagnostics.log(
            requestType = "WEEKEND_VERIFY",
            outcome = "CANDIDATE_SELECTED",
            message = "ANYWHERE origins=$departureId period=$periodKey: candidato unico ${selected.airportIata} ${selected.price} ${selected.currency}"
        )

        return when (
            val verification = verificationEngine.verify(
                apiKey = apiKey,
                departureId = departureId,
                destinationScope = ANYWHERE_CACHE_MARKER,
                periodKey = periodKey,
                seed = seed,
                forceRefresh = forceRefresh
            )
        ) {
            is WeekendVerificationOutcome.Success -> VerificationAttachment(
                verifiedWeekend = verification.result,
                fromCache = verification.fromCache,
                attemptedIata = selected.airportIata
            )
            is WeekendVerificationOutcome.Unavailable -> VerificationAttachment(
                fromCache = verification.fromCache,
                attemptedIata = selected.airportIata,
                message = verification.message
            )
        }
    }

    private fun AnywhereWeekendCandidate.toVerificationSeed() = WeekendVerificationSeed(
        airportIata = airportIata,
        outboundDate = outboundDate,
        returnDate = returnDate,
        monthKey = monthKey
    )

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
                    "TRAVEL_EXPLORE", "HTTP_ERROR", response.code(),
                    "Anywhere origins=$departureId ${month.label}: ${mapped.message}"
                )
                return SingleMonthOutcome.Error(mapped.message, mapped.suggestSettings)
            }

            val scope = "Ovunque, origini=$departureId, ${month.label}"
            when (val parsed = TravelExploreRawParser.parse(response.body(), scope)) {
                is TravelExploreRawParseOutcome.Success -> {
                    val candidates = parsed.candidates.map { candidate ->
                        AnywhereWeekendCandidate(
                            city = candidate.city,
                            country = candidate.country,
                            airportIata = candidate.airportIata,
                            airportName = candidate.airportName,
                            outboundDate = candidate.outboundDate,
                            returnDate = candidate.returnDate,
                            price = candidate.price,
                            currency = candidate.currency,
                            monthLabel = month.label,
                            monthKey = month.yearMonthKey
                        )
                    }.sortedBy { it.price }
                    diagnostics.log(
                        "TRAVEL_EXPLORE", "SUCCESS", response.code(),
                        "Anywhere origins=$departureId ${month.label}: ${candidates.size} destinazioni utilizzabili, migliore ${candidates.first().airportIata} ${candidates.first().price} ${candidates.first().currency}"
                    )
                    SingleMonthOutcome.Success(candidates)
                }
                TravelExploreRawParseOutcome.NoOpportunities -> {
                    diagnostics.log(
                        "TRAVEL_EXPLORE", "NO_OPPORTUNITIES", response.code(),
                        "Anywhere origins=$departureId ${month.label}: lista destinazioni valida, nessuna opportunità weekend prezzata"
                    )
                    SingleMonthOutcome.NoOpportunities
                }
                is TravelExploreRawParseOutcome.Error -> {
                    diagnostics.log(
                        "TRAVEL_EXPLORE", parsed.diagnosticOutcome, response.code(),
                        "Anywhere origins=$departureId ${month.label}: ${parsed.message}"
                    )
                    SingleMonthOutcome.Error(parsed.message, parsed.suggestSettings)
                }
            }
        } catch (_: IOException) {
            val message = "Errore di rete durante Travel Explore Ovunque. Controlla la connessione e riprova."
            diagnostics.log("TRAVEL_EXPLORE", "NETWORK_ERROR", message = "Anywhere origins=$departureId ${month.label}: $message")
            SingleMonthOutcome.Error(message)
        } catch (_: Exception) {
            val message = "Travel Explore Ovunque ha restituito dati che VolaFlex non è riuscita a interpretare in sicurezza."
            diagnostics.log("TRAVEL_EXPLORE", "STRUCTURE_ANOMALY", message = "Anywhere origins=$departureId ${month.label}: $message")
            SingleMonthOutcome.Error(message)
        }
    }

    private suspend fun readLiveQuota(apiKey: String): QuotaResult {
        return try {
            val response = service.getAccount(apiKey)
            if (!response.isSuccessful) {
                val mapped = mapHttpError(response.code())
                diagnostics.log("SERPAPI_ACCOUNT", "ERROR", response.code(), mapped.message)
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
            diagnostics.log("SERPAPI_ACCOUNT", "SUCCESS", response.code(), "Quota live: $searchesLeft query rimaste")
            QuotaResult.Available(searchesLeft)
        } catch (_: Exception) {
            val message = "Impossibile verificare la quota SerpApi. Ricerca bloccata per sicurezza."
            diagnostics.log("SERPAPI_ACCOUNT", "ERROR", message = message)
            QuotaResult.Error(message)
        }
    }

    private fun mapHttpError(code: Int): AnywhereWeekendSearchOutcome.Error = when (code) {
        401, 403 -> AnywhereWeekendSearchOutcome.Error("Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni.", true)
        429 -> AnywhereWeekendSearchOutcome.Error("SerpApi ha bloccato la richiesta per quota o limite orario raggiunto.")
        400 -> AnywhereWeekendSearchOutcome.Error("SerpApi non ha accettato i parametri della ricerca Ovunque.")
        else -> AnywhereWeekendSearchOutcome.Error("SerpApi ha risposto con errore HTTP $code durante la ricerca Ovunque.")
    }

    private fun readableSerpApiError(rawError: String): String = if (looksLikeApiKeyError(rawError)) {
        "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni."
    } else {
        "SerpApi non ha completato la richiesta: ${rawError.take(180)}"
    }

    private fun looksLikeApiKeyError(message: String): Boolean {
        val normalized = message.lowercase()
        return "api key" in normalized || "api_key" in normalized || "unauthorized" in normalized || "authentication" in normalized
    }

    private data class VerificationAttachment(
        val verifiedWeekend: VerifiedWeekendResult? = null,
        val fromCache: Boolean = false,
        val attemptedIata: String? = null,
        val message: String? = null
    )

    private sealed interface SingleMonthOutcome {
        data class Success(val candidates: List<AnywhereWeekendCandidate>) : SingleMonthOutcome
        data object NoOpportunities : SingleMonthOutcome
        data class Error(val message: String, val suggestSettings: Boolean = false) : SingleMonthOutcome
    }

    private sealed interface QuotaResult {
        data class Available(val searchesLeft: Int) : QuotaResult
        data class Error(val message: String, val suggestSettings: Boolean = false) : QuotaResult
    }
}
