package dev.miniwatts

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.miniwatts.core.CompareRow
import dev.miniwatts.core.CompareSide
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Session
import dev.miniwatts.core.SessionComparison

/**
 * Two sessions side by side.
 *
 * Field-by-field columns carry the values in the same units the session detail already uses
 * (`words.n` for watt-hours and watts, `words.temperature` for the peak temperature), so a comparison
 * never introduces a second unit convention.
 *
 * The rule this page exists to respect: two sessions have different observed windows, so a derived
 * ratio (Wh vs Wh, % vs %) is only claimed when the covered windows are close in length. Every other
 * difference is a plain subtraction of two integrals over their own windows, which is honest without
 * a normalisation. Missing readings stay "不可用" on both sides and are never rendered as 0.
 */
class ComparePanels(private val ui: UiKit, private val words: Words, private val fahrenheit: Boolean) {
    private fun t(zh: String, en: String) = words.t(zh, en)

    private companion object {
        /**
         * The label column has to fit a six-character CJK label on one line, so it is not the
         * narrowest column; the delta is, because it only ever holds a short signed number.
         */
        const val LABEL_WEIGHT = 1.05f
        const val VALUE_WEIGHT = 1f
        const val DELTA_WEIGHT = 0.8f

        /** A gutter so two adjacent values can never render as one run of text. */
        const val GUTTER_DP = 8
    }

    /** Sample lists are attached after the page exists, so the charts fill in when the query lands. */
    lateinit var leftChart: ChartView
        private set
    lateinit var rightChart: ChartView
        private set

    fun page(
        parent: LinearLayout,
        comparison: SessionComparison,
        onChange: () -> Unit,
    ) {
        val (a, b) = comparison
        val coverage = a.coveredMs > 0 && b.coveredMs > 0

        ui.action(parent, t("更换对比会话", "Change compared sessions"), "back") { onChange() }

        val hero = ui.card(parent, t("对比", "Comparison"), "history")
        val names =
            LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL }
        names.addView(ui.label(sideTitle(a, "A"), 15f, ui.blue, true), LinearLayout.LayoutParams(0, -2, 1f))
        names.addView(ui.label(sideTitle(b, "B"), 15f, ui.green, true), LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(names)
        val spans = LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL }
        spans.addView(ui.label(span(a), 11f, ui.muted), LinearLayout.LayoutParams(0, -2, 1f))
        spans.addView(ui.label(span(b), 11f, ui.muted), LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(spans)
        val heroNote =
            ui.label(
                t(
                    "同一字段左右各为一整段会话。缺口不补值、不积分，两侧覆盖时长通常不同。",
                    "Each column is one whole session. Gaps are never filled or integrated, so the two covered windows usually differ.",
                ),
                12f,
                ui.muted,
            )
        hero.addView(heroNote)
        if (a.signConflictObserved || b.signConflictObserved)
            hero.addView(ui.label(words.sessionSignConflict(), 12f, ui.orange))
        if (a.uncertainEnd || b.uncertainEnd)
            hero.addView(
                ui.label(
                    t(
                        "至少一侧的结束时间是最后观测时间，实际结束时间无法确认。",
                        "At least one end time is the last observation; the real end is unknown.",
                    ),
                    12f,
                    ui.orange,
                )
            )

        val gate =
            ui.label(
                if (comparison.ratioComparable)
                    t(
                        "两侧覆盖时长接近，差值可直接比较。能量仍是两段各自窗口上的积分。",
                        "The two covered windows are close in length, so the deltas are directly comparable. Each energy is still an integral over its own window.",
                    )
                else
                    t(
                        "两侧覆盖时长相差较大，只给绝对差值与各自均值，不给比例；两段不同窗口的比值不算比值。",
                        "The covered windows differ too much, so only absolute deltas and each side's own average are given, never a ratio: a ratio between two different windows is not a ratio.",
                    ),
                12f,
                if (comparison.ratioComparable) ui.muted else ui.orange,
            )
        hero.addView(gate)
        if (!coverage)
            hero.addView(
                ui.label(
                    t(
                        "至少一侧没有任何有效覆盖，无法给出覆盖相关的结论。",
                        "At least one side has no covered window, so nothing about coverage can be concluded.",
                    ),
                    12f,
                    ui.orange,
                )
            )

        // The two curves come before the field table: the visual shape is what the comparison is for,
        // and the table is long enough that it would otherwise push them off the first screens.
        val leftCard = ui.card(parent, t("A 电芯净功率", "A net cell power"), "history")
        leftChart = ChartView(ui.context, adapterInput = true)
        leftCard.addView(leftChart, LinearLayout.LayoutParams(-1, ui.dp(160)))
        leftCard.addView(
            ui.label(
                t(
                    "━ 电芯净功率 W · 推算　━ 适配器输入（仅有实测时）",
                    "━ Net cell power W · estimated  ━ Adapter input (only when measured)",
                ),
                11f,
                ui.muted,
            )
        )

        val rightCard = ui.card(parent, t("B 电芯净功率", "B net cell power"), "history")
        rightChart = ChartView(ui.context, adapterInput = true)
        rightCard.addView(rightChart, LinearLayout.LayoutParams(-1, ui.dp(160)))
        rightCard.addView(
            ui.label(
                t(
                    "━ 电芯净功率 W · 推算　━ 适配器输入（仅有实测时）",
                    "━ Net cell power W · estimated  ━ Adapter input (only when measured)",
                ),
                11f,
                ui.muted,
            )
        )

        val table = ui.card(parent, t("逐项对比", "Field by field"), "info")
        table.addView(header())
        ui.divider(table)
        rows(a, b).forEach { row ->
            table.addView(rowView(row))
            ui.divider(table)
        }
        table.addView(
            ui.label(
                t(
                    "「不可用」表示该项没有可用来源或本次没有测到，不是 0。",
                    "Unavailable means the value has no source or was not measured this time; it is not a zero.",
                ),
                12f,
                ui.muted,
            )
        )

        ui.action(parent, t("返回历史记录", "Back to history"), "back") { onChange() }
    }

    /** Called once sample lists arrive, so the charts show the same gaps as the history detail. */
    fun attach(left: List<Sample>, right: List<Sample>) {
        leftChart.samples = left
        rightChart.samples = right
    }

    private fun sideTitle(side: CompareSide, tag: String) =
        "${t("会话", "Session")} $tag · #${side.id}"

    private fun span(side: CompareSide) =
        "${words.time(side.startMs)} · ${words.duration(side.durationMs)}${if (side.open) " · " + t("进行中", "in progress") else ""}"

    /**
     * Column widths are shared by the header and every row. The header needs a blank first cell to
     * line up with the rows' label column; without it the three headings sit over the wrong columns.
     */
    private fun labelColumnParams() = LinearLayout.LayoutParams(0, -2, LABEL_WEIGHT)

    private fun valueParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -2, VALUE_WEIGHT).apply { marginEnd = ui.dp(GUTTER_DP) }

    private fun deltaParams() = LinearLayout.LayoutParams(0, -2, DELTA_WEIGHT)

    private fun header(): LinearLayout =
        LinearLayout(ui.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(6), 0, ui.dp(2))
            addView(View(ui.context), labelColumnParams())
            addView(ui.label("A", 12f, ui.blue, true), valueParams())
            addView(ui.label("B", 12f, ui.green, true), valueParams())
            addView(ui.label(t("差值", "Delta"), 12f, ui.muted, true), deltaParams())
        }

    /**
     * A value cell that shrinks its text rather than wrapping.
     *
     * The columns are fixed fractions of the card, so a long value (a date and time, or a percentage
     * range) would otherwise wrap onto a second line and break the row-to-row alignment. Auto-sizing
     * keeps every row one line high whatever the locale or the value's length.
     */
    private fun valueCell(value: String, present: Boolean, color: Int): TextView =
        oneLine(ui.label(value, 14f, if (present) color else ui.muted), 11, 14)

    private fun oneLine(view: TextView, minSp: Int, maxSp: Int): TextView =
        view.apply {
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(
                minSp,
                maxSp,
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }

    /**
     * One row. A value that exists on only one side stays a single value and never fabricates a
     * partner: the missing cell prints "不可用" on its own side.
     */
    private fun rowView(row: CompareRow): LinearLayout {
        fun format(value: String?): String = value ?: t("不可用", "Unavailable")
        val rowLayout =
            LinearLayout(ui.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(8), 0, ui.dp(8))
            }
        val labelColumn = ui.column()
        labelColumn.addView(ui.label(t(row.labelZh, row.labelEn), 13f, ui.muted))
        if (row.alwaysUnavailable)
            labelColumn.addView(ui.label(t("无来源", "no source"), 10f, ui.muted))
        rowLayout.addView(labelColumn, labelColumnParams())
        val leftColor = if (row.higher == -1) ui.blue else ui.ink
        val rightColor = if (row.higher == 1) ui.green else ui.ink
        rowLayout.addView(valueCell(format(row.left), row.left != null, leftColor), valueParams())
        rowLayout.addView(valueCell(format(row.right), row.right != null, rightColor), valueParams())
        // A local val, because a cross-module val property does not smart cast.
        val preformatted = row.deltaText
        val delta =
            when {
                preformatted != null -> preformatted
                row.delta == null -> t("不可用", "Unavailable")
                row.delta == 0.0 -> "±0"
                else ->
                    String.format(
                        java.util.Locale.getDefault(),
                        "%+.${row.deltaDigits}f %s",
                        row.delta,
                        row.deltaUnit,
                    ).trim()
            }
        rowLayout.addView(
            oneLine(ui.label(delta, 12f, if (row.delta == null) ui.muted else ui.violet), 10, 12),
            deltaParams(),
        )
        rowLayout.contentDescription =
            "${t(row.labelZh, row.labelEn)}: A ${format(row.left)}, B ${format(row.right)}, ${t("差值", "delta")} $delta"
        return rowLayout
    }

    /** The full field set, in a stable order so two comparisons can be read the same way. */
    private fun rows(a: CompareSide, b: CompareSide): List<CompareRow> {
        val sameWindow = (a.coveredMs == b.coveredMs && a.coveredMs > 0)
        fun deltaOf(x: Double?, y: Double?): Double? = if (x != null && y != null) x - y else null
        fun wh(v: Double?): String? = v?.let { words.n(it, "Wh", 3) }
        fun w(v: Double?): String? = v?.let { words.n(it, "W") }
        fun pct(v: Double?): String? = v?.let { words.n(it, "%", 1) }
        return listOf(
            CompareRow(
                "开始",
                "Start",
                words.timeMinutes(a.startMs),
                words.timeMinutes(b.startMs),
            ),
            CompareRow(
                "结束",
                "End",
                a.endMs?.let(words::timeMinutes) ?: t("进行中", "In progress"),
                b.endMs?.let(words::timeMinutes) ?: t("进行中", "In progress"),
            ),
            CompareRow(
                "时长",
                "Duration",
                words.duration(a.durationMs),
                words.duration(b.durationMs),
                a.durationMs.toDouble(),
                b.durationMs.toDouble(),
                a.durationMs.toDouble() - b.durationMs.toDouble(),
                t("秒", "s"),
                0,
                deltaText = words.signedDuration(a.durationMs.toDouble() - b.durationMs.toDouble()),
            ),
            CompareRow(
                "电量起止",
                "Charge range",
                "${a.startLevel ?: "—"}% → ${a.endLevel ?: "—"}%",
                "${b.startLevel ?: "—"}% → ${b.endLevel ?: "—"}%",
            ),
            CompareRow(
                "电量变化",
                "Charge change",
                a.levelDelta?.let { "${if (it >= 0) "+" else ""}$it %" },
                b.levelDelta?.let { "${if (it >= 0) "+" else ""}$it %" },
                a.levelDelta?.toDouble(),
                b.levelDelta?.toDouble(),
                deltaOf(a.levelDelta?.toDouble(), b.levelDelta?.toDouble()),
                "%",
                0,
            ),
            CompareRow(
                "电芯净能量",
                "Net cell energy",
                wh(a.energyWh),
                wh(b.energyWh),
                a.energyWh,
                b.energyWh,
                a.energyWh - b.energyWh,
                "Wh",
                3,
            ),
            CompareRow(
                "平均电芯功率",
                "Average cell power",
                w(a.averageW),
                w(b.averageW),
                a.averageW,
                b.averageW,
                deltaOf(a.averageW, b.averageW),
                "W",
                2,
            ),
            CompareRow(
                "峰值电芯功率",
                "Peak cell power",
                w(a.peakW),
                w(b.peakW),
                a.peakW,
                b.peakW,
                deltaOf(a.peakW, b.peakW),
                "W",
                2,
            ),
            CompareRow(
                "有效覆盖",
                "Covered time",
                words.duration(a.coveredMs),
                words.duration(b.coveredMs),
                a.coveredMs.toDouble(),
                b.coveredMs.toDouble(),
                deltaOf(a.coveredMs.toDouble(), b.coveredMs.toDouble()),
                t("秒", "s"),
                0,
                deltaText =
                    deltaOf(a.coveredMs.toDouble(), b.coveredMs.toDouble())
                        ?.let { words.signedDuration(it) },
            ),
            CompareRow(
                "最高电池温度",
                "Peak battery temp",
                a.peakDeciC?.let { words.temperature(it, fahrenheit) },
                b.peakDeciC?.let { words.temperature(it, fahrenheit) },
                a.peakDeciC?.div(10.0),
                b.peakDeciC?.div(10.0),
                deltaOf(a.peakDeciC?.div(10.0), b.peakDeciC?.div(10.0)),
                if (fahrenheit) "°F" else "°C",
                1,
            ),
            CompareRow(
                "适配器输入能量",
                "Adapter input energy",
                wh(a.measuredInputWh),
                wh(b.measuredInputWh),
                a.measuredInputWh,
                b.measuredInputWh,
                deltaOf(a.measuredInputWh, b.measuredInputWh),
                "Wh",
                3,
            ),
            CompareRow(
                "适配器平均输入功率",
                "Adapter average input",
                w(a.inputAverageW),
                w(b.inputAverageW),
                a.inputAverageW,
                b.inputAverageW,
                deltaOf(a.inputAverageW, b.inputAverageW),
                "W",
                2,
            ),
            CompareRow(
                "电芯获得比例",
                "Cell share of input",
                pct(a.cellShareOfInputPct),
                pct(b.cellShareOfInputPct),
                a.cellShareOfInputPct,
                b.cellShareOfInputPct,
                if (sameWindow) deltaOf(a.cellShareOfInputPct, b.cellShareOfInputPct) else null,
                "%",
                1,
            ),
            CompareRow(
                "状态与电芯方向相反",
                "State vs cell direction",
                if (a.signConflictObserved) t("曾出现", "Observed") else t("未出现", "None"),
                if (b.signConflictObserved) t("曾出现", "Observed") else t("未出现", "None"),
            ),
            CompareRow(
                "额定功率 / 通路电阻",
                "Rated power / path resistance",
                null,
                null,
                alwaysUnavailable = true,
            ),
        )
    }
}
