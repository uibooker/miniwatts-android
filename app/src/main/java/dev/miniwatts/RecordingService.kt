package dev.miniwatts

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import dev.miniwatts.core.Sample

class RecordingService : Service() {
    private val engine
        get() = (application as MiniWattsApp).engine

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: LinearLayout? = null
    private var meter: TextView? = null
    private var startedAt = 0L
    private val listener: (Sample) -> Unit = { s ->
        getSystemService(NotificationManager::class.java).notify(7, notification(s))
        meter?.text =
            Words(this).summary(s, engine.prefs.getBoolean("fahrenheit", false)) +
                "\n" +
                Words(this).t("记录", "Recording") +
                " ${(s.wallMs-startedAt).coerceAtLeast(0)/1000}s"
    }

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("recording", "MiniWatts", NotificationManager.IMPORTANCE_LOW)
            )
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(
                7,
                notification(engine.latest),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        else startForeground(7, notification(engine.latest))
        engine.recording = true
        engine.listeners.add(listener)
        engine.updateSampling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        syncOverlay()
        return START_NOT_STICKY
    }

    private fun notification(s: Sample?): Notification {
        val w = Words(this)
        val open =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                2,
                Intent(this, RecordingService::class.java).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(this, "recording")
            .setSmallIcon(R.drawable.ic_meter)
            .setContentTitle(w.t("MiniWatts · 持续记录", "MiniWatts · Recording"))
            .setContentText(
                w.summary(s, engine.prefs.getBoolean("fahrenheit", false)).replace('\n', ' ') +
                    " · ${(System.currentTimeMillis()-startedAt)/1000}s"
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .addAction(Notification.Action.Builder(null, w.t("停止", "Stop"), stop).build())
            .build()
    }

    private fun syncOverlay() {
        val enabled = engine.prefs.getBoolean("overlay", false) && Settings.canDrawOverlays(this)
        if (!enabled) {
            removeOverlay()
            return
        }
        if (overlay != null) return
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 14, 20, 14)
                setBackgroundColor(Color.rgb(15, 56, 52))
            }
        val text =
            DraggableMeter(this).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                text =
                    Words(this@RecordingService)
                        .summary(engine.latest, engine.prefs.getBoolean("fahrenheit", false))
            }
        val close =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                this.text = Words(this@RecordingService).t("关闭悬浮窗 ×", "Close overlay ×")
                setPadding(0, 16, 0, 12)
                setOnClickListener {
                    engine.prefs.edit().putBoolean("overlay", false).apply()
                    removeOverlay()
                }
            }
        box.addView(text)
        box.addView(close)
        val p =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 20
                    y = 180
                }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        text.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x =
                        (startX + event.rawX - downX)
                            .toInt()
                            .coerceIn(
                                0,
                                (resources.displayMetrics.widthPixels - box.width).coerceAtLeast(0),
                            )
                    p.y =
                        (startY + event.rawY - downY)
                            .toInt()
                            .coerceIn(
                                0,
                                (resources.displayMetrics.heightPixels - box.height).coerceAtLeast(
                                    0
                                ),
                            )
                    runCatching { wm.updateViewLayout(box, p) }
                }
                MotionEvent.ACTION_UP -> text.performClick()
            }
            true
        }
        runCatching {
                wm.addView(box, p)
                overlay = box
                meter = text
            }
            .onFailure { engine.prefs.edit().putBoolean("overlay", false).apply() }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        meter = null
    }

    override fun onDestroy() {
        removeOverlay()
        engine.listeners.remove(listener)
        engine.recording = false
        engine.updateSampling()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class DraggableMeter(context: android.content.Context) : TextView(context) {
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
