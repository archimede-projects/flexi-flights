package com.archimedeprojects.volaflex.data.local

data class CountryArea(
    val name: String,
    val iso2: String,
    val kgmid: String
)

/**
 * Local zero-network mapping used by Travel Explore arrival_area_id.
 * KGMIDs are country Freebase IDs (the format documented by SerpApi).
 * Keep this catalog deliberately curated: an unverified ID is worse than a
 * temporarily missing country because it could waste shared API quota.
 */
object CountryAreaCatalog {
    val all: List<CountryArea> = listOf(
        CountryArea("Algeria", "DZ", "/m/0h3y"),
        CountryArea("Austria", "AT", "/m/0h7x"),
        CountryArea("Belgio", "BE", "/m/0154j"),
        CountryArea("Brasile", "BR", "/m/015fr"),
        CountryArea("Bulgaria", "BG", "/m/015qh"),
        CountryArea("Canada", "CA", "/m/0d060g"),
        CountryArea("Croazia", "HR", "/m/01pj7"),
        CountryArea("Danimarca", "DK", "/m/0k6nt"),
        CountryArea("Egitto", "EG", "/m/02k54"),
        CountryArea("Finlandia", "FI", "/m/02vzc"),
        CountryArea("Francia", "FR", "/m/0f8l9c"),
        CountryArea("Germania", "DE", "/m/0345h"),
        CountryArea("Giappone", "JP", "/m/03_3d"),
        CountryArea("Grecia", "GR", "/m/035qy"),
        CountryArea("Irlanda", "IE", "/m/012wgb"),
        CountryArea("Italia", "IT", "/m/03rjj"),
        CountryArea("Malta", "MT", "/m/04v3q"),
        CountryArea("Marocco", "MA", "/m/04wgh"),
        CountryArea("Messico", "MX", "/m/0b90_r"),
        CountryArea("Paesi Bassi", "NL", "/m/059j2"),
        CountryArea("Polonia", "PL", "/m/05qhw"),
        CountryArea("Portogallo", "PT", "/m/05r4w"),
        CountryArea("Regno Unito", "GB", "/m/07ssc"),
        CountryArea("Repubblica Ceca", "CZ", "/m/01mjq"),
        CountryArea("Romania", "RO", "/m/06c1y"),
        CountryArea("Spagna", "ES", "/m/06mkj"),
        CountryArea("Stati Uniti", "US", "/m/09c7w0"),
        CountryArea("Svezia", "SE", "/m/0d0vqn"),
        CountryArea("Svizzera", "CH", "/m/06mzp"),
        CountryArea("Thailandia", "TH", "/m/07f1x"),
        CountryArea("Tunisia", "TN", "/m/07fj_"),
        CountryArea("Turchia", "TR", "/m/01znc_"),
        CountryArea("Ungheria", "HU", "/m/03gj2")
    ).sortedBy { it.name }

    fun byIso2(iso2: String): CountryArea? = all.firstOrNull {
        it.iso2.equals(iso2.trim(), ignoreCase = true)
    }
}
