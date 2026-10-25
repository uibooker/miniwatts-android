package dev.miniwatts.core

/**
 * A coarse full-charge-capacity estimate derived from the charge counter and the state of charge.
 *
 * This is the AccuBattery-style method, and it is labelled an estimate everywhere it is shown: it
 * assumes both the charge counter and the fuel gauge are linear in the state of charge, which holds
 * in the constant-current region and degrades near full charge (the CV phase adds charge without
 * adding percentage). It is the only route to a capacity number on a device where the privileged
 * channel is unavailable, and it must never be presented as a measurement.
 */
data class CapacityEstimate(
    /** Charge gained divided by the state-of-charge gained, µAh. */
    val fullUah: Long,
    /** State-of-charge span the estimate was derived from, percentage points. */
    val levelDelta: Int,
    /** Charge-counter span the estimate was derived from, µAh. */
    val chargeDeltaUah: Long,
    val sessionId: Long? = null,
)

object Capacity {
    /**
     * Below this state-of-charge change the ratio is dominated by gauge resolution and the estimate
     * becomes meaningless, so the sample is rejected rather than shown with a wide error bar.
     */
    const val MIN_LEVEL_DELTA = 20

    private const val PLAUSIBLE_LOW = 0.5
    private const val PLAUSIBLE_HIGH = 1.5
    private const val ABSOLUTE_LOW_UAH = 500_000L
    private const val ABSOLUTE_HIGH_UAH = 20_000_000L

    /**
     * Uses the widest usable span in [samples], which must be in time order: the first and last
     * sample carrying both a level and a charge counter.
     */
    fun estimate(samples: List<Sample>, sessionId: Long? = null): CapacityEstimate? {
        val usable = samples.filter { it.level != null && it.chargeUah != null }
        if (usable.size < 2) return null
        val first = usable.first()
        val last = usable.last()
        return estimate(
            startLevel = first.level!!,
            startUah = first.chargeUah!!.toLong(),
            endLevel = last.level!!,
            endUah = last.chargeUah!!.toLong(),
            sessionId = sessionId,
        )
    }

    /**
     * The arithmetic is the same whether the endpoints came from in-memory samples or from a pair of
     * cheap database queries, so both callers share this one implementation.
     */
    fun estimate(
        startLevel: Int,
        startUah: Long,
        endLevel: Int,
        endUah: Long,
        sessionId: Long? = null,
    ): CapacityEstimate? {
        val levelDelta = endLevel - startLevel
        if (levelDelta < MIN_LEVEL_DELTA) return null
        val chargeDelta = endUah - startUah
        if (chargeDelta <= 0) return null
        return CapacityEstimate(
            fullUah = chargeDelta * 100 / levelDelta,
            levelDelta = levelDelta,
            chargeDeltaUah = chargeDelta,
            sessionId = sessionId,
        )
    }

    /**
     * A known design capacity makes the sanity check strict. Without one, only a band wide enough to
     * admit every phone battery is defensible, because rejecting an honest estimate is worse than
     * showing a rough one.
     */
    fun plausible(fullUah: Long, designUah: Long?): Boolean =
        if (designUah != null && designUah > 0)
            fullUah >= (designUah * PLAUSIBLE_LOW).toLong() &&
                fullUah <= (designUah * PLAUSIBLE_HIGH).toLong()
        else fullUah in ABSOLUTE_LOW_UAH..ABSOLUTE_HIGH_UAH

    /** Median of the accepted estimates, which resists a single bad charging session. */
    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
