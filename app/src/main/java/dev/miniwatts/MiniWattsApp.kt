package dev.miniwatts

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.miniwatts.core.Capacity
import dev.miniwatts.core.CapacityEstimate
import dev.miniwatts.core.Sample
import dev.miniwatts.core.ThermalSnapshot
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

    /**
     * Optional privileged channel. Off by default: it is bound only while the user enabled it and
     * sampling runs. When it is off, or refused by policy, every privileged reading stays null.
     */
    val privileged = PrivilegedReader(app, versionCode())

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
    private var thermalAt = 0L

    /**
     * Latest thermal snapshot, kept out of [Sample] on purpose: a device can expose ~80 zones and
     * storing them per sample would bloat every row. Refreshed on the io thread and throttled.
     */
    @Volatile
    var thermal: ThermalSnapshot? = null
        private set

    val interval: Long
        get() = prefs.getInt("interval", 1).coerceIn(1, 30) * 1000L

    val privilegedEnabled: Boolean
        get() = prefs.getBoolean("shizuku", false)

    /** io thread only. Returns the cached snapshot when the throttle window has not elapsed. */
    fun refreshThermal(monoMs: Long, force: Boolean = false): ThermalSnapshot? {
        if (!force && monoMs - thermalAt < THERMAL_REFRESH_MS) return thermal
        thermalAt = monoMs
        thermal = runCatching { ThermalReader().read() }.getOrNull()
        return thermal
    }

    /**
     * Session-only history of the CPU frequency ratio, for the performance page. Deliberately not
     * persisted: it shows the shape of the last few minutes and is dropped on restart. A pause longer
     * than [PERF_GAP_MS] inserts a null so the chart shows a break instead of a straight line across
     * unobserved time.
     */
    private val perf = ArrayDeque<Double?>()
    private var perfAt = 0L

    /** Main thread only. */
    fun perfHistory(): List<Double?> = perf.toList()
    /** Main thread only. */
    private fun recordPerf(monoMs: Long, ratio: Double?) {
        if (perf.isNotEmpty() && monoMs - perfAt < PERF_MIN_GAP_MS) return
        if (perfAt != 0L) {
            when {
                monoMs <= perfAt -> {
                    perf.clear()
                    perfAt = 0L
                }
                monoMs - perfAt > PERF_GAP_MS -> perf.addLast(null)
            }
        }
        perfAt = monoMs
        perf.addLast(ratio)
        while (perf.size > PERF_POINTS) perf.removeFirst()
    }

    /**
     * io thread. Coarse full-charge-capacity estimates from recent sessions, newest first. This is the
     * only capacity number available without the privileged channel, so it is returned as an estimate
     * with the span it came from rather than as a measurement.
     *
     * The design capacity from the privileged channel, when it is available, tightens the sanity
     * check: an estimate that contradicts a measured design capacity is dropped instead of shown.
     */
    fun capacityEstimates(maxSessions: Int = 20): List<CapacityEstimate> {
        val design = privileged.latest?.adapter?.designCapacityUah?.toLong()
        return runCatching { store.sessions() }
            .getOrDefault(emptyList())
            .take(maxSessions)
            .mapNotNull { session ->
                val window = runCatching { store.chargeWindows(session.id) }.getOrDefault(emptyList())
                Capacity.estimate(window, session.id)
                    ?.takeIf { Capacity.plausible(it.fullUah, design) }
            }
    }

    private fun versionCode(): Int =
        runCatching {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
                else
                    @Suppress("DEPRECATION") info.versionCode
            }
            .getOrDefault(1)

    fun updateSampling() {
        syncPrivileged()
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
                                val probe = privileged.probe()
                                val s =
                                    SensorReader(app)
                                        .read(interval, probe?.adapter)
                                        .copy(continuity = continuity)
                                val snapshot = refreshThermal(s.monoMs)
                                store.ingest(s, prefs.getInt("retention", 60))
                                prefs.edit().putString("latest", s.json().toString()).apply()
                                main.post {
                                    lastError = null
                                    latest = s
                                    recent.addLast(s)
                                    while (recent.size > 180) recent.removeFirst()
                                    recordPerf(
                                        s.monoMs,
                                        snapshot?.cpu?.mapNotNull { it.ratio }?.maxOrNull(),
                                    )
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

    /** Binds the Shizuku user service only while it is enabled and sampling is actually running. */
    private fun syncPrivileged() {
        val want = privilegedEnabled && (foreground || recording)
        val bind = { if (want) privileged.connect() else privileged.disconnect() }
        if (Looper.myLooper() == Looper.getMainLooper()) bind() else main.post(bind)
    }

    var lastError: String? = null

    private companion object {
        /**
         * Thermal zones change slowly and a snapshot costs ~300 small file reads, so two seconds is
         * both fresh enough for the page and cheap enough to run next to sampling.
         */
        const val THERMAL_REFRESH_MS = 2000L

        /** The frequency-ratio history matches that cadence; 90 points is about three minutes. */
        const val PERF_MIN_GAP_MS = 1900L
        const val PERF_GAP_MS = 6000L
        const val PERF_POINTS = 90
    }
}
