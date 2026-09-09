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
data class TravelExploreResponseDto(
    @SerialName("start_date")
    val startDate: String? = null,
    @SerialName("end_date")
    val endDate: String? = null,
    val flights: List<TravelExploreFlightDto> = emptyList(),
    val destinations: List<TravelExploreDestinationDto> = emptyList(),
    @SerialName("search_parameters")
    val searchParameters: SearchParametersDto? = null,
    val error: String? = null
)

@Serializable
data class TravelExploreFlightDto(
    @SerialName("departure_airport")
    val departureAirport: ExploreAirportDto? = null,
    @SerialName("arrival_airport")
    val arrivalAirport: ExploreAirportDto? = null,
    val duration: Int? = null,
    val price: Int? = null,
    @SerialName("cheapest_flight")
    val cheapestFlight: Boolean? = null,
    @SerialName("number_of_stops")
    val numberOfStops: Int? = null,
    val airline: String? = null,
    @SerialName("airline_code")
    val airlineCode: String? = null
)

@Serializable
data class ExploreAirportDto(
    val name: String? = null,
    val id: String? = null
)

@Serializable
data class TravelExploreDestinationDto(
    @SerialName("destination_id")
    val destinationId: String? = null,
    val name: String? = null,
    val country: String? = null,
    @SerialName("destination_airport")
    val destinationAirport: TravelExploreDestinationAirportDto? = null,
    @SerialName("start_date")
    val startDate: String? = null,
    @SerialName("end_date")
    val endDate: String? = null,
    @SerialName("flight_price")
    val flightPrice: Int? = null,
    @SerialName("number_of_stops")
    val numberOfStops: Int? = null,
    val airline: String? = null,
    @SerialName("airline_code")
    val airlineCode: String? = null
)

@Serializable
data class TravelExploreDestinationAirportDto(
    val code: String? = null,
    val location: String? = null,
    val name: String? = null
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
