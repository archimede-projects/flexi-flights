package com.archimedeprojects.volaflex.data

import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheDao
import com.archimedeprojects.volaflex.data.local.WeekendSearchCacheEntity
import com.archimedeprojects.volaflex.data.network.FlightOptionDto
import com.archimedeprojects.volaflex.data.network.SerpApiService
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

private const val VERIFICATION_CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L
private const val VERIFICATION_QUOTA_RESERVE = 5
private const val FRIDAY_OUTBOUND_TIMES = "17,23"
private const val SATURDAY_OUTBOUND_TIMES = "5,11"
private const val SUNDAY_RETURN_TIMES = "17,23"
private const val MONDAY_RETURN_TIMES = "0,23"
private const val VERIFY_SUCCESS = "SUCCESS"
private const val VERIFY_NO_MATCH = "NO_MATCH"

data class WeekendVerificationSeed(
    val airportIata: String,
    val outboundDate: String,
    val returnDate: String,
    val monthKey: String
)

sealed interface WeekendVerificationOutcome {
    data class Success(
        val result: VerifiedWeekendResult,
        val fromCache: Boolean = false
    ) : WeekendVerificationOutcome

    data class Unavailable(
        val message: String,
        val fromCache: Boolean = false
    ) : WeekendVerificationOutcome
}

@Serializable
private data class WeekendVerificationCachePayload(
    val candidateIata: String,
    val outcome: String,
    val result: VerifiedWeekendResult? = null,
    val message: String? = null
)

private data class WeekendVerificationPattern(
    val label: String,
    val outboundDate: LocalDate,
    val returnDate: LocalDate,
    val outboundTimes: String,
    val returnTimes: String,
    val returnWindowLabel: String
)

private sealed interface PatternVerification {
    data class Success(val result: VerifiedWeekendResult) : PatternVerification
    data object NoOpportunities : PatternVerification
    data class TransientError(val message: String) : PatternVerification
}

private sealed interface VerificationQuotaResult {
    data class Available(val searchesLeft: Int) : VerificationQuotaResult
    data class Error(val message: String) : VerificationQuotaResult
}

class WeekendVerificationEngine(
    private val service: SerpApiService,
    private val cacheDao: WeekendSearchCacheDao,
    private val diagnostics: DiagnosticRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun hasValidPattern(seed: WeekendVerificationSeed): Boolean = buildVerificationPatterns(seed).isNotEmpty()

    suspend fun verify(
        apiKey: String,
        departureId: String,
        destinationScope: String,
        periodKey: String,
        seed: WeekendVerificationSeed,
        forceRefresh: Boolean = false
    ): WeekendVerificationOutcome {
        val departure = departureId
            .split(',')
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(",")
        val scope = destinationScope.trim().uppercase(Locale.ROOT)
        val normalizedSeed = seed.copy(airportIata = seed.airportIata.trim().uppercase(Locale.ROOT))
        val now = System.currentTimeMillis()
        val minimumFreshTimestamp = now - VERIFICATION_CACHE_TTL_MILLIS

        if (!forceRefresh) {
            loadCachedVerification(
                departureId = departure,
                destinationScope = scope,
                periodKey = periodKey,
                seed = normalizedSeed,
                minimumFreshTimestamp = minimumFreshTimestamp
            )?.let { return it }
        }

        val patterns = buildVerificationPatterns(normalizedSeed)
        if (patterns.isEmpty()) {
            val message = "Il candidato ${normalizedSeed.airportIata} non contiene un intervallo Explore da cui ricavare un weekend venerdì/sabato → domenica/lunedì valido nello stesso mese."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "NO_OPPORTUNITIES",
                message = "$scope $departure→${normalizedSeed.airportIata}: $message"
            )
            cacheTerminalNoMatch(
                departureId = departure,
                destinationScope = scope,
                periodKey = periodKey,
                seed = normalizedSeed,
                message = message,
                cachedAtEpochMillis = now,
                minimumFreshTimestamp = minimumFreshTimestamp
            )
            return WeekendVerificationOutcome.Unavailable(message)
        }

        val searchesLeft = when (val quota = readLiveQuota(apiKey)) {
            is VerificationQuotaResult.Available -> quota.searchesLeft
            is VerificationQuotaResult.Error -> return WeekendVerificationOutcome.Unavailable(quota.message)
        }

        val minimumRequired = VERIFICATION_QUOTA_RESERVE + patterns.size
        if (searchesLeft < minimumRequired) {
            val message = "Verifica weekend bloccata: servono almeno $minimumRequired query residue per controllare ${patterns.size} combinazioni e conservare la riserva di $VERIFICATION_QUOTA_RESERVE. Quota attuale: $searchesLeft. La Discovery Explore resta disponibile come indicativa."
            diagnostics.log(
                requestType = "QUOTA_GUARD",
                outcome = "BLOCKED",
                message = "$scope $departure→${normalizedSeed.airportIata}: $message"
            )
            return WeekendVerificationOutcome.Unavailable(message)
        }

        val verifiedOptions = mutableListOf<VerifiedWeekendResult>()
        for (pattern in patterns) {
            when (
                val outcome = verifyPattern(
                    apiKey = apiKey,
                    departureId = departure,
                    arrivalId = normalizedSeed.airportIata,
                    destinationScope = scope,
                    pattern = pattern,
                    searchesLeftBeforeVerification = searchesLeft
                )
            ) {
                is PatternVerification.Success -> verifiedOptions += outcome.result
                PatternVerification.NoOpportunities -> Unit
                is PatternVerification.TransientError -> {
                    return WeekendVerificationOutcome.Unavailable(outcome.message)
                }
            }
        }

        val cheapestVerified = verifiedOptions.minByOrNull { it.price }
        if (cheapestVerified == null) {
            val message = "Nessun volo compatibile con le fasce weekend richieste è stato trovato per il candidato ${normalizedSeed.airportIata}. Non vengono verificati automaticamente altri candidati."
            cacheTerminalNoMatch(
                departureId = departure,
                destinationScope = scope,
                periodKey = periodKey,
                seed = normalizedSeed,
                message = message,
                cachedAtEpochMillis = now,
                minimumFreshTimestamp = minimumFreshTimestamp
            )
            return WeekendVerificationOutcome.Unavailable(message)
        }

        cacheSuccess(
            departureId = departure,
            destinationScope = scope,
            periodKey = periodKey,
            seed = normalizedSeed,
            result = cheapestVerified,
            cachedAtEpochMillis = now,
            minimumFreshTimestamp = minimumFreshTimestamp
        )
        return WeekendVerificationOutcome.Success(cheapestVerified)
    }

    private suspend fun loadCachedVerification(
        departureId: String,
        destinationScope: String,
        periodKey: String,
        seed: WeekendVerificationSeed,
        minimumFreshTimestamp: Long
    ): WeekendVerificationOutcome? {
        val successKey = verificationCacheKey(
            departureId,
            destinationScope,
            periodKey,
            seed,
            VERIFY_SUCCESS
        )
        val noMatchKey = verificationCacheKey(
            departureId,
            destinationScope,
            periodKey,
            seed,
            VERIFY_NO_MATCH
        )

        val success = runCatching { cacheDao.findFresh(successKey, minimumFreshTimestamp) }.getOrNull()
        if (success != null) {
            val payload = runCatching {
                json.decodeFromString<WeekendVerificationCachePayload>(success.candidatesJson)
            }.getOrNull()
            val result = payload?.result
            if (payload?.outcome == VERIFY_SUCCESS && result != null) {
                diagnostics.log(
                    requestType = "CACHE",
                    outcome = "HIT",
                    message = "Weekend Verify $destinationScope $departureId→${seed.airportIata} period=$periodKey outcome=$VERIFY_SUCCESS"
                )
                return WeekendVerificationOutcome.Success(result, fromCache = true)
            }
        }

        val noMatch = runCatching { cacheDao.findFresh(noMatchKey, minimumFreshTimestamp) }.getOrNull()
        if (noMatch != null) {
            val payload = runCatching {
                json.decodeFromString<WeekendVerificationCachePayload>(noMatch.candidatesJson)
            }.getOrNull()
            if (payload?.outcome == VERIFY_NO_MATCH && !payload.message.isNullOrBlank()) {
                diagnostics.log(
                    requestType = "CACHE",
                    outcome = "HIT",
                    message = "Weekend Verify $destinationScope $departureId→${seed.airportIata} period=$periodKey outcome=$VERIFY_NO_MATCH"
                )
                return WeekendVerificationOutcome.Unavailable(payload.message, fromCache = true)
            }
        }

        return null
    }

    private suspend fun verifyPattern(
        apiKey: String,
        departureId: String,
        arrivalId: String,
        destinationScope: String,
        pattern: WeekendVerificationPattern,
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
                val message = verificationHttpMessage(response.code())
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "HTTP_ERROR",
                    httpStatus = response.code(),
                    message = "$destinationScope $departureId→$arrivalId ${pattern.label}: $message"
                )
                return PatternVerification.TransientError(message)
            }

            val body = response.body()
            if (body == null) {
                val message = "Google Flights ha restituito una risposta senza body durante la verifica weekend."
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "BODY_MISSING",
                    httpStatus = response.code(),
                    message = "$destinationScope $departureId→$arrivalId ${pattern.label}: $message"
                )
                return PatternVerification.TransientError(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = readableSerpApiError(body.error)
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "PROVIDER_ERROR",
                    httpStatus = response.code(),
                    message = "$destinationScope $departureId→$arrivalId ${pattern.label}: $message"
                )
                return PatternVerification.TransientError(message)
            }

            val returnedOptions = body.bestFlights + body.otherFlights
            if (returnedOptions.isEmpty()) {
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "NO_OPPORTUNITIES",
                    httpStatus = response.code(),
                    message = "$destinationScope $departureId→$arrivalId ${pattern.label}: nessun volo compatibile"
                )
                return PatternVerification.NoOpportunities
            }

            val option = returnedOptions
                .filter { it.price != null && it.flights.isNotEmpty() }
                .minByOrNull { it.price ?: Int.MAX_VALUE }

            if (option == null) {
                val message = "Google Flights ha restituito opzioni, ma nessuna contiene contemporaneamente prezzo e segmenti di volo utilizzabili."
                diagnostics.log(
                    requestType = "WEEKEND_VERIFY",
                    outcome = "STRUCTURE_ANOMALY",
                    httpStatus = response.code(),
                    message = "$destinationScope $departureId→$arrivalId ${pattern.label}: $message"
                )
                return PatternVerification.TransientError(message)
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
                message = "$destinationScope $departureId→$arrivalId ${pattern.label}: ${verified.price} ${verified.currency}"
            )
            PatternVerification.Success(verified)
        } catch (_: IOException) {
            val message = "Errore di rete durante la verifica Google Flights del weekend."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "NETWORK_ERROR",
                message = "$destinationScope $departureId→$arrivalId ${pattern.label}: $message"
            )
            PatternVerification.TransientError(message)
        } catch (_: SerializationException) {
            val message = "Google Flights ha restituito dati in un formato non previsto durante la verifica weekend."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "STRUCTURE_ANOMALY",
                message = "$destinationScope $departureId→$arrivalId ${pattern.label}: $message"
            )
            PatternVerification.TransientError(message)
        } catch (_: Exception) {
            val message = "Errore imprevisto o struttura non interpretabile durante la verifica Google Flights del weekend."
            diagnostics.log(
                requestType = "WEEKEND_VERIFY",
                outcome = "STRUCTURE_ANOMALY",
                message = "$destinationScope $departureId→$arrivalId ${pattern.label}: $message"
            )
            PatternVerification.TransientError(message)
        }
    }

    private fun buildVerificationPatterns(seed: WeekendVerificationSeed): List<WeekendVerificationPattern> {
        val exploreStart = runCatching { LocalDate.parse(seed.outboundDate) }.getOrNull() ?: return emptyList()
        val exploreEnd = runCatching { LocalDate.parse(seed.returnDate) }.getOrNull() ?: return emptyList()
        if (exploreEnd.isBefore(exploreStart)) return emptyList()

        val targetMonth = runCatching {
            seed.monthKey.takeIf { it.isNotBlank() }?.let(YearMonth::parse)
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
                    WeekendVerificationPattern(
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
                    WeekendVerificationPattern(
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

    private fun FlightOptionDto.toVerifiedWeekendResult(
        pattern: WeekendVerificationPattern,
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

    private suspend fun readLiveQuota(apiKey: String): VerificationQuotaResult {
        return try {
            val response = service.getAccount(apiKey)
            if (!response.isSuccessful) {
                val message = verificationHttpMessage(response.code(), duringAccountCheck = true)
                diagnostics.log("SERPAPI_ACCOUNT", "ERROR", response.code(), message)
                return VerificationQuotaResult.Error(message)
            }

            val body = response.body()
            if (body == null) {
                val message = "Impossibile leggere la quota SerpApi. Verifica bloccata per sicurezza."
                diagnostics.log("SERPAPI_ACCOUNT", "ERROR", response.code(), message)
                return VerificationQuotaResult.Error(message)
            }

            if (!body.error.isNullOrBlank()) {
                val message = readableSerpApiError(body.error)
                diagnostics.log("SERPAPI_ACCOUNT", "ERROR", response.code(), message)
                return VerificationQuotaResult.Error(message)
            }

            val searchesLeft = body.totalSearchesLeft ?: body.planSearchesLeft
            if (searchesLeft == null) {
                val message = "SerpApi non ha restituito il numero di query rimaste. Verifica bloccata per sicurezza."
                diagnostics.log("SERPAPI_ACCOUNT", "ERROR", response.code(), message)
                return VerificationQuotaResult.Error(message)
            }

            diagnostics.log(
                requestType = "SERPAPI_ACCOUNT",
                outcome = "SUCCESS",
                httpStatus = response.code(),
                message = "Quota live prima di WEEKEND_VERIFY: $searchesLeft query rimaste"
            )
            VerificationQuotaResult.Available(searchesLeft)
        } catch (_: IOException) {
            val message = "Impossibile verificare la quota SerpApi per un errore di rete. Verifica bloccata per sicurezza."
            diagnostics.log("SERPAPI_ACCOUNT", "ERROR", message = message)
            VerificationQuotaResult.Error(message)
        } catch (_: SerializationException) {
            val message = "Impossibile interpretare la risposta Account API. Verifica bloccata per sicurezza."
            diagnostics.log("SERPAPI_ACCOUNT", "ERROR", message = message)
            VerificationQuotaResult.Error(message)
        } catch (_: Exception) {
            val message = "Impossibile verificare la quota SerpApi. Verifica bloccata per sicurezza."
            diagnostics.log("SERPAPI_ACCOUNT", "ERROR", message = message)
            VerificationQuotaResult.Error(message)
        }
    }

    private suspend fun cacheSuccess(
        departureId: String,
        destinationScope: String,
        periodKey: String,
        seed: WeekendVerificationSeed,
        result: VerifiedWeekendResult,
        cachedAtEpochMillis: Long,
        minimumFreshTimestamp: Long
    ) {
        val payload = WeekendVerificationCachePayload(
            candidateIata = seed.airportIata,
            outcome = VERIFY_SUCCESS,
            result = result
        )
        saveVerificationCache(
            cacheKey = verificationCacheKey(departureId, destinationScope, periodKey, seed, VERIFY_SUCCESS),
            departureId = departureId,
            arrivalId = "$destinationScope|${seed.airportIata}",
            periodKey = periodKey,
            payload = payload,
            searchesLeftBeforeSearch = result.searchesLeftBeforeVerification,
            cachedAtEpochMillis = cachedAtEpochMillis,
            minimumFreshTimestamp = minimumFreshTimestamp
        )
    }

    private suspend fun cacheTerminalNoMatch(
        departureId: String,
        destinationScope: String,
        periodKey: String,
        seed: WeekendVerificationSeed,
        message: String,
        cachedAtEpochMillis: Long,
        minimumFreshTimestamp: Long
    ) {
        val payload = WeekendVerificationCachePayload(
            candidateIata = seed.airportIata,
            outcome = VERIFY_NO_MATCH,
            message = message
        )
        saveVerificationCache(
            cacheKey = verificationCacheKey(departureId, destinationScope, periodKey, seed, VERIFY_NO_MATCH),
            departureId = departureId,
            arrivalId = "$destinationScope|${seed.airportIata}",
            periodKey = periodKey,
            payload = payload,
            searchesLeftBeforeSearch = 0,
            cachedAtEpochMillis = cachedAtEpochMillis,
            minimumFreshTimestamp = minimumFreshTimestamp
        )
    }

    private suspend fun saveVerificationCache(
        cacheKey: String,
        departureId: String,
        arrivalId: String,
        periodKey: String,
        payload: WeekendVerificationCachePayload,
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
                    candidatesJson = json.encodeToString(payload),
                    searchesLeftBeforeSearch = searchesLeftBeforeSearch,
                    cachedAtEpochMillis = cachedAtEpochMillis
                )
            )
            cacheDao.deleteOlderThan(minimumFreshTimestamp)
        }
    }

    private fun verificationCacheKey(
        departureId: String,
        destinationScope: String,
        periodKey: String,
        seed: WeekendVerificationSeed,
        outcome: String
    ): String = buildString {
        append("WEEKEND_VERIFY|")
        append(departureId)
        append('|')
        append(destinationScope)
        append('|')
        append(periodKey)
        append('|')
        append(seed.airportIata)
        append('|')
        append(seed.monthKey.ifBlank { "NO_MONTH" })
        append('|')
        append(seed.outboundDate)
        append(':')
        append(seed.returnDate)
        append('|')
        append(outcome)
    }

    private fun verificationHttpMessage(code: Int, duringAccountCheck: Boolean = false): String = when (code) {
        401, 403 -> "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni."
        429 -> "SerpApi ha bloccato la richiesta per quota o limite orario raggiunto."
        400 -> if (duringAccountCheck) {
            "SerpApi non ha accettato la chiave salvata. Controllala nelle Impostazioni."
        } else {
            "SerpApi non ha accettato i parametri della verifica weekend Google Flights."
        }
        else -> "SerpApi ha risposto con errore HTTP $code durante la verifica weekend."
    }

    private fun readableSerpApiError(rawError: String): String = if (looksLikeApiKeyError(rawError)) {
        "Chiave SerpApi non valida o non autorizzata. Controllala nelle Impostazioni."
    } else {
        "SerpApi non ha completato la verifica: ${rawError.take(180)}"
    }

    private fun looksLikeApiKeyError(message: String): Boolean {
        val normalized = message.lowercase()
        return "api key" in normalized ||
            "api_key" in normalized ||
            "unauthorized" in normalized ||
            "authentication" in normalized
    }
}
