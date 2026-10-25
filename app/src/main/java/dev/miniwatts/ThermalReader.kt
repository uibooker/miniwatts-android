package dev.miniwatts

import dev.miniwatts.core.CoolingDevice
import dev.miniwatts.core.CpuPolicy
import dev.miniwatts.core.Thermal
import dev.miniwatts.core.ThermalSnapshot
import dev.miniwatts.core.ThermalZone
import java.io.File

/**
 * Reads the kernel thermal class.
 *
 * Verified on Xiaomi 15 (dada, Android 16): an ordinary app with no permissions can list
 * `/sys/class/thermal` and read 80 of the 84 zones, so this needs neither root nor the optional
 * Shizuku channel. Every node is read independently and best effort: a refusal is reported in the
 * snapshot instead of being hidden, and entries that are not temperatures are named so their absence
 * from the temperature list is explained rather than implied.
 *
 * The whole snapshot is about 300 small file reads, so callers throttle it instead of taking it on
 * every sample.
 */
class ThermalReader {

    fun read(): ThermalSnapshot {
        val zones = mutableListOf<ThermalZone>()
        val skipped = mutableListOf<String>()
        val denied = mutableListOf<String>()

        for (dir in children(THERMAL, "thermal_zone", MAX_ZONES)) {
            val name = read(File(dir, "type"))?.trim()
            if (name.isNullOrEmpty()) {
                denied += dir.name
                continue
            }
            val raw = read(File(dir, "temp"))
            if (raw == null) {
                denied += name
                continue
            }
            val zone = Thermal.zone(name, raw)
            if (zone == null) skipped += name else zones += zone
        }

        val cooling = mutableListOf<CoolingDevice>()
        for (dir in children(THERMAL, "cooling_device", MAX_COOLING)) {
            val name = read(File(dir, "type"))?.trim()
            if (name.isNullOrEmpty()) {
                denied += dir.name
                continue
            }
            cooling +=
                CoolingDevice(
                    name = name,
                    current = read(File(dir, "cur_state"))?.trim()?.toIntOrNull(),
                    max = read(File(dir, "max_state"))?.trim()?.toIntOrNull(),
                )
        }

        val cpu = mutableListOf<CpuPolicy>()
        for (dir in children(CPUFREQ, "policy", MAX_POLICIES)) {
            cpu +=
                CpuPolicy(
                    name = dir.name,
                    currentKhz = read(File(dir, "scaling_cur_freq"))?.trim()?.toIntOrNull(),
                    maxKhz = read(File(dir, "cpuinfo_max_freq"))?.trim()?.toIntOrNull(),
                )
        }

        return ThermalSnapshot(
            // Group order first so the UI is stable, then by name.
            zones = zones.sortedWith(compareBy({ it.group.ordinal }, { it.name })),
            cooling = cooling.sortedBy { it.name },
            cpu = cpu.sortedBy { it.name },
            skipped = skipped.distinct().sorted(),
            denied = denied.distinct().sorted(),
        )
    }

    /**
     * Children of [dir] whose name starts with [prefix], ordered by the numeric suffix so that
     * `thermal_zone2` comes before `thermal_zone10`.
     */
    private fun children(dir: File, prefix: String, limit: Int): List<File> {
        val entries =
            runCatching { dir.listFiles() }.getOrNull()?.filter { it.name.startsWith(prefix) }
                ?: return emptyList()
        return entries
            .sortedWith(
                compareBy(
                    { it.name.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE },
                    { it.name },
                )
            )
            .take(limit)
    }

    /** Control characters are stripped so a NUL-padded node can never reach the UI or an export. */
    private fun read(file: File): String? =
        runCatching {
                file.readText()
                    .filter { !it.isISOControl() }
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }
            .getOrNull()

    private companion object {
        val THERMAL = File("/sys/class/thermal")
        val CPUFREQ = File("/sys/devices/system/cpu/cpufreq")
        const val MAX_ZONES = 128
        const val MAX_COOLING = 64
        const val MAX_POLICIES = 16
    }
}
