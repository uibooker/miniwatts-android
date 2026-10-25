package dev.miniwatts.core

/**
 * Functional grouping of `/sys/class/thermal` zones. The label text lives in the UI layer so this
 * module stays free of user-visible strings.
 */
enum class ThermalGroup {
    BATTERY,
    CHARGER,
    CPU,
    GPU,
    PMIC,
    MODEM,
    DISPLAY,
    WIRELESS,
    CAMERA,
    OTHER,
}

/**
 * One thermal zone.
 *
 * [raw] is kept verbatim because the unit is decided by [deciC]: the kernel reports `-273000` for a
 * sensor that is not populated, and some entries in that class are not temperatures at all. Those
 * get [deciC] = null rather than a made-up number, so the UI can never show "battery 4306 °C".
 */
data class ThermalZone(val name: String, val group: ThermalGroup, val deciC: Int?, val raw: String)

data class CoolingDevice(val name: String, val current: Int?, val max: Int?) {
    /** True while the kernel is actually applying this mitigation. */
    val active: Boolean
        get() = current != null && current > 0
}

data class CpuPolicy(val name: String, val currentKhz: Int?, val maxKhz: Int?) {
    val ratio: Double?
        get() = if (currentKhz != null && maxKhz != null && maxKhz > 0)
            currentKhz.toDouble() / maxKhz else null
}

/**
 * One read of the thermal class. Everything is best effort: a node that policy refuses is named in
 * [denied] instead of silently disappearing, and entries that are not temperatures are named in
 * [skipped] so their absence from [zones] is explained rather than implied.
 */
data class ThermalSnapshot(
    val zones: List<ThermalZone>,
    val cooling: List<CoolingDevice>,
    val cpu: List<CpuPolicy>,
    val skipped: List<String>,
    val denied: List<String>,
) {
    val measured: List<ThermalZone>
        get() = zones.filter { it.deciC != null }

    val activeCooling: List<CoolingDevice>
        get() = cooling.filter { it.active }

    fun hottest(group: ThermalGroup): ThermalZone? =
        measured.filter { it.group == group }.maxByOrNull { it.deciC ?: Int.MIN_VALUE }

    /** Groups that actually have a readable temperature, in declaration order. */
    val presentGroups: List<ThermalGroup>
        get() = ThermalGroup.entries.filter { group -> measured.any { it.group == group } }

    val available: Boolean
        get() = measured.isNotEmpty()

    companion object {
        val EMPTY = ThermalSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

object Thermal {

    /** The kernel's "sensor not populated" sentinel for the thermal zone temperature file. */
    const val ABSENT_MILLI = -273000L

    /** Physically plausible bounds in 0.1 °C; anything outside is treated as not a temperature. */
    private const val MIN_DECI = -400
    private const val MAX_DECI = 1500

    /**
     * Zone names that exist in the thermal class but do not report a temperature. Verified on
     * Xiaomi 15 (dada): `vbat` reports millivolts, `pmih010x-ibat-lvl*` reports current and
     * `socd` reports a percentage-like value, so none of them may be rendered as °C.
     */
    private val NOT_TEMPERATURE = listOf("vbat", "ibat", "socd", "bcl", "-lvl")

    fun isTemperature(name: String): Boolean {
        val n = name.lowercase()
        return NOT_TEMPERATURE.none { n.contains(it) }
    }

    fun group(name: String): ThermalGroup {
        val n = name.lowercase()
        return when {
            n.contains("battery") -> ThermalGroup.BATTERY
            n.contains("charger") || n.contains("conn_therm") -> ThermalGroup.CHARGER
            n.contains("cpuss") || n.contains("cpu") -> ThermalGroup.CPU
            n.contains("gpuss") || n.contains("gpu") -> ThermalGroup.GPU
            n.contains("pm8550") ||
                n.contains("pm8010") ||
                n.contains("pmr735") ||
                n.contains("pmih010x") ||
                n.contains("pmic") -> ThermalGroup.PMIC
            n.contains("mdmss") ||
                n.contains("nsphvx") ||
                n.contains("nsphmx") ||
                n.contains("modem") -> ThermalGroup.MODEM
            n.contains("display") || n.contains("disp") -> ThermalGroup.DISPLAY
            n.contains("wifi") ||
                n.contains("wireless") ||
                n.contains("pa_therm") ||
                n.contains("sdr") ||
                n.contains("mmw") ||
                n.contains("wlan") -> ThermalGroup.WIRELESS
            n.contains("camera") || n.contains("video") || n.contains("flash") ->
                ThermalGroup.CAMERA
            else -> ThermalGroup.OTHER
        }
    }

    /**
     * The kernel reports thermal zone temperatures in millidegrees Celsius. The absent sentinel and
     * any implausible value become null, so a driver with a different unit cannot leak through as a
     * temperature.
     */
    fun deciCelsius(raw: String): Int? {
        val milli = raw.trim().toLongOrNull() ?: return null
        if (milli == ABSENT_MILLI) return null
        val deci = (milli / 100).toInt()
        return deci.takeIf { it in MIN_DECI..MAX_DECI }
    }

    /** Builds a zone, or null when this name is a known non-temperature entry. */
    fun zone(name: String, raw: String): ThermalZone? =
        if (isTemperature(name)) ThermalZone(name, group(name), deciCelsius(raw), raw.trim()) else null

    /** Formats 0.1 °C for diagnostics, keeping the raw milli-degree value next to it. */
    fun formatDeci(deci: Int): String = "${deci / 10}.${kotlin.math.abs(deci % 10)} °C"
}
