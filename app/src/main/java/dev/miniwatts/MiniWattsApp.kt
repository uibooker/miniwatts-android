package dev.miniwatts

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.miniwatts.core.Sample
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MiniWattsApp : Application() {
    lateinit var engine: Engine

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
    }
}

class Engine(val app: Application) {
    val prefs = app.getSharedPreferences("settings", 0)
    val store = Store(app)
    val io = Executors.newSingleThreadScheduledExecutor()
    val main = Handler(Looper.getMainLooper())
    val listeners = mutableSetOf<(Sample) -> Unit>()
    var latest: Sample? =
        prefs.getString("latest", null)?.let {
            runCatching { org.json.JSONObject(it).sample() }.getOrNull()
        }
        private set

    val recent = ArrayDeque<Sample>()
    var foreground = false
    var recording = false
    private var task: ScheduledFuture<*>? = null
    private var widgetAt = 0L
    private var scheduledInterval = 0L
    val interval: Long
        get() = prefs.getInt("interval", 1).coerceIn(1, 30) * 1000L

    fun updateSampling() {
        if ((foreground || recording) && task != null && scheduledInterval == interval) return
        task?.cancel(false)
        task = null
        scheduledInterval = interval
        val continuity = System.nanoTime()
        if (foreground || recording)
            task =
                io.scheduleWithFixedDelay(
                    {
                        runCatching {
                                val s =
                                    SensorReader(app).read(interval).copy(continuity = continuity)
                                store.ingest(s, prefs.getInt("retention", 60))
                                prefs.edit().putString("latest", s.json().toString()).apply()
                                main.post {
                                    lastError = null
                                    latest = s
                                    recent.addLast(s)
                                    while (recent.size > 120) recent.removeFirst()
                                    listeners.toList().forEach { it(s) }
                                    if (s.monoMs - widgetAt >= 60_000) {
                                        widgetAt = s.monoMs
                                        MeterWidget.update(app, s)
                                    }
                                }
                            }
                            .onFailure { error ->
                                main.post {
                                    lastError = error.javaClass.simpleName + ": " + error.message
                                }
                            }
                    },
                    0,
                    interval,
                    TimeUnit.MILLISECONDS,
                )
    }

    var lastError: String? = null
}
