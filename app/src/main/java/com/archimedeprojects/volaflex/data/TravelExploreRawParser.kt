package com.archimedeprojects.volaflex.data

import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ParsedExploreDestination(
    val city: String,
    val country: String,
    val airportIata: String,
    val airportName: String,
    val outboundDate: String,
    val returnDate: String,
    val price: Int,
    val currency: String
)

sealed interface TravelExploreRawParseOutcome {
    data class Success(val candidates: List<ParsedExploreDestination>) : TravelExploreRawParseOutcome
    data object NoOpportunities : TravelExploreRawParseOutcome
    data class Error(
        val diagnosticOutcome: String,
        val message: String,
        val suggestSettings: Boolean = false
    ) : TravelExploreRawParseOutcome
}

/**
 * Shared defensive parser for Travel Explore discovery responses.
 *
 * v3.4 Anywhere and v3.5 Country both use this exact parser so provider anomalies
 * have identical semantics and diagnostics. In particular, a present-but-empty
 * `destinations` array is treated as SURPRISING_EMPTY rather than silently as
 * "no flights", because Explore had recent empty-response regressions in 2026.
 */
object TravelExploreRawParser {

    fun parse(
        body: JsonObject?,
        scopeLabel: String
    ): TravelExploreRawParseOutcome {
        if (body == null) {
            return TravelExploreRawParseOutcome.Error(
                diagnosticOutcome = "BODY_MISSING",
                message = "Travel Explore ha restituito un body mancante per $scopeLabel. Non viene interpretato come assenza di voli."
            )
        }

        val providerError = body.stringOrNull("error")
        if (!providerError.isNullOrBlank()) {
            return TravelExploreRawParseOutcome.Error(
                diagnosticOutcome = "PROVIDER_ERROR",
                message = readableProviderError(providerError),
                suggestSettings = looksLikeApiKeyError(providerError)
            )
        }

        val destinationsElement = body["destinations"]
            ?: return TravelExploreRawParseOutcome.Error(
                diagnosticOutcome = "STRUCTURE_ANOMALY",
                message = "Travel Explore non ha incluso il campo 'destinations' per $scopeLabel. Struttura anomala: nessuna conclusione sui voli."
            )

        val destinations = destinationsElement as? JsonArray
            ?: return TravelExploreRawParseOutcome.Error(
                diagnosticOutcome = "STRUCTURE_ANOMALY",
                message = "Il campo 'destinations' di Travel Explore non è una lista per $scopeLabel. Struttura JSON anomala."
            )

        if (destinations.isEmpty()) {
            return TravelExploreRawParseOutcome.Error(
                diagnosticOutcome = "SURPRISING_EMPTY",
                message = "Travel Explore ha restituito una lista 'destinations' presente ma vuota per $scopeLabel. Vista la storia recente del provider, il caso è trattato come risposta sorprendentemente vuota e non come prova di assenza voli."
            )
        }

        val currency = (body["search_parameters"] as? JsonObject)
            ?.stringOrNull("currency")
            ?: "EUR"
        var malformedOfferEntries = 0

        val candidates = destinations.mapNotNull { element ->
            val destination = element as? JsonObject
            if (destination == null) {
                malformedOfferEntries += 1
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
                if (hasOfferSignal) malformedOfferEntries += 1
                return@mapNotNull null
            }

            val resolvedIata = requireNotNull(airportIata)
            ParsedExploreDestination(
                city = city,
                country = country,
                airportIata = resolvedIata,
                airportName = airportName ?: resolvedIata,
                outboundDate = requireNotNull(outboundDate),
                returnDate = requireNotNull(returnDate),
                price = price,
                currency = currency
            )
        }

        if (candidates.isEmpty()) {
            return if (malformedOfferEntries > 0) {
                TravelExploreRawParseOutcome.Error(
                    diagnosticOutcome = "STRUCTURE_ANOMALY",
                    message = "Travel Explore ha restituito destinazioni con segnali di offerta ma campi essenziali mancanti/non validi per $scopeLabel. Nessun candidato viene mostrato."
                )
            } else {
                TravelExploreRawParseOutcome.NoOpportunities
            }
        }

        return TravelExploreRawParseOutcome.Success(candidates.sortedBy { it.price })
    }

    private fun readableProviderError(rawError: String): String {
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
}
