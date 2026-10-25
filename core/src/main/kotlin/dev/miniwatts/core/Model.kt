package dev.miniwatts.core

/**
 * Charger/adapter information that is not part of the cell measurements.
 *
 * Every field is nullable: missing means unavailable, never a synthetic zero. [source] records
 * where the whole record came from, because the same kind of value can be public (platform
 * broadcast), vendor (Xiaomi MIUI broadcast) or privileged (read through the optional Shizuku
 * channel), and the UI has to say which.
 */
data class Adapter(
    /**
     * `max_charging_current` from ACTION_BATTERY_CHANGED: the maximum charging current the charger
     * supports, µA. The platform reads it from the charger power supply and broadcasts it.
     */
    val maxCurrentUa: Int? = null,
    /** `max_charging_voltage` from the same broadcast: the charger's maximum charging voltage, µV. */
    val maxVoltageUv: Int? = null,
    /** `invalid_charger` (0/1): the platform marks the attached charger as non-compliant. */
    val invalid: Int? = null,
    /** Xiaomi `miui.intent.extra.quick_charge_type`. The enum is vendor-defined and unverified. */
    val quickChargeType: Int? = null,
    /** Xiaomi `miui.intent.extra.POWER_MAX`: vendor-advertised wattage tier in W (may be 0). */
    val advertisedW: Int? = null,
    /** Xiaomi `miui.intent.extra.CAR_CHARGE`. */
    val carCharge: Int? = null,
    /** Privileged: measured VBUS at the phone's port, µV. */
    val inputVoltageUv: Int? = null,
    /** Privileged: measured IBUS at the phone's port, µA. */
    val inputCurrentUa: Int? = null,
    /** Privileged: charger type from the vendor driver, e.g. "PD". */
    val realType: String? = null,
    /** Privileged: Type-C power role; "sink" while the phone draws power from the port. */
    val powerRole: String? = null,
    val dataRole: String? = null,
    /** Privileged: PD state machine state, e.g. "SNK_Ready". */
    val pdState: String? = null,
    /** Privileged: vendor PD authentication result (0/1). */
    val pdVerified: Int? = null,
    /**
     * Privileged: cell current as the vendor fuel gauge reports it, µA. Positive enters the cell.
     *
     * This is the privileged counterpart of the public `BATTERY_PROPERTY_CURRENT_NOW`, which reads
     * 0 on this device even while the cell is charging or discharging. It is therefore the reading
     * that says what the cell itself is doing, rather than what the platform was able to report.
     */
    val cellCurrentUa: Int? = null,
    /** Privileged: cell terminal voltage, µV. Same node family as [cellCurrentUa]. */
    val cellVoltageUv: Int? = null,
    /** Privileged: full-charge capacity, µAh. */
    val fullChargeUah: Int? = null,
    /** Privileged: design capacity, µAh. */
    val designCapacityUah: Int? = null,
    /** Privileged: vendor state-of-health percentage. */
    val sohPercent: Int? = null,
    /** Human-readable provenance of the fields above. */
    val source: String? = null,
) {
    /**
     * Advertised/negotiated ceiling in W: `max_charging_current × max_charging_voltage`.
     *
     * This is a contract limit reported by the platform, not measured input power. A phone can sit
     * far below it, so it must never be labelled as the charger's live output.
     */
    val ceilingW: Double?
        get() =
            if (maxCurrentUa == null || maxVoltageUv == null || maxCurrentUa <= 0 || maxVoltageUv <= 0)
                null
            else maxCurrentUa.toDouble() * maxVoltageUv / 1e12

    /**
     * Measured adapter input power in W. Only the privileged channel can read the input rail, so
     * this stays null for every unprivileged reading.
     *
     * The magnitude is used: an input rail is a supply, and which way it flows is described by
     * [powerRole] and the sample's plugged state, not by this sign.
     */
    val inputW: Double?
        get() =
            if (inputCurrentUa == null || inputVoltageUv == null || inputVoltageUv <= 0) null
            else kotlin.math.abs(inputCurrentUa.toDouble()) * inputVoltageUv / 1e12

    /** Capacity health percentage. Needs both capacities; neither is available without privilege. */
    val capacityHealthPct: Double?
        get() =
            if (fullChargeUah == null || designCapacityUah == null || fullChargeUah <= 0 ||
                designCapacityUah <= 0
            )
                null
            else fullChargeUah * 100.0 / designCapacityUah

    /** True when at least one field carries a value; an all-null record is not worth keeping. */
    val available: Boolean
        get() =
            listOf(
                    maxCurrentUa,
                    maxVoltageUv,
                    invalid,
                    quickChargeType,
                    advertisedW,
                    carCharge,
                    inputVoltageUv,
                    inputCurrentUa,
                    realType,
                    powerRole,
                    dataRole,
                    pdState,
                    pdVerified,
                    cellCurrentUa,
                    cellVoltageUv,
                    fullChargeUah,
                    designCapacityUah,
                    sohPercent,
                )
                .any { it != null }
}

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
    val adapter: Adapter? = null,
) {
    val powerW: Double?
        get() = Units.power(currentUa, voltageMv)

    val signConflict: Boolean
        get() =
            currentUa != null && ((status == 2 && currentUa < 0) || (status == 3 && currentUa > 0))

    /**
     * Measured adapter input that cannot be all of the input, in W. Null when the input is trusted
     * or was never measured.
     *
     * Energy only flows one way through a charger, so a cell figure well above the port figure means
     * the port figure is short — the vendor driver exposed one of several input paths, not the total.
     * The margins exist because the two rails are separate sensors read a moment apart, so a load
     * step can put the cell briefly ahead by a few percent.
     *
     * The value is kept (and exported) rather than discarded: it is evidence about the device, and
     * the UI says why the input reading is not being used instead of quietly showing a wrong one.
     */
    val partialInputW: Double?
        get() {
            val input = adapter?.inputW ?: return null
            val cell = powerW ?: return null
            return if (cell > input * INPUT_SHORT_RATIO && cell - input > INPUT_SHORT_MARGIN_W) input
            else null
        }

    /**
     * Measured adapter input, or null when it was measured but looks short (see [partialInputW]).
     *
     * Everything displayed, integrated or derived goes through this, so a short reading cannot
     * silently become the denominator of a share or the minuend of a loss figure.
     */
    val trustedInputW: Double?
        get() = adapter?.inputW?.takeIf { partialInputW == null }

    /**
     * Estimated system load plus conversion losses: measured adapter input minus net cell power.
     *
     * Defined only while an external supply is attached, the input rail was measured and trusted, and
     * the input actually exceeds the cell. It is an estimate: it lumps together CPU/display draw and
     * every conversion loss, and it must not be called heat.
     */
    val loadW: Double?
        get() {
            if (plugged == null || plugged == 0) return null
            val input = trustedInputW ?: return null
            val cell = powerW ?: return null
            if (input <= cell) return null
            return input - cell
        }

    /**
     * Share of the measured input that enters the cell, in percent.
     *
     * Null unless the cell is taking charge right now: a "share that reached the cell" has no
     * meaning while the cell is being drained, and a negative percentage would be reporting
     * something that is not a share at all. Capped at 100 % because the two rails are separate
     * sensors read a moment apart, so a brief overshoot is timing rather than a real >100 %; a gap
     * larger than that is the short-input case in [partialInputW].
     */
    val cellSharePct: Double?
        get() {
            val input = trustedInputW ?: return null
            val cell = powerW ?: return null
            if (cell <= 0 || input <= 0) return null
            return kotlin.math.min(cell * 100.0 / input, 100.0)
        }

    companion object {
        /**
         * A load step can put the cell briefly ahead of the port by a few percent, so the short-input
         * check needs margins. The cases it exists for are not close: the observed ones were 1.8x and
         * 2.6x.
         */
        const val INPUT_SHORT_RATIO = 1.2
        const val INPUT_SHORT_MARGIN_W = 1.5
    }
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

    /**
     * The same trapezoid over the measured adapter input instead of the cell.
     *
     * It is a separate integral because the input rail is only readable through the optional
     * privileged channel: a session can have a fully covered cell window and no input coverage at
     * all, and the two must never be conflated.
     */
    fun betweenInput(a: Sample, b: Sample): Segment? {
        val pa = a.trustedInputW ?: return null
        val pb = b.trustedInputW ?: return null
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
    /**
     * True once this session saw the platform's charge state and the cell current's direction
     * disagree. That is not an error: the state describes the charging policy while the cell current
     * is the actual net flow, so a cell can discharge under load while the state still says charging.
     * The flag only records that the session contains such samples.
     */
    val signConflictObserved: Boolean = false,
    /** Measured adapter input energy over the session, Wh. Zero also means "never measured". */
    val inputWh: Double = 0.0,
    /** Time the input integral actually covers. Zero is what separates "unmeasured" from "zero". */
    val inputCoveredMs: Long = 0,
) {
    val averageW: Double?
        get() = if (coveredMs > 0) energyWh * 3_600_000 / coveredMs else null

    val inputAverageW: Double?
        get() = if (inputCoveredMs > 0) inputWh * 3_600_000 / inputCoveredMs else null

    /** True when the input rail was measured at all during this session. */
    val hasInputCoverage: Boolean
        get() = inputCoveredMs > 0

    /**
     * Share of the measured input energy that reached the cell, in percent.
     *
     * Null unless the input was measured over at least half of the cell's covered window: a ratio
     * between two different windows is not a ratio. It is deliberately not called an efficiency,
     * because it can exceed 100 % across a short window (the cell also draws on charge stored
     * earlier) and it goes negative when the charger covered only part of the load.
     *
     * Null as well unless the cell net-charged over the window. A session whose cell was net
     * discharged (a charger that cannot keep up with the system) is described by its own two
     * figures — net cell energy and delivered input energy, both shown — rather than by a negative
     * "share that reached the cell", which is not a share.
     *
     * It can exceed 100 % when the two integrals cover different windows; the input coverage gate
     * above keeps that difference bounded but does not remove it. A paired integral over the same
     * intervals would remove it, and would need a new stored column.
     */
    val cellShareOfInputPct: Double?
        get() {
            if (energyWh <= 0) return null
            if (inputWh <= 0 || inputCoveredMs <= 0 || coveredMs <= 0) return null
            if (inputCoveredMs * 2 < coveredMs) return null
            return energyWh * 100.0 / inputWh
        }
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
