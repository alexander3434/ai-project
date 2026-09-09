package com.aiturbo.time

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Resolves a free-form location string ("Moscow", "London", "Europe/Paris")
 * into a java.time [ZoneId], or null if it cannot be determined.
 */
interface TimeZoneResolver {
    suspend fun resolve(location: String): ZoneId?
}

/** Tries every resolver in order and returns the first non-null result. */
class CompositeTimeZoneResolver(private val resolvers: List<TimeZoneResolver>) : TimeZoneResolver {
    constructor(vararg resolvers: TimeZoneResolver) : this(resolvers.toList())

    override suspend fun resolve(location: String): ZoneId? {
        for (resolver in resolvers) {
            val zone = resolver.resolve(location)
            if (zone != null) return zone
        }
        return null
    }
}

/** Accepts IANA time zone ids passed directly, e.g. "Europe/Paris". */
class DirectZoneResolver : TimeZoneResolver {
    override suspend fun resolve(location: String): ZoneId? {
        if (!location.contains("/")) return null
        return runCatching { ZoneId.of(location) }.getOrNull()
    }
}

/**
 * Offline fallback for well-known cities — no HTTP call needed.
 * Cities not in the map fall through to the LLM resolver.
 */
class BuiltinTimeZoneResolver(
    private val cityToZone: Map<String, String> = KNOWN_CITY_TIME_ZONES,
) : TimeZoneResolver {

    override suspend fun resolve(location: String): ZoneId? {
        val zoneId = cityToZone[location.lowercase().trim()] ?: return null
        return runCatching { ZoneId.of(zoneId) }.getOrNull()
    }

    companion object {
        private val KNOWN_CITY_TIME_ZONES: Map<String, String> = mapOf(
            // Europe
            "moscow" to "Europe/Moscow",
            "saint petersburg" to "Europe/Moscow",
            "st petersburg" to "Europe/Moscow",
            "sochi" to "Europe/Moscow",
            "kazan" to "Europe/Moscow",
            "nizhny novgorod" to "Europe/Moscow",
            "samara" to "Europe/Samara",
            "kaliningrad" to "Europe/Kaliningrad",
            "yekaterinburg" to "Asia/Yekaterinburg",
            "ekaterinburg" to "Asia/Yekaterinburg",
            "ufa" to "Asia/Yekaterinburg",
            "novosibirsk" to "Asia/Novosibirsk",
            "omsk" to "Asia/Omsk",
            "krasnoyarsk" to "Asia/Krasnoyarsk",
            "irkutsk" to "Asia/Irkutsk",
            "vladivostok" to "Asia/Vladivostok",
            "london" to "Europe/London",
            "edinburgh" to "Europe/London",
            "manchester" to "Europe/London",
            "birmingham" to "Europe/London",
            "dublin" to "Europe/Dublin",
            "paris" to "Europe/Paris",
            "marseille" to "Europe/Paris",
            "lyon" to "Europe/Paris",
            "berlin" to "Europe/Berlin",
            "munich" to "Europe/Berlin",
            "hamburg" to "Europe/Berlin",
            "madrid" to "Europe/Madrid",
            "barcelona" to "Europe/Madrid",
            "rome" to "Europe/Rome",
            "milan" to "Europe/Rome",
            "amsterdam" to "Europe/Amsterdam",
            "brussels" to "Europe/Brussels",
            "vienna" to "Europe/Vienna",
            "warsaw" to "Europe/Warsaw",
            "prague" to "Europe/Prague",
            "stockholm" to "Europe/Stockholm",
            "oslo" to "Europe/Oslo",
            "helsinki" to "Europe/Helsinki",
            "copenhagen" to "Europe/Copenhagen",
            "zurich" to "Europe/Zurich",
            "geneva" to "Europe/Zurich",
            "lisbon" to "Europe/Lisbon",
            "athens" to "Europe/Athens",
            "istanbul" to "Europe/Istanbul",
            "kyiv" to "Europe/Kyiv",
            "kiev" to "Europe/Kyiv",
            "minsk" to "Europe/Minsk",
            "riga" to "Europe/Riga",
            "vilnius" to "Europe/Vilnius",
            "tallinn" to "Europe/Tallinn",
            "chisinau" to "Europe/Chisinau",
            "bucharest" to "Europe/Bucharest",
            "sofia" to "Europe/Sofia",
            "belgrade" to "Europe/Belgrade",
            "zagreb" to "Europe/Zagreb",
            "budapest" to "Europe/Budapest",
            "reykjavik" to "Atlantic/Reykjavik",
            // North America
            "new york" to "America/New_York",
            "washington" to "America/New_York",
            "boston" to "America/New_York",
            "miami" to "America/New_York",
            "toronto" to "America/Toronto",
            "ottawa" to "America/Toronto",
            "montreal" to "America/Toronto",
            "chicago" to "America/Chicago",
            "houston" to "America/Chicago",
            "dallas" to "America/Chicago",
            "denver" to "America/Denver",
            "phoenix" to "America/Phoenix",
            "los angeles" to "America/Los_Angeles",
            "san francisco" to "America/Los_Angeles",
            "seattle" to "America/Los_Angeles",
            "las vegas" to "America/Los_Angeles",
            "vancouver" to "America/Vancouver",
            "anchorage" to "America/Anchorage",
            "honolulu" to "Pacific/Honolulu",
            "mexico city" to "America/Mexico_City",
            "havana" to "America/Havana",
            // South America
            "sao paulo" to "America/Sao_Paulo",
            "rio de janeiro" to "America/Sao_Paulo",
            "buenos aires" to "America/Argentina/Buenos_Aires",
            "santiago" to "America/Santiago",
            "lima" to "America/Lima",
            "bogota" to "America/Bogota",
            "caracas" to "America/Caracas",
            "quito" to "America/Guayaquil",
            "la paz" to "America/La_Paz",
            "montevideo" to "America/Montevideo",
            "asuncion" to "America/Asuncion",
            // Asia
            "tokyo" to "Asia/Tokyo",
            "osaka" to "Asia/Tokyo",
            "kyoto" to "Asia/Tokyo",
            "sapporo" to "Asia/Tokyo",
            "seoul" to "Asia/Seoul",
            "busan" to "Asia/Seoul",
            "pyongyang" to "Asia/Pyongyang",
            "beijing" to "Asia/Shanghai",
            "shanghai" to "Asia/Shanghai",
            "guangzhou" to "Asia/Shanghai",
            "shenzhen" to "Asia/Shanghai",
            "chengdu" to "Asia/Shanghai",
            "hong kong" to "Asia/Hong_Kong",
            "taipei" to "Asia/Taipei",
            "singapore" to "Asia/Singapore",
            "kuala lumpur" to "Asia/Kuala_Lumpur",
            "bangkok" to "Asia/Bangkok",
            "jakarta" to "Asia/Jakarta",
            "bali" to "Asia/Makassar",
            "manila" to "Asia/Manila",
            "hanoi" to "Asia/Ho_Chi_Minh",
            "ho chi minh" to "Asia/Ho_Chi_Minh",
            "phnom penh" to "Asia/Phnom_Penh",
            "yangon" to "Asia/Yangon",
            "delhi" to "Asia/Kolkata",
            "new delhi" to "Asia/Kolkata",
            "mumbai" to "Asia/Kolkata",
            "colombo" to "Asia/Colombo",
            "kathmandu" to "Asia/Kathmandu",
            "dhaka" to "Asia/Dhaka",
            "karachi" to "Asia/Karachi",
            "kabul" to "Asia/Kabul",
            "tehran" to "Asia/Tehran",
            "baghdad" to "Asia/Baghdad",
            "riyadh" to "Asia/Riyadh",
            "jeddah" to "Asia/Riyadh",
            "dubai" to "Asia/Dubai",
            "abu dhabi" to "Asia/Dubai",
            "doha" to "Asia/Qatar",
            "amman" to "Asia/Amman",
            "beirut" to "Asia/Beirut",
            "damascus" to "Asia/Damascus",
            "jerusalem" to "Asia/Jerusalem",
            "tel aviv" to "Asia/Jerusalem",
            "tbilisi" to "Asia/Tbilisi",
            "yerevan" to "Asia/Yerevan",
            "baku" to "Asia/Baku",
            "almaty" to "Asia/Almaty",
            "astana" to "Asia/Almaty",
            "tashkent" to "Asia/Tashkent",
            "samarkand" to "Asia/Samarkand",
            "bishkek" to "Asia/Bishkek",
            "dushanbe" to "Asia/Dushanbe",
            "ashgabat" to "Asia/Ashgabat",
            "ulaanbaatar" to "Asia/Ulaanbaatar",
            // Africa
            "cairo" to "Africa/Cairo",
            "lagos" to "Africa/Lagos",
            "accra" to "Africa/Accra",
            "nairobi" to "Africa/Nairobi",
            "addis ababa" to "Africa/Addis_Ababa",
            "dar es salaam" to "Africa/Dar_es_Salaam",
            "johannesburg" to "Africa/Johannesburg",
            "cape town" to "Africa/Johannesburg",
            "kinshasa" to "Africa/Kinshasa",
            "dakar" to "Africa/Dakar",
            "tunis" to "Africa/Tunis",
            "algiers" to "Africa/Algiers",
            "casablanca" to "Africa/Casablanca",
            "rabat" to "Africa/Casablanca",
            // Oceania
            "sydney" to "Australia/Sydney",
            "canberra" to "Australia/Sydney",
            "melbourne" to "Australia/Melbourne",
            "brisbane" to "Australia/Brisbane",
            "gold coast" to "Australia/Brisbane",
            "adelaide" to "Australia/Adelaide",
            "perth" to "Australia/Perth",
            "darwin" to "Australia/Darwin",
            "hobart" to "Australia/Hobart",
            "auckland" to "Pacific/Auckland",
            "wellington" to "Pacific/Auckland",
        )
    }
}

/** Wraps another resolver and caches its results (including failures) in memory. */
class CachingTimeZoneResolver(private val delegate: TimeZoneResolver) : TimeZoneResolver {
    private val cache = HashMap<String, ZoneId?>()
    private val mutex = Mutex()

    override suspend fun resolve(location: String): ZoneId? {
        val key = location.lowercase().trim()
        mutex.withLock {
            if (cache.containsKey(key)) return cache[key]
        }
        val zone = delegate.resolve(location)
        mutex.withLock {
            cache[key] = zone
        }
        return zone
    }
}
