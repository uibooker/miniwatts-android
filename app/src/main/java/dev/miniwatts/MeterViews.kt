package dev.miniwatts

import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.math.abs

class PowerGauge(context: Context, private val ui: UiKit) : FrameLayout(context) {
    private val arc = ArcView(context, ui)
    private val value = ui.number("—", 54f, ui.blue)
    private val words = Words(context)

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
        center.addView(
            value,
            LinearLayout.LayoutParams(
                -1,
                ui.dp(if (resources.configuration.fontScale > 1.2f) 94 else 74),
            ),
        )
        center.addView(ui.label("W", 18f, ui.muted))
        center.addView(ui.label(words.t("电芯净功率 · 推算", "Net cell power · estimated"), 14f, ui.muted))
        center.addView(ui.label(words.t("绝对值量程 0–30 W", "Magnitude scale 0–30 W"), 12f, ui.muted))
        addView(center, LayoutParams(-1, -2, Gravity.CENTER))
        minimumHeight = ui.dp(if (resources.configuration.fontScale > 1.2f) 320 else 264)
    }

    fun update(power: Double?) {
        value.text = power?.let { String.format(java.util.Locale.getDefault(), "%+.2f", it) } ?: "—"
        arc.power = power
        contentDescription =
            words.t("电芯净功率，${value.text} W，推算", "Net cell power, ${value.text} W, estimated")
    }

    private class ArcView(context: Context, val ui: UiKit) : View(context) {
        var power: Double? = null
            set(value) {
                field = value
                invalidate()
            }

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val size = minOf(width.toFloat(), height.toFloat()) - ui.dp(26)
            val cx = width / 2f
            val cy = height / 2f
            val rect = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            p.style = Paint.Style.STROKE
            p.strokeWidth = ui.dp(11).toFloat()
            p.strokeCap = Paint.Cap.ROUND
            p.color = ui.line
            c.drawArc(rect, 135f, 270f, false, p)
            p.strokeWidth = ui.dp(1).toFloat()
            p.strokeCap = Paint.Cap.BUTT
            for (i in 0..30) {
                c.save()
                c.rotate(135f + i * 9f, cx, cy)
                c.drawLine(
                    cx + size / 2 + ui.dp(9),
                    cy,
                    cx + size / 2 + ui.dp(if (i % 5 == 0) 16 else 12),
                    cy,
                    p,
                )
                c.restore()
            }
            power?.let {
                p.color = ui.blue
                p.strokeWidth = ui.dp(11).toFloat()
                p.strokeCap = Paint.Cap.ROUND
                c.drawArc(rect, 135f, (abs(it) / 30 * 270).coerceIn(0.0, 270.0).toFloat(), false, p)
            }
        }
    }
}

class LevelBar(context: Context, private val ui: UiKit, private val accent: Int = ui.green) :
    View(context) {
    var fraction: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = ui.line
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        fraction?.let {
            p.color = accent
            c.drawRoundRect(0f, 0f, width * it.coerceIn(0f, 1f), height.toFloat(), r, r, p)
        }
    }
}

/** A schematic location, never a map of unmeasured zones. */
class BatterySchematic(context: Context, private val ui: UiKit) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = ui.dp(104).toFloat()
        val h = height - ui.dp(12).toFloat()
        val x = (width - w) / 2
        p.style = Paint.Style.FILL
        p.color = ui.tint(ui.orange)
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
        p.color = ui.orange
        c.drawRoundRect(x + w * .28f, h * .40f, x + w * .72f, h * .68f, 5f, 5f, p)
        c.drawLine(width / 2f - 6, h * .37f, width / 2f + 6, h * .37f, p)
        c.drawLine(width / 2f, h * .47f, width / 2f, h * .60f, p)
    }
}
