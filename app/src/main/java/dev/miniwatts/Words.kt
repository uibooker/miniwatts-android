package dev.miniwatts

import android.content.Context
import dev.miniwatts.core.Sample
import dev.miniwatts.core.ThermalGroup
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

    /**
     * Minute-precision time, for the comparison table's narrow columns.
     *
     * Seconds stay everywhere a single reading is described; in a four-column table they only force
     * the value onto a second line, and the exact times remain in the session detail and the export.
     */
    fun timeMinutes(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    /** A signed duration, so a time delta reads "-1m 30s" rather than "-90000 秒". */
    fun signedDuration(deltaMs: Double): String {
        val sign = if (deltaMs < 0) "-" else "+"
        return sign + duration(kotlin.math.abs(deltaMs).toLong())
    }

    fun duration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return when {
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds / 60) % 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Magnitude of a headline reading.
     *
     * The cell power's direction is stated in words (the charge state), and the signed value is kept
     * in the stored samples, the trend chart, the cell current and every export — so nothing is lost.
     * It is simply not repeated as a headline, where on some devices the sign reads inverted and a
     * charging phone would show a minus. The gauge arc has always been drawn on the magnitude scale.
     */
    fun magnitude(value: Number?): Double? = value?.let { kotlin.math.abs(it.toDouble()) }

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

    /**
     * What the cell itself is doing, from the sign of its own current.
     *
     * This is the counterpart of [status]: the platform reports the charging *state* (a charger is
     * attached and charging is enabled), while the cell's net current can still be negative when the
     * system draws more than the input supplies. Saying both in words is what lets the headline power
     * drop its sign without losing the direction.
     */
    fun cellDirection(powerW: Double?): String? =
        when {
            powerW == null -> null
            powerW > 0 -> t("电芯充电", "cell charging")
            powerW < 0 -> t("电芯放电", "cell discharging")
            else -> t("电芯净电流为零", "cell current ~0")
        }

    /**
     * Why a measured input reading is not being used.
     *
     * The port figure came out below what is reaching the cell, which cannot happen through a
     * charger: the vendor driver exposed one input path rather than the total. The reading is kept
     * in Raw data and in the exports, and this says why nothing is derived from it.
     */
    fun inputLooksShort(measured: Double): String =
        t(
            "输入侧实测 ${n(measured, "W")} 低于进入电芯的功率，说明读到的只是其中一路输入，不是全部；因此负载、损耗与电芯占比都不计算（原始值保留在导出里）。",
            "The measured input of ${n(measured, "W")} is below what is reaching the cell, so only one input path was read rather than the total; load, losses and the cell share are therefore not computed (the raw value stays in the exports).",
        )

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

    /**
     * Shared one-glance summary for the notification, the floating meter and the widget.
     *
     * The adapter line appears only when the optional privileged channel actually measured the input
     * rail; without it the text is exactly what it was before, so nothing suggests a zero input.
     */
    fun summary(s: Sample?, fahrenheit: Boolean): String =
        if (s == null) t("暂无读数", "No readings")
        else
            buildString {
                append("${s.level?.let { "$it%" } ?: "—"}   ${n(magnitude(s.powerW), "W")}   ")
                append(temperature(s.temperatureDeciC, fahrenheit))
                append('\n')
                append(time(s.wallMs))
                s.trustedInputW?.let { input ->
                    append('\n')
                    append(
                        t(
                            "适配器输入 ${n(input, "W")} · 实测",
                            "Adapter input ${n(input, "W")} · measured",
                        )
                    )
                }
            }

    fun boundary(): String =
        t(
            "W 为电芯侧净功率（推算），正值流入电芯，负值流出；不是适配器功率。缺失值不当作 0。",
            "W is estimated net cell power: positive enters the cell, negative leaves it. This is not adapter power. Missing values are not zero.",
        )

    /** The platform reports what the charger supports; it does not measure what it delivers. */
    fun ceilingNote(): String =
        t(
            "适配器数值来自系统上报的充电器上限（最大充电电压 × 最大充电电流），是协商上限，不是此刻实际输入。",
            "Adapter values are the charger ceiling the platform reports (max charging voltage × max charging current). That is a negotiated limit, not what the charger delivers right now.",
        )

    fun advertisedNote(): String =
        t(
            "小米快充广播的档位与类型由厂商定义，枚举含义未经本机验证；POWER_MAX 可能为 0。",
            "The MIUI quick-charge tier and type are vendor-defined; their enum is unverified on this device and POWER_MAX may be 0.",
        )

    fun privilegedNote(): String =
        t(
            "输入侧实时电压/电流、PD 状态、容量健康度与真实充电器类型需要特权读取。开启 Shizuku 增强后会由一个只读白名单服务尝试获取；若 Shizuku 以 ADB 身份运行，系统策略会拒绝这些节点，读数保持不可用。",
            "Live input voltage/current, PD state, capacity health and the real charger type need privileged reads. With the optional Shizuku channel enabled, a read-only whitelist service tries them; if Shizuku runs with ADB identity, system policy refuses these nodes and the readings stay unavailable.",
        )

    fun role(value: String?): String =
        when (value?.lowercase()) {
            null -> t("不可用", "Unavailable")
            "sink" -> t("受电端 sink", "sink")
            "source" -> t("供电端 source", "source")
            else -> value
        }

    fun invalidCharger(value: Int?): String =
        when (value) {
            null -> t("不可用", "Unavailable")
            0 -> t("未标记", "Not flagged")
            else -> t("系统标记为不合规充电器", "Flagged as non-compliant")
        }

    fun quickChargeType(value: Int?): String =
        value?.toString() ?: t("不可用", "Unavailable")

    fun privilegedStatus(state: String?): String =
        when (state) {
            "ready" -> t("已连接", "Connected")
            "no_permission" -> t("未授权", "No permission")
            "no_shizuku" -> t("未安装 Shizuku", "Shizuku not installed")
            "binding" -> t("连接中", "Connecting")
            "failed" -> t("连接失败", "Connection failed")
            else -> t("未启用", "Off")
        }

    fun thermalGroup(group: ThermalGroup): String =
        when (group) {
            ThermalGroup.BATTERY -> t("电池", "Battery")
            ThermalGroup.CHARGER -> t("充电与接口", "Charger & port")
            ThermalGroup.CPU -> t("CPU", "CPU")
            ThermalGroup.GPU -> t("GPU", "GPU")
            ThermalGroup.PMIC -> t("电源管理", "PMIC")
            ThermalGroup.MODEM -> t("调制解调器", "Modem")
            ThermalGroup.DISPLAY -> t("显示", "Display")
            ThermalGroup.WIRELESS -> t("无线与射频", "Wireless & RF")
            ThermalGroup.CAMERA -> t("相机与视频", "Camera & video")
            ThermalGroup.OTHER -> t("其他", "Other")
        }

    /**
     * The badge on the adapter and device pages, stating the concrete situation rather than the
     * abstract pair "state vs direction".
     *
     * Null when the two do not disagree. The power page does not use it: that page shows the
     * platform state and the cell's direction as two pills side by side, so a third line saying they
     * disagree would only repeat them.
     *
     * The full explanation lives in [signConflictHelp], reachable from Settings, and the regression
     * suite pins the split (this stays a short one-liner, the help text stays long).
     */
    fun stateAndCellDisagree(status: Int?, powerW: Double?): String? =
        when {
            status == 2 && powerW != null && powerW < 0 ->
                t("插着电，电芯在放电", "plugged in, cell discharging")
            status == 3 && powerW != null && powerW > 0 ->
                t("电芯在充电，状态仍显示放电", "cell charging, state still says discharging")
            else -> null
        }

    /** The explanation behind the badge, shown in Help rather than on a live page. */
    fun signConflictHelp(): String =
        t(
            "「插着电，电芯在放电」是怎么回事：系统上报的充放电状态描述的是「已插电且允许充电」这一策略，而电芯净电流是此刻真正流入或流出电芯的量。插着电但系统耗电超过输入时，电芯会净放电——两者并不矛盾，只是说的不是同一件事；反过来（状态仍显示放电、电芯已在充电）一般出现在刚插上时，同样会标记。App 不做取舍：功率数字取绝对值（表盘圆弧本来就是这个量程），方向由「电芯充电/电芯放电」这枚说明给出，原始带符号值保留在趋势曲线、电芯电流与全部导出里。需要方向的负载与损耗、电芯占比、电芯获得比例在两者相反期间都不计算。判断趋势请看电量与净能量随时间的变化。",
            "About \"plugged in, cell discharging\": the platform's charge state describes the policy ('a charger is attached and charging is allowed'), while the cell's net current is what actually flows into or out of the cell right now. When the system draws more than the input supplies, the cell discharges even though the state still says charging, and the two are not in contradiction. The app does not pick a winner: the power figure is a magnitude (the gauge arc has always used that scale), the direction is stated by the cell-charging / cell-discharging marker, and the signed original is kept in the trend chart, the cell current and every export. Load and losses, the cell share, and the cell share of input are all withheld while the two disagree. Read the trend from the charge level and net energy over time.",
        )

    fun sessionSignConflict(): String =
        t(
            "本会话曾出现充放电状态与电芯方向不一致的时段",
            "this session has periods where the charge state and the cell direction disagreed",
        )

    fun summarySignConflict(): String =
        t(
            "汇总含状态与电芯方向不一致的会话",
            "Totals include sessions where the charge state and the cell direction disagreed",
        )

    fun capabilities(): String =
        t(
            "能力矩阵\n电源类型：公开电池广播，实测\n电芯电流/电压：公开 API，设备可能不支持\n适配器协商上限（最大充电电压 × 最大充电电流）：来自系统电池广播，实测；是充电器上限，不是实时输入\n小米快充类型与档位：小米粘性广播，实测；枚举含义未经机型验证\n输入侧实时电压/电流、真实充电器类型、PD 状态、容量健康度：需要特权。开启可选 Shizuku 通道后由一个只读白名单服务尝试；Shizuku 以 ADB 身份运行时系统策略会拒绝这些节点\n温度探头：内核 /sys/class/thermal 热区，免权限可读（本机 73 个热区中 70 个可读）；按名称剔除不是温度的节点，-273000 视为传感器缺失\n热缓解档位与 CPU 频率：内核 cooling device 与 cpufreq，免权限可读\n容量（无特权时的替代）：用电荷计数器与电量跨度推算满充容量，取多次充电的中位数；只在恒流段成立，是估算不是实测\n会话级输入能量：与电芯能量分开积分；只有输入实测覆盖达到电芯窗口一半时才给「电芯获得比例」，该比例可能大于 100% 或为负，不叫效率\n负载与损耗、电芯占比：仅在有输入侧实测读数时为推算值，且不等于热量\nPD PDO 逐档列表 / 线缆阻抗 / 通路损耗：不可用，内核与系统均未提供给第三方应用\n环境温度探头 / GPU 占用率：本机无环境温度传感器，GPU 占用率节点被 SELinux 拒绝\n效率 / 成对能量比例：没有配对输入样本时不可用",
            "Capabilities\nPower source: measured via public broadcast\nCell current/voltage: public API, device dependent\nAdapter negotiated ceiling (max charging voltage x max charging current): from the system battery broadcast, measured; a charger ceiling, not live input\nXiaomi quick-charge type and tier: Xiaomi sticky broadcast, measured; the enum is unverified on this device\nLive input voltage/current, real charger type, PD state, capacity health: privileged. With the optional Shizuku channel enabled a read-only whitelist service tries them; an ADB-identity Shizuku is refused by system policy\nTemperature probes: kernel /sys/class/thermal zones, readable without permissions (70 of 73 zones on this device); entries that are not temperatures are dropped by name and -273000 means the sensor is absent\nThermal mitigation levels and CPU frequency: kernel cooling devices and cpufreq, readable without permissions\nCapacity (the fallback without privileges): full-charge capacity derived from the charge counter and the state-of-charge span, median over several charging sessions; only valid in the constant-current region, an estimate rather than a measurement\nSession-level input energy: integrated separately from the cell; the cell share of input is given only when the input covered at least half of the cell window, and it can exceed 100% or be negative, so it is not called an efficiency\nLoad and losses, cell share: estimates, and only when the input rail was actually measured; not heat\nPD PDO list / cable resistance / path loss: unavailable, neither the kernel nor the platform exposes them to third-party apps\nAmbient temperature probe / GPU utilization: this device has no ambient temperature sensor and the GPU utilization node is refused by SELinux\nEfficiency / paired energy ratio: unavailable without paired input samples",
        )
}
