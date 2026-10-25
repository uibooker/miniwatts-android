package dev.miniwatts

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared native surfaces and typography for all screens. */
class UiKit(val context: Context) {
    val dark =
        context.resources.configuration.let {
            val choice = context.getSharedPreferences("settings", 0).getString("theme", "system")
            choice == "dark" ||
                (choice == "system" &&
                    it.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES)
        }
    val ink = Color.parseColor(if (dark) "#F1F4F7" else "#101114")
    val muted = Color.parseColor(if (dark) "#AEB7C8" else "#717789")
    val surface = Color.parseColor(if (dark) "#172F39" else "#FFFFFF")
    val line = Color.parseColor(if (dark) "#304A55" else "#ECECEF")
    val track = Color.parseColor(if (dark) "#36424E" else "#E7E7EC")
    val selection = Color.parseColor(if (dark) "#344750" else "#DFE2E8")
    val blue = Color.parseColor(if (dark) "#68D1EF" else "#0089AF")
    val green = Color.parseColor(if (dark) "#70DEB4" else "#0A9E65")
    val orange = Color.parseColor(if (dark) "#FFC078" else "#B57108")
    val red = Color.parseColor(if (dark) "#FF9390" else "#C73930")
    val violet = Color.parseColor(if (dark) "#C4B8EF" else "#6C599C")

    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    fun shape(color: Int = surface, radius: Int = 24, stroke: Boolean = true) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke) setStroke(dp(1), line)
        }

    fun tint(color: Int) =
        Color.argb(if (dark) 35 else 20, Color.red(color), Color.green(color), Color.blue(color))

    fun blendWithSurface(color: Int) = Color.rgb(
        (Color.red(color) + Color.red(surface)) / 2,
        (Color.green(color) + Color.green(surface)) / 2,
        (Color.blue(color) + Color.blue(surface)) / 2,
    )

    fun ripple(color: Int = surface, radius: Int = 16, stroke: Boolean = true) =
        RippleDrawable(ColorStateList.valueOf(tint(blue)), shape(color, radius, stroke), null)

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun label(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }

    fun number(value: String, size: Float = 30f, color: Int = ink) =
        label(value, size, color).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }

    fun card(
        parent: LinearLayout,
        title: String? = null,
        icon: String? = null,
        color: Int = muted,
    ): LinearLayout {
        val card =
            column().apply {
                background = shape()
                setPadding(dp(16), dp(14), dp(16), dp(16))
            }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        if (title != null) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            if (icon != null)
                row.addView(
                    IconView(context, icon, color),
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) },
                )
            row.addView(label(title, 13f, color, true))
            card.addView(row)
        }
        return card
    }

    fun note(parent: LinearLayout, value: String) {
        card(parent).addView(label(value, 13f, muted))
    }

    fun divider(parent: LinearLayout) {
        parent.addView(
            View(context).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(-1, dp(1)),
        )
    }

    fun row(parent: LinearLayout, title: String, value: String, color: Int = ink): TextView {
        val row =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
        row.addView(label(title, 14f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        val data = label(value, 14f, color).apply { gravity = Gravity.END }
        row.addView(data, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
        divider(parent)
        return data
    }

    fun action(parent: LinearLayout, title: String, icon: String = "next", action: () -> Unit) {
        val row =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(16), dp(8), dp(12), dp(8))
                background = ripple()
                isFocusable = true
                setOnClickListener { action() }
            }
        row.addView(label(title, 15f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(IconView(context, icon, blue), LinearLayout.LayoutParams(dp(24), dp(24)))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    fun pill(parent: LinearLayout, color: Int): TextView =
        label("", 12f, color, true).apply {
            background = shape(tint(color), 14, false)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            parent.addView(
                this,
                LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(8)
                    bottomMargin = dp(6)
                },
            )
        }

    fun metrics(
        parent: LinearLayout,
        titles: List<String>,
        colors: List<Int> = titles.map { ink },
    ): List<TextView> {
        // Large fonts and narrow windows use vertical metrics rather than clipping columns.
        val stacked =
            context.resources.configuration.screenWidthDp < 350 ||
                context.resources.configuration.fontScale > 1.2f
        val row =
            LinearLayout(context).apply {
                orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            }
        parent.addView(row)
        return titles.mapIndexed { i, title ->
            val cell = column().apply { setPadding(0, dp(12), dp(6), dp(4)) }
            cell.addView(label(title, 12f, muted))
            val value =
                MetricTextView(context).apply {
                    maxLines = 1
                    text = "—"
                    textSize = if (titles.size > 2) 28f else 32f
                    setTextColor(colors[i])
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    fontFeatureSettings = "tnum"
                    setAutoSizeTextTypeUniformWithConfiguration(
                        18,
                        if (titles.size > 2) 28 else 32,
                        1,
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                    )
                }
            cell.addView(value)
            row.addView(
                cell,
                if (stacked) LinearLayout.LayoutParams(-1, -2)
                else LinearLayout.LayoutParams(0, -2, 1f),
            )
            value
        }
    }

    fun iconButton(icon: String, description: String, color: Int = blue, action: () -> Unit): FrameLayout =
        FrameLayout(context).apply {
            background = ripple(surface, 24)
            contentDescription = description
            isFocusable = true
            setOnClickListener { action() }
            addView(
                IconView(context, icon, color),
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
            )
        }
}

class GridBackground(private val ui: UiKit) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        // Grid strokes leave a translucent color on this reused paint.
        paint.alpha = 255
        paint.shader =
            LinearGradient(
                0f,
                0f,
                bounds.width().toFloat(),
                bounds.height().toFloat(),
                if (ui.dark) Color.rgb(11, 34, 43) else Color.rgb(239, 253, 253),
                if (ui.dark) Color.rgb(16, 27, 39) else Color.rgb(241, 243, 249),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = if (ui.dark) Color.argb(22, 160, 208, 217) else Color.argb(18, 71, 119, 135)
        paint.strokeWidth = 1f
        val step = ui.dp(27)
        for (x in 0..bounds.width() step step) canvas.drawLine(
            x.toFloat(),
            0f,
            x.toFloat(),
            bounds.height().toFloat(),
            paint,
        )
        for (y in 0..bounds.height() step step) canvas.drawLine(
            0f,
            y.toFloat(),
            bounds.width().toFloat(),
            y.toFloat(),
            paint,
        )
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(filter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}

/** One stroke-based icon family, drawn locally without external assets. */
class IconView(context: Context, private val kind: String, var color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val save = c.save()
        c.scale(width / 24f, height / 24f)
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        fun path(vararg points: Float) {
            val path = Path()
            path.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            c.drawPath(path, p)
        }
        when (kind) {
            "power" -> {
                p.style = Paint.Style.FILL
                path(14f, 2f, 5f, 14f, 11f, 14f, 10f, 22f, 19f, 10f, 13f, 10f, 14f, 2f)
            }
            "thermal" -> {
                c.drawRoundRect(9f, 2f, 15f, 17f, 3f, 3f, p)
                c.drawCircle(12f, 18f, 4f, p)
                c.drawLine(12f, 7f, 12f, 19f, p)
                for (y in 5..13 step 4) c.drawLine(18f, y.toFloat(), 20f, y.toFloat(), p)
            }
            "charger" -> {
                p.style = Paint.Style.FILL
                c.drawRoundRect(7f, 6f, 18f, 18f, 4f, 4f, p)
                c.drawRoundRect(2f, 9f, 9f, 15f, 1f, 1f, p)
                c.drawRoundRect(17f, 8f, 23f, 10f, 1f, 1f, p)
                c.drawRoundRect(17f, 14f, 23f, 16f, 1f, 1f, p)
            }
            "device" -> {
                p.style = Paint.Style.FILL
                path(2f, 7f, 12f, 2f, 22f, 7f, 12f, 12f, 2f, 7f)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.4f
                path(2f, 12f, 12f, 17f, 22f, 12f)
                path(2f, 17f, 12f, 22f, 22f, 17f)
            }
            "phone" -> {
                c.drawRoundRect(6f, 2f, 18f, 22f, 2f, 2f, p)
                c.drawLine(10f, 5f, 14f, 5f, p)
                c.drawLine(10f, 19f, 14f, 19f, p)
            }
            "history" -> {
                path(3f, 3f, 3f, 21f, 22f, 21f)
                path(6f, 15f, 11f, 9f, 16f, 13f, 21f, 5f)
                for ((x, y) in listOf(6f to 15f, 11f to 9f, 16f to 13f, 21f to 5f)) c.drawCircle(
                    x,
                    y,
                    1.2f,
                    p,
                )
            }
            "battery" -> {
                c.drawRoundRect(2f, 6f, 20f, 18f, 3f, 3f, p)
                c.drawLine(22f, 10f, 22f, 14f, p)
                c.drawLine(6f, 12f, 15f, 12f, p)
            }
            "settings" -> {
                c.drawCircle(12f, 12f, 6f, p)
                c.drawCircle(12f, 12f, 2.5f, p)
                for (i in 0..7) {
                    c.save()
                    c.rotate(i * 45f, 12f, 12f)
                    c.drawLine(12f, 2f, 12f, 5f, p)
                    c.restore()
                }
            }
            "back" -> path(15f, 5f, 8f, 12f, 15f, 19f)
            "delete" -> {
                path(7f, 7f, 8f, 21f, 16f, 21f, 17f, 7f)
                path(4f, 6f, 20f, 6f)
                path(9f, 6f, 9f, 3f, 15f, 3f, 15f, 6f)
                c.drawLine(11f, 10f, 11f, 17f, p)
                c.drawLine(14f, 10f, 14f, 17f, p)
            }
            "export" -> {
                path(4f, 14f, 4f, 21f, 20f, 21f, 20f, 14f)
                path(7f, 8f, 12f, 3f, 17f, 8f)
                c.drawLine(12f, 3f, 12f, 16f, p)
            }
            "info" -> {
                c.drawCircle(12f, 12f, 9f, p)
                c.drawLine(12f, 11f, 12f, 17f, p)
                c.drawPoint(12f, 7f, p)
            }
            else -> path(9f, 5f, 16f, 12f, 9f, 19f)
        }
        c.restoreToCount(save)
    }
}

/** Keep the unit beside its reading, with a quieter size as in the reference. */
private class MetricTextView(context: Context) : TextView(context) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        val value = text?.toString().orEmpty()
        val split = value.lastIndexOf(' ')
        val unit = value.substringAfterLast(' ')
        if (split > 0 && unit in listOf("W", "Wh", "V", "A", "mA", "mAh", "°C", "°F", "%")) {
            val styled = android.text.SpannableString(value)
            styled.setSpan(
                android.text.style.RelativeSizeSpan(.5f),
                split + 1,
                styled.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            super.setText(styled, type)
        } else super.setText(text, type)
    }
}
