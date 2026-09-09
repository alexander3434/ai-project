package com.aiturbo

import com.aiturbo.time.BuiltinTimeZoneResolver
import com.aiturbo.time.CachingTimeZoneResolver
import com.aiturbo.time.CompositeTimeZoneResolver
import com.aiturbo.time.DirectZoneResolver
import com.aiturbo.time.TimeZoneResolver
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeZoneResolverTest {

    private object FailingResolver : TimeZoneResolver {
        override suspend fun resolve(location: String): ZoneId? = null
    }

    private val chain: TimeZoneResolver = CompositeTimeZoneResolver(
        DirectZoneResolver(),
        BuiltinTimeZoneResolver(),
        CachingTimeZoneResolver(FailingResolver),
    )

    @Test
    fun `accepts an IANA time zone id directly`() = runTest {
        assertEquals(ZoneId.of("Europe/Paris"), chain.resolve("Europe/Paris"))
        assertEquals(ZoneId.of("America/New_York"), chain.resolve("America/New_York"))
    }

    @Test
    fun `rejects malformed zone ids even when they contain a slash`() = runTest {
        assertNull(chain.resolve("Bad/Zone"))
    }

    @Test
    fun `resolves well-known cities without calling the LLM`() = runTest {
        assertEquals(ZoneId.of("Europe/Moscow"), chain.resolve("Moscow"))
        assertEquals(ZoneId.of("Europe/London"), chain.resolve("london")) // case-insensitive
        assertEquals(ZoneId.of("Asia/Tokyo"), chain.resolve("  Tokyo  ")) // trimmed
    }

    @Test
    fun `unknown locations fall through to the next resolver and return null`() = runTest {
        assertNull(chain.resolve("Some Unknown Place"))
    }

    @Test
    fun `caching resolver caches results including failures`() = runTest {
        val counting = object : TimeZoneResolver {
            var calls = 0
            override suspend fun resolve(location: String): ZoneId? {
                calls++
                return if (location == "Neverland") null else ZoneId.of("Europe/Paris")
            }
        }
        val cached = CachingTimeZoneResolver(counting)

        assertEquals(ZoneId.of("Europe/Paris"), cached.resolve("Paris"))
        assertNull(cached.resolve("Neverland"))
        assertEquals(ZoneId.of("Europe/Paris"), cached.resolve("Paris"))
        assertNull(cached.resolve("Neverland"))

        assertEquals(2, counting.calls)
    }
}
