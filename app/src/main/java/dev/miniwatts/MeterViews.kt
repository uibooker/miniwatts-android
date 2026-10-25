package dev.miniwatts

import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.math.abs

class PowerGauge(context: Context, private val ui: UiKit) : FrameLayout(context) {
    private val arc = ArcView(context, ui)
    private val value = ui.number("—", 52f, ui.blue)
    private val words = Words(context)
    private val inputNote =
        ui.label(words.t("适配器输入不可用", "No adapter input"), 12f, ui.muted)
            .apply {
                gravity = Gravity.CENTER
                maxLines = 1
                // English runs longer than the Chinese caption; without a lower floor it overflows
                // the fixed width and its ends land on the arc.
                setAutoSizeTextTypeUniformWithConfiguration(
                    8,
                    12,
                    1,
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                )
            }

    init {
        addView(arc, LayoutParams(-1, -1))
        val center = ui.column().apply { gravity = Gravity.CENTER }
        value.gravity = Gravity.CENTER
        value.maxLines = 1
        value.setAutoSizeTextTypeUniformWithConfiguration(
            32,
            54,
            1,
            android.util.TypedValue.COMPLEX_UNIT_SP,
        )
        val reading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        reading.addView(value, LinearLayout.LayoutParams(-2, ui.dp(72)))
        reading.addView(ui.label("W", 19f, ui.muted).apply {
            setPadding(ui.dp(4), ui.dp(12), 0, 0)
        })
        center.addView(reading, LinearLayout.LayoutParams(-2, -2))
        center.addView(
            ui.label(words.t("电芯净功率 · 推算", "Net cell power · est."), 14f, ui.muted)
                .apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                },
            LinearLayout.LayoutParams(ui.dp(180), -2),
        )
        center.addView(inputNote, LinearLayout.LayoutParams(ui.dp(200), -2))
        addView(center, LayoutParams(-1, -2, Gravity.CENTER))
        // Landscape leaves roughly half the vertical space, so a 250 dp gauge pushes its number
        // under the navigation bar; there the arc and the minimum height shrink together.
        val landscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        minimumHeight =
            ui.dp(
                when {
                    landscape -> 170
                    resources.configuration.fontScale > 1.2f -> 320
                    else -> 250
                },
            )
    }

    /**
     * [adapterInput] is the measured adapter input in W, which only the optional privileged channel
     * can read. Until it exists the outer ring stays an empty scale and the caption says so, rather
     * than implying a zero reading.
     */
    fun update(power: Double?, adapterInput: Double?) {
        // The arc is drawn on the magnitude scale, so the number matches it. Direction comes from the
        // charge state, and the signed reading stays in the chart, the cell current and the exports.
        value.text =
            power?.let { String.format(java.util.Locale.getDefault(), "%.1f", words.magnitude(it)) }
                ?: "—"
        arc.power = power
        arc.adapterInput = adapterInput
        inputNote.text =
            if (adapterInput == null) words.t("适配器输入不可用", "No adapter input")
            else
                words.t(
                    "适配器输入 ${String.format(java.util.Locale.getDefault(), "%.1f", adapterInput)} W · 实测",
                    "Adapter input ${String.format(java.util.Locale.getDefault(), "%.1f", adapterInput)} W · measured",
                )
        contentDescription =
            if (adapterInput == null)
                words.t(
                    "电芯净功率绝对值 ${value.text} W，推算，量程 0–30 W；方向见充放电状态；外圈适配器输入不可用",
                    "Net cell power magnitude ${value.text} W, estimated, scale 0–30 W; direction is the charge state; outer adapter input ring unavailable",
                )
            else
                words.t(
                    "电芯净功率绝对值 ${value.text} W，推算；适配器输入 ${String.format(java.util.Locale.getDefault(), "%.1f", adapterInput)} W，实测，外圈同量程 0–30 W",
                    "Net cell power magnitude ${value.text} W, estimated; adapter input ${String.format(java.util.Locale.getDefault(), "%.1f", adapterInput)} W, measured, outer ring on the same 0–30 W scale",
                )
    }

    private class ArcView(context: Context, val ui: UiKit) : View(context) {
        var power: Double? = null
            set(value) {
                field = value
                invalidate()
            }

        /** Measured adapter input; the outer ring stays empty while this is null. */
        var adapterInput: Double? = null
            set(value) {
                field = value
                invalidate()
            }

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val size = minOf(width.toFloat(), height.toFloat()) - ui.dp(30)
            val cx = width / 2f
            val cy = height / 2f
            val rect = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            p.style = Paint.Style.STROKE
            p.strokeWidth = ui.dp(13).toFloat()
            p.strokeCap = Paint.Cap.ROUND
            p.color = ui.track
            c.drawArc(rect, 135f, 270f, false, p)
            // Outer channel: measured adapter input, on the same 0-30 W magnitude scale.
            adapterInput?.let {
                p.color = ui.blue
                c.drawArc(rect, 135f, (abs(it) / 30 * 270).coerceIn(0.0, 270.0).toFloat(), false, p)
            }
            p.strokeWidth = ui.dp(1).toFloat()
            p.strokeCap = Paint.Cap.BUTT
            p.color = ui.track
            for (i in 0..30) {
                c.save()
                c.rotate(135f + i * 9f, cx, cy)
                c.drawLine(
                    cx + size / 2 - ui.dp(3),
                    cy,
                    cx + size / 2 + ui.dp(if (i % 5 == 0) 4 else 1),
                    cy,
                    p,
                )
                c.restore()
            }
            // Inner channel: net cell power.
            rect.inset(ui.dp(22).toFloat(), ui.dp(22).toFloat())
            p.color = ui.track
            p.strokeWidth = ui.dp(8).toFloat()
            p.strokeCap = Paint.Cap.ROUND
            c.drawArc(rect, 135f, 270f, false, p)
            power?.let {
                p.color = ui.green
                p.strokeWidth = ui.dp(8).toFloat()
                p.strokeCap = Paint.Cap.ROUND
                c.drawArc(rect, 135f, (abs(it) / 30 * 270).coerceIn(0.0, 270.0).toFloat(), false, p)
            }
        }
    }
}

class LevelBar(context: Context, private val ui: UiKit, private val accent: Int = ui.green) :
    View(context) {    var fraction: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = ui.track
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        fraction?.let {
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
                ui.blendWithSurface(accent), accent, Shader.TileMode.CLAMP)
            c.drawRoundRect(0f, 0f, width * it.coerceIn(0f, 1f), height.toFloat(), r, r, p)
            p.shader = null
        }
    }
}

/** A schematic location, never a map of unmeasured zones. */
class BatterySchematic(context: Context, private val ui: UiKit) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var temperature: Int? = null
        set(value) {
            field = value
            contentDescription = Words(context).t(
                "电池温度 ${value?.div(10.0) ?: "不可用"} °C，位置仅为示意",
                "Battery temperature ${value?.div(10.0) ?: "unavailable"} °C, schematic location only",
            )
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        val w = ui.dp(110).toFloat()
        val h = height - ui.dp(16).toFloat()
        val x = (width - w) / 2
        p.style = Paint.Style.FILL
        p.color = ui.tint(ui.muted)
        c.drawRoundRect(x, 6f, x + w, h, ui.dp(20).toFloat(), ui.dp(20).toFloat(), p)
        p.style = Paint.Style.STROKE
        p.color = ui.line
        p.strokeWidth = ui.dp(2).toFloat()
        c.drawRoundRect(x, 6f, x + w, h, ui.dp(20).toFloat(), ui.dp(20).toFloat(), p)
        c.drawLine(
            width / 2f - ui.dp(12),
            ui.dp(15).toFloat(),
            width / 2f + ui.dp(12),
            ui.dp(15).toFloat(),
            p,
        )
        val color = when {
            temperature == null -> ui.muted
            temperature!! >= 450 -> ui.red
            temperature!! >= 360 -> ui.orange
            else -> ui.green
        }
        val cx = width / 2f
        val cy = h * .60f
        p.style = Paint.Style.FILL
        p.color = ui.surface
        c.drawCircle(cx, cy, ui.dp(23).toFloat(), p)
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = ui.dp(1).toFloat()
        c.drawRoundRect(cx - ui.dp(7), cy - ui.dp(13), cx + ui.dp(7), cy - ui.dp(5), 2f, 2f, p)
        p.style = Paint.Style.FILL
        p.textSize = ui.dp(12).toFloat()
        p.textAlign = Paint.Align.CENTER
        c.drawText(temperature?.let { String.format(java.util.Locale.getDefault(), "%.1f°", it / 10.0) } ?: "—", cx, cy + ui.dp(11), p)
    }
}

/**
 * A compact bar history for one 0..1 series, used for the session-only CPU frequency ratio.
 *
 * A null entry leaves a visible gap instead of a zero-height bar, so "not measured" can never be
 * read as "0".
 */
class RatioHistoryView(
    context: Context,
    private val ui: UiKit,
    private val accent: Int = ui.blue,
) : View(context) {
    var values: List<Double?> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        p.color = ui.track
        val radius = ui.dp(3).toFloat()
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, p)
        val slot = width.toFloat() / values.size
        val barWidth = (slot * 0.72f).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            val fraction = value.coerceIn(0.0, 1.0)
            val barHeight = (height * fraction).toFloat()
            if (barHeight <= 0f) return@forEachIndexed
            p.color = accent
            val left = index * slot + (slot - barWidth) / 2
            c.drawRoundRect(
                left,
                height - barHeight,
                left + barWidth,
                height.toFloat(),
                barWidth / 2,
                barWidth / 2,
                p,
            )
        }
    }
}
