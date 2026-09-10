package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheDao
import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheEntity
import com.archimedeprojects.volaflex.data.network.FlightOptionDto
import com.archimedeprojects.volaflex.data.network.SerpApiService
import com.archimedeprojects.volaflex.data.network.TravelExploreResponseDto
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val WEEKEND_CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L
private const val WEEKEND_QUOTA_RESERVE = 5
private const val FRIDAY_OUTBOUND_TIMES = "17,23"
private const val SATURDAY_OUTBOUND_TIMES = "5,11"
private const val SUNDAY_RETURN_TIMES = "17,23"
private const val MONDAY_RETURN_TIMES = "0,23"

@Serializable
data class VerifiedWeekendResult(
    val patternLabel: String,
    val outboundDate: String,
    val returnDate: String,
    val price: Int,
    val currency: String,
    val airlines: String,
    val outboundDepartureTime: String,
    val outboundArrivalTime: String,
    val outboundStops: Int,
    val returnWindowLabel: String,
    val searchesLeftBeforeVerification: Int,
    val verifiedAtEpochMillis: Long
)

@Serializable
data class WeekendCandidate(
    val outboundDate: String,
    val returnDate: String,
    val price: Int,
    val currency: String,
    val destinationIata: String,
    val destinationName: String,
    val monthLabel: String,
    val monthKey: String = "",
    val verification: VerifiedWeekendResult? = null
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
    val cachedAtEpochMillis: Long? = null,
    val verificationFromCache: Boolean = false,
    val verificationMessage: String? = null
) {
    val verifiedWeekend: VerifiedWeekendResult?
        get() = candidates.firstNotNullOfOrNull { it.verification }
}

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
                val cachedCandidates = runCatching {
                    json.decodeFromString<List<WeekendCandidate>>(cached.candidatesJson)
                }.getOrNull()

                if (!cachedCandidates.isNullOrEmpty()) {
                    val alreadyVerified = cachedCandidates.any { it.verification != null }
                    diagnostics.log(
                        requestType = "CACHE",
                        outcome = "HIT",
                        message = if (alreadyVerified) {
                            "Weekend verificato $departure→$arrival $periodKey"
                        } else {
                            "Weekend Discovery $departure→$arrival $periodKey; verifica da completare"
                        }
                    )

                    if (alreadyVerified) {
                        return WeekendSearchOutcome.Success(
                            WeekendSearchResult(
                                candidates = cachedCandidates,
                                searchesLeftBeforeSearch = cached.searchesLeftBeforeSearch,
                                fromCache = true,
                                cachedAtEpochMillis = cached.cachedAtEpochMillis,
                                verificationFromCache = true
                            )
                        )
                    }

                    val verification = verifyBestCandidate(
                        apiKey = apiKey,
                        departureId = departure,
                        arrivalId = arrival,
                        candidates = cachedCandidates
                    )

                    return when (verification) {
                        is VerificationPhase.Success -> {
                            updateCachedCandidates(
                                cached = cached,
                                candidates = verification.candidates
                            )
                            WeekendSearchOutcome.Success(
                                WeekendSearchResult(
                                    candidates = verification.candidates,
                                    searchesLeftBeforeSearch = cached.searchesLeftBeforeSearch,
                                    fromCache = true,
                                    cachedAtEpochMillis = cached.cachedAtEpochMillis
                                )
                            )
                        }
                        is VerificationPhase.Unavailable -> {
                            WeekendSearchOutcome.Success(
                                WeekendSearchResult(
                                    candidates = cachedCandidates,
                                    searchesLeftBeforeSearch = cached.searchesLeftBeforeSearch,
                                    fromCache = true,
                                    cachedAtEpochMillis = cached.cachedAtEpochMillis,
                                    verificationMessage = verification.message
                                )
                            )
                        }
                    }
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

        val estimatedExploreQueries = months.size
        val minimumRequired = WEEKEND_QUOTA_RESERVE + estimatedExploreQueries
        if (searchesLeft < minimumRequired) {
            val message = "Ricerca weekend bloccata: servono almeno $minimumRequired query residue " +
                "per eseguire $estimatedExploreQueries query Explore e conservare una riserva di " +
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

        saveCache(
            cacheKey = cacheKey,
            departureId = departure,
            arrivalId = arrival,
            periodKey = periodKey,
            candidates = sortedCandidates,
            searchesLeftBeforeSearch = searchesLeft,
            cachedAtEpochMillis = now,
            minimumFreshTimestamp = minimumFreshTimestamp
        )

        return when (
            val verification = verifyBestCandidate(
                apiKey = apiKey,
                departureId = departure,
                arrivalId = arrival,
                candidates = sortedCandidates
            )
        ) {
            is VerificationPhase.Success -> {
                saveCache(
                    cacheKey = cacheKey,
                    departureId = departure,
                    arrivalId = arrival,
                    periodKey = periodKey,
                    candidates = verification.candidates,
                    searchesLeftBeforeSearch = searchesLeft,
                    cachedAtEpochMillis = now,
                    minimumFreshTimestamp = minimumFreshTimestamp
                )
                WeekendSearchOutcome.Success(
                    WeekendSearchResult(
                        candidates = verification.candidates,
                        searchesLeftBeforeSearch = searchesLeft
                    )
                )
            }
            is VerificationPhase.Unavailable -> {
                WeekendSearchOutcome.Success(
                    WeekendSearchResult(
                        candidates = sortedCandidates,
                        searchesLeftBeforeSearch = searchesLeft,
                        verificationMessage = verification.message
                    )
                )
            }
        }
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
                monthLabel = month.label,
                monthKey = month.yearMonthKey
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

    private suspend fun verifyBestCandidate(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        candidates: List<WeekendCandidate>
    ): VerificationPhase {
        val bestIndex = candidates.indices.minByOrNull { candidates[it].price }
            ?: return VerificationPhase.Unavailable("Nessun candidato Explore disponibile da verificare.")
        val bestCandidate = candidates[bestIndex]
        val patterns = buildVerificationPatterns(bestCandidate)

        if (patterns.isEmpty()) {
            val message = "Explore ha trovato un intervallo indicativo, ma non è stato possibile ricavare un weekend venerdì/sabato → domenica/lunedì valido nello stesso mese."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "EMPTY",
                message = message
            )
            return VerificationPhase.Unavailable(message)
        }

        val searchesLeft = when (val quotaResult = readLiveQuota(apiKey)) {
            is QuotaResult.Available -> quotaResult.searchesLeft
            is QuotaResult.Error -> return VerificationPhase.Unavailable(quotaResult.message)
        }

        val minimumRequired = WEEKEND_QUOTA_RESERVE + patterns.size
        if (searchesLeft < minimumRequired) {
            val message = "Verifica weekend bloccata: servono almeno $minimumRequired query residue per controllare ${patterns.size} combinazioni e conservare la riserva di $WEEKEND_QUOTA_RESERVE. Quota attuale: $searchesLeft. Il risultato Explore resta disponibile come indicativo."
            diagnostics.log(
                requestType = "QUOTA_GUARD",
                outcome = "BLOCKED",
                message = message
            )
            return VerificationPhase.Unavailable(message)
        }

        val verifiedOptions = mutableListOf<VerifiedWeekendResult>()
        var verificationError: String? = null

        for (pattern in patterns) {
            when (
                val outcome = verifyPattern(
                    apiKey = apiKey,
                    departureId = departureId,
                    arrivalId = arrivalId,
                    pattern = pattern,
                    searchesLeftBeforeVerification = searchesLeft
                )
            ) {
                is PatternVerification.Success -> verifiedOptions += outcome.result
                PatternVerification.Empty -> Unit
                is PatternVerification.Error -> {
                    verificationError = outcome.message
                    break
                }
            }
        }

        val cheapestVerified = verifiedOptions.minByOrNull { it.price }
        if (cheapestVerified == null) {
            return VerificationPhase.Unavailable(
                verificationError
                    ?: "Nessun volo compatibile con le fasce weekend richieste è stato trovato per il candidato Explore."
            )
        }

        val updatedCandidates = candidates.toMutableList()
        updatedCandidates[bestIndex] = bestCandidate.copy(
            verification = cheapestVerified
        )

        return VerificationPhase.Success(updatedCandidates)
    }

    private suspend fun verifyPattern(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        pattern: WeekendPattern,
        searchesLeftBeforeVerification: Int
    ): PatternVerification {
        return try {
            val response = service.searchGoogleFlightsWeekendVerification(
                engine = "google_flights",
                departureId = departureId,
                arrivalId = arrivalId,
                outboundDate = pattern.outboundDate.toString(),
                returnDate = pattern.returnDate.toString(),
                outboundTimes = pattern.outboundTimes,
                returnTimes = pattern.returnTimes,
                type = 1,
                travelClass = 1,
                sortBy = 2,
                currency = "EUR",
                language = "it",
                country = "it",
                apiKey = apiKey
            )

            if (!response.isSuccessful) {
                val mapped = mapHttpError(response.code(), duringAccountCheck = false)
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = "${pattern.label}: ${mapped.message}"
                )
                return PatternVerification.Error(mapped.message)
            }

            val body = response.body()
            if (body == null) {
                val message = "Google Flights ha restituito una risposta vuota durante la verifica weekend."
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = "${pattern.label}: $message"
                )
                return PatternVerification.Error(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = readableSerpApiError(body.error)
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "ERROR",
                    httpStatus = response.code(),
                    message = "${pattern.label}: $message"
                )
                return PatternVerification.Error(message)
            }

            val option = (body.bestFlights + body.otherFlights)
                .filter { it.price != null && it.flights.isNotEmpty() }
                .minByOrNull { it.price ?: Int.MAX_VALUE }

            if (option == null) {
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "EMPTY",
                    httpStatus = response.code(),
                    message = "$departureId→$arrivalId ${pattern.label}: nessun volo"
                )
                return PatternVerification.Empty
            }

            val verified = option.toVerifiedWeekendResult(
                pattern = pattern,
                currency = body.searchParameters?.currency ?: "EUR",
                searchesLeftBeforeVerification = searchesLeftBeforeVerification
            )

            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "SUCCESS",
                httpStatus = response.code(),
                message = "$departureId→$arrivalId ${pattern.label}: ${verified.price} ${verified.currency}"
            )
            PatternVerification.Success(verified)
        } catch (_: IOException) {
            val message = "Errore di rete durante la verifica Google Flights del weekend."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "ERROR",
                message = "${pattern.label}: $message"
            )
            PatternVerification.Error(message)
        } catch (_: SerializationException) {
            val message = "Google Flights ha restituito dati in un formato non previsto durante la verifica weekend."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "ERROR",
                message = "${pattern.label}: $message"
            )
            PatternVerification.Error(message)
        } catch (_: Exception) {
            val message = "Errore imprevisto durante la verifica del weekend."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "ERROR",
                message = "${pattern.label}: $message"
            )
            PatternVerification.Error(message)
        }
    }

    private fun buildVerificationPatterns(candidate: WeekendCandidate): List<WeekendPattern> {
        val exploreStart = runCatching { LocalDate.parse(candidate.outboundDate) }.getOrNull()
            ?: return emptyList()
        val exploreEnd = runCatching { LocalDate.parse(candidate.returnDate) }.getOrNull()
            ?: return emptyList()
        val targetMonth = runCatching {
            candidate.monthKey.takeIf { it.isNotBlank() }?.let(YearMonth::parse)
                ?: YearMonth.from(exploreStart)
        }.getOrElse { YearMonth.from(exploreStart) }

        val monthStart = targetMonth.atDay(1)
        val monthEnd = targetMonth.atEndOfMonth()
        val rangeStart = maxOf(exploreStart, monthStart, LocalDate.now())
        val rangeEnd = minOf(exploreEnd, monthEnd)

        var referenceFriday = if (!rangeStart.isAfter(rangeEnd)) {
            rangeStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
                .takeIf { !it.isAfter(rangeEnd) }
        } else {
            null
        }

        if (referenceFriday == null) {
            val midpointDay = ((exploreStart.dayOfMonth + exploreEnd.dayOfMonth) / 2)
                .coerceIn(1, targetMonth.lengthOfMonth())
            val midpoint = targetMonth.atDay(midpointDay)
            val nextFriday = midpoint.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
            val previousFriday = midpoint.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
            referenceFriday = listOf(previousFriday, nextFriday)
                .filter { YearMonth.from(it) == targetMonth && !it.isBefore(LocalDate.now()) }
                .minByOrNull { kotlin.math.abs(it.toEpochDay() - midpoint.toEpochDay()) }
        }

        val friday = referenceFriday ?: return emptyList()
        val saturday = friday.plusDays(1)
        val sunday = friday.plusDays(2)
        val monday = friday.plusDays(3)

        return buildList {
            if (YearMonth.from(friday) == targetMonth && YearMonth.from(sunday) == targetMonth) {
                add(
                    WeekendPattern(
                        label = "Venerdì sera → domenica sera",
                        outboundDate = friday,
                        returnDate = sunday,
                        outboundTimes = FRIDAY_OUTBOUND_TIMES,
                        returnTimes = SUNDAY_RETURN_TIMES,
                        returnWindowLabel = "Domenica sera, 17:00–23:59"
                    )
                )
            }
            if (YearMonth.from(saturday) == targetMonth && YearMonth.from(monday) == targetMonth) {
                add(
                    WeekendPattern(
                        label = "Sabato mattina → lunedì",
                        outboundDate = saturday,
                        returnDate = monday,
                        outboundTimes = SATURDAY_OUTBOUND_TIMES,
                        returnTimes = MONDAY_RETURN_TIMES,
                        returnWindowLabel = "Lunedì, tutta la giornata"
                    )
                )
            }
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
        monthLabel: String,
        monthKey: String
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
                monthLabel = monthLabel,
                monthKey = monthKey
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
            monthLabel = monthLabel,
            monthKey = monthKey
        )
    }

    private fun FlightOptionDto.toVerifiedWeekendResult(
        pattern: WeekendPattern,
        currency: String,
        searchesLeftBeforeVerification: Int
    ): VerifiedWeekendResult {
        val firstSegment = flights.first()
        val lastSegment = flights.last()
        val airlineNames = flights
            .mapNotNull { it.airline?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString(" / ")
            .ifBlank { "Compagnia non disponibile" }

        return VerifiedWeekendResult(
            patternLabel = pattern.label,
            outboundDate = pattern.outboundDate.toString(),
            returnDate = pattern.returnDate.toString(),
            price = requireNotNull(price),
            currency = currency,
            airlines = airlineNames,
            outboundDepartureTime = firstSegment.departureAirport?.time ?: "Orario non disponibile",
            outboundArrivalTime = lastSegment.arrivalAirport?.time ?: "Orario non disponibile",
            outboundStops = layovers.size,
            returnWindowLabel = pattern.returnWindowLabel,
            searchesLeftBeforeVerification = searchesLeftBeforeVerification,
            verifiedAtEpochMillis = System.currentTimeMillis()
        )
    }

    private suspend fun saveCache(
        cacheKey: String,
        departureId: String,
        arrivalId: String,
        periodKey: String,
        candidates: List<WeekendCandidate>,
        searchesLeftBeforeSearch: Int,
        cachedAtEpochMillis: Long,
        minimumFreshTimestamp: Long
    ) {
        runCatching {
            cacheDao.upsert(
                WeekendSearchCacheEntity(
                    cacheKey = cacheKey,
                    departureId = departureId,
                    arrivalId = arrivalId,
                    periodKey = periodKey,
                    candidatesJson = json.encodeToString(candidates),
                    searchesLeftBeforeSearch = searchesLeftBeforeSearch,
                    cachedAtEpochMillis = cachedAtEpochMillis
                )
            )
            cacheDao.deleteOlderThan(minimumFreshTimestamp)
        }
    }

    private suspend fun updateCachedCandidates(
        cached: WeekendSearchCacheEntity,
        candidates: List<WeekendCandidate>
    ) {
        runCatching {
            cacheDao.upsert(
                cached.copy(
                    candidatesJson = json.encodeToString(candidates)
                )
            )
        }
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
                    "SerpApi non ha accettato i parametri della ricerca weekend."
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

    private data class WeekendPattern(
        val label: String,
        val outboundDate: LocalDate,
        val returnDate: LocalDate,
        val outboundTimes: String,
        val returnTimes: String,
        val returnWindowLabel: String
    )

    private sealed interface PatternVerification {
        data class Success(val result: VerifiedWeekendResult) : PatternVerification
        data object Empty : PatternVerification
        data class Error(val message: String) : PatternVerification
    }

    private sealed interface VerificationPhase {
        data class Success(val candidates: List<WeekendCandidate>) : VerificationPhase
        data class Unavailable(val message: String) : VerificationPhase
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
