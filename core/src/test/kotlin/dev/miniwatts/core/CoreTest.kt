package dev.miniwatts.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun s(t: Long = 0, ua: Int? = 1_000_000, plugged: Int = 1, boot: Long = 1) =
        Sample(
            100_000 + t,
            t,
            boot,
            50,
            plugged,
            2,
            2,
            ua,
            null,
            4000,
            300,
            null,
            null,
            null,
            null,
            1000,
        )

    private fun session() = Session(1, 100000, null, 50, 50, 1, 0, 100000)

    @Test
    fun unitsAndSentinels() {
        assertEquals(4.0, Units.power(1_000_000, 4000)!!, 0.000001)
        assertEquals(-2.0, Units.power(-500_000, 4000)!!, 0.000001)
        assertNull(Units.property(Int.MIN_VALUE))
        assertNull(Units.property(Long.MIN_VALUE))
        assertNull(Units.power(null, 4000))
        assertNull(Units.power(10, 0))
        assertEquals(0.0, Units.power(0, 4000)!!, 0.0)
    }

    @Test
    fun trapezoidAndSignChange() {
        assertEquals(4.0 / 3600, Integration.between(s(), s(1000))!!.wh, 1e-10)
        assertEquals(0.0, Integration.between(s(), s(1000, -1_000_000))!!.wh, 1e-10)
        assertTrue(s(ua = -1).signConflict)
    }

    @Test
    fun gapsNeverIntegrate() {
        assertNull(Integration.between(s(), s(0)))
        assertNull(Integration.between(s(), s(-1)))
        assertNull(Integration.between(s(), s(3001)))
        assertNull(Integration.between(s(), s(1000, null)))
        assertNull(Integration.between(s(), s(1000, boot = 2)))
        assertNull(Integration.between(s(), s(120_000)))
    }

    @Test
    fun plugUnplugAndRecovery() {
        assertEquals(
            Transition(false, false, false),
            Sessions.transition(null, s().copy(plugged = null)),
        )
        assertEquals(
            Transition(false, false, false),
            Sessions.transition(session(), s(1000).copy(plugged = null)),
        )
        assertTrue(Sessions.transition(null, s()).open)
        assertFalse(Sessions.transition(null, s(plugged = 0)).open)
        assertEquals(Transition(false, false, false), Sessions.transition(session(), s(1000)))
        assertEquals(
            Transition(true, false, false),
            Sessions.transition(session(), s(1000, plugged = 0)),
        )
        assertEquals(Transition(false, false, false), Sessions.transition(session(), s(5000)))
        assertEquals(Transition(false, false, false), Sessions.transition(session(), s(3_600_000)))
        assertEquals(
            Transition(true, false, true),
            Sessions.transition(session(), s(3_600_000, plugged = 0)),
        )
        assertEquals(
            Transition(true, true, true),
            Sessions.transition(session(), s(1000, boot = 2)),
        )
    }

    @Test
    fun measuredAverageUsesCoverage() {
        assertNull(session().averageW)
        assertEquals(2.0, session().copy(energyWh = 0.5, coveredMs = 900000).averageW!!, 1e-10)
    }

    @Test
    fun intervalBoundariesAndClockReset() {
        assertNull(Integration.between(s(), s(1000).copy(continuity = 1)))
        assertNotNull(Integration.between(s(), s(3000)))
        assertNotNull(
            Integration.between(s().copy(intervalMs = 30_000), s(60_000).copy(intervalMs = 30_000))
        )
        assertNull(
            Integration.between(s().copy(intervalMs = 30_000), s(60_001).copy(intervalMs = 30_000))
        )
        assertEquals(4.0 / 3600, Integration.between(s(), s(1000).copy(wallMs = 0))!!.wh, 1e-10)
        assertEquals(
            Transition(true, true, true),
            Sessions.transition(session().copy(lastMonoMs = 2000), s(1000)),
        )
        assertEquals(Transition(false, false, false), Sessions.transition(session(), s()))
    }
}
