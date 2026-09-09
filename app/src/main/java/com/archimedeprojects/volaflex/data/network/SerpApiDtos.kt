package com.archimedeprojects.volaflex.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SerpAccountResponseDto(
    @SerialName("account_status")
    val accountStatus: String? = null,
    @SerialName("plan_searches_left")
    val planSearchesLeft: Int? = null,
    @SerialName("total_searches_left")
    val totalSearchesLeft: Int? = null,
    val error: String? = null
)

@Serializable
data class GoogleFlightsResponseDto(
    @SerialName("best_flights")
    val bestFlights: List<FlightOptionDto> = emptyList(),
    @SerialName("other_flights")
    val otherFlights: List<FlightOptionDto> = emptyList(),
    @SerialName("search_parameters")
    val searchParameters: SearchParametersDto? = null,
    val error: String? = null
)

@Serializable
data class SearchParametersDto(
    val currency: String? = null
)

@Serializable
data class FlightOptionDto(
    val flights: List<FlightSegmentDto> = emptyList(),
    val layovers: List<LayoverDto> = emptyList(),
    @SerialName("total_duration")
    val totalDuration: Int? = null,
    val price: Int? = null,
    val type: String? = null,
    @SerialName("departure_token")
    val departureToken: String? = null
)

@Serializable
data class FlightSegmentDto(
    @SerialName("departure_airport")
    val departureAirport: AirportTimeDto? = null,
    @SerialName("arrival_airport")
    val arrivalAirport: AirportTimeDto? = null,
    val airline: String? = null,
    @SerialName("flight_number")
    val flightNumber: String? = null
)

@Serializable
data class AirportTimeDto(
    val name: String? = null,
    val id: String? = null,
    val time: String? = null
)

@Serializable
data class LayoverDto(
    val duration: Int? = null,
    val name: String? = null,
    val id: String? = null,
    val overnight: Boolean? = null
)
