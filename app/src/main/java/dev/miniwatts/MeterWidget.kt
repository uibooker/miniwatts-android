package dev.miniwatts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import dev.miniwatts.core.Sample

class MeterWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val engine = (context.applicationContext as MiniWattsApp).engine
        engine.io.execute {
            // Reuse the last privileged probe instead of binding Shizuku from a widget update: this
            // view is explicitly a "last sample" one, and binding a user service here would be both
            // surprising and expensive.
            val s =
                runCatching {
                        SensorReader(context)
                            .read(engine.interval, engine.privileged.latest?.adapter)
                    }
                    .getOrNull()
            if (s != null) engine.prefs.edit().putString("latest", s.json().toString()).apply()
            update(context, s)
        }
    }

    companion object {
        fun update(context: Context, sample: Sample?) {
            val w = Words(context)
            val prefs = context.getSharedPreferences("settings", 0)
            val view = RemoteViews(context.packageName, R.layout.widget)
            view.setTextViewText(
                R.id.widget_text,
                "MiniWatts\n" +
                    w.summary(sample, prefs.getBoolean("fahrenheit", false)) +
                    "\n" +
                    w.t("上次采样 · 非实时", "Last sample · not live"),
            )
            // The optional adapter line is one row more, so shrink the text instead of clipping it in
            // the default 100dp-tall widget.
            view.setTextViewTextSize(
                R.id.widget_text,
                TypedValue.COMPLEX_UNIT_SP,
                if (sample?.trustedInputW == null) 16f else 14f,
            )
            view.setOnClickPendingIntent(
                R.id.widget_text,
                PendingIntent.getActivity(
                    context,
                    3,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(
                manager.getAppWidgetIds(ComponentName(context, MeterWidget::class.java)),
                view,
            )
        }
    }
}
