package com.archimedeprojects.volaflex.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchApiCalendarResponseDto(
    val calendar: List<SearchApiCalendarEntryDto> = emptyList(),
    val error: String? = null
)

@Serializable
data class SearchApiCalendarEntryDto(
    val departure: String? = null,
    @SerialName("return")
    val returnDate: String? = null,
    val price: Int? = null,
    @SerialName("has_no_flights")
    val hasNoFlights: Boolean? = null,
    @SerialName("is_lowest_price")
    val isLowestPrice: Boolean? = null
)
