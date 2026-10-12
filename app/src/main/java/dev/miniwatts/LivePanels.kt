package dev.miniwatts

import android.widget.LinearLayout
import android.widget.TextView
import dev.miniwatts.core.Sample

/** Live screens retain their views; each sample updates values without rebuilding the UI. */
class LivePanels(private val ui: UiKit, private val words: Words, private val fahrenheit: Boolean) {
    private val bindings = mutableListOf<(Sample?) -> Unit>()
    private var chart: ChartView? = null

    private fun t(zh: String, en: String) = words.t(zh, en)

    private fun n(v: Number?, unit: String, digits: Int = 2) =
        v?.let { words.n(it, unit, digits) } ?: "— $unit"

    private fun temp(s: Sample?) =
        s?.temperatureDeciC?.let { words.temperature(it, fahrenheit) }
            ?: "— ${if (fahrenheit) "°F" else "°C"}"

    private fun bind(view: TextView, value: (Sample?) -> String) {
        bindings.add { view.text = value(it) }
    }

    private fun stamp(parent: LinearLayout, source: String) {
        val view = ui.label("", 12f, ui.muted)
        parent.addView(view)
        bind(view) { s ->
            "${s?.let { words.time(it.wallMs) } ?: t("等待采样", "Waiting for sample")} · $source"
        }
    }

    fun update(s: Sample?, recent: List<Sample>) {
        bindings.forEach { it(s) }
        chart?.samples = recent
    }

    fun power(parent: LinearLayout) {
        val hero = ui.card(parent)
        val gauge = PowerGauge(ui.context, ui)
        hero.addView(gauge, LinearLayout.LayoutParams(-1, -2))
        bindings.add { gauge.update(it?.powerW) }
        val states = ui.column()
        hero.addView(states)
        bind(ui.pill(states, ui.blue)) { s ->
            "${words.status(s?.status)} · ${words.source(s?.plugged)}"
        }
        bind(ui.pill(states, ui.green)) { s ->
            "${t("系统热状态", "System thermal")} · ${thermal(s?.thermal)}"
        }
        val warning = ui.label("", 13f, ui.orange)
        hero.addView(warning)
        bindings.add { s ->
            warning.text =
                t(
                    "电流符号与状态冲突；保留原始值，需设备诊断确认。",
                    "Current sign conflicts with status; raw value preserved. Verify on device.",
                )
            warning.visibility =
                if (s?.signConflict == true) android.view.View.VISIBLE else android.view.View.GONE
        }
        val battery = ui.card(parent, t("电池", "BATTERY"), "battery")
        bind(ui.number("—", 36f, ui.green).also { battery.addView(it) }) { "${it?.level ?: "—"} %" }
        val bar = LevelBar(ui.context, ui)
        battery.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { topMargin = ui.dp(4) })
        bindings.add {
            bar.fraction = it?.level?.div(100f)
            bar.contentDescription =
                t("电量 ${it?.level ?: "不可用"}%", "Charge ${it?.level ?: "unavailable"}%")
        }
        val values =
            ui.metrics(
                battery,
                listOf(
                    t("电压 · 实测", "Voltage · measured"),
                    t("电流 · 实测", "Current · measured"),
                    t("温度 · 实测", "Temp · measured"),
                ),
                listOf(ui.ink, ui.green, ui.orange),
            )
        bind(values[0]) { n(it?.voltageMv?.div(1000.0), "V") }
        bind(values[1]) { n(it?.currentUa?.div(1000.0), "mA", 0) }
        bind(values[2], ::temp)
        stamp(battery, "ACTION_BATTERY_CHANGED / BatteryManager")
        val health = ui.label("", 12f, ui.muted).also { battery.addView(it) }
        bind(health) { "${words.health(it?.health)} · ${t("非容量健康度", "not capacity health")}" }
        trend(parent, false)
        ui.note(parent, words.boundary())
    }

    fun temperature(parent: LinearLayout) {
        val state = ui.card(parent, t("系统热状态", "SYSTEM THERMAL STATE"), "thermal", ui.orange)
        bind(ui.number("—", 30f, ui.green).also { state.addView(it) }) { thermal(it?.thermal) }
        state.addView(
            ui.label(
                t("公开 API 的热限频等级，不是温度。", "Public API throttling severity, not temperature."),
                14f,
                ui.muted,
            )
        )
        val stages = LinearLayout(ui.context)
        state.addView(
            stages,
            LinearLayout.LayoutParams(-1, ui.dp(8)).apply {
                topMargin = ui.dp(12)
                bottomMargin = ui.dp(6)
            },
        )
        val bars =
            (0..6).map {
                LevelBar(ui.context, ui, if (it < 2) ui.green else ui.orange).also { bar ->
                    stages.addView(
                        bar,
                        LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = ui.dp(3) },
                    )
                }
            }
        bindings.add { s ->
            bars.forEachIndexed { i, bar ->
                bar.fraction = if (s?.thermal?.let { i <= it } == true) 1f else null
            }
            stages.contentDescription = thermal(s?.thermal)
        }
        state.addView(ui.label(t("0 无热限频 → 6 关机级", "0 None → 6 Shutdown"), 12f, ui.muted))
        stamp(state, "PowerManager · API 29+")
        val battery = ui.card(parent, t("电池温度", "BATTERY TEMPERATURE"), "battery", ui.orange)
        bind(ui.number("—", 48f, ui.orange).also { battery.addView(it) }, ::temp)
        battery.addView(ui.label(t("实测 · 仅电池传感器", "Measured · battery sensor only"), 14f, ui.muted))
        battery.addView(
            BatterySchematic(ui.context, ui).apply {
                contentDescription =
                    t(
                        "手机示意，仅一个电池标记；不是传感器位置图。",
                        "Phone schematic, one battery marker; not a sensor location map.",
                    )
            },
            LinearLayout.LayoutParams(-1, ui.dp(168)),
        )
        battery.addView(
            ui.label(
                t("示意位置，不代表设备实际布局。", "Schematic location, not actual device layout."),
                12f,
                ui.muted,
            )
        )
        stamp(battery, "ACTION_BATTERY_CHANGED · 0.1°C")
        trend(parent, true)
        ui.note(
            parent,
            t(
                "额外探头不可用：本版没有已确认语义与单位的机型 sysfs 白名单，不读取任意节点、不调用 su。",
                "Extra probes unavailable: no model-specific sysfs whitelist with verified units. No arbitrary node reads or su.",
            ),
        )
    }

    fun charger(parent: LinearLayout) {
        val hero = ui.card(parent, t("电源连接", "POWER CONNECTION"), "charger", ui.violet)
        bind(ui.number("—", 40f, ui.blue).also { hero.addView(it) }) { words.source(it?.plugged) }
        bind(ui.label("", 18f, ui.muted).also { hero.addView(it) }) { words.status(it?.status) }
        stamp(hero, "ACTION_BATTERY_CHANGED · ${t("实测类型", "measured type")}")
        val caps = ui.card(parent, t("适配器能力", "ADAPTER CAPABILITIES"), "info")
        listOf(
                t("实时输入功率", "Live input power"),
                t("输入电压 / 电流", "Input voltage / current"),
                t("额定最大功率", "Rated maximum"),
                t("PD / 身份", "PD / identity"),
                t("效率 / 成对能量", "Efficiency / paired energy"),
            )
            .forEach { ui.row(caps, it, t("不可用", "Unavailable"), ui.muted) }
        caps.addView(
            ui.label(
                t(
                    "Android 无通用公开接口；不根据电芯读数猜测输入或额定功率。",
                    "No generic public Android API. Cell readings cannot establish adapter input or rating.",
                ),
                13f,
                ui.muted,
            )
        )
        val live = ui.card(parent, t("实时电芯指标", "LIVE CELL METRICS"), "battery", ui.green)
        val values =
            ui.metrics(
                live,
                listOf(
                    t("电压 · 实测", "Voltage · measured"),
                    t("电流 · 实测", "Current · measured"),
                    t("净功率 · 推算", "Net power · estimated"),
                ),
                listOf(ui.ink, ui.green, ui.blue),
            )
        bind(values[0]) { n(it?.voltageMv?.div(1000.0), "V") }
        bind(values[1]) { n(it?.currentUa?.div(1000.0), "mA", 0) }
        bind(values[2]) { n(it?.powerW, "W") }
        stamp(live, "BatteryManager / ACTION_BATTERY_CHANGED")
        val detail = ui.card(parent, t("原始信息与来源", "RAW DETAILS & SOURCES"), "info")
        bind(ui.row(detail, t("电源枚举", "Source enum"), "—")) { it?.plugged?.toString() ?: "—" }
        bind(ui.row(detail, t("状态枚举", "Status enum"), "—")) { it?.status?.toString() ?: "—" }
        ui.row(detail, t("电流来源", "Current source"), "BatteryManager")
        ui.row(detail, t("电压来源", "Voltage source"), "Battery broadcast")
        ui.note(parent, words.boundary())
    }

    private fun trend(parent: LinearLayout, temperature: Boolean) {
        val card =
            ui.card(
                parent,
                if (temperature) t("电池温度趋势", "BATTERY TEMPERATURE TREND")
                else t("功率趋势", "POWER TREND"),
                "history",
            )
        card.addView(
            ui.label(
                if (temperature) t("电池 · 实测 · °C", "Battery · measured · °C")
                else t("电芯净功率 · 推算 · W", "Net cell power · estimated · W"),
                13f,
                if (temperature) ui.orange else ui.blue,
            )
        )
        chart = ChartView(ui.context, temperature)
        card.addView(chart, LinearLayout.LayoutParams(-1, ui.dp(200)))
        card.addView(
            ui.label(
                t("最近 120 样本 · 采样缺口断线", "Latest 120 samples · gaps disconnected"),
                12f,
                ui.muted,
            )
        )
    }

    private fun thermal(status: Int?): String =
        when (status) {
            0 -> t("无热限频", "None")
            1 -> t("轻微", "Light")
            2 -> t("中等", "Moderate")
            3 -> t("严重", "Severe")
            4 -> t("危急", "Critical")
            5 -> t("紧急", "Emergency")
            6 -> t("关机级", "Shutdown")
            else -> t("不可用", "Unavailable")
        }
}
