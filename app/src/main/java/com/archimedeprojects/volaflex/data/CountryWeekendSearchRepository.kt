package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.CountryArea
import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheDao
import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheEntity
import com.archimedeprojects.volaflex.data.network.SerpApiService
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val COUNTRY_WEEKEND_CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L
private const val COUNTRY_WEEKEND_QUOTA_RESERVE = 5

@Serializable
data class CountryWeekendCandidate(
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

data class CountryWeekendSearchResult(
    val selectedCountry: CountryArea,
    val candidates: List<CountryWeekendCandidate>,
    val searchesLeftBeforeSearch: Int,
    val exploreRequests: Int,
    val fromCache: Boolean = false,
    val cachedAtEpochMillis: Long? = null
)

sealed interface CountryWeekendSearchOutcome {
    data class Success(val result: CountryWeekendSearchResult) : CountryWeekendSearchOutcome
    data class Blocked(val message: String) : CountryWeekendSearchOutcome
    data class Error(
        val message: String,
        val suggestSettings: Boolean = false
    ) : CountryWeekendSearchOutcome
}

class CountryWeekendSearchRepository(
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
        country: CountryArea,
        months: List<WeekendMonthRequest>,
        forceRefresh: Boolean = false
    ): CountryWeekendSearchOutcome {
        if (months.isEmpty()) {
            return CountryWeekendSearchOutcome.Error("Seleziona almeno un mese da cercare.")
        }

        val origins = departureIds
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        if (origins.isEmpty()) {
            return CountryWeekendSearchOutcome.Error("Inserisci almeno un aeroporto di partenza.")
        }
        if (origins.size > 3) {
            return CountryWeekendSearchOutcome.Error("Per ora puoi usare al massimo 3 aeroporti di partenza.")
        }

        val departure = origins.joinToString(",")
        val periodKey = months.joinToString(",") { it.yearMonthKey }
        val countryMarker = "COUNTRY:${country.iso2.uppercase(Locale.ROOT)}"
        val cacheKey = "WEEKEND|$departure|$countryMarker|$periodKey"
        val now = System.currentTimeMillis()
        val minimumFreshTimestamp = now - COUNTRY_WEEKEND_CACHE_TTL_MILLIS

        if (!forceRefresh) {
            val cached = runCatching {
                cacheDao.findFresh(cacheKey, minimumFreshTimestamp)
            }.getOrNull()

            if (cached != null) {
                val cachedCandidates = runCatching {
                    json.decodeFromString<List<CountryWeekendCandidate>>(cached.candidatesJson)
                }.getOrNull()

                if (!cachedCandidates.isNullOrEmpty()) {
                    diagnostics.log(
                        requestType = "CACHE",
                        outcome = "HIT",
                        message = "Weekend Country origins=$departure country=${country.iso2} period=$periodKey"
                    )
                    return CountryWeekendSearchOutcome.Success(
                        CountryWeekendSearchResult(
                            selectedCountry = country,
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
            is QuotaResult.Error -> return CountryWeekendSearchOutcome.Error(
                message = quota.message,
                suggestSettings = quota.suggestSettings
            )
        }

        val estimatedExploreQueries = months.size
        val minimumRequired = COUNTRY_WEEKEND_QUOTA_RESERVE + estimatedExploreQueries
        if (searchesLeft < minimumRequired) {
            val message = "Ricerca Paese bloccata: servono almeno $minimumRequired query SerpApi residue " +
                "per eseguire $estimatedExploreQueries query Explore e conservare la riserva di " +
                "$COUNTRY_WEEKEND_QUOTA_RESERVE. Quota attuale: $searchesLeft."
            diagnostics.log("QUOTA_GUARD", "BLOCKED", message = message)
            return CountryWeekendSearchOutcome.Blocked(message)
        }

        val allCandidates = mutableListOf<CountryWeekendCandidate>()
        var exploreRequests = 0
        var monthsWithoutOpportunities = 0

        for (month in months) {
            when (
                val outcome = searchSingleMonth(
                    apiKey = apiKey,
                    departureId = departure,
                    country = country,
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
                    return CountryWeekendSearchOutcome.Error(
                        message = outcome.message,
                        suggestSettings = outcome.suggestSettings
                    )
                }
            }
        }

        if (allCandidates.isEmpty()) {
            val message = if (monthsWithoutOpportunities == months.size) {
                "Travel Explore ha risposto con una struttura valida, ma non ha segnalato opportunità weekend prezzate per ${country.name} nel periodo selezionato."
            } else {
                "Travel Explore non ha prodotto candidati utilizzabili per ${country.name}."
            }
            return CountryWeekendSearchOutcome.Error(message)
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
                    arrivalId = countryMarker,
                    periodKey = periodKey,
                    candidatesJson = json.encodeToString(sortedCandidates),
                    searchesLeftBeforeSearch = searchesLeft,
                    cachedAtEpochMillis = now
                )
            )
            cacheDao.deleteOlderThan(minimumFreshTimestamp)
        }

        return CountryWeekendSearchOutcome.Success(
            CountryWeekendSearchResult(
                selectedCountry = country,
                candidates = sortedCandidates,
                searchesLeftBeforeSearch = searchesLeft,
                exploreRequests = exploreRequests
            )
        )
    }

    private suspend fun searchSingleMonth(
        apiKey: String,
        departureId: String,
        country: CountryArea,
        month: WeekendMonthRequest
    ): SingleMonthOutcome {
        return try {
            val response = service.searchTravelExploreCountry(
                engine = "google_travel_explore",
                departureId = departureId,
                arrivalAreaId = country.kgmid,
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
                    message = "Country ${country.iso2} origins=$departureId ${month.label}: ${mapped.message}"
                )
                return SingleMonthOutcome.Error(mapped.message, mapped.suggestSettings)
            }

            val scope = "Paese ${country.name} (${country.iso2}), origini=$departureId, ${month.label}"
            when (val parsed = TravelExploreRawParser.parse(response.body(), scope)) {
                is TravelExploreRawParseOutcome.Success -> {
                    val candidates = parsed.candidates.map { candidate ->
                        CountryWeekendCandidate(
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
                        requestType = "TRAVEL_EXPLORE",
                        outcome = "SUCCESS",
                        httpStatus = response.code(),
                        message = "Country ${country.iso2} origins=$departureId ${month.label}: ${candidates.size} destinazioni utilizzabili, migliore ${candidates.first().airportIata} ${candidates.first().price} ${candidates.first().currency}"
                    )
                    SingleMonthOutcome.Success(candidates)
                }
                TravelExploreRawParseOutcome.NoOpportunities -> {
                    diagnostics.log(
                        requestType = "TRAVEL_EXPLORE",
                        outcome = "NO_OPPORTUNITIES",
                        httpStatus = response.code(),
                        message = "Country ${country.iso2} origins=$departureId ${month.label}: struttura valida, nessuna opportunità weekend prezzata"
                    )
                    SingleMonthOutcome.NoOpportunities
                }
                is TravelExploreRawParseOutcome.Error -> {
                    diagnostics.log(
                        requestType = "TRAVEL_EXPLORE",
                        outcome = parsed.diagnosticOutcome,
                        httpStatus = response.code(),
                        message = "Country ${country.iso2} origins=$departureId ${month.label}: ${parsed.message}"
                    )
                    SingleMonthOutcome.Error(parsed.message, parsed.suggestSettings)
                }
            }
        } catch (_: IOException) {
            val message = "Errore di rete durante Travel Explore Paese. Controlla la connessione e riprova."
            diagnostics.log(
                requestType = "TRAVEL_EXPLORE",
                outcome = "NETWORK_ERROR",
                message = "Country ${country.iso2} origins=$departureId ${month.label}: $message"
            )
            SingleMonthOutcome.Error(message)
        } catch (_: Exception) {
            val message = "Travel Explore Paese ha restituito dati che VolaFlex non è riuscita a interpretare in sicurezza."
            diagnostics.log(
                requestType = "TRAVEL_EXPLORE",
                outcome = "STRUCTURE_ANOMALY",
                message = "Country ${country.iso2} origins=$departureId ${month.label}: $message"
            )
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

    private fun mapHttpError(code: Int): CountryWeekendSearchOutcome.Error {
        return when (code) {
            401, 403 -> CountryWeekendSearchOutcome.Error(
                "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni.",
                suggestSettings = true
            )
            429 -> CountryWeekendSearchOutcome.Error(
                "SerpApi ha bloccato la richiesta per quota o limite orario raggiunto."
            )
            400 -> CountryWeekendSearchOutcome.Error(
                "SerpApi non ha accettato i parametri della ricerca Paese."
            )
            else -> CountryWeekendSearchOutcome.Error(
                "SerpApi ha risposto con errore HTTP $code durante la ricerca Paese."
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
        data class Success(val candidates: List<CountryWeekendCandidate>) : SingleMonthOutcome
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
