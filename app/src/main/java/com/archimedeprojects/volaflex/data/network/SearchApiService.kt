package com.archimedeprojects.volaflex.data.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface SearchApiService {

    @GET("search")
    suspend fun searchGoogleFlightsCalendar(
        @Query("engine") engine: String,
        @Query("flight_type") flightType: String,
        @Query("departure_id") departureId: String,
        @Query("arrival_id") arrivalId: String,
        @Query("outbound_date") outboundDate: String,
        @Query("return_date") returnDate: String,
        @Query("outbound_date_start") outboundDateStart: String,
        @Query("outbound_date_end") outboundDateEnd: String,
        @Query("return_date_start") returnDateStart: String,
        @Query("return_date_end") returnDateEnd: String,
        @Query("travel_class") travelClass: String,
        @Query("currency") currency: String,
        @Query("hl") language: String,
        @Query("gl") country: String,
        @Query("api_key") apiKey: String
    ): Response<SearchApiCalendarResponseDto>
}

object SearchApiNetwork {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    val service: SearchApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://www.searchapi.io/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(SearchApiService::class.java)
    }
}
