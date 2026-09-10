package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.NightsSearchCacheDao
import com.archimedeprojects.volaflex.data.local.NightsSearchCacheEntity
import com.archimedeprojects.volaflex.data.network.FlightOptionDto
import com.archimedeprojects.volaflex.data.network.SearchApiService
import com.archimedeprojects.volaflex.data.network.SerpApiService
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val NIGHTS_CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L
private const val NIGHTS_QUOTA_RESERVE = 5
private const val SERP_EXHAUSTIVE_MAX_DATES = 10
private const val SERP_SAMPLE_INITIAL_DATES = 5
private const val SERP_SAMPLE_MAX_DATES = 7
private const val CALENDAR_MAX_SIDE = 14
private const val MAX_NIGHTS_ORIGINS = 3
private const val MAX_NIGHTS_DESTINATIONS = 3

@Serializable
data class NightsSearchResult(
    val outboundDate: String,
    val returnDate: String,
    val nights: Int,
    val price: Int,
    val currency: String,
    val airlines: String,
    val departureTime: String,
    val arrivalTime: String,
    val stops: Int,
    val targetDate: String,
    val flexibilityDays: Int,
    val strategy: String,
    val strategyLabel: String,
    val totalCandidateDates: Int,
    val evaluatedCandidateDates: Int,
    val indicativePrice: Int? = null,
    val calendarRequests: Int = 0,
    val serpApiSearchRequests: Int = 0,
    val searchesLeftBeforeSearch: Int,
    val verifiedAtEpochMillis: Long,
    val departureAirportId: String = "Non disponibile",
    val arrivalAirportId: String = "Non disponibile",
    val fromCache: Boolean = false,
    val cachedAtEpochMillis: Long? = null
)

sealed interface NightsSearchOutcome {
    data class Success(val result: NightsSearchResult) : NightsSearchOutcome
    data class Blocked(val message: String) : NightsSearchOutcome
    data class Error(
        val message: String,
        val suggestSettings: Boolean = false
    ) : NightsSearchOutcome
}

class NightsSearchRepository(
    private val serpService: SerpApiService,
    private val searchApiService: SearchApiService,
    private val cacheDao: NightsSearchCacheDao,
    private val diagnostics: DiagnosticRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun search(
        serpApiKey: String,
        searchApiKey: String?,
        departureIds: List<String>,
        arrivalIds: List<String>,
        nights: Int,
        targetDate: LocalDate,
        flexibilityDays: Int,
        forceRefresh: Boolean = false
    ): NightsSearchOutcome {
        if (nights !in 1..30) {
            return NightsSearchOutcome.Error("Per ora il numero di notti deve essere tra 1 e 30.")
        }
        if (flexibilityDays !in 0..60) {
            return NightsSearchOutcome.Error("Per ora la flessibilità deve essere tra 0 e 60 giorni.")
        }

        val normalizedDepartures = normalizeAirportList(departureIds)
        val normalizedArrivals = normalizeAirportList(arrivalIds)

        if (normalizedDepartures.isEmpty()) {
            return NightsSearchOutcome.Error("Inserisci almeno un aeroporto di partenza.")
        }
        if (normalizedArrivals.isEmpty()) {
            return NightsSearchOutcome.Error("Inserisci almeno un aeroporto di destinazione.")
        }
        if (normalizedDepartures.size > MAX_NIGHTS_ORIGINS) {
            return NightsSearchOutcome.Error("Per ora puoi usare al massimo 3 aeroporti di partenza.")
        }
        if (normalizedArrivals.size > MAX_NIGHTS_DESTINATIONS) {
            return NightsSearchOutcome.Error("Per ora puoi usare al massimo 3 aeroporti di destinazione.")
        }
        if (normalizedDepartures.any { it in normalizedArrivals }) {
            return NightsSearchOutcome.Error("Origini e destinazioni non possono sovrapporsi.")
        }

        val departureQuery = normalizedDepartures.joinToString(",")
        val arrivalQuery = normalizedArrivals.joinToString(",")
        val today = LocalDate.now()
        val candidateDates = (-flexibilityDays..flexibilityDays)
            .map { offset -> targetDate.plusDays(offset.toLong()) }
            .filterNot { it.isBefore(today) }
            .distinct()
            .sorted()

        if (candidateDates.isEmpty()) {
            return NightsSearchOutcome.Error("Il range selezionato non contiene date di partenza valide da oggi in avanti.")
        }

        val strategy = chooseStrategy(
            candidateCount = candidateDates.size,
            hasSearchApiKey = !searchApiKey.isNullOrBlank()
        )
        val cacheKey = buildCacheKey(
            departures = departureQuery,
            arrivals = arrivalQuery,
            nights = nights,
            targetDate = targetDate,
            flexibilityDays = flexibilityDays,
            strategy = strategy
        )
        val now = System.currentTimeMillis()
        val minimumFreshTimestamp = now - NIGHTS_CACHE_TTL_MILLIS

        if (!forceRefresh) {
            val cached = runCatching {
                cacheDao.findFresh(cacheKey, minimumFreshTimestamp)
            }.getOrNull()

            if (cached != null) {
                val cachedResult = runCatching {
                    json.decodeFromString<NightsSearchResult>(cached.resultJson)
                }.getOrNull()

                if (cachedResult != null) {
                    diagnostics.log(
                        requestType = "CACHE",
                        outcome = "HIT",
                        message = "N notti origins=$departureQuery destinations=$arrivalQuery, $nights notti, ±$flexibilityDays giorni, strategy=${strategy.name}"
                    )
                    return NightsSearchOutcome.Success(
                        cachedResult.copy(
                            fromCache = true,
                            cachedAtEpochMillis = cached.cachedAtEpochMillis,
                            serpApiSearchRequests = 0,
                            calendarRequests = 0
                        )
                    )
                }
            }
        }

        val searchesLeft = when (val quota = readLiveQuota(serpApiKey)) {
            is QuotaResult.Available -> quota.searchesLeft
            is QuotaResult.Error -> {
                return NightsSearchOutcome.Error(
                    message = quota.message,
                    suggestSettings = quota.suggestSettings
                )
            }
        }

        val maxSerpRequests = when (strategy) {
            NightsStrategy.SERP_EXHAUSTIVE -> candidateDates.size
            NightsStrategy.SERP_SAMPLE -> minOf(SERP_SAMPLE_MAX_DATES, candidateDates.size)
            NightsStrategy.SEARCHAPI_CALENDAR -> 1
        }
        val minimumRequired = NIGHTS_QUOTA_RESERVE + maxSerpRequests
        if (searchesLeft < minimumRequired) {
            val message = "Ricerca N notti bloccata: servono almeno $minimumRequired query SerpApi residue " +
                "per questa strategia e per conservare la riserva di $NIGHTS_QUOTA_RESERVE. " +
                "Quota attuale: $searchesLeft."
            diagnostics.log(
                requestType = "QUOTA_GUARD",
                outcome = "BLOCKED",
                message = message
            )
            return NightsSearchOutcome.Blocked(message)
        }

        val liveResult = when (strategy) {
            NightsStrategy.SERP_EXHAUSTIVE -> searchSerpExhaustive(
                apiKey = serpApiKey,
                departureId = departureQuery,
                arrivalId = arrivalQuery,
                nights = nights,
                candidateDates = candidateDates,
                targetDate = targetDate,
                flexibilityDays = flexibilityDays,
                searchesLeft = searchesLeft,
                now = now
            )

            NightsStrategy.SERP_SAMPLE -> searchSerpSample(
                apiKey = serpApiKey,
                departureId = departureQuery,
                arrivalId = arrivalQuery,
                nights = nights,
                candidateDates = candidateDates,
                targetDate = targetDate,
                flexibilityDays = flexibilityDays,
                searchesLeft = searchesLeft,
                now = now
            )

            NightsStrategy.SEARCHAPI_CALENDAR -> searchCalendarThenVerify(
                serpApiKey = serpApiKey,
                searchApiKey = requireNotNull(searchApiKey).trim(),
                departureId = departureQuery,
                arrivalId = arrivalQuery,
                nights = nights,
                candidateDates = candidateDates,
                targetDate = targetDate,
                flexibilityDays = flexibilityDays,
                searchesLeft = searchesLeft,
                now = now
            )
        }

        if (liveResult is NightsSearchOutcome.Success) {
            runCatching {
                cacheDao.upsert(
                    NightsSearchCacheEntity(
                        cacheKey = cacheKey,
                        departureId = departureQuery,
                        arrivalId = arrivalQuery,
                        nights = nights,
                        targetDate = targetDate.toString(),
                        flexibilityDays = flexibilityDays,
                        strategy = strategy.name,
                        resultJson = json.encodeToString(
                            liveResult.result.copy(
                                fromCache = false,
                                cachedAtEpochMillis = null
                            )
                        ),
                        cachedAtEpochMillis = now
                    )
                )
                cacheDao.deleteOlderThan(minimumFreshTimestamp)
            }
        }

        return liveResult
    }

    private suspend fun searchSerpExhaustive(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        nights: Int,
        candidateDates: List<LocalDate>,
        targetDate: LocalDate,
        flexibilityDays: Int,
        searchesLeft: Int,
        now: Long
    ): NightsSearchOutcome {
        val evaluated = evaluateExactDates(
            apiKey = apiKey,
            departureId = departureId,
            arrivalId = arrivalId,
            nights = nights,
            dates = candidateDates
        )

        return when (evaluated) {
            is ExactBatchResult.Error -> NightsSearchOutcome.Error(
                evaluated.message,
                evaluated.suggestSettings
            )
            is ExactBatchResult.Success -> {
                val best = evaluated.options.minByOrNull { it.price }
                    ?: return NightsSearchOutcome.Error(
                        "Nessun volo trovato nelle ${candidateDates.size} date controllate."
                    )

                NightsSearchOutcome.Success(
                    best.toResult(
                        nights = nights,
                        targetDate = targetDate,
                        flexibilityDays = flexibilityDays,
                        strategy = NightsStrategy.SERP_EXHAUSTIVE,
                        strategyLabel = "SerpApi diretto — range piccolo esaustivo",
                        totalCandidateDates = candidateDates.size,
                        evaluatedCandidateDates = evaluated.requestCount,
                        indicativePrice = best.price,
                        calendarRequests = 0,
                        serpApiSearchRequests = evaluated.requestCount,
                        searchesLeft = searchesLeft,
                        now = now
                    )
                )
            }
        }
    }

    private suspend fun searchSerpSample(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        nights: Int,
        candidateDates: List<LocalDate>,
        targetDate: LocalDate,
        flexibilityDays: Int,
        searchesLeft: Int,
        now: Long
    ): NightsSearchOutcome {
        val initialDates = evenlySpacedDates(
            dates = candidateDates,
            desiredCount = minOf(SERP_SAMPLE_INITIAL_DATES, candidateDates.size)
        )
        val firstBatch = evaluateExactDates(
            apiKey = apiKey,
            departureId = departureId,
            arrivalId = arrivalId,
            nights = nights,
            dates = initialDates
        )

        if (firstBatch is ExactBatchResult.Error) {
            return NightsSearchOutcome.Error(firstBatch.message, firstBatch.suggestSettings)
        }
        firstBatch as ExactBatchResult.Success

        val allOptions = firstBatch.options.toMutableList()
        val testedDates = initialDates.toMutableSet()
        var requestCount = firstBatch.requestCount
        val firstBest = allOptions.minByOrNull { it.price }

        if (firstBest != null && requestCount < SERP_SAMPLE_MAX_DATES) {
            val bestIndex = candidateDates.indexOf(firstBest.outboundDate)
            val neighborDates = listOf(bestIndex - 1, bestIndex + 1)
                .filter { it in candidateDates.indices }
                .map { candidateDates[it] }
                .filterNot { it in testedDates }
                .take(SERP_SAMPLE_MAX_DATES - requestCount)

            if (neighborDates.isNotEmpty()) {
                val neighborBatch = evaluateExactDates(
                    apiKey = apiKey,
                    departureId = departureId,
                    arrivalId = arrivalId,
                    nights = nights,
                    dates = neighborDates
                )
                if (neighborBatch is ExactBatchResult.Error) {
                    return NightsSearchOutcome.Error(
                        neighborBatch.message,
                        neighborBatch.suggestSettings
                    )
                }
                neighborBatch as ExactBatchResult.Success
                allOptions += neighborBatch.options
                requestCount += neighborBatch.requestCount
            }
        }

        val best = allOptions.minByOrNull { it.price }
            ?: return NightsSearchOutcome.Error(
                "Nessun volo trovato nelle date campionate. Prova un'altra data target o un intervallo diverso."
            )

        return NightsSearchOutcome.Success(
            best.toResult(
                nights = nights,
                targetDate = targetDate,
                flexibilityDays = flexibilityDays,
                strategy = NightsStrategy.SERP_SAMPLE,
                strategyLabel = "SerpApi — modalità risparmio quota (campionamento)",
                totalCandidateDates = candidateDates.size,
                evaluatedCandidateDates = requestCount,
                indicativePrice = best.price,
                calendarRequests = 0,
                serpApiSearchRequests = requestCount,
                searchesLeft = searchesLeft,
                now = now
            )
        )
    }

    private suspend fun searchCalendarThenVerify(
        serpApiKey: String,
        searchApiKey: String,
        departureId: String,
        arrivalId: String,
        nights: Int,
        candidateDates: List<LocalDate>,
        targetDate: LocalDate,
        flexibilityDays: Int,
        searchesLeft: Int,
        now: Long
    ): NightsSearchOutcome {
        val calendarCandidates = mutableListOf<CalendarCandidate>()
        var calendarRequests = 0

        for ((chunkIndex, chunk) in candidateDates.chunked(CALENDAR_MAX_SIDE).withIndex()) {
            val outboundStart = chunk.first()
            val outboundEnd = chunk.last()
            val returnStart = outboundStart.plusDays(nights.toLong())
            val returnEnd = outboundEnd.plusDays(nights.toLong())

            val response = try {
                searchApiService.searchGoogleFlightsCalendar(
                    engine = "google_flights_calendar",
                    flightType = "round_trip",
                    departureId = departureId,
                    arrivalId = arrivalId,
                    outboundDate = outboundStart.toString(),
                    returnDate = returnStart.toString(),
                    outboundDateStart = outboundStart.toString(),
                    outboundDateEnd = outboundEnd.toString(),
                    returnDateStart = returnStart.toString(),
                    returnDateEnd = returnEnd.toString(),
                    travelClass = "economy",
                    currency = "EUR",
                    language = "it",
                    country = "it",
                    apiKey = searchApiKey
                )
            } catch (_: IOException) {
                val message = "Errore di rete durante SearchAPI.io Calendar."
                diagnostics.log("SEARCHAPI_CALENDAR", "ERROR", message = message)
                return NightsSearchOutcome.Error(message)
            } catch (_: SerializationException) {
                val message = "SearchAPI.io Calendar ha restituito dati in un formato non previsto."
                diagnostics.log("SEARCHAPI_CALENDAR", "ERROR", message = message)
                return NightsSearchOutcome.Error(message)
            } catch (_: Exception) {
                val message = "Errore imprevisto durante SearchAPI.io Calendar."
                diagnostics.log("SEARCHAPI_CALENDAR", "ERROR", message = message)
                return NightsSearchOutcome.Error(message)
            }

            calendarRequests += 1

            if (!response.isSuccessful) {
                val suggestSettings = response.code() == 401 || response.code() == 403
                val message = when (response.code()) {
                    401, 403 -> "Chiave SearchAPI.io non valida o non autorizzata. Controllala nelle Impostazioni."
                    429 -> "SearchAPI.io ha bloccato la richiesta per quota o limite raggiunto."
                    400 -> "SearchAPI.io Calendar non ha accettato i parametri del blocco di date."
                    else -> "SearchAPI.io Calendar ha risposto con errore HTTP ${response.code()}."
                }
                diagnostics.log(
                    requestType = "SEARCHAPI_CALENDAR",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = "Blocco ${chunkIndex + 1}, origins=$departureId destinations=$arrivalId: $message"
                )
                return NightsSearchOutcome.Error(message, suggestSettings)
            }

            val body = response.body()
            if (body == null) {
                val message = "SearchAPI.io Calendar ha restituito una risposta vuota."
                diagnostics.log(
                    requestType = "SEARCHAPI_CALENDAR",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = message
                )
                return NightsSearchOutcome.Error(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = "SearchAPI.io non ha completato la richiesta: ${body.error.take(180)}"
                diagnostics.log(
                    requestType = "SEARCHAPI_CALENDAR",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = message
                )
                return NightsSearchOutcome.Error(
                    message = message,
                    suggestSettings = looksLikeApiKeyError(body.error)
                )
            }

            val chunkSet = chunk.toSet()
            val valid = body.calendar.mapNotNull { entry ->
                val outbound = entry.departure?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val returnDate = entry.returnDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val price = entry.price

                if (
                    outbound == null ||
                    returnDate == null ||
                    price == null ||
                    entry.hasNoFlights == true ||
                    outbound !in chunkSet ||
                    returnDate != outbound.plusDays(nights.toLong())
                ) {
                    null
                } else {
                    CalendarCandidate(outbound, returnDate, price)
                }
            }

            calendarCandidates += valid
            diagnostics.log(
                requestType = "SEARCHAPI_CALENDAR",
                outcome = if (valid.isEmpty()) "EMPTY" else "SUCCESS",
                httpStatus = response.code(),
                message = "Blocco ${chunkIndex + 1}: origins=$departureId destinations=$arrivalId, ${chunk.size} partenze, ${valid.size} combinazioni esatte da $nights notti"
            )
        }

        val bestCalendar = calendarCandidates.minByOrNull { it.price }
            ?: return NightsSearchOutcome.Error(
                "SearchAPI.io Calendar non ha trovato combinazioni esatte da $nights notti nel range selezionato."
            )

        return when (
            val verified = queryExactDate(
                apiKey = serpApiKey,
                departureId = departureId,
                arrivalId = arrivalId,
                outboundDate = bestCalendar.outboundDate,
                nights = nights
            )
        ) {
            ExactQueryResult.Empty -> NightsSearchOutcome.Error(
                "Il candidato Calendar più economico non ha prodotto voli nella verifica Google Flights precisa."
            )
            is ExactQueryResult.Error -> NightsSearchOutcome.Error(
                verified.message,
                verified.suggestSettings
            )
            is ExactQueryResult.Success -> NightsSearchOutcome.Success(
                verified.option.toResult(
                    nights = nights,
                    targetDate = targetDate,
                    flexibilityDays = flexibilityDays,
                    strategy = NightsStrategy.SEARCHAPI_CALENDAR,
                    strategyLabel = "SearchAPI.io Calendar → verifica SerpApi",
                    totalCandidateDates = candidateDates.size,
                    evaluatedCandidateDates = candidateDates.size,
                    indicativePrice = bestCalendar.price,
                    calendarRequests = calendarRequests,
                    serpApiSearchRequests = 1,
                    searchesLeft = searchesLeft,
                    now = now
                )
            )
        }
    }

    private suspend fun evaluateExactDates(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        nights: Int,
        dates: List<LocalDate>
    ): ExactBatchResult {
        val options = mutableListOf<ExactCandidate>()
        var requestCount = 0

        for (date in dates) {
            when (
                val result = queryExactDate(
                    apiKey = apiKey,
                    departureId = departureId,
                    arrivalId = arrivalId,
                    outboundDate = date,
                    nights = nights
                )
            ) {
                ExactQueryResult.Empty -> requestCount += 1
                is ExactQueryResult.Success -> {
                    requestCount += 1
                    options += result.option
                }
                is ExactQueryResult.Error -> {
                    return ExactBatchResult.Error(
                        message = result.message,
                        suggestSettings = result.suggestSettings
                    )
                }
            }
        }

        return ExactBatchResult.Success(options, requestCount)
    }

    private suspend fun queryExactDate(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        outboundDate: LocalDate,
        nights: Int
    ): ExactQueryResult {
        val returnDate = outboundDate.plusDays(nights.toLong())

        return try {
            val response = serpService.searchGoogleFlightsByPrice(
                engine = "google_flights",
                departureId = departureId,
                arrivalId = arrivalId,
                outboundDate = outboundDate.toString(),
                returnDate = returnDate.toString(),
                type = 1,
                travelClass = 1,
                sortBy = 2,
                currency = "EUR",
                language = "it",
                country = "it",
                apiKey = apiKey
            )

            if (!response.isSuccessful) {
                val mapped = mapSerpHttpError(response.code())
                diagnostics.log(
                    requestType = "GOOGLE_FLIGHTS",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = "N notti origins=$departureId destinations=$arrivalId $outboundDate→$returnDate: ${mapped.message}"
                )
                return mapped
            }

            val body = response.body()
            if (body == null) {
                val message = "SerpApi ha restituito una risposta vuota durante la ricerca N notti."
                diagnostics.log("GOOGLE_FLIGHTS", "ERROR", response.code(), message)
                return ExactQueryResult.Error(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = readableSerpApiError(body.error)
                diagnostics.log("GOOGLE_FLIGHTS", "ERROR", response.code(), message)
                return ExactQueryResult.Error(
                    message = message,
                    suggestSettings = looksLikeApiKeyError(body.error)
                )
            }

            val option = (body.bestFlights + body.otherFlights)
                .filter { it.price != null && it.flights.isNotEmpty() }
                .minByOrNull { requireNotNull(it.price) }

            if (option == null) {
                diagnostics.log(
                    requestType = "GOOGLE_FLIGHTS",
                    outcome = "EMPTY",
                    httpStatus = response.code(),
                    message = "N notti origins=$departureId destinations=$arrivalId $outboundDate→$returnDate: nessun volo"
                )
                ExactQueryResult.Empty
            } else {
                val exact = option.toExactCandidate(
                    outboundDate = outboundDate,
                    returnDate = returnDate,
                    currency = body.searchParameters?.currency ?: "EUR"
                )
                diagnostics.log(
                    requestType = "GOOGLE_FLIGHTS",
                    outcome = "SUCCESS",
                    httpStatus = response.code(),
                    message = "N notti origins=$departureId destinations=$arrivalId $outboundDate→$returnDate: ${exact.price} ${exact.currency}, winner=${exact.departureAirportId}→${exact.arrivalAirportId}"
                )
                ExactQueryResult.Success(exact)
            }
        } catch (_: IOException) {
            val message = "Errore di rete durante Google Flights."
            diagnostics.log("GOOGLE_FLIGHTS", "ERROR", message = message)
            ExactQueryResult.Error(message)
        } catch (_: SerializationException) {
            val message = "SerpApi ha restituito dati in un formato non previsto."
            diagnostics.log("GOOGLE_FLIGHTS", "ERROR", message = message)
            ExactQueryResult.Error(message)
        } catch (_: Exception) {
            val message = "Errore imprevisto durante la ricerca Google Flights."
            diagnostics.log("GOOGLE_FLIGHTS", "ERROR", message = message)
            ExactQueryResult.Error(message)
        }
    }

    private suspend fun readLiveQuota(apiKey: String): QuotaResult {
        return try {
            val response = serpService.getAccount(apiKey)
            if (!response.isSuccessful) {
                val mapped = mapSerpHttpError(response.code())
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

    private fun evenlySpacedDates(
        dates: List<LocalDate>,
        desiredCount: Int
    ): List<LocalDate> {
        if (desiredCount >= dates.size) return dates
        if (desiredCount <= 1) return listOf(dates[dates.size / 2])

        return (0 until desiredCount)
            .map { position ->
                val index = (position * (dates.lastIndex.toDouble() / (desiredCount - 1))).roundToInt()
                dates[index]
            }
            .distinct()
            .sorted()
    }

    private fun chooseStrategy(
        candidateCount: Int,
        hasSearchApiKey: Boolean
    ): NightsStrategy {
        return when {
            candidateCount <= SERP_EXHAUSTIVE_MAX_DATES -> NightsStrategy.SERP_EXHAUSTIVE
            hasSearchApiKey -> NightsStrategy.SEARCHAPI_CALENDAR
            else -> NightsStrategy.SERP_SAMPLE
        }
    }

    private fun normalizeAirportList(values: List<String>): List<String> {
        return values
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun buildCacheKey(
        departures: String,
        arrivals: String,
        nights: Int,
        targetDate: LocalDate,
        flexibilityDays: Int,
        strategy: NightsStrategy
    ): String {
        return "NIGHTS|$departures|$arrivals|$nights|$targetDate|$flexibilityDays|${strategy.name}"
    }

    private fun mapSerpHttpError(code: Int): ExactQueryResult.Error {
        return when (code) {
            401, 403 -> ExactQueryResult.Error(
                "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni.",
                suggestSettings = true
            )
            429 -> ExactQueryResult.Error("SerpApi ha bloccato la richiesta per quota o limite orario raggiunto.")
            400 -> ExactQueryResult.Error("SerpApi non ha accettato i parametri della ricerca N notti.")
            else -> ExactQueryResult.Error("SerpApi ha risposto con errore HTTP $code.")
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

    private fun FlightOptionDto.toExactCandidate(
        outboundDate: LocalDate,
        returnDate: LocalDate,
        currency: String
    ): ExactCandidate {
        val firstSegment = flights.first()
        val lastSegment = flights.last()
        val airlineNames = flights
            .mapNotNull { it.airline?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString(" / ")
            .ifBlank { "Compagnia non disponibile" }

        return ExactCandidate(
            outboundDate = outboundDate,
            returnDate = returnDate,
            price = requireNotNull(price),
            currency = currency,
            airlines = airlineNames,
            departureAirportId = firstSegment.departureAirport?.id ?: "Non disponibile",
            arrivalAirportId = lastSegment.arrivalAirport?.id ?: "Non disponibile",
            departureTime = firstSegment.departureAirport?.time ?: "Orario non disponibile",
            arrivalTime = lastSegment.arrivalAirport?.time ?: "Orario non disponibile",
            stops = layovers.size
        )
    }

    private fun ExactCandidate.toResult(
        nights: Int,
        targetDate: LocalDate,
        flexibilityDays: Int,
        strategy: NightsStrategy,
        strategyLabel: String,
        totalCandidateDates: Int,
        evaluatedCandidateDates: Int,
        indicativePrice: Int?,
        calendarRequests: Int,
        serpApiSearchRequests: Int,
        searchesLeft: Int,
        now: Long
    ): NightsSearchResult {
        return NightsSearchResult(
            outboundDate = outboundDate.toString(),
            returnDate = returnDate.toString(),
            nights = nights,
            price = price,
            currency = currency,
            airlines = airlines,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            stops = stops,
            targetDate = targetDate.toString(),
            flexibilityDays = flexibilityDays,
            strategy = strategy.name,
            strategyLabel = strategyLabel,
            totalCandidateDates = totalCandidateDates,
            evaluatedCandidateDates = evaluatedCandidateDates,
            indicativePrice = indicativePrice,
            calendarRequests = calendarRequests,
            serpApiSearchRequests = serpApiSearchRequests,
            searchesLeftBeforeSearch = searchesLeft,
            verifiedAtEpochMillis = now,
            departureAirportId = departureAirportId,
            arrivalAirportId = arrivalAirportId
        )
    }

    private enum class NightsStrategy {
        SERP_EXHAUSTIVE,
        SEARCHAPI_CALENDAR,
        SERP_SAMPLE
    }

    private data class CalendarCandidate(
        val outboundDate: LocalDate,
        val returnDate: LocalDate,
        val price: Int
    )

    private data class ExactCandidate(
        val outboundDate: LocalDate,
        val returnDate: LocalDate,
        val price: Int,
        val currency: String,
        val airlines: String,
        val departureAirportId: String,
        val arrivalAirportId: String,
        val departureTime: String,
        val arrivalTime: String,
        val stops: Int
    )

    private sealed interface ExactQueryResult {
        data class Success(val option: ExactCandidate) : ExactQueryResult
        data object Empty : ExactQueryResult
        data class Error(
            val message: String,
            val suggestSettings: Boolean = false
        ) : ExactQueryResult
    }

    private sealed interface ExactBatchResult {
        data class Success(
            val options: List<ExactCandidate>,
            val requestCount: Int
        ) : ExactBatchResult

        data class Error(
            val message: String,
            val suggestSettings: Boolean = false
        ) : ExactBatchResult
    }

    private sealed interface QuotaResult {
        data class Available(val searchesLeft: Int) : QuotaResult
        data class Error(
            val message: String,
            val suggestSettings: Boolean = false
        ) : QuotaResult
    }
}
