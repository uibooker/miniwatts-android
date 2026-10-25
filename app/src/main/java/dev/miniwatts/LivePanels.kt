package dev.miniwatts

import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.miniwatts.core.CoolingDevice
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Session
import dev.miniwatts.core.ThermalGroup
import dev.miniwatts.core.ThermalSnapshot
import dev.miniwatts.core.ThermalZone

/** Live screens retain views so sampling never resets their scroll position. */
class LivePanels(
    private val ui: UiKit,
    private val words: Words,
    private val fahrenheit: Boolean,
    /** Latest kernel thermal snapshot; null until the reader has produced one. */
    private val thermal: () -> ThermalSnapshot? = { null },
    /** Opens the performance detail sub-page. */
    private val onOpenThermalDetail: () -> Unit = {},
    /** Session-only CPU frequency ratio history; never persisted. */
    private val perfHistory: () -> List<Double?> = { emptyList() },
) {
    private val bindings = mutableListOf<(Sample?) -> Unit>()
    private val sessionBindings = mutableListOf<(Session?) -> Unit>()
    private var chart: ChartView? = null
    private var chartCount: TextView? = null

    /**
     * Capacity estimate fields. They come from stored history, so they are filled asynchronously by
     * the activity rather than from the current sample.
     */
    var capacityValue: TextView? = null
    var capacityNote: TextView? = null

    private fun t(zh: String, en: String) = words.t(zh, en)
    private fun n(v: Number?, unit: String, digits: Int = 2) =
        v?.let { words.n(it, unit, digits) } ?: "— $unit"
    private fun temp(s: Sample?) =
        s?.temperatureDeciC?.let { words.temperature(it, fahrenheit) }
            ?: "— ${if (fahrenheit) "°F" else "°C"}"
    private fun bind(view: TextView, value: (Sample?) -> String) {
        bindings.add { view.text = value(it) }
    }

    fun update(s: Sample?, recent: List<Sample>) {
        bindings.forEach { it(s) }
        val latest = recent.lastOrNull()
        val window = recent.filter {
            latest != null && it.boot == latest.boot && it.monoMs >= latest.monoMs - 180_000
        }
        chart?.samples = window
        chartCount?.text = t("${window.size} 个样本", "${window.size} samples")
    }

    fun updateSession(session: Session?) = sessionBindings.forEach { it(session) }

    /**
     * A one-line badge for the adapter and device pages.
     *
     * The power page does not carry it: that page already shows the platform state and the cell's own
     * direction as two pills side by side, so a third line saying they disagree would repeat them.
     * The explanation behind either form lives in Help.
     */
    private fun conflictWarning(parent: LinearLayout) {
        val warning = ui.pill(parent, ui.orange)
        bindings.add { s ->
            val text =
                if (s?.signConflict == true) words.stateAndCellDisagree(s.status, s.powerW) else null
            warning.visibility = if (text == null) View.GONE else View.VISIBLE
            if (text != null) warning.text = text
        }
    }

    private fun level(parent: LinearLayout) {
        val row = LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        row.addView(ui.label(t("电池电量", "Battery level"), 14f, ui.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        bind(ui.number("—", 18f, ui.green).also { row.addView(it) }) { "${it?.level ?: "—"}%" }
        val bar = LevelBar(ui.context, ui)
        parent.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(4) })
        bindings.add {
            bar.fraction = it?.level?.div(100f)
            bar.contentDescription = t("电量 ${it?.level ?: "不可用"}%", "Charge ${it?.level ?: "unavailable"}%")
        }
    }

    fun power(parent: LinearLayout) {
        val hero = ui.card(parent)
        val gauge = PowerGauge(ui.context, ui)
        // Landscape gives the hero card the whole screen width; an unconstrained gauge then draws a
        // huge arc whose number ends up below the fold. Cap the gauge and center it instead.
        val gaugeWidth =
            minOf(
                ui.context.resources.displayMetrics.widthPixels - ui.dp(64),
                ui.dp(360),
            ).coerceAtLeast(ui.dp(240))
        val gaugeHolder = FrameLayout(ui.context)
        gaugeHolder.addView(
            gauge,
            FrameLayout.LayoutParams(gaugeWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        hero.addView(gaugeHolder, LinearLayout.LayoutParams(-1, -2))
        bindings.add { gauge.update(it?.powerW, it?.trustedInputW) }
        val states = LinearLayout(ui.context).apply {
            orientation = if (ui.context.resources.configuration.fontScale > 1.2f)
                LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        hero.addView(states)
        bind(ui.pill(states, ui.blue)) { words.status(it?.status) }
        // The cell's own direction. It is what lets the headline power be a magnitude: the platform's
        // state says "charging" whenever a charger is attached, which is not the same statement.
        val direction = ui.pill(states, ui.green)
        bindings.add { s ->
            direction.text = words.cellDirection(s?.powerW) ?: ""
            direction.visibility = if (s?.powerW == null) View.GONE else View.VISIBLE
            direction.setTextColor(if ((s?.powerW ?: 0.0) < 0) ui.orange else ui.green)
        }
        val state = ui.pill(states, ui.green)
        bind(state) { thermal(it?.thermal) }
        bindings.add { state.setTextColor(thermalColor(it?.thermal)) }

        val battery = ui.card(parent, t("电池", "Battery"), "battery")
        level(battery)
        val values = ui.metrics(battery,
            listOf(t("电芯电压", "Cell voltage"), t("电芯电流", "Cell current"), t("电芯温度", "Battery temp")),
            listOf(ui.ink, ui.green, ui.orange))
        bind(values[0]) { n(it?.voltageMv?.div(1000.0), "V") }
        bind(values[1]) { n(it?.currentUa?.div(1_000_000.0), "A") }
        bind(values[2], ::temp)
        bindings.add { values[2].setTextColor(temperatureColor(it?.temperatureDeciC)) }

        val path = ui.card(parent, t("供电路径", "Power path"), "charger")
        val power = ui.metrics(path, listOf(t("适配器输入（实测）", "Adapter input (measured)"), t("电芯净功率", "Net cell power")), listOf(ui.blue, ui.green))
        bind(power[0]) { n(it?.trustedInputW, "W") }
        // Headline cell power is shown as a magnitude, matching the gauge arc: direction is the charge
        // state, and the signed reading stays in the chart, the cell current and the exports.
        bind(power[1]) { n(words.magnitude(it?.powerW), "W") }
        val derived = ui.metrics(path, listOf(t("负载与损耗（推算）", "Load & losses (est.)"), t("电芯占比（推算）", "Cell share (est.)")), listOf(ui.orange, ui.orange))
        bind(derived[0]) { n(it?.loadW, "W") }
        bind(derived[1]) { n(it?.cellSharePct, "%", 0) }
        val pathNote = ui.label("", 12f, ui.muted)
        path.addView(pathNote)
        val ceilingNote = ui.label("", 12f, ui.muted)
        path.addView(ceilingNote)
        bindings.add { s ->
            pathNote.text =
                when {
                    s?.partialInputW != null -> words.inputLooksShort(s.partialInputW!!)
                    s?.adapter?.inputW == null ->
                        t(
                            "未取得输入侧实测读数，因此负载、损耗和电芯占比不可计算。缺失不是 0。",
                            "No measured input reading, so load, losses and cell share cannot be computed. Missing is not zero.",
                        )
                    s.cellSharePct == null ->
                        t(
                            "电芯此刻在净放电，没有「输入中进到电芯的比例」；负载与损耗仍按 实测输入 − 电芯净功率 给出。",
                            "The cell is being drained right now, so there is no share of the input that reached it; load and losses still come from measured input minus net cell power.",
                        )
                    else ->
                        t(
                            "负载与损耗 = 实测输入 − 电芯净功率，含系统功耗与转换损耗，不等于热量。",
                            "Load and losses = measured input − net cell power; it covers system draw and conversion losses, and is not heat.",
                        )
                }
            val ceiling = s?.adapter?.ceilingW
            ceilingNote.text =
                if (ceiling == null) t("适配器协商上限不可用。", "Adapter negotiated ceiling unavailable.")
                else
                    t(
                        "适配器协商上限 ${words.n(ceiling, "W")}：充电器上报的上限，不是此刻实际输入。",
                        "Adapter negotiated ceiling ${words.n(ceiling, "W")}: the charger's reported limit, not what it delivers now.",
                    )
        }
        trend(parent, false)

        val session = ui.card(parent, t("本次充电", "This session"), "history")
        val summary = ui.metrics(session, listOf(t("电芯净能量", "Net cell energy"), t("增加电量", "Charge gain")), listOf(ui.green, ui.ink))
        val covered = ui.label("", 12f, ui.muted)
        session.addView(covered)
        sessionBindings.add { s ->
            summary[0].text = n(s?.energyWh, "Wh", 3)
            summary[1].text = gain(s)
            covered.text = if (s == null) t("接入电源后自动建立会话", "Plug in to start a session")
                else t("${words.duration(s.lastWallMs - s.startMs)} · 实测覆盖 ${words.duration(s.coveredMs)} · 能量为带符号推算", "${words.duration(s.lastWallMs - s.startMs)} · covered ${words.duration(s.coveredMs)} · signed estimated energy")
        }
        val warning = ui.label(words.sessionSignConflict(), 12f, ui.orange)
        session.addView(warning)
        sessionBindings.add { warning.visibility = if (it?.signConflictObserved == true) View.VISIBLE else View.GONE }
        val inputSummary =
            ui.metrics(
                session,
                listOf(t("适配器输入", "Adapter input"), t("电芯获得比例", "Cell share of input")),
                listOf(ui.blue, ui.green),
            )
        val inputNote = ui.label("", 12f, ui.muted)
        session.addView(inputNote)
        sessionBindings.add { s ->
            inputSummary[0].text =
                if (s?.hasInputCoverage == true) n(s.inputWh, "Wh", 3) else t("不可用", "Unavailable")
            inputSummary[1].text = n(s?.cellShareOfInputPct, "%", 0)
            inputNote.text =
                when {
                    s == null -> ""
                    !s.hasInputCoverage ->
                        t(
                            "适配器输入能量需要特权通道；本次会话没有实测输入，因此不显示 0。",
                            "Adapter input energy needs the privileged channel; this session has no measured input, so it shows nothing rather than a zero.",
                        )
                    s.energyWh <= 0 ->
                        t(
                            "本次会话电芯净放电（净能量 ${words.n(s.energyWh, "Wh", 3)}，输入 ${words.n(s.inputWh, "Wh", 3)}），没有「进到电芯的比例」可言；两个数值本身照常给出。",
                            "The cell was net discharged over this session (net ${words.n(s.energyWh, "Wh", 3)} against ${words.n(s.inputWh, "Wh", 3)} delivered), so there is no share that reached it; both figures are still reported.",
                        )
                    s.cellShareOfInputPct == null ->
                        t(
                            "输入只实测覆盖了 ${words.duration(s.inputCoveredMs)}，不足电芯窗口（${words.duration(s.coveredMs)}）的一半，两个不同窗口的比值不算比值，因此不给比例。",
                            "The input was only measured over ${words.duration(s.inputCoveredMs)}, less than half of the cell window (${words.duration(s.coveredMs)}); a ratio between two different windows is not a ratio, so it is withheld.",
                        )
                    else ->
                        t(
                            "输入实测覆盖 ${words.duration(s.inputCoveredMs)}。比例 = 电芯净能量 ÷ 适配器输入，刻意不叫效率：它可能大于 100%，也可能为负——插着电但系统耗电超过输入时，电芯确实在净放电。",
                            "Input measured over ${words.duration(s.inputCoveredMs)}. Share = net cell energy ÷ adapter input, deliberately not called an efficiency: it can exceed 100% or be negative, because a plugged-in phone whose system draws more than the input really does net-discharge its cell.",
                        )
                }
        }
        updateSession(null)
    }

    fun temperature(parent: LinearLayout) {
        val state = ui.card(parent, t("系统发热状态", "System thermal state"), "thermal")
        val status = ui.number("—", 26f, ui.green).also { state.addView(it) }
        bind(status) { thermal(it?.thermal) }
        bindings.add { status.setTextColor(thermalColor(it?.thermal)) }
        state.addView(ui.label(t("系统报告的热压力等级，温度读数见下方。", "System-reported thermal pressure. Temperature readings are below."), 12f, ui.muted))
        val stages = LinearLayout(ui.context)
        state.addView(stages, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(10) })
        val bars = (0..6).map { i ->
            LevelBar(ui.context, ui, thermalColor(i)).also {
                stages.addView(it, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = ui.dp(2) })
            }
        }
        bindings.add { s ->
            bars.forEachIndexed { i, bar -> bar.fraction = if (s?.thermal?.let { i <= it } == true) 1f else null }
            stages.contentDescription = thermal(s?.thermal)
        }
        state.addView(ui.label(t("正常   ·   轻度发热   ·   严重发热   ·   关机级", "Normal · Light · Severe · Shutdown"), 10f, ui.muted))

        val map = ui.card(parent, t("热力图 · 示意图", "Thermal schematic"), "phone")
        val schematic = BatterySchematic(ui.context, ui)
        map.addView(schematic, LinearLayout.LayoutParams(-1, ui.dp(210)))
        bindings.add { schematic.temperature = it?.temperatureDeciC }
        val metrics = ui.metrics(map, listOf(t("电池", "Battery"), t("充电芯片", "Charger IC"), t("SoC 芯片", "SoC")), listOf(ui.orange, ui.blue, ui.blue))
        bind(metrics[0], ::temp)
        bindings.add { metrics[0].setTextColor(temperatureColor(it?.temperatureDeciC)) }
        val chipCell = metrics[1]
        val socCell = metrics[2]
        val mapNote = ui.label("", 12f, ui.muted)
        map.addView(mapNote)
        bindings.add {
            val snapshot = thermal()
            val chip = snapshot?.hottest(ThermalGroup.CHARGER)
            val soc = snapshot?.hottest(ThermalGroup.CPU)
            chipCell.text = zoneValue(chip)
            chipCell.setTextColor(zoneColor(chip?.deciC))
            socCell.text = zoneValue(soc)
            socCell.setTextColor(zoneColor(soc?.deciC))
            mapNote.text =
                if (snapshot?.available == true)
                    t("电池取电芯温度；充电芯片与 SoC 取对应热区当前最高值。标记位置为示意，不代表本机内部布局。", "Battery is the cell sensor; charger IC and SoC take the hottest zone in their group. The marker is schematic, not a device layout.")
                else
                    t("仅电芯温度可读：未枚举到可读热区。标记位置为示意，不代表本机内部布局。", "Only the cell sensor is readable: no thermal zone could be enumerated. The marker is schematic, not a device layout.")
        }

        val probes = ui.card(parent, t("温度探头", "Thermal probes"), "thermal")
        // The group name is its own left column instead of being padded inside one monospace blob:
        // padEnd counts characters, but the platform renders CJK from a proportional fallback font,
        // so "电池" and "充电与接口" never line up by character count. The right column stays pure
        // ASCII, where monospace padding is exact.
        val probeColumns = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
        probes.addView(probeColumns)
        var probeSignature: String? = null
        var probeValues = listOf<TextView>()
        val probeNote = ui.label("", 12f, ui.muted)
        probes.addView(probeNote)
        bindings.add {
            val snapshot = thermal()
            val rows = snapshot?.presentGroups.orEmpty().mapNotNull { group ->
                snapshot!!.hottest(group)?.let { group to it }
            }
            val signature = rows.joinToString("|") { it.second.name }
            if (signature != probeSignature) {
                probeSignature = signature
                probeColumns.removeAllViews()
                probeValues =
                    rows.map { (group, _) ->
                        val row =
                            LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL }
                        probeColumns.addView(row, LinearLayout.LayoutParams(-1, -2))
                        row.addView(
                            ui.label(words.thermalGroup(group), 12f, ui.muted),
                            LinearLayout.LayoutParams(0, -2, 0.32f),
                        )
                        ui.label("", 11f, ui.ink).also { value ->
                            value.typeface = Typeface.MONOSPACE
                            value.maxLines = 1
                            value.ellipsize = android.text.TextUtils.TruncateAt.END
                            row.addView(value, LinearLayout.LayoutParams(0, -2, 0.68f))
                        }
                    }
            }
            rows.forEachIndexed { index, (_, zone) ->
                probeValues.getOrNull(index)?.let { value ->
                    value.text = "${zone.name.padEnd(PROBE_NAME_COLUMNS)}${zoneValue(zone)}"
                    value.setTextColor(zoneColor(zone.deciC))
                }
            }
            if (rows.isEmpty())
                probeNote.text =
                    if (snapshot == null) t("温度探头不可用。", "Thermal probes unavailable.")
                    else t("未枚举到可读热区。", "No readable thermal zone was enumerated.")
            else
                probeNote.text =
                    if (snapshot == null) t("温度探头不可用。", "Thermal probes unavailable.")
                    else
                        t(
                            "每组显示当前最热的探头。共 ${snapshot.measured.size} 个探头可读；${snapshot.skipped.size} 个节点不是温度（电池电压/电流/百分比），${snapshot.denied.size} 个节点被系统拒绝。",
                            "Each row is that group's hottest probe. ${snapshot.measured.size} readable probes; ${snapshot.skipped.size} nodes are not temperatures (battery voltage/current/percent) and ${snapshot.denied.size} nodes were refused by the system.",
                        )
        }

        val cooling = ui.card(parent, t("热缓解", "Thermal mitigation"), "thermal")
        val coolingCount = ui.number("—", 22f, ui.green)
        cooling.addView(coolingCount)
        val coolingList = ui.label("", 13f, ui.muted)
        coolingList.typeface = Typeface.MONOSPACE
        cooling.addView(coolingList)
        cooling.addView(ui.label(t("上方为当前生效档位数 / 内核暴露的档位总数。档位大于 0 表示该项正在生效，可能是限频、CPU 热插拔或背光调节。", "Above: active levels / total levels the kernel exposes. A level above 0 means that item is currently applied, which can be a frequency cap, CPU offlining or a backlight adjustment."), 12f, ui.muted))
        bindings.add {
            val snapshot = thermal()
            val active = snapshot?.activeCooling.orEmpty()
            coolingCount.text = if (snapshot == null) "—" else "${active.size} / ${snapshot.cooling.size}"
            coolingCount.setTextColor(if (active.isEmpty()) ui.green else ui.orange)
            coolingList.text =
                when {
                    snapshot == null -> t("不可用", "Unavailable")
                    snapshot.cooling.isEmpty() -> t("本机未暴露 cooling device。", "This device exposes no cooling devices.")
                    active.isEmpty() -> t("当前无热缓解生效。", "No mitigation is active right now.")
                    else -> {
                        // Pad to the longest active name so the levels form a column instead of
                        // trailing each name at a different offset.
                        val width = active.maxOf { it.name.length } + 2
                        active.joinToString("\n") { "${it.name.padEnd(width)}${it.current}/${it.max}" }
                    }
                }
        }

        val cpuCard = ui.card(parent, t("CPU 频率", "CPU frequency"), "thermal")
        val cpuList = ui.label("", 13f, ui.ink)
        cpuList.typeface = Typeface.MONOSPACE
        cpuCard.addView(cpuList)
        cpuCard.addView(ui.label(t("按内核调频策略汇总，当前值取该策略内某一 CPU 的实时频率。", "Grouped by kernel frequency policy; the current value is a live CPU frequency inside that policy."), 12f, ui.muted))
        bindings.add {
            val snapshot = thermal()
            val policies =
                snapshot?.cpu?.filter { it.currentKhz != null || it.maxKhz != null }.orEmpty()
            cpuList.text =
                if (policies.isEmpty()) t("不可用", "Unavailable")
                else {
                    // Both columns are padded so the separator and the maxima line up; a policy whose
                    // current frequency is unreadable still shows "—" in the same column.
                    val nameWidth = policies.maxOf { it.name.length } + 2
                    val valueWidth = policies.maxOf { ghz(it.maxKhz).length }
                    policies.joinToString("\n") { policy ->
                        "${policy.name.padEnd(nameWidth)}${ghz(policy.currentKhz).padStart(valueWidth)} / ${ghz(policy.maxKhz)}"
                    }
                }
        }

        trend(parent, true)
        val battery = ui.card(parent, t("电池", "Battery"), "battery")
        bind(ui.row(battery, t("电芯温度", "Battery temperature"), "—", ui.orange), ::temp)
        val bar = LevelBar(ui.context, ui, ui.orange)
        battery.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(6) })
        bindings.add { bar.fraction = it?.temperatureDeciC?.let { value -> ((value / 10f - 20) / 40).coerceIn(0f, 1f) } }
        battery.addView(ui.label(t("电池传感器 · 刻度 20–60 °C", "Battery sensor · scale 20–60 °C"), 11f, ui.muted))

        val gaps = ui.card(parent, t("仍不可用的读数", "Still unavailable"), "device")
        for (name in listOf(t("环境温度探头", "Ambient temperature probe"), t("GPU 占用率", "GPU utilization"), t("Wi-Fi / 基带吞吐", "Wi-Fi / modem throughput")))
            ui.row(gaps, name, t("不可用", "Unavailable"), ui.muted)
        gaps.addView(ui.label(t("本机传感器表里没有环境温度探头；GPU 占用率节点被 SELinux 拒绝。热区只提供温度，不提供功耗。", "This device has no ambient temperature sensor, and the GPU utilization node is refused by SELinux. Thermal zones give temperature, not power."), 12f, ui.muted))
        ui.action(parent, t("全部热区与调频详情", "All thermal zones and frequency detail"), "next") {
            onOpenThermalDetail()
        }
    }

    /**
     * Performance detail page. Everything here is permission-free kernel data; the frequency history
     * is session-only and is never written to the database.
     */
    fun thermalDetail(parent: LinearLayout) {
        val cpu = ui.card(parent, t("CPU 调频策略", "CPU frequency policies"), "thermal")
        val cpuRows = ui.column()
        cpu.addView(cpuRows)
        var cpuSignature: String? = null
        var cpuBars = listOf<Pair<TextView, LevelBar>>()

        val cooling = ui.card(parent, t("调频与热缓解档位", "Frequency caps and mitigation levels"), "thermal")
        val coolingCount = ui.number("—", 24f, ui.green)
        cooling.addView(coolingCount)
        val coolingList = ui.label("", 12f, ui.ink).apply { typeface = Typeface.MONOSPACE }
        cooling.addView(coolingList)
        val coolingNote = ui.label("", 12f, ui.muted)
        cooling.addView(coolingNote)

        val zones = ui.card(parent, t("全部热区", "All thermal zones"), "thermal")
        val zoneList = ui.label("", 12f, ui.ink).apply { typeface = Typeface.MONOSPACE }
        zones.addView(zoneList)
        val zoneNote = ui.label("", 12f, ui.muted)
        zones.addView(zoneNote)

        val history = ui.card(parent, t("CPU 频率比例（本次会话）", "CPU frequency ratio (this session)"), "history")
        val bars = RatioHistoryView(ui.context, ui, ui.violet)
        history.addView(bars, LinearLayout.LayoutParams(-1, ui.dp(56)))
        val historyNote = ui.label("", 12f, ui.muted)
        history.addView(historyNote)
        history.addView(ui.label(t("取各调频策略 当前/最高 的最大值。比例偏低可能是空闲，也可能是被限频，需结合上方档位判断。本图不落库，重启或长时间停止采样后从头开始。", "Maximum of current/max across the frequency policies. A low ratio can mean idle or capped, so read it together with the levels above. This chart is not persisted; it restarts after a reboot or a long pause."), 12f, ui.muted))

        bindings.add {
            val snapshot = thermal()
            val policies = snapshot?.cpu.orEmpty().filter { it.currentKhz != null || it.maxKhz != null }
            val signature = policies.joinToString("|") { it.name }
            if (signature != cpuSignature) {
                cpuSignature = signature
                cpuRows.removeAllViews()
                cpuBars =
                    policies.map { _ ->
                        val cell = ui.column().apply { setPadding(0, ui.dp(6), 0, ui.dp(2)) }
                        val value = ui.label("", 13f, ui.ink)
                        cell.addView(value)
                        val bar = LevelBar(ui.context, ui, ui.violet)
                        cell.addView(
                            bar,
                            LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(4) },
                        )
                        cpuRows.addView(cell)
                        value to bar
                    }
            }
            cpuBars.forEachIndexed { index, (value, bar) ->
                val policy = policies.getOrNull(index)
                value.text =
                    if (policy == null) t("不可用", "Unavailable")
                    else "${policy.name}   ${ghz(policy.currentKhz)} / ${ghz(policy.maxKhz)}"
                bar.fraction = policy?.ratio?.toFloat()
            }

            val active = snapshot?.activeCooling.orEmpty()
            val caps = active.count { it.name.startsWith("cpufreq-") }
            coolingCount.text =
                if (snapshot == null) "—"
                else
                    t(
                        "限频 ${caps} 项 · 生效 ${active.size} / ${snapshot.cooling.size}",
                        "$caps capped · ${active.size} active / ${snapshot.cooling.size}",
                    )
            coolingCount.setTextColor(if (caps > 0) ui.orange else ui.green)
            // A phone can expose 60+ cooling devices and almost all of them sit at zero, so listing
            // every one buries the zone table below. Active levels plus every frequency cap are the
            // ones that carry information; the complete table lives in the diagnostics export.
            val listed = snapshot?.cooling?.filter { it.active || it.name.startsWith("cpufreq-") }
            val hidden = (snapshot?.cooling?.size ?: 0) - (listed?.size ?: 0)
            coolingList.text =
                listed
                    ?.sortedWith(compareByDescending<CoolingDevice> { it.active }.thenBy { it.name })
                    ?.joinToString("\n") { device ->
                        "${if (device.active) "●" else "○"} ${device.name.padEnd(22)}${device.current ?: "-"}/${device.max ?: "-"}"
                    }
                    ?.ifEmpty { t("本机未暴露 cooling device。", "This device exposes no cooling devices.") }
                    ?: t("不可用", "Unavailable")
            if (snapshot != null)
                coolingNote.text =
                    t(
                        "● 表示该档位当前生效。cpufreq-* 是内核对该调频策略设置的上限，始终列出。另有 $hidden 项未生效的缓解项未在此展开，完整表在「设置 → 诊断 → 导出诊断 JSON」里。",
                        "● means the level is currently applied. cpufreq-* caps that policy's frequency and is always listed. Another $hidden inactive mitigation entries are not expanded here; the complete table is in Settings → Diagnostics → Export diagnostics JSON.",
                    )

            zoneList.text =
                snapshot?.measured
                    ?.sortedByDescending { it.deciC ?: Int.MIN_VALUE }
                    ?.joinToString("\n") { zone ->
                        "${words.temperature(zone.deciC, fahrenheit).padStart(8)}  ${zone.name.padEnd(22)}${words.thermalGroup(zone.group)}"
                    }
                    ?.ifEmpty { t("不可用", "Unavailable") }
                    ?: t("不可用", "Unavailable")
            zoneNote.text =
                if (snapshot == null) t("尚未读取热区。", "Thermal zones have not been read yet.")
                else
                    t(
                        "共 ${snapshot.measured.size} 个可读热区；${snapshot.skipped.size} 个非温度节点（${snapshot.skipped.joinToString("、")}）和 ${snapshot.denied.size} 个被拒节点（${snapshot.denied.joinToString("、").ifEmpty { "无" }}）未列入。",
                        "${snapshot.measured.size} readable zones; ${snapshot.skipped.size} non-temperature nodes (${snapshot.skipped.joinToString(", ")}) and ${snapshot.denied.size} refused nodes (${snapshot.denied.joinToString(", ").ifEmpty { "none" }}) are not listed.",
                    )

            bars.values = perfHistory()
            historyNote.text =
                t(
                    "${bars.values.count { it != null }} 个采样点（约每 2 秒一点，最多 90 点）",
                    "${bars.values.count { it != null }} points (about one every 2 s, up to 90)",
                )
        }
    }

    fun charger(parent: LinearLayout) {
        val hero = ui.card(parent, t("实际功率与额定功率", "Actual & rated power"), "charger")
        val headline = ui.number("— / —", 34f, ui.blue)
        hero.addView(headline)
        bindings.add { s ->
            headline.text = "${n(s?.trustedInputW, "W")} / ${n(s?.adapter?.ceilingW, "W")}"
        }
        val utilization = ui.row(hero, t("适配器利用率", "Adapter utilization"), "— %", ui.orange)
        bindings.add { s ->
            val input = s?.trustedInputW
            val ceilingW = s?.adapter?.ceilingW
            utilization.text =
                if (input != null && ceilingW != null && ceilingW > 0)
                    words.n(input * 100 / ceilingW, "%", 0)
                else t("不可用", "Unavailable")
        }
        hero.addView(ui.label(t("左：输入侧实测功率（需特权通道）。右：系统上报的充电器协商上限。", "Left: measured input power (needs the privileged channel). Right: the charger ceiling the platform reports."), 12f, ui.muted))

        val handshake = ui.card(parent, t("握手信息", "Handshake"), "info")
        bind(ui.row(handshake, t("电源来源", "Power source"), "—")) { words.source(it?.plugged) }
        bind(ui.row(handshake, t("真实类型", "Real type"), "—", ui.muted)) {
            it?.adapter?.realType ?: t("不可用", "Unavailable")
        }
        bind(ui.row(handshake, t("供电角色", "Power role"), "—", ui.muted)) {
            if (it?.plugged == 0) t("未连接", "Not connected") else words.role(it?.adapter?.powerRole)
        }
        bind(ui.row(handshake, t("快充类型（小米）", "Quick-charge type (MIUI)"), "—", ui.muted)) {
            words.quickChargeType(it?.adapter?.quickChargeType)
        }
        bind(ui.row(handshake, t("上报档位（小米）", "Reported tier (MIUI)"), "—", ui.muted)) {
            it?.adapter?.advertisedW?.let { w -> "$w W" } ?: t("不可用", "Unavailable")
        }
        bind(ui.row(handshake, t("合规标记", "Compliance"), "—", ui.muted)) {
            words.invalidCharger(it?.adapter?.invalid)
        }
        handshake.addView(ui.label(t("AC / USB 是系统电源分类，不能据此判断 USB-C 接口或 PD 协议。", "AC / USB are system power categories; they do not identify a USB-C connector or PD protocol."), 12f, ui.muted))
        handshake.addView(ui.label(words.advertisedNote(), 12f, ui.muted))

        val ceiling = ui.card(parent, t("协商上限", "Negotiated ceiling"), "info")
        val limits =
            ui.metrics(
                ceiling,
                listOf(t("最大充电电压", "Max charging voltage"), t("最大充电电流", "Max charging current")),
                listOf(ui.blue, ui.blue),
            )
        bind(limits[0]) { n(it?.adapter?.maxVoltageUv?.div(1_000_000.0), "V") }
        bind(limits[1]) { n(it?.adapter?.maxCurrentUa?.div(1_000_000.0), "A") }
        bind(ui.row(ceiling, t("上限功率", "Ceiling power"), "—", ui.blue)) {
            n(it?.adapter?.ceilingW, "W")
        }
        ceiling.addView(ui.label(words.ceilingNote(), 12f, ui.muted))

        val profiles = ui.card(parent, t("广播供电规格", "Advertised profiles"), "info")
        bind(ui.row(profiles, t("PD 状态机", "PD state"), "—", ui.muted)) {
            it?.adapter?.pdState ?: t("不可用", "Unavailable")
        }
        bind(ui.row(profiles, t("PD 认证", "PD authentication"), "—", ui.muted)) {
            when (it?.adapter?.pdVerified) {
                null -> t("不可用", "Unavailable")
                0 -> t("未通过 / 未认证", "Not verified")
                else -> t("已认证", "Verified")
            }
        }
        ui.row(profiles, t("PDO 逐档列表", "PDO list"), t("不可用", "Unavailable"), ui.muted)
        profiles.addView(ui.label(t("本机内核只暴露端口角色与 PD 版本，没有适配器的 PDO/PPS 档位；Android 也没有公开 API。", "This kernel exposes the port role and PD revision only, not the adapter's PDO/PPS objects, and Android has no public API for them."), 12f, ui.muted))

        val live = ui.card(parent, t("实时供电轨", "Live power rails"), "history")
        bind(ui.row(live, t("适配器输入", "Adapter input"), "— V × — A", ui.muted)) {
            val volts = it?.adapter?.inputVoltageUv
            val amps = it?.adapter?.inputCurrentUa
            if (volts == null || amps == null) "— V × — A"
            else "${words.n(volts / 1_000_000.0, "V")} × ${words.n(amps / 1_000_000.0, "A")}"
        }
        bind(ui.row(live, t("适配器输入功率", "Adapter input power"), "— W", ui.blue)) {
            n(it?.trustedInputW, "W")
        }
        val values = ui.metrics(live, listOf(t("电池侧电压", "Cell voltage"), t("电池侧电流", "Cell current"), t("电芯净功率", "Net cell power")), listOf(ui.ink, ui.green, ui.green))
        bind(values[0]) { n(it?.voltageMv?.div(1000.0), "V") }
        bind(values[1]) { n(it?.currentUa?.div(1_000_000.0), "A") }
        bind(values[2]) { n(words.magnitude(it?.powerW), "W") }
        conflictWarning(live)
        bind(ui.label("", 11f, ui.muted).also { live.addView(it) }) {
            val source = it?.adapter?.source
            if (source == null) t("适配器数据来源：不可用", "Adapter data source: unavailable")
            else t("适配器数据来源：$source", "Adapter data source: $source")
        }
        live.addView(ui.label(words.privilegedNote(), 12f, ui.muted))

        val cable = ui.card(parent, t("线缆与连接", "Cable & connection"), "charger")
        ui.row(cable, t("通路电阻", "Path resistance"), "— mΩ", ui.muted)
        ui.row(cable, t("电压损失", "Voltage drop"), "— V", ui.muted)
        ui.row(cable, t("通路损耗", "Path loss"), "— W", ui.muted)
        cable.addView(ui.label(t("缺少适配器侧的额定电压与成对读数，无法估算线缆阻抗和损耗。", "The adapter's rated voltage and paired readings are missing, so cable resistance and losses cannot be estimated."), 12f, ui.muted))
    }

    fun device(parent: LinearLayout) {
        val device = ui.card(parent, t("本机", "This device"), "phone")
        val identity = ui.label("${Build.MANUFACTURER} ${Build.MODEL}", 13f, ui.muted)
        device.addView(identity)
        val row = LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(IconView(ui.context, "phone", ui.blue), LinearLayout.LayoutParams(ui.dp(40), ui.dp(54)).apply { marginEnd = ui.dp(12) })
        val status = ui.column()
        row.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bind(ui.label("—", 20f, ui.ink, true).also { status.addView(it) }) { words.status(it?.status) }
        bind(ui.label("—", 13f, ui.muted).also { status.addView(it) }) {
            n(words.magnitude(it?.powerW), "W") + " " + t("电芯净功率", "net cell power")
        }
        val percent = ui.number("—", 38f, ui.ink)
        row.addView(percent)
        bind(percent) { "${it?.level ?: "—"}%" }
        device.addView(row)
        val bar = LevelBar(ui.context, ui)
        device.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { topMargin = ui.dp(12) })
        bindings.add { bar.fraction = it?.level?.div(100f) }
        conflictWarning(device)
        val battery = ui.card(parent, t("电池信息", "Battery information"), "battery")
        bind(ui.row(battery, t("健康状态", "Health status"), "—")) { words.health(it?.health) }
        bind(ui.row(battery, t("循环次数", "Cycle count"), "—")) { it?.cycles?.toString() ?: t("不可用", "Unavailable") }
        bind(ui.row(battery, t("电荷计数器", "Charge counter"), "—")) { n(it?.chargeUah?.div(1000.0), "mAh", 0) }
        bind(ui.row(battery, t("容量健康度（推算）", "Capacity health (est.)"), "—", ui.muted)) {
            n(it?.adapter?.capacityHealthPct, "%", 0)
        }
        bind(ui.row(battery, t("满充容量 / 设计容量", "Full / design capacity"), "—", ui.muted)) {
            val full = it?.adapter?.fullChargeUah
            val design = it?.adapter?.designCapacityUah
            if (full == null || design == null) t("不可用", "Unavailable")
            else "${words.n(full / 1000.0, "mAh", 0)} / ${words.n(design / 1000.0, "mAh", 0)}"
        }
        bind(ui.row(battery, t("电池 SOH（厂商）", "Battery SOH (vendor)"), "—", ui.muted)) {
            it?.adapter?.sohPercent?.let { v -> "$v %" } ?: t("不可用", "Unavailable")
        }
        battery.addView(ui.label(t("电荷计数器表示当前电荷，不等于设计容量或本次充入电量。容量健康度需要满充容量与设计容量，只有特权通道能读到。", "The charge counter is the present charge, not the design capacity or what this session added. Capacity health needs both the full-charge and design capacity, which only the privileged channel can read."), 12f, ui.muted))

        val estimate = ui.card(parent, t("容量估算（无特权通道时）", "Capacity estimate (without the privileged channel)"), "battery")
        capacityValue = ui.number("…", 26f, ui.muted)
        estimate.addView(capacityValue)
        capacityNote = ui.label(t("正在统计历史充电记录…", "Reading charging history…"), 12f, ui.ink)
        estimate.addView(capacityNote)
        estimate.addView(ui.label(t("算法：满充容量 ≈ 充入电荷 ÷ 电量上升百分比，取多次充电记录的中位数。它假设电荷计数器与电量计线性，只在恒流段成立，接近满电的恒压段会低估；需要一次电量上升 ≥20% 且电荷计数器可用的充电才有结果。这是估算，不是实测——若上一张卡已通过特权通道读到真实满充/设计容量，请以那一行为准。", "Method: full capacity ≈ charge added ÷ state-of-charge rise, median over several charging sessions. It assumes the charge counter and the fuel gauge are linear in the state of charge, which only holds in the constant-current region, and it under-reads near full charge in the CV phase. A session needs a rise of at least 20% with the charge counter available to count. This is an estimate, not a measurement — where the card above has real full/design capacities from the privileged channel, prefer that row."), 12f, ui.muted))
        val accessories = ui.card(parent, t("配件", "Accessories"), "device")
        accessories.addView(ui.label(t("本版本暂不支持读取耳机等配件电量。", "Accessory battery readings are not supported in this version."), 14f, ui.muted))
        val system = ui.card(parent, t("系统", "System"), "info")
        ui.row(system, "Android", "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        ui.row(system, t("补丁级别", "Security patch"), Build.VERSION.SECURITY_PATCH ?: t("不可用", "Unavailable"))
        // The full kernel string repeats build hashes and wraps over several lines; the diagnostics
        // export keeps it verbatim.
        ui.row(system, t("内核", "Kernel"), kernelShort() ?: t("不可用", "Unavailable"))
        ui.row(system, t("电池技术", "Battery technology"), technology ?: t("不可用", "Unavailable"))
    }

    private fun kernelShort(): String? =
        System.getProperty("os.version")?.split('-')?.take(3)?.joinToString("-")?.takeIf { it.isNotBlank() }

    /** Static per device, so it is read once when the page is built rather than per sample. */
    private val technology: String? by lazy {
        runCatching {
                ui.context
                    .registerReceiver(
                        null,
                        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
                    )
                    ?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY)
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun trend(parent: LinearLayout, temperature: Boolean) {
        val card = ui.card(parent, if (temperature) t("电池温度（最近 3 分钟）", "Battery temperature (last 3 min)") else t("最近 3 分钟", "Last 3 minutes"), "history")
        chartCount = ui.label("", 11f, ui.muted).also { card.addView(it) }
        // The power chart also draws the measured adapter input when the privileged channel provided
        // it; the temperature chart has nothing to overlay.
        chart = ChartView(ui.context, temperature, adapterInput = !temperature)
        card.addView(chart, LinearLayout.LayoutParams(-1, ui.dp(170)))
        card.addView(ui.label(if (temperature) t("━ 电池温度 °C", "━ Battery temperature °C") else t("━ 电芯净功率 W · 推算", "━ Net cell power W · estimated"), 12f, if (temperature) ui.orange else ui.blue))
        if (!temperature) {
            val inputLegend = ui.label(t("━ 适配器输入 W · 实测（需特权通道）", "━ Adapter input W · measured (needs the privileged channel)"), 12f, ui.green)
            card.addView(inputLegend)
            bindings.add { s ->
                inputLegend.visibility = if (s?.trustedInputW == null) View.GONE else View.VISIBLE
            }
        }
        card.addView(ui.label(t("采样缺口断线", "Sampling gaps are disconnected"), 11f, ui.muted))
    }

    private fun gain(s: Session?): String {
        val start = s?.startLevel ?: return "— %"
        val end = s.endLevel ?: return "— %"
        return "${if (end >= start) "+" else ""}${end - start} %"
    }

    private fun temperatureColor(deci: Int?) = when {
        deci == null -> ui.muted
        deci >= 450 -> ui.red
        deci >= 360 -> ui.orange
        else -> ui.green
    }

    /** Longest per-group zone name on the reference device (`pmih010x_lite_tz`) plus one space. */
    private val PROBE_NAME_COLUMNS = 17

    /** A thermal zone value; an absent sensor is never shown as a number. */
    private fun zoneValue(zone: ThermalZone?): String =
        when {
            zone == null -> t("不可用", "Unavailable")
            zone.deciC == null -> t("传感器缺失", "Sensor absent")
            else -> words.temperature(zone.deciC, fahrenheit)
        }

    private fun zoneColor(deci: Int?) = temperatureColor(deci)

    private fun ghz(khz: Int?): String =
        khz?.let { String.format(java.util.Locale.getDefault(), "%.2f GHz", it / 1_000_000.0) }
            ?: "—"
    private fun thermalColor(status: Int?) = when (status) {
        null -> ui.muted
        0 -> ui.green
        1, 2 -> ui.orange
        else -> ui.red
    }
    private fun thermal(status: Int?): String = when (status) {
        0 -> t("正常", "Normal")
        1 -> t("轻度发热", "Light")
        2 -> t("中度发热", "Moderate")
        3 -> t("严重发热", "Severe")
        4 -> t("危急", "Critical")
        5 -> t("紧急", "Emergency")
        6 -> t("关机级", "Shutdown")
        else -> t("不可用", "Unavailable")
    }
}
