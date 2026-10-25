package dev.miniwatts

import android.view.Gravity
import android.widget.LinearLayout
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Session

class HistoryPanels(private val ui: UiKit, private val words: Words) {
    private fun t(zh: String, en: String) = words.t(zh, en)
    private fun duration(ms: Long) = words.duration(ms)

    /**
     * Measured adapter input energy, or "unavailable".
     *
     * The rail is only readable through the optional privileged channel, so a session can have none;
     * that prints as unavailable and never as a zero.
     */
    private fun inputEnergy(s: Session): String =
        if (s.hasInputCoverage) words.n(s.inputWh, "Wh", 3) else t("不可用", "Unavailable")

    /** The cell's share of the measured input, withheld whenever the app will not derive it. */
    private fun cellShare(s: Session): String =
        s.cellShareOfInputPct?.let { words.n(it, "%", 1) } ?: t("不可用", "Unavailable")
    private fun conflictWarning(parent: LinearLayout, session: Session) {
        if (session.signConflictObserved)
            parent.addView(ui.label(words.sessionSignConflict(), 12f, ui.orange))
    }

    /**
     * [selected] drives the comparison selection: when it is non-null every card carries an explicit
     * "select" action and the cards already picked are marked. A long press opens the detail view, so
     * one session is still reachable while the selection is active.
     */
    fun list(
        parent: LinearLayout,
        rows: List<Session>,
        previews: Map<Long, List<Sample>>,
        open: (Session) -> Unit,
        selected: Set<Long>? = null,
        onSelect: (Session) -> Unit = {},
    ) {
        val current = rows.firstOrNull { it.endMs == null }
        if (current != null) {
            val active = ui.card(parent, t("进行中", "In progress"), "power")
            active.background = ui.shape().apply { setStroke(ui.dp(1), ui.blendWithSurface(ui.blue)) }
            active.addView(ui.label(duration(current.lastWallMs - current.startMs), 12f, ui.muted))
            val values = ui.metrics(active,
                listOf(t("适配器输入", "Adapter input"), t("电芯净能量", "Net cell energy"), t("增加电量", "Charge gain")),
                listOf(ui.blue, ui.green, ui.ink))
            values[0].text = inputEnergy(current)
            values[1].text = words.n(current.energyWh, "Wh", 3)
            values[2].text = gain(current)
            active.addView(ChartView(ui.context).apply { samples = previews[current.id].orEmpty() }, LinearLayout.LayoutParams(-1, ui.dp(136)))
            active.addView(ui.label(t("实测覆盖 ${duration(current.coveredMs)} · 电芯净功率曲线", "Covered ${duration(current.coveredMs)} · net cell power"), 11f, ui.muted))
            conflictWarning(active, current)
            compareAction(active, current, selected, onSelect)
            active.isFocusable = true
            active.setOnClickListener { open(current) }
            active.contentDescription = t("当前会话，查看详情", "Current session, view details")
        }
        val summary = ui.card(parent, t("所有会话", "All sessions"), "history")
        val totals = ui.metrics(summary,
            listOf(t("电芯净能量", "Net cell energy"), t("有效覆盖", "Covered time"), t("会话数", "Sessions")),
            listOf(ui.green, ui.ink, ui.ink))
        totals[0].text = words.n(rows.sumOf { it.energyWh }, "Wh", 3)
        totals[1].text = duration(rows.sumOf { it.coveredMs })
        totals[2].text = rows.size.toString()
        if (rows.any { it.signConflictObserved }) summary.addView(ui.label(words.summarySignConflict(), 12f, ui.orange))
        if (rows.isEmpty()) ui.note(parent, t("接入电源后自动建立充电会话，历史会保存在本机。", "Plug in to start a charging session. History is saved on this device."))
        rows.filter { it.id != current?.id }.forEach { s ->
            val card = ui.card(parent)
            val heading = LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(ui.label(t("充电会话", "Charging session"), 17f, ui.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(ui.number(words.n(s.energyWh, "Wh", 3), 21f, ui.blue))
            card.addView(heading)
            val info = LinearLayout(ui.context)
            info.addView(ui.label("${words.time(s.startMs)} · ${duration((s.endMs ?: s.lastWallMs) - s.startMs)}", 11f, ui.muted), LinearLayout.LayoutParams(0, -2, 1f))
            info.addView(ui.label("${s.startLevel ?: "—"}% → ${s.endLevel ?: "—"}%", 11f, ui.muted))
            card.addView(info)
            card.addView(ChartView(ui.context, sparkline = true).apply { samples = previews[s.id].orEmpty() }, LinearLayout.LayoutParams(-1, ui.dp(48)))
            conflictWarning(card, s)
            compareAction(card, s, selected, onSelect)
            card.isFocusable = true
            card.background = ui.ripple(ui.surface, 24)
            card.setOnClickListener { open(s) }
            if (selected != null) card.setOnLongClickListener { onSelect(s); true }
            card.contentDescription = t("${words.time(s.startMs)} 充电会话，查看详情", "Charging session ${words.time(s.startMs)}, view details")
        }
    }

    /**
     * The comparison selection affordance. It is added only while a selection is in progress, so the
     * ordinary history list is unchanged, and the action is a real view with its own accessibility
     * label because a long press on the card is not discoverable.
     */
    private fun compareAction(
        card: LinearLayout,
        session: Session,
        selected: Set<Long>?,
        onSelect: (Session) -> Unit,
    ) {
        if (selected == null) return
        val picked = session.id in selected
        val row =
            LinearLayout(ui.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(40)
                setPadding(0, ui.dp(2), 0, ui.dp(2))
                background = ui.ripple(ui.surface, 12, false)
                isFocusable = true
                setOnClickListener { onSelect(session) }
                contentDescription =
                    if (picked) t("取消选中此会话", "Deselect this session")
                    else t("选中此会话用于对比", "Select this session for comparison")
            }
        row.addView(
            ui.label(
                if (picked) t("已选中对比", "Selected for comparison")
                else t("选中对比", "Select for comparison"),
                12f,
                if (picked) ui.blue else ui.muted,
                picked,
            ),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        row.addView(IconView(ui.context, if (picked) "info" else "next", if (picked) ui.blue else ui.muted), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
        card.addView(row)
    }

    fun detail(parent: LinearLayout, s: Session, fahrenheit: Boolean): Pair<ChartView, ChartView> {
        val hero = ui.card(parent, t("能量", "Energy"), "power")
        hero.addView(ui.label(duration((s.endMs ?: s.lastWallMs) - s.startMs), 12f, ui.muted))
        val energy = ui.metrics(hero, listOf(t("适配器输入", "Adapter input"), t("电芯净能量", "Net cell energy"), t("增加电量", "Charge gain")), listOf(ui.blue, ui.green, ui.ink))
        energy[0].text = inputEnergy(s)
        energy[1].text = words.n(s.energyWh, "Wh", 3)
        energy[2].text = gain(s)
        hero.addView(ui.label(t("实测覆盖 ${duration(s.coveredMs)}，采样缺口已排除。电芯净能量按带符号功率推算。", "Measured coverage ${duration(s.coveredMs)}; gaps excluded. Net cell energy is estimated from signed power."), 12f, ui.muted))
        conflictWarning(hero, s)

        val powerCard = ui.card(parent, t("充电曲线", "Charging curve"), "history")
        powerCard.addView(ui.label(t("峰值", "Peak") + " " + words.n(s.peakW, "W"), 12f, ui.muted))
        val power = ChartView(ui.context)
        powerCard.addView(power, LinearLayout.LayoutParams(-1, ui.dp(180)))
        powerCard.addView(ui.label(t("━ 电芯净功率 W · 推算", "━ Net cell power W · estimated"), 12f, ui.blue))
        val tempCard = ui.card(parent, t("电量与温度", "Charge & temperature"), "thermal")
        val temperature = ChartView(ui.context, temperature = true, batteryLevel = true)
        tempCard.addView(temperature, LinearLayout.LayoutParams(-1, ui.dp(200)))
        tempCard.addView(ui.label(t("━ 电量 %", "━ Charge %"), 12f, ui.violet))
        tempCard.addView(ui.label(t("━ 电池温度 °C", "━ Battery temperature °C"), 12f, ui.orange))

        val detail = ui.card(parent, t("详细信息", "Details"), "info")
        ui.row(detail, t("开始", "Start"), words.time(s.startMs))
        ui.row(detail, t("结束", "End"), s.endMs?.let(words::time) ?: t("进行中", "In progress"))
        ui.row(detail, t("电量起止", "Charge level"), "${s.startLevel ?: "—"}% → ${s.endLevel ?: "—"}%")
        ui.row(detail, t("电芯峰值功率", "Peak cell power"), words.n(s.peakW, "W"), ui.blue)
        ui.row(detail, t("平均电芯功率", "Average cell power"), words.n(s.averageW, "W"))
        ui.row(detail, t("最高电池温度", "Peak battery temp"), words.temperature(s.peakDeciC, fahrenheit), ui.orange)
        ui.row(detail, t("有效覆盖", "Covered time"), duration(s.coveredMs))
        ui.row(detail, t("电芯获得比例", "Cell share of input"), cellShare(s), if (s.cellShareOfInputPct == null) ui.muted else ui.green)
        ui.row(detail, t("额定功率", "Rated power"), t("不可用", "Unavailable"), ui.muted)
        ui.row(detail, t("通路电阻", "Path resistance"), t("不可用", "Unavailable"), ui.muted)
        if (s.uncertainEnd) detail.addView(ui.label(t("结束时间为最后观测时间，离线插拔或重启后的实际结束时间无法确认。", "End is the last observation; the actual end after offline unplugging or reboot is unknown."), 12f, ui.muted))
        detail.addView(ui.label(t("均值按有效覆盖时长计算；缺口不补值、不积分。曲线保留最新 1500 样本。适配器输入能量需特权通道，未实测显示「不可用」而非 0。电芯获得比例 = 电芯净能量 ÷ 适配器输入（刻意不叫效率）：输入覆盖不足电芯窗口一半、或该会话电芯净放电（此时所示两项本身仍给出）时不给值。", "Averages use covered time. Gaps are not filled or integrated. Charts retain the latest 1500 samples. Adapter input energy needs the privileged channel and reads unavailable rather than zero when unmeasured. Cell share of input = net cell energy / adapter input (deliberately not called an efficiency): withheld when the input covered less than half the cell window, or when the cell net-discharged over the session (both of its own figures are still shown)."), 12f, ui.muted))
        return power to temperature
    }

    private fun gain(s: Session): String {
        val start = s.startLevel ?: return "— %"
        val end = s.endLevel ?: return "— %"
        return "${if (end >= start) "+" else ""}${end - start} %"
    }
}
