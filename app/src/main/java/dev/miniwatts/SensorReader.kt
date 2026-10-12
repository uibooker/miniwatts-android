package dev.miniwatts

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Units

class SensorReader(private val context: Context) {
    fun read(interval: Long): Sample {
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
        return Sample(
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                .toLong(),
            level,
            extra(BatteryManager.EXTRA_PLUGGED),
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
        )
    }
}
