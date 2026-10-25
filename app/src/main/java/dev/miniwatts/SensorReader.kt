package dev.miniwatts

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import dev.miniwatts.core.Adapter
import dev.miniwatts.core.PrivilegedParser
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Units

/**
 * Reads the platform. Everything here is public API plus a small number of broadcast extras whose
 * keys are hidden constants but whose values are plain data in a sticky broadcast; the keys are used
 * as string literals on purpose, see docs/ADAPTER_POWER_SOURCE_RESEARCH.md.
 *
 * [privileged] is the optional Shizuku probe result, merged in as a supplement.
 */
class SensorReader(private val context: Context) {
    fun read(interval: Long, privileged: Adapter? = null): Sample {
        val intent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: error("Battery broadcast unavailable")
        val bm = context.getSystemService(BatteryManager::class.java)
        fun extra(key: String): Int? =
            intent
                ?.takeIf { it.hasExtra(key) }
                ?.getIntExtra(key, Int.MIN_VALUE)
                ?.let(Units::property)
        fun property(key: Int): Int? =
            runCatching { Units.property(bm.getIntProperty(key)) }.getOrNull()
        val scale = extra(BatteryManager.EXTRA_SCALE)
        val level =
            extra(BatteryManager.EXTRA_LEVEL)
                ?.takeIf { it >= 0 && scale != null && scale > 0 && it <= scale }
                ?.let { it * 100 / scale!! }
        val plugged = extra(BatteryManager.EXTRA_PLUGGED)
        return Sample(
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                .toLong(),
            level,
            plugged,
            extra(BatteryManager.EXTRA_STATUS),
            extra(BatteryManager.EXTRA_HEALTH),
            property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            extra(BatteryManager.EXTRA_VOLTAGE)?.takeIf { it > 0 },
            extra(BatteryManager.EXTRA_TEMPERATURE),
            property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it >= 0 },
            runCatching {
                    Units.property(
                            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                        )
                        ?.takeIf { it >= 0 }
                }
                .getOrNull(),
            if (Build.VERSION.SDK_INT >= 34)
                extra(BatteryManager.EXTRA_CYCLE_COUNT)?.takeIf { it >= 0 }
            else null,
            if (Build.VERSION.SDK_INT >= 29)
                runCatching {
                        context.getSystemService(PowerManager::class.java).currentThermalStatus
                    }
                    .getOrNull()
            else null,
            interval,
            0,
            adapter(intent, plugged, privileged),
        )
    }

    /**
     * Adapter/charger record.
     *
     * `max_charging_current` and `max_charging_voltage` are the charger values the platform already
     * read and put into ACTION_BATTERY_CHANGED: the maximum current and voltage the charger
     * supports, µA and µV, taken from the highest-power online charger supply. They are a contract
     * ceiling, not measured input power, and the UI must say so.
     *
     * The MIUI quick-charge broadcast only exists on Xiaomi ROMs and is read only while an external
     * supply is attached, so an unplug can never leave a stale "fast charging" label behind.
     */
    private fun adapter(intent: Intent?, plugged: Int?, privileged: Adapter?): Adapter? {
        val xiaomi = if (plugged != null && plugged != 0) xiaomiQuickCharge() else null
        val declared =
            Adapter(
                maxCurrentUa = intent.int("max_charging_current"),
                maxVoltageUv = intent.int("max_charging_voltage"),
                invalid = intent.int("invalid_charger"),
                quickChargeType = xiaomi.int("miui.intent.extra.quick_charge_type"),
                advertisedW = xiaomi.int("miui.intent.extra.POWER_MAX"),
                carCharge = xiaomi.int("miui.intent.extra.CAR_CHARGE"),
                source =
                    buildString {
                        append("ACTION_BATTERY_CHANGED extras")
                        if (xiaomi != null) append(" + miui ACTION_QUICK_CHARGE_TYPE")
                    },
            )
        return PrivilegedParser.merge(declared, privileged)?.takeIf { it.available }
    }

    private fun Intent?.int(key: String): Int? =
        this?.takeIf { it.hasExtra(key) }
            ?.getIntExtra(key, Int.MIN_VALUE)
            ?.takeIf { it > Int.MIN_VALUE }

    private fun xiaomiQuickCharge(): Intent? =
        runCatching { context.registerReceiver(null, IntentFilter(MIUI_QUICK_CHARGE)) }.getOrNull()

    private companion object {
        /**
         * Xiaomi's quick-charge broadcast. It is a sticky broadcast that a normal app can read; on
         * non-Xiaomi ROMs the action is unknown and the query returns null.
         */
        const val MIUI_QUICK_CHARGE = "miui.intent.action.ACTION_QUICK_CHARGE_TYPE"
    }
}
