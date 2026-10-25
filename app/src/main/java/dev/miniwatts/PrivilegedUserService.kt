package dev.miniwatts

import android.os.Process
import java.io.File

/**
 * Runs inside the Shizuku server process, which holds the identity the user started Shizuku with:
 * the ADB shell or root. It only reads a fixed list of power-supply nodes and reports the raw text,
 * or the reason when a node cannot be read.
 *
 * This class is instantiated reflectively by Shizuku, so it needs a public no-argument constructor
 * and must not depend on app state. It is never started by this app on its own, and it exposes no
 * method that accepts a path or a command from the caller.
 *
 * Verified against Xiaomi 15 (`dada`, Android 16 / HyperOS OS3.0.303.0): an ADB-shell Shizuku cannot
 * read these nodes, because the vendor SELinux labels (`vendor_sysfs_usb_supply`,
 * `vendor_sysfs_battery_supply`, `vendor_sysfs_xm_power`, `vendor_sysfs_usb_c`) grant no access to
 * `shell`. The same probe therefore reports `!EACCES` under an ADB-shell Shizuku and the UI stays
 * "unavailable"; only a root-backed Shizuku yields values on that device.
 */
class PrivilegedUserService : IPrivileged.Stub() {

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }

    override fun probe(): String {
        val out = StringBuilder()
        out.append("uid=").append(uid()).append('\n')
        out.append("selinux=").append(readFirst(SELINUX)).append('\n')
        for ((key, candidates) in NODES) {
            out.append(key).append('=').append(readFirst(candidates)).append('\n')
        }
        return out.toString()
    }

    private fun uid(): String =
        try {
            Process.myUid().toString()
        } catch (t: Throwable) {
            "!ERROR"
        }

    /** Reads the first candidate that yields a value; otherwise reports the first real failure. */
    private fun readFirst(candidates: List<String>): String {        var failure = "!ENOENT"
        for (path in candidates) {
            val value = read(path)
            if (!value.startsWith("!")) return value
            if (value != "!ENOENT") failure = value
        }
        return failure
    }

    /**
     * A node that is missing and a node that policy denies both throw FileNotFoundException, so the
     * message is what separates "this device has no such node" from "reading it was refused".
     *
     * Control characters are stripped here as well: `/proc/self/attr/current` returns a
     * NUL-terminated context, and that byte must never travel to the app's UI or exports.
     */
    private fun read(path: String, limit: Int = 64): String =
        try {
            val text = File(path).readText().filter { !it.isISOControl() }.trim()
            when {
                text.isEmpty() -> "!EMPTY"
                text.length > limit -> text.take(limit)
                else -> text
            }
        } catch (t: Throwable) {
            when {
                t.message?.contains("Permission denied", ignoreCase = true) == true -> "!EACCES"
                t is java.io.FileNotFoundException -> "!ENOENT"
                else -> "!ERROR"
            }
        }

    private companion object {
        val SELINUX = listOf("/proc/self/attr/current", "/proc/self/attr/exec")

        /** Port names differ across kernel integrations; the first readable candidate wins. */
        val TYPEC_PORTS: List<String> =
            listOf("/sys/class/typec/port0", "/sys/class/typec/port1", "/sys/class/typec/usbc0")

        /** Fixed, read-only whitelist. Paths are device-verified; other models report !ENOENT. */
        val NODES: List<Pair<String, List<String>>> =
            listOf(
                "usb.voltage_now" to listOf("/sys/class/power_supply/usb/voltage_now"),
                "usb.current_now" to listOf("/sys/class/power_supply/usb/current_now"),
                "usb.current_max" to listOf("/sys/class/power_supply/usb/current_max"),
                "usb.voltage_max" to listOf("/sys/class/power_supply/usb/voltage_max"),
                "usb.type" to
                    listOf(
                        "/sys/class/power_supply/usb/real_type",
                        "/sys/class/power_supply/usb/type",
                    ),
                "usb.input_current_limit" to
                    listOf("/sys/class/power_supply/usb/input_current_limit"),
                // The vendor fuel gauge is the only readable source for the cell's own current and
                // terminal voltage: BatteryManager's CURRENT_NOW reports 0 on this device even while
                // the cell charges or discharges. Read-only, and part of the power-supply whitelist.
                "battery.current_now" to listOf("/sys/class/power_supply/battery/current_now"),
                "battery.voltage_now" to listOf("/sys/class/power_supply/battery/voltage_now"),
                "battery.charge_full" to listOf("/sys/class/power_supply/battery/charge_full"),
                "battery.charge_full_design" to
                    listOf("/sys/class/power_supply/battery/charge_full_design"),
                "charger.real_type" to
                    listOf("/sys/class/xm_power/charger/charger_common/real_type"),
                "charger.quick_charge_type" to
                    listOf("/sys/class/xm_power/charger/charger_common/quick_charge_type"),
                "charger.power_max" to
                    listOf("/sys/class/xm_power/charger/charger_common/power_max"),
                "fg.soh_new" to
                    listOf(
                        "/sys/class/xm_power/fuelgauge/strategy_fg/soh_new",
                        "/sys/class/power_supply/battery/soh",
                    ),
                "typec.power_role" to TYPEC_PORTS.map { "$it/power_role" },
                "typec.data_role" to TYPEC_PORTS.map { "$it/data_role" },
                "typec.power_operation_mode" to TYPEC_PORTS.map { "$it/power_operation_mode" },
                "pd.current_state" to
                    listOf("/sys/class/xm_power/typec/strategy_pd_auth/current_state"),
                "pd.current_pr" to listOf("/sys/class/xm_power/typec/strategy_pd_auth/current_pr"),
                "pd.verified" to listOf("/sys/class/xm_power/typec/strategy_pd_auth/verified"),
                "pd.adapter_id" to
                    listOf("/sys/class/xm_power/typec/strategy_pd_auth/adapter_id"),
            )
    }
}
