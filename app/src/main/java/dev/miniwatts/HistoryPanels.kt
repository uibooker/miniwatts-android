package dev.miniwatts

import android.widget.LinearLayout
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Session

class HistoryPanels(private val ui: UiKit, private val words: Words) {
    private fun t(zh: String, en: String) = words.t(zh, en)

    private fun duration(ms: Long) = "${ms / 3600000}h ${(ms / 60000) % 60}m ${(ms / 1000) % 60}s"

    fun list(
        parent: LinearLayout,
        rows: List<Session>,
        previews: Map<Long, List<Sample>>,
        open: (Session) -> Unit,
    ) {
        val current = rows.firstOrNull { it.endMs == null }
        val active = ui.card(parent, t("当前会话", "CURRENT SESSION"), "power", ui.blue)
        if (current == null)
            active.addView(ui.label(t("暂无进行中的充电会话", "No active charging session"), 18f, ui.muted))
        else {
            active.addView(
                ui.label(t("进行中 / 待采样恢复", "Active / awaiting sampling"), 18f, ui.blue, true)
            )
            active.addView(
                ui.label(
                    "${words.time(current.startMs)} · ${duration(current.lastWallMs - current.startMs)}",
                    12f,
                    ui.muted,
                )
            )
            val values =
                ui.metrics(
                    active,
                    listOf(t("电芯净能量 · 推算", "Net cell energy · est."), t("电量增幅", "Charge gain")),
                    listOf(ui.blue, ui.green),
                )
            values[0].text = words.n(current.energyWh, "Wh", 4)
            values[1].text = gain(current)
            active.addView(
                ui.label("${t("有效覆盖", "Covered")}: ${duration(current.coveredMs)}", 13f, ui.muted)
            )
            active.addView(
                ChartView(ui.context).apply { samples = previews[current.id].orEmpty() },
                LinearLayout.LayoutParams(-1, ui.dp(160)),
            )
            active.isFocusable = true
            active.setOnClickListener { open(current) }
            active.contentDescription = t("当前会话，查看详情", "Current session, view details")
        }
        val summary = ui.card(parent, t("全部会话汇总", "ALL SESSIONS"), "history")
        val totals =
            ui.metrics(
                summary,
                listOf(t("会话数", "Sessions"), t("净能量 · 推算", "Net energy · est.")),
                listOf(ui.ink, ui.blue),
            )
        totals[0].text = rows.size.toString()
        totals[1].text = words.n(rows.sumOf { it.energyWh }, "Wh", 4)
        summary.addView(
            ui.label(
                "${t("有效覆盖", "Covered")}: ${duration(rows.sumOf { it.coveredMs })}",
                14f,
                ui.muted,
            )
        )
        if (rows.isEmpty())
            ui.note(
                parent,
                t(
                    "接入电源并采样后自动建立会话。历史没有虚构示例数据。",
                    "Plug in and sample to create a session. No example readings are generated.",
                ),
            )
        rows.forEach { s ->
            val card = ui.card(parent)
            card.addView(ui.label(words.time(s.startMs), 18f, ui.ink, true))
            card.addView(ui.number(words.n(s.energyWh, "Wh", 4), 30f, ui.blue))
            card.addView(
                ui.label(
                    "${s.startLevel ?: "—"}% → ${s.endLevel ?: "—"}% · ${s.endMs?.let(words::time) ?: t("进行中 / 待恢复", "Active / awaiting recovery")}",
                    13f,
                    ui.muted,
                )
            )
            card.addView(
                ui.label(
                    "${t("有效覆盖", "Covered")}: ${duration(s.coveredMs)} · ${t("点击查看详情", "Tap for details")}",
                    12f,
                    ui.muted,
                )
            )
            val samples = previews[s.id].orEmpty()
            if (samples.any { it.powerW != null })
                card.addView(
                    ChartView(ui.context).apply { this.samples = samples },
                    LinearLayout.LayoutParams(-1, ui.dp(104)),
                )
            card.isFocusable = true
            card.background = ui.ripple(ui.surface, 24)
            card.setOnClickListener { open(s) }
        }
    }

    fun detail(parent: LinearLayout, s: Session, fahrenheit: Boolean): Pair<ChartView, ChartView> {
        val hero = ui.card(parent, t("会话详情", "SESSION DETAILS"), "history")
        hero.addView(
            ui.label(
                "${words.time(s.startMs)} → ${s.endMs?.let(words::time) ?: t("待结束", "Open")}",
                16f,
                ui.muted,
            )
        )
        hero.addView(ui.number(words.n(s.energyWh, "Wh", 4), 40f, ui.blue))
        hero.addView(
            ui.label(t("已测电芯净能量 · 推算", "Observed net cell energy · estimated"), 14f, ui.muted)
        )
        ui.row(hero, t("电量起止", "Charge level"), "${s.startLevel ?: "—"}% → ${s.endLevel ?: "—"}%")
        ui.row(hero, t("电量增幅", "Charge gain"), gain(s), ui.green)
        ui.row(
            hero,
            t("观测时长", "Observed duration"),
            duration((s.endMs ?: s.lastWallMs) - s.startMs),
        )
        ui.row(hero, t("有效覆盖", "Covered"), duration(s.coveredMs))
        val metrics = ui.card(parent, t("覆盖区间指标", "COVERED SEGMENT METRICS"), "battery")
        val values =
            ui.metrics(
                metrics,
                listOf(t("峰值功率", "Peak power"), t("平均功率", "Average power")),
                listOf(ui.blue, ui.green),
            )
        values[0].text = words.n(s.peakW, "W")
        values[1].text = words.n(s.averageW, "W")
        ui.row(
            metrics,
            t("最高电池温度", "Peak battery temp"),
            words.temperature(s.peakDeciC, fahrenheit),
            ui.orange,
        )
        if (s.uncertainEnd)
            ui.note(
                parent,
                t(
                    "结束时间为最后观测时间；离线插拔/重启导致实际结束时间无法确认。",
                    "End is the last observation; actual end after offline unplug/reboot is unknown.",
                ),
            )
        fun curve(temperature: Boolean): ChartView {
            val card =
                ui.card(
                    parent,
                    if (temperature) t("温度曲线 · 实测 · °C", "TEMPERATURE · MEASURED · °C")
                    else t("净功率曲线 · 推算 · W", "NET POWER · ESTIMATED · W"),
                    "history",
                )
            return ChartView(ui.context, temperature).also {
                card.addView(it, LinearLayout.LayoutParams(-1, ui.dp(210)))
            }
        }
        val power = curve(false)
        val temperature = curve(true)
        ui.note(
            parent,
            t(
                "均值=净Wh/有效覆盖时长。缺口不补值、不积分；零覆盖时Wh=0表示没有积分区间。曲线仅保留最新1500样本。没有适配器输入能量或效率。",
                "Average = net Wh / covered duration. Gaps are not filled or integrated; zero coverage means no integrated segments. Charts retain latest 1500 samples. No adapter input energy or efficiency.",
            ),
        )
        return power to temperature
    }

    private fun gain(s: Session): String {
        val start = s.startLevel ?: return "— %"
        val end = s.endLevel ?: return "— %"
        val gain = end - start
        return "${if (gain >= 0) "+" else ""}$gain %"
    }
}
