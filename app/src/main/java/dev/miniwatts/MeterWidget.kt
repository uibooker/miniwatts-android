package dev.miniwatts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.miniwatts.core.Sample

class MeterWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val engine = (context.applicationContext as MiniWattsApp).engine
        engine.io.execute {
            val s = runCatching { SensorReader(context).read(engine.interval) }.getOrNull()
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
