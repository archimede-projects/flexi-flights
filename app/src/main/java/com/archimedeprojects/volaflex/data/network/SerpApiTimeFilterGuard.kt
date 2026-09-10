package com.archimedeprojects.volaflex.data.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Protects SerpApi quota from malformed Google Flights time filters.
 *
 * Official SerpApi format for outbound_times / return_times is either:
 * - two comma-separated integer hours: departure start,end
 * - four comma-separated integer hours: departure start,end,arrival start,end
 *
 * Every value must be an integer in 0..23. A malformed value is rejected
 * locally before the HTTP request can leave the device.
 */
internal class SerpApiTimeFilterGuard : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host == "serpapi.com" && url.queryParameter("engine") == "google_flights") {
            validate(url.queryParameter("outbound_times"), "outbound_times")
            validate(url.queryParameter("return_times"), "return_times")
        }

        return chain.proceed(request)
    }

    private fun validate(value: String?, parameterName: String) {
        if (value == null) return
        if (!isValidHourRange(value)) {
            throw IOException(
                "Parametro SerpApi $parameterName non valido: usa 2 o 4 ore intere 0-23 separate da virgola."
            )
        }
    }

    internal companion object {
        fun isValidHourRange(value: String): Boolean {
            val parts = value.split(',')
            if (parts.size != 2 && parts.size != 4) return false

            val hours = parts.map { token ->
                if (token.isBlank() || token.any { !it.isDigit() }) return false
                token.toIntOrNull() ?: return false
            }

            if (hours.any { it !in 0..23 }) return false
            if (hours[0] > hours[1]) return false
            if (hours.size == 4 && hours[2] > hours[3]) return false

            return true
        }
    }
}
