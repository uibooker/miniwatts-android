package dev.miniwatts

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import dev.miniwatts.core.Integration
import dev.miniwatts.core.Sample
import java.util.Locale

/** Filled segments share the integration continuity rule, including across sampling gaps. */
class ChartView
@JvmOverloads
constructor(
    context: Context,
    private val temperature: Boolean = false,
    private val sparkline: Boolean = false,
    private val batteryLevel: Boolean = false,
    /** Draw the measured adapter input as a second line on the same watt scale. */
    private val adapterInput: Boolean = false,
) : View(context) {
    private val ui = UiKit(context)
    private val words = Words(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var samples: List<Sample> = emptyList()
        set(value) {
            field = value
            val hasInput = adapterInput && value.any { it.trustedInputW != null }
            contentDescription = words.t(
                "${if (batteryLevel) "电量与温度" else if (temperature) "温度" else "电芯净功率"}曲线，${value.size} 个样本，缺口断线" +
                    if (hasInput) "，并含适配器输入实测曲线" else "",
                "${if (batteryLevel) "Charge and temperature" else if (temperature) "Temperature" else "Net cell power"} chart, ${value.size} samples, gaps disconnected" +
                    if (hasInput) ", including the measured adapter input" else "",
            )
            invalidate()
        }

    /**
     * Plot domain. Level and temperature follow fixed rules; power keeps zero inside the range so the
     * baseline stays meaningful. Cell power and adapter input share one domain so the two lines are
     * directly comparable.
     */
    private fun domainOf(values: List<Double>, level: Boolean): Pair<Double, Double> {
        var min =
            if (level) 0.0
            else if (temperature) values.min() - 1
            else minOf(0.0, values.min())
        var max =
            if (level) 100.0
            else if (temperature) values.max() + 1
            else maxOf(0.0, values.max())
        if (max - min < 1) {
            if (min >= 0) max = min + 1 else min = max - 1
        }
        return min to max
    }

    /** Measured adapter input as a second line, with the same gap rule as every other series. */
    private fun drawInputOverlay(
        c: Canvas,
        plotTop: Float,
        plotBottom: Float,
        domain: Pair<Double, Double>,
        x: (Int) -> Float,
    ) {
        val (min, max) = domain
        if (max - min <= 0) return
        fun y(v: Double) = plotTop + (plotBottom - plotTop) * (1 - (v - min) / (max - min)).toFloat()
        paint.color = ui.green
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ui.dp(2).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        var path = Path()
        var points = 0
        var lastX = 0f
        var lastY = 0f
        fun flush() {
            if (points == 1) c.drawCircle(lastX, lastY, ui.dp(1).toFloat(), paint)
            else if (points > 1) c.drawPath(path, paint)
            path = Path()
            points = 0
        }
        samples.forEachIndexed { i, s ->
            val v = s.trustedInputW
            if (v == null) flush()
            else {
                if (points > 0 && !Integration.connected(samples[i - 1], s)) flush()
                if (points == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                lastX = x(i)
                lastY = y(v)
                points++
            }
        }
        flush()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (samples.isEmpty()) {
            paint.textSize = ui.dp(12).toFloat()
            paint.color = ui.muted
            c.drawText(words.t("等待有效样本", "Waiting for valid samples"), 0f, height / 2f, paint)
            return
        }
        val left = ui.dp(2).toFloat()
        val right = width - ui.dp(if (sparkline) 2 else 40).toFloat()
        val top = ui.dp(if (sparkline) 4 else 20).toFloat()
        val bottom = height - ui.dp(if (sparkline) 4 else 26).toFloat()
        if (right <= left || bottom <= top) return
        val elapsed = LongArray(samples.size)
        for (i in 1 until samples.size) elapsed[i] =
            elapsed[i - 1] + (samples[i].monoMs - samples[i - 1].monoMs).coerceAtLeast(1)
        val span = elapsed.last().coerceAtLeast(1)
        fun x(i: Int) = left + (right - left) * elapsed[i].toFloat() / span
        paint.textSize = ui.dp(10).toFloat()
        paint.strokeCap = Paint.Cap.ROUND

        fun plot(plotTop: Float, plotBottom: Float, color: Int, unit: String, level: Boolean = false, domain: Pair<Double, Double>? = null) {
            fun value(s: Sample): Double? = when {
                level -> s.level?.toDouble()
                temperature -> s.temperatureDeciC?.div(10.0)
                else -> s.powerW
            }
            val values = samples.mapNotNull(::value)
            if (values.isEmpty()) {
                paint.color = ui.muted
                c.drawText(words.t("暂无读数", "No readings"), left, (plotTop + plotBottom) / 2, paint)
                return
            }
            val (min, max) = domain ?: domainOf(values, level)
            fun y(v: Double) = plotTop + (plotBottom - plotTop) * (1 - (v - min) / (max - min)).toFloat()
            if (!sparkline) {
                for (i in 0..2) {
                    val v = min + (max - min) * i / 2
                    paint.color = ui.line
                    paint.strokeWidth = 1f
                    c.drawLine(left, y(v), right, y(v), paint)
                    paint.color = ui.muted
                    c.drawText(String.format(Locale.getDefault(), "%.0f%s", if (kotlin.math.abs(v) < .5) 0.0 else v, unit),
                        right + ui.dp(6), y(v) + paint.textSize / 3, paint)
                }
            }
            val baseline = if (temperature || level) plotBottom else y(0.0)
            var path = Path()
            var firstX = 0f
            var lastX = 0f
            var lastY = 0f
            var points = 0
            fun flush() {
                if (points == 0) return
                if (!sparkline && points > 1 && !level) {
                    val fill = Path(path)
                    fill.lineTo(lastX, baseline)
                    fill.lineTo(firstX, baseline)
                    fill.close()
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(0f, plotTop, 0f, plotBottom,
                        android.graphics.Color.argb(100, android.graphics.Color.red(color),
                            android.graphics.Color.green(color), android.graphics.Color.blue(color)),
                        ui.tint(color), Shader.TileMode.CLAMP)
                    c.drawPath(fill, paint)
                    paint.shader = null
                }
                paint.color = color
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ui.dp(2).toFloat()
                if (points == 1) c.drawCircle(firstX, lastY, ui.dp(1).toFloat(), paint)
                else c.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                path = Path()
                points = 0
            }
            samples.forEachIndexed { i, s ->
                val v = value(s)
                if (v == null) {
                    flush()
                } else {
                    if (points > 0 && !Integration.connected(samples[i - 1], s)) flush()
                    if (points == 0) {
                        firstX = x(i)
                        path.moveTo(firstX, y(v))
                    } else path.lineTo(x(i), y(v))
                    lastX = x(i)
                    lastY = y(v)
                    points++
                }
            }
            flush()
        }
        if (batteryLevel) {
            val middle = (top + bottom) / 2
            plot(top, middle - ui.dp(12), ui.violet, "%", true)
            plot(middle + ui.dp(12), bottom, ui.orange, "°")
        } else if (!temperature && adapterInput) {
            // One shared watt scale for both lines, so a difference between them is a real difference
            // and not a second axis.
            val inputValues = samples.mapNotNull { it.trustedInputW }
            val shared = domainOf(samples.mapNotNull { it.powerW } + inputValues, level = false)
            plot(top, bottom, ui.blue, "W", domain = shared)
            drawInputOverlay(c, top, bottom, shared, ::x)
        } else plot(top, bottom, if (temperature) ui.orange else ui.blue, if (temperature) "°" else "W")
        if (!sparkline) {
            paint.color = ui.muted
            for (i in 0..3) {
                val label = words.duration(span * i / 3)
                val xx = left + (right - left) * i / 3
                val origin = (xx - paint.measureText(label) / 2).coerceIn(left, maxOf(left, right - paint.measureText(label)))
                c.drawText(label, origin, height - ui.dp(5).toFloat(), paint)
            }
        }
    }
}
