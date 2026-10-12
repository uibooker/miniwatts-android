package dev.miniwatts.core

/** Nullable values are unavailable, never a synthetic zero. Positive current enters the cell. */
data class Sample(
    val wallMs: Long,
    val monoMs: Long,
    val boot: Long,
    val level: Int?,
    val plugged: Int?,
    val status: Int?,
    val health: Int?,
    val currentUa: Int?,
    val averageUa: Int?,
    val voltageMv: Int?,
    val temperatureDeciC: Int?,
    val chargeUah: Int?,
    val energyNwh: Long?,
    val cycles: Int?,
    val thermal: Int?,
    val intervalMs: Long,
    val continuity: Long = 0,
) {
    val powerW: Double?
        get() = Units.power(currentUa, voltageMv)

    val signConflict: Boolean
        get() =
            currentUa != null && ((status == 2 && currentUa < 0) || (status == 3 && currentUa > 0))
}

object Units {
    fun property(value: Int): Int? = value.takeUnless { it == Int.MIN_VALUE || it == Int.MAX_VALUE }

    fun property(value: Long): Long? =
        value.takeUnless { it == Long.MIN_VALUE || it == Long.MAX_VALUE }

    fun power(currentUa: Int?, voltageMv: Int?): Double? =
        if (currentUa == null || voltageMv == null || voltageMv <= 0) null
        else currentUa.toDouble() * voltageMv / 1_000_000_000.0
}

data class Segment(val wh: Double, val coveredMs: Long)

object Integration {
    fun connected(a: Sample, b: Sample): Boolean {
        val dt = b.monoMs - a.monoMs
        return a.boot == b.boot &&
            a.continuity == b.continuity &&
            dt > 0 &&
            dt <= 3 * maxOf(a.intervalMs, b.intervalMs) &&
            dt <= 60_000
    }

    fun between(a: Sample, b: Sample): Segment? {
        val pa = a.powerW ?: return null
        val pb = b.powerW ?: return null
        if (!connected(a, b)) return null
        val dt = b.monoMs - a.monoMs
        return Segment((pa + pb) / 2 * dt / 3_600_000.0, dt)
    }
}

data class Session(
    val id: Long,
    val startMs: Long,
    val endMs: Long?,
    val startLevel: Int?,
    val endLevel: Int?,
    val boot: Long,
    val lastMonoMs: Long,
    val lastWallMs: Long,
    val energyWh: Double = 0.0,
    val coveredMs: Long = 0,
    val peakW: Double? = null,
    val peakDeciC: Int? = null,
    val uncertainEnd: Boolean = false,
) {
    val averageW: Double?
        get() = if (coveredMs > 0) energyWh * 3_600_000 / coveredMs else null
}

data class Transition(val close: Boolean, val open: Boolean, val uncertainRecovery: Boolean)

object Sessions {
    // A sampling gap does not prove an unplug. Only reboot/time reset ends a restored session.
    fun transition(active: Session?, sample: Sample): Transition {
        val stale =
            active != null && (active.boot != sample.boot || sample.monoMs < active.lastMonoMs)
        val delayedUnplug =
            active != null &&
                sample.plugged == 0 &&
                (sample.monoMs - active.lastMonoMs > 3 * sample.intervalMs ||
                    sample.monoMs - active.lastMonoMs > 60_000)
        return Transition(
            active != null && (sample.plugged == 0 || stale),
            sample.plugged != null && sample.plugged != 0 && (active == null || stale),
            stale || delayedUnplug,
        )
    }
}
