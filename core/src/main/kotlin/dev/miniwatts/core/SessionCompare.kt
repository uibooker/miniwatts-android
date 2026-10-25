package dev.miniwatts.core

/**
 * One side of a two-session comparison, in the same units the history detail already uses.
 *
 * Every value is nullable, and null means "unavailable" rather than zero: a comparison must never
 * turn a missing reading into a numeric win for one of the two sessions. This mirrors the rule the
 * rest of the app follows for missing platform readings.
 */
data class CompareSide(
    val id: Long,
    val startMs: Long,
    val endMs: Long?,
    val open: Boolean,
    /** Wall-clock duration, `endMs ?: lastWallMs` minus `startMs`. */
    val durationMs: Long,
    val startLevel: Int?,
    val endLevel: Int?,
    /** Signed level change. Null unless both endpoints were observed. */
    val levelDelta: Int?,
    val energyWh: Double,
    val averageW: Double?,
    val peakW: Double?,
    val peakDeciC: Int?,
    val coveredMs: Long,
    val uncertainEnd: Boolean,
    val signConflictObserved: Boolean,
    /** Measured adapter input energy, Wh. Read [inputWh] only when [hasInputCoverage] is true. */
    val inputWh: Double,
    val inputCoveredMs: Long,
    val cellShareOfInputPct: Double?,
) {
    /**
     * True only when the input rail was actually measured somewhere in this session.
     *
     * Without it the input columns stay unavailable even though [inputWh] holds a stored 0, which is
     * what "never measured" is encoded as in the database.
     */
    val hasInputCoverage: Boolean
        get() = inputCoveredMs > 0

    /** Measured input energy as a displayable value, or null when the rail was never read. */
    val measuredInputWh: Double?
        get() = if (hasInputCoverage) inputWh else null

    val inputAverageW: Double?
        get() = if (inputCoveredMs > 0) inputWh * 3_600_000 / inputCoveredMs else null
}

/** One comparable field, already reduced to "what can honestly be said about these two". */
data class CompareRow(
    val labelZh: String,
    val labelEn: String,
    val left: String?,
    val right: String?,
    val leftNumeric: Double? = null,
    val rightNumeric: Double? = null,
    /** Signed difference for display, only when both sides carry a numeric value. */
    val delta: Double? = null,
    val deltaUnit: String = "",
    val deltaDigits: Int = 0,
    /**
     * Pre-formatted delta, for a field whose values are already formatted strings (a duration, say).
     * When set it wins over the numeric formatting, because "-90000 s" is not how a duration reads.
     */
    val deltaText: String? = null,
    /** The field can never be measured on this build, so the page must not imply it is missing data. */
    val alwaysUnavailable: Boolean = false,
) {
    /** -1, 0 or 1 for the side with the larger numeric value; null when it cannot be decided. */
    val higher: Int?
        get() {
            val a = leftNumeric ?: return null
            val b = rightNumeric ?: return null
            return when {
                a > b -> -1
                b > a -> 1
                else -> 0
            }
        }
}

/**
 * Two sessions side by side.
 *
 * The two session windows are usually different lengths, and the app already refuses to divide one
 * window by another elsewhere. So [ratioComparable] repeats that gate: a per-wattage or percentage
 * comparison is offered only when the two covered windows are close enough for the statement to mean
 * something. The raw values are always shown; only the derived ratio is withheld.
 */
data class SessionComparison(val left: CompareSide, val right: CompareSide) {
    /** Ratio of the shorter covered window to the longer one; null when neither was covered. */
    val coverageBalance: Double?
        get() {
            val a = left.coveredMs
            val b = right.coveredMs
            if (a <= 0 || b <= 0) return null
            return minOf(a, b).toDouble() / maxOf(a, b)
        }

    /**
     * True when the two covered windows are within a factor of two, matching the session
     * cell-share gate: a ratio between two very different windows is not a ratio.
     */
    val ratioComparable: Boolean
        get() = (coverageBalance ?: 0.0) >= 0.5

    /**
     * Signed difference of net cell energy, Wh. Always available: both sides are integrals over
     * their own covered window and the sign is preserved, so this is a subtraction, not a ratio.
     */
    val energyDeltaWh: Double
        get() = left.energyWh - right.energyWh

    companion object {
        fun of(left: Session, right: Session): SessionComparison =
            SessionComparison(side(left), side(right))

        fun side(s: Session): CompareSide =
            CompareSide(
                id = s.id,
                startMs = s.startMs,
                endMs = s.endMs,
                open = s.endMs == null,
                durationMs = ((s.endMs ?: s.lastWallMs) - s.startMs).coerceAtLeast(0),
                startLevel = s.startLevel,
                endLevel = s.endLevel,
                levelDelta = levelDelta(s),
                energyWh = s.energyWh,
                averageW = s.averageW,
                peakW = s.peakW,
                peakDeciC = s.peakDeciC,
                coveredMs = s.coveredMs,
                uncertainEnd = s.uncertainEnd,
                signConflictObserved = s.signConflictObserved,
                inputWh = s.inputWh,
                inputCoveredMs = s.inputCoveredMs,
                cellShareOfInputPct = s.cellShareOfInputPct,
            )

        private fun levelDelta(s: Session): Int? {
            val start = s.startLevel ?: return null
            val end = s.endLevel ?: return null
            return end - start
        }
    }
}
