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

interface SerpApiService {

    @GET("account.json")
    suspend fun getAccount(
        @Query("api_key") apiKey: String
    ): Response<SerpAccountResponseDto>

    @GET("search")
    suspend fun searchGoogleFlights(
        @Query("engine") engine: String,
        @Query("departure_id") departureId: String,
        @Query("arrival_id") arrivalId: String,
        @Query("outbound_date") outboundDate: String,
        @Query("return_date") returnDate: String,
        @Query("type") type: Int,
        @Query("travel_class") travelClass: Int,
        @Query("currency") currency: String,
        @Query("hl") language: String,
        @Query("gl") country: String,
        @Query("api_key") apiKey: String
    ): Response<GoogleFlightsResponseDto>

    @GET("search")
    suspend fun searchTravelExplore(
        @Query("engine") engine: String,
        @Query("departure_id") departureId: String,
        @Query("arrival_id") arrivalId: String,
        @Query("month") month: Int,
        @Query("travel_duration") travelDuration: Int,
        @Query("travel_class") travelClass: Int,
        @Query("travel_mode") travelMode: Int,
        @Query("currency") currency: String,
        @Query("hl") language: String,
        @Query("gl") country: String,
        @Query("api_key") apiKey: String
    ): Response<TravelExploreResponseDto>
}

object SerpApiNetwork {

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

    val service: SerpApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://serpapi.com/")
            .client(okHttpClient)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(SerpApiService::class.java)
    }
}
