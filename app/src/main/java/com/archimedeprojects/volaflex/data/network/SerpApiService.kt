package com.archimedeprojects.volaflex.data.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    suspend fun searchGoogleFlightsByPrice(
        @Query("engine") engine: String,
        @Query("departure_id") departureId: String,
        @Query("arrival_id") arrivalId: String,
        @Query("outbound_date") outboundDate: String,
        @Query("return_date") returnDate: String,
        @Query("type") type: Int,
        @Query("travel_class") travelClass: Int,
        @Query("sort_by") sortBy: Int,
        @Query("currency") currency: String,
        @Query("hl") language: String,
        @Query("gl") country: String,
        @Query("api_key") apiKey: String
    ): Response<GoogleFlightsResponseDto>

    /**
     * SerpApi expects outbound_times / return_times as 2 or 4 comma-separated
     * integer hours in the 0..23 range, e.g. "17,23" or "5,11".
     * SerpApiTimeFilterGuard validates this again immediately before network I/O.
     */
    @GET("search")
    suspend fun searchGoogleFlightsWeekendVerification(
        @Query("engine") engine: String,
        @Query("departure_id") departureId: String,
        @Query("arrival_id") arrivalId: String,
        @Query("outbound_date") outboundDate: String,
        @Query("return_date") returnDate: String,
        @Query("outbound_times") outboundTimes: String,
        @Query("return_times") returnTimes: String,
        @Query("type") type: Int,
        @Query("travel_class") travelClass: Int,
        @Query("sort_by") sortBy: Int,
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

    /**
     * v3.4 Anywhere discovery deliberately omits both arrival_id and arrival_area_id.
     * JsonObject is used here so the repository can distinguish a missing
     * `destinations` field from a present-but-empty list for robust diagnostics.
     */
    @GET("search")
    suspend fun searchTravelExploreAnywhere(
        @Query("engine") engine: String,
        @Query("departure_id") departureId: String,
        @Query("month") month: Int,
        @Query("travel_duration") travelDuration: Int,
        @Query("travel_class") travelClass: Int,
        @Query("travel_mode") travelMode: Int,
        @Query("currency") currency: String,
        @Query("hl") language: String,
        @Query("gl") country: String,
        @Query("api_key") apiKey: String
    ): Response<JsonObject>
}

object SerpApiNetwork {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(SerpApiTimeFilterGuard())
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
