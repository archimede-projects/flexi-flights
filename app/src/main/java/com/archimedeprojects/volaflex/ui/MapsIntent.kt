package com.archimedeprojects.volaflex.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.archimedeprojects.volaflex.data.local.AirportDirectory
import java.util.Locale

private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
private const val GOOGLE_MAPS_WEB_SEARCH = "https://www.google.com/maps/search/?api=1&query="

internal data class MapsDestination(
    val iata: String,
    val query: String
)

internal fun mapsDestinationForIata(rawIata: String): MapsDestination? {
    val iata = rawIata.trim().uppercase(Locale.ROOT)
    if (iata.isBlank()) return null

    val airport = AirportDirectory.find(iata)
    val query = if (airport != null) {
        "${airport.name}, ${airport.city} (${airport.iata})"
    } else {
        "$iata airport"
    }
    return MapsDestination(iata = iata, query = query)
}

internal fun openAirportInMaps(context: Context, rawIata: String) {
    val destination = mapsDestinationForIata(rawIata) ?: return
    val encodedQuery = Uri.encode(destination.query)
    val mapsIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:0,0?q=$encodedQuery")
    ).apply {
        setPackage(GOOGLE_MAPS_PACKAGE)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(mapsIntent)
    } catch (_: ActivityNotFoundException) {
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("$GOOGLE_MAPS_WEB_SEARCH$encodedQuery")
        ).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
    }
}

@Composable
internal fun OpenInMapsButton(
    destinationIata: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { openAirportInMaps(context, destinationIata) },
        modifier = modifier,
        enabled = destinationIata.isNotBlank()
    ) {
        Text("Apri in Maps")
    }
}
