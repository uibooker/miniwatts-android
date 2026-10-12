package dev.miniwatts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.miniwatts.core.Integration
import dev.miniwatts.core.Sample
import java.util.Locale

/** The same continuity rule drives integration and visible line segments. */
class ChartView
@JvmOverloads
constructor(context: Context, private val temperature: Boolean = false) : View(context) {
    private val ui = UiKit(context)
    private val words = Words(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var samples: List<Sample> = emptyList()
        set(value) {
            field = value
            contentDescription =
                words.t(
                    "${if (temperature) "温度" else "功率"}曲线，${value.size} 个样本，缺口断线",
                    "${if (temperature) "Temperature" else "Power"} chart, ${value.size} samples, gaps disconnected",
                )
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        fun value(s: Sample): Double? = if (temperature) s.temperatureDeciC?.div(10.0) else s.powerW
        val values = samples.mapNotNull(::value)
        paint.textSize =
            android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                11f,
                resources.displayMetrics,
            )
        paint.strokeWidth = ui.dp(1).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        val left = ui.dp(44).toFloat()
        val right = width - ui.dp(8).toFloat()
        val top = ui.dp(16).toFloat()
        val bottom = height - ui.dp(32).toFloat()
        if (values.isEmpty()) {
            paint.color = ui.muted
            c.drawText(words.t("等待有效样本", "Waiting for valid samples"), 0f, height / 2f, paint)
            return
        }
        var min = if (temperature) values.min() - 1 else minOf(0.0, values.min())
        var max = if (temperature) values.max() + 1 else maxOf(0.0, values.max())
        if (max - min < 1) {
            min -= .5
            max += .5
        }
        fun y(v: Double) = top + (bottom - top) * (1 - (v - min) / (max - min)).toFloat()
        // A boot/time reset gets its own point, never a backwards x coordinate.
        val elapsed = LongArray(samples.size)
        for (i in 1 until samples.size) elapsed[i] =
            elapsed[i - 1] + (samples[i].monoMs - samples[i - 1].monoMs).coerceAtLeast(1)
        val span = elapsed.last().coerceAtLeast(1)
        fun x(i: Int) = left + (right - left) * elapsed[i].toFloat() / span
        for (i in 0..3) {
            val v = min + (max - min) * i / 3
            paint.color = ui.line
            c.drawLine(left, y(v), right, y(v), paint)
            paint.color = ui.muted
            c.drawText(
                String.format(Locale.getDefault(), "%.1f", v),
                0f,
                y(v) + paint.textSize / 3,
                paint,
            )
        }
        for (i in 0..4) {
            paint.color = ui.line
            val xx = left + (right - left) * i / 4
            c.drawLine(xx, top, xx, bottom, paint)
        }
        paint.color = if (temperature) ui.orange else ui.blue
        paint.strokeWidth = ui.dp(2).toFloat()
        samples.forEachIndexed { i, s ->
            val v = value(s) ?: return@forEachIndexed
            c.drawCircle(x(i), y(v), ui.dp(2).toFloat(), paint)
            if (i > 0) {
                val a = samples[i - 1]
                val va = value(a)
                if (va != null && Integration.connected(a, s))
                    c.drawLine(x(i - 1), y(va), x(i), y(v), paint)
            }
        }
        paint.color = ui.muted
        val start = words.time(samples.first().wallMs).substringAfter(' ')
        val end = words.time(samples.last().wallMs).substringAfter(' ')
        c.drawText(start, left, height - ui.dp(7).toFloat(), paint)
        if (right - left > paint.measureText(start) + paint.measureText(end) + ui.dp(12))
            c.drawText(end, right - paint.measureText(end), height - ui.dp(7).toFloat(), paint)
    }
}
