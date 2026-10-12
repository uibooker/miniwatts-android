package dev.miniwatts

import android.content.Context
import dev.miniwatts.core.Sample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Words(context: Context) {
    private val language =
        context.getSharedPreferences("settings", 0).getString("language", "system")
    val en = language == "en" || (language == "system" && Locale.getDefault().language != "zh")

    fun t(zh: String, en: String): String = if (this.en) en else zh

    fun time(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

    fun n(value: Number?, unit: String, digits: Int = 2): String =
        value?.let { String.format(Locale.getDefault(), "%.${digits}f %s", it.toDouble(), unit) }
            ?: t("不可用", "Unavailable")

    fun temperature(deci: Int?, fahrenheit: Boolean): String =
        n(
            deci?.let { if (fahrenheit) it / 10.0 * 1.8 + 32 else it / 10.0 },
            if (fahrenheit) "°F" else "°C",
            1,
        )

    fun source(plugged: Int?): String {
        if (plugged == null) return t("不可用", "Unavailable")
        return buildList {
                if (plugged and 1 != 0) add("AC")
                if (plugged and 2 != 0) add("USB")
                if (plugged and 4 != 0) add(t("无线", "Wireless"))
                if (plugged and 8 != 0) add(t("底座", "Dock"))
            }
            .joinToString(" + ")
            .ifEmpty { if (plugged == 0) t("电池供电", "Battery") else t("未知电源", "Unknown source") }
    }

    fun status(s: Int?): String =
        when (s) {
            2 -> t("充电中", "Charging")
            3 -> t("放电中", "Discharging")
            4 -> t("未充电", "Not charging")
            5 -> t("已充满", "Full")
            else -> t("未知", "Unknown")
        }

    fun health(h: Int?): String =
        when (h) {
            2 -> t("状态良好", "Good")
            3 -> t("过热", "Overheat")
            4 -> t("失效", "Dead")
            5 -> t("过压", "Over voltage")
            6 -> t("故障", "Failure")
            7 -> t("过冷", "Cold")
            else -> t("未知/不可用", "Unknown/unavailable")
        }

    fun summary(s: Sample?, fahrenheit: Boolean): String =
        if (s == null) t("暂无读数", "No readings")
        else
            "${s.level?.let { "$it%" } ?: "—"}   ${n(s.powerW,"W")}   ${temperature(s.temperatureDeciC,fahrenheit)}\n${time(s.wallMs)}"

    fun boundary(): String =
        t(
            "W 为电芯侧净功率（推算），正值流入电芯，负值流出；不是适配器功率。缺失值不当作 0。",
            "W is estimated net cell power: positive enters the cell, negative leaves it. This is not adapter power. Missing values are not zero.",
        )

    fun capabilities(): String =
        t(
            "能力矩阵\n电源类型：公开电池广播，实测\n电芯电流/电压：公开 API，设备可能不支持\n适配器输入电压/电流/实时功率：不可用，无通用公开 API\nPD PDO / 协商 / 适配器身份：不可用，无通用公开 API\n额定最大功率 / 容量健康百分比：不可用\n额外温度探头：不可用；本版本无语义和单位经确认的机型白名单，因此不读取任意 sysfs 节点\n效率 / 成对能量比例：不可用，缺少配对输入样本\n负载和损耗不等同于热量。",
            "Capabilities\nPower source: measured via public broadcast\nCell current/voltage: public API, device dependent\nAdapter input voltage/current/live power: unavailable, no generic public API\nPD PDO / handshake / adapter identity: unavailable, no generic public API\nRated maximum power / capacity health percent: unavailable\nExtra temperature probes: unavailable; no model-specific sysfs whitelist with verified units in this release\nEfficiency / paired energy ratio: unavailable without paired input samples\nLoad and losses are not heat.",
        )
}
