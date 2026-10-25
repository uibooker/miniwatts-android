package dev.miniwatts

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.miniwatts.core.Adapter
import dev.miniwatts.core.CoolingDevice
import dev.miniwatts.core.CpuPolicy
import dev.miniwatts.core.ExportCatalog
import dev.miniwatts.core.ExportKind
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Session
import dev.miniwatts.core.SessionComparison
import dev.miniwatts.core.Thermal
import dev.miniwatts.core.ThermalGroup
import dev.miniwatts.core.ThermalSnapshot
import java.io.StringWriter
import kotlin.math.abs
import org.json.JSONObject

/** Runs rendering and persistence checks without adding a test framework to the application. */
class RegressionRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        check(targetContext.packageName.endsWith(".macdebug")) {
            "Run regressions with -PisolatedDebug=true to preserve the installed app's data."
        }
        val tests = listOf(
            "gradientSurvivesRepeatedDraws" to ::gradientSurvivesRepeatedDraws,
            "gaugeLabelsAreCentered" to ::gaugeLabelsAreCentered,
            "conflictWarningsFollowReadingsAndHistory" to ::conflictWarningsFollowReadingsAndHistory,
            "conflictSurvivesSampleRetentionAndRestart" to ::conflictSurvivesSampleRetentionAndRestart,
            "legacyHistoryMigratesWithoutLosingData" to ::legacyHistoryMigratesWithoutLosingData,
            "fiveTabsOpenTheirOwnScreens" to ::fiveTabsOpenTheirOwnScreens,
            "thermalZonesAreReadableAndConsistent" to ::thermalZonesAreReadableAndConsistent,
            "powerChartOverlaysAdapterInput" to ::powerChartOverlaysAdapterInput,
            "summaryAddsAdapterOnlyWhenMeasured" to ::summaryAddsAdapterOnlyWhenMeasured,
            "thermalDetailRendersZonesAndFrequency" to ::thermalDetailRendersZonesAndFrequency,
            "capacityEstimateReadsStoredChargeSpan" to ::capacityEstimateReadsStoredChargeSpan,
            // The UI journeys run before `inputEnergyMigratesAndAccumulates`, which deletes the
            // database. The store connection the activity reads through is opened by the test above;
            // a delete afterwards would leave it pointing at a moved file, and its next write would
            // fail with SQLITE_READONLY_DBMOVED.
            "historyOffersTheComparisonSelection" to ::historyOffersTheComparisonSelection,
            "foregroundSurvivesARotation" to ::foregroundSurvivesARotation,
            "inputEnergyMigratesAndAccumulates" to ::inputEnergyMigratesAndAccumulates,
            "probeColumnsLineUp" to ::probeColumnsLineUp,
            "exportCentreListsEveryDocument" to ::exportCentreListsEveryDocument,
            "comparisonRendersBothSessionsWithoutInventingValues" to
                ::comparisonRendersBothSessionsWithoutInventingValues,
        )
        var failures = 0
        tests.forEachIndexed { index, (name, test) ->
            val status = Bundle().apply {
                putString("id", "MiniWatts regressions")
                putString("class", RegressionRunner::class.java.name)
                putString("test", name)
                putInt("numtests", tests.size)
                putInt("current", index + 1)
            }
            sendStatus(1, status)
            val result = runCatching { test() }
            result.onFailure {
                failures++
                status.putString("stack", it.stackTraceToString())
            }
            sendStatus(if (result.isSuccess) 0 else -2, status)
        }
        finish(Activity.RESULT_OK, Bundle().apply {
            putInt("tests", tests.size)
            putInt("failures", failures)
            putString("stream", "Tests run: ${tests.size}, failures: $failures\n")
        })
    }

    private fun onUiThread(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { runCatching(action).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private fun gradientSurvivesRepeatedDraws() = onUiThread {        val ui = UiKit(targetContext)
        val drawable = GridBackground(ui)
        val size = ui.dp(104)
        drawable.setBounds(0, 0, size, size)
        val first = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawable.draw(Canvas(first))
            drawable.draw(Canvas(second))
            val x = ui.dp(13)
            check(first.getPixel(x, x) == second.getPixel(x, x)) {
                "Gradient color changed after redraw: " +
                    "${first.getPixel(x, x).toUInt().toString(16)} -> " +
                    second.getPixel(x, x).toUInt().toString(16)
            }
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    private fun gaugeLabelsAreCentered() = onUiThread {
        val ui = UiKit(targetContext)
        val gauge = PowerGauge(targetContext, ui)
        val width = ui.dp(320)
        val height = ui.dp(264)
        gauge.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ui.dp(400), View.MeasureSpec.AT_MOST),
        )
        gauge.layout(0, 0, width, gauge.measuredHeight)
        check(gauge.getChildAt(0).height == gauge.height) {
            "Arc layer disappeared when the gauge was measured inside a card."
        }
        gauge.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        gauge.layout(0, 0, width, height)
        val gaugeLabels = labels(gauge)
        check(gaugeLabels.size == 4)
        val reading = gaugeLabels[0].parent as View
        val center = reading.parent as View
        check(abs(center.left + reading.left + reading.width / 2f - width / 2f) <= 2f) {
            "Number and inline W unit are not centered together."
        }
        val descriptions = gaugeLabels.drop(2)
        descriptions.forEach { label ->
            val layout = label.layout
            var origin = label.left + label.totalPaddingLeft
            var parent = label.parent
            while (parent is View && parent !== gauge) {
                origin += parent.left
                parent = parent.parent
            }
            val center = origin + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2
            check(abs(center - width / 2f) <= 2f) {
                "Gauge label '${label.text}' is at $center; expected ${width / 2f}."
            }
        }
    }

    private fun sample(time: Long, current: Int) = Sample(
        wallMs = 100_000 + time,
        monoMs = time,
        boot = 1,
        level = 50,
        plugged = 1,
        status = 2,
        health = 2,
        currentUa = current,
        averageUa = null,
        voltageMv = 4000,
        temperatureDeciC = 300,
        chargeUah = null,
        energyNwh = null,
        cycles = null,
        thermal = null,
        intervalMs = 1000,
    )

    private fun labels(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    /**
     * The power chart draws the measured adapter input as a second line on the shared watt scale.
     * The overlay colour may appear only when an input value exists, and a series full of gaps must
     * still draw without throwing.
     */
    private fun powerChartOverlaysAdapterInput() = onUiThread {
        val ui = UiKit(targetContext)
        val input = Adapter(inputVoltageUv = 5_000_000, inputCurrentUa = 1_000_000)
        val withInput = (0..5).map { sample(it * 1000L, 500_000).copy(adapter = input) }
        val mixed = withInput.take(3) + withInput.drop(3).map { it.copy(adapter = null) }

        check(hasPixel(drawPowerChart(ui, withInput), ui.green)) {
            "Adapter input line is missing when the input was measured."
        }
        check(hasPixel(drawPowerChart(ui, mixed), ui.green)) {
            "Adapter input line is missing in a series that also has gaps."
        }
        check(!hasPixel(drawPowerChart(ui, withInput.map { it.copy(adapter = null) }), ui.green)) {
            "Adapter input line was drawn without any measured input."
        }
    }

    /**
     * The capacity estimate reads the charge span back out of the database through the real store
     * query and only reports a number for a session that satisfies the criteria.
     */
    private fun capacityEstimateReadsStoredChargeSpan() {
        val engine = (targetContext.applicationContext as MiniWattsApp).engine
        // A fresh boot forces a new session, so this never mixes with whatever the picture already had.
        val base = 5_000_000L
        engine.store.ingest(
            sample(0, 500_000)
                .copy(boot = 99, monoMs = base, wallMs = base, level = 40, chargeUah = 2_000_000),
            1500,
        )
        engine.store.ingest(
            sample(0, 500_000)
                .copy(
                    boot = 99,
                    monoMs = base + 60_000,
                    wallMs = base + 60_000,
                    level = 60,
                    chargeUah = 3_080_000,
                ),
            1500,
        )
        val session = engine.store.sessions().first()
        val estimates = engine.capacityEstimates()
        val mine = estimates.firstOrNull { it.sessionId == session.id }
        checkNotNull(mine) {
            "No estimate for the session that spans 40% -> 60%. Got: $estimates"
        }
        check(mine.fullUah == 5_400_000L) { "Expected 5400 mAh, got ${mine.fullUah} uAh." }
        check(mine.levelDelta == 20) { "Expected a 20 point span, got ${mine.levelDelta}." }

        // A second session with too small a rise must not produce an estimate for that session.
        engine.store.ingest(
            sample(0, 500_000)
                .copy(boot = 100, monoMs = base, wallMs = base, level = 50, chargeUah = 2_000_000),
            1500,
        )
        engine.store.ingest(
            sample(0, 500_000)
                .copy(
                    boot = 100,
                    monoMs = base + 60_000,
                    wallMs = base + 60_000,
                    level = 55,
                    chargeUah = 2_200_000,
                ),
            1500,
        )
        val short = engine.store.sessions().first()
        check(engine.capacityEstimates().none { it.sessionId == short.id }) {
            "A 5 point span must not produce a capacity estimate."
        }
    }

    private fun drawPowerChart(ui: UiKit, samples: List<Sample>): Bitmap {        val chart = ChartView(targetContext, adapterInput = true)
        chart.samples = samples
        val width = ui.dp(320)
        val height = ui.dp(170)
        chart.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        chart.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bitmap))
        return bitmap
    }

    private fun hasPixel(bitmap: Bitmap, color: Int): Boolean {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) == color) return true
        }
        return false
    }

    /**
     * Clicks a labelled action row by walking up to the ancestor that owns the click listener.
     *
     * `ui.action` rows carry their label on a child TextView and no content description, so the row
     * itself is what has to receive the click.
     */
    private fun clickLabel(root: View, label: String) = onUiThread { clickLabelOnMain(root, label) }

    /** [clickLabel] for callers that already run inside an [onUiThread] block. */
    private fun clickLabelOnMain(root: View, label: String) {
        val text =
            labels(root).firstOrNull { it.text.toString() == label }
                ?: error("No row labelled \"$label\".")
        var node: View = text
        while (!node.isClickable && node.parent is View) node = node.parent as View
        check(
            node.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                null,
            )
        ) { "The row \"$label\" did not accept a click." }
    }

    private fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()

    /**
     * The performance detail page renders the zones, the mitigation levels and the frequency history
     * from the snapshot it is given, and the thermal page's entry row actually opens it.
     */
    private fun thermalDetailRendersZonesAndFrequency() {
        val prefs = targetContext.getSharedPreferences("settings", 0)
        val originalLanguage = prefs.getString("language", null)
        try {
            check(prefs.edit().putString("language", "zh").commit())
            onUiThread {
                val ui = UiKit(targetContext)
                val words = Words(targetContext)
                val snapshot =
                    ThermalSnapshot(
                        zones =
                            listOf(
                                Thermal.zone("cpu-0-1-1", "74900")!!,
                                Thermal.zone("battery", "27600")!!,
                                Thermal.zone("sdr0", "-273000")!!,
                            ),
                        cooling =
                            listOf(
                                CoolingDevice("cpufreq-cpu0", 3, 15),
                                CoolingDevice("cpu-hotplug0", 0, 1),
                            ),
                        cpu = listOf(CpuPolicy("policy0", 2_400_000, 3_532_800)),
                        skipped = listOf("vbat"),
                        denied = listOf("wireless"),
                    )
                val history = listOf(0.5, 0.9, null, 0.7)

                var opened = false
                val summaryPage =
                    LivePanels(ui, words, false, { snapshot }, { opened = true }, { emptyList() })
                val summary = LinearLayout(targetContext)
                summaryPage.temperature(summary)
                summaryPage.update(sample(0, 500_000), emptyList())
                val entry =
                    labels(summary).firstOrNull { it.text.toString().contains("全部热区") }
                        ?: error("Thermal page has no entry to the performance detail page.")
                (entry.parent as View).performClick()
                check(opened) { "The entry row did not open the performance detail page." }

                val page = LinearLayout(targetContext)
                val detail =
                    LivePanels(ui, words, false, { snapshot }, {}, { history })
                detail.thermalDetail(page)
                detail.update(sample(0, 500_000), emptyList())
                val texts = labels(page).map { it.text.toString() }

                // Zone list: hottest first, absent sensor kept out of the numbers.
                check(texts.any { it.contains("cpu-0-1-1") && it.contains("74.9") }) {
                    "Zone list is missing the hottest CPU zone: $texts"
                }
                check(texts.any { it.contains("battery") && it.contains("27.6") }) {
                    "Zone list is missing the battery zone."
                }
                check(texts.none { it.contains("sdr0") }) {
                    "An absent sensor must not appear among the measured zones."
                }
                check(texts.any { it.contains("1 / 2") }) {
                    "Mitigation count is missing."
                }
                check(texts.any { it.contains("cpufreq-cpu0") }) {
                    "Mitigation list is missing the frequency cap."
                }
                check(texts.any { it.contains("policy0") && it.contains("2.40") }) {
                    "CPU policy row is missing."
                }
                check(texts.any { it.contains("vbat") && it.contains("wireless") }) {
                    "Excluded and refused node names must be reported."
                }
                val bars = views(page).filterIsInstance<RatioHistoryView>().singleOrNull()
                    ?: error("Frequency history view is missing.")
                check(bars.values == history) { "History values were not bound: ${bars.values}" }
                check(texts.any { it.contains("3 个采样点") }) {
                    "History note must count only the measured points: $texts"
                }
            }
        } finally {
            prefs
                .edit()
                .apply {
                    if (originalLanguage == null) remove("language")
                    else putString("language", originalLanguage)
                }
                .commit()
        }
    }

    /**
     * The shared notification / floating meter / widget summary gains the adapter line only when the
     * privileged channel actually measured the input rail, so a missing channel never reads as a zero.
     */
    private fun summaryAddsAdapterOnlyWhenMeasured() {
        val prefs = targetContext.getSharedPreferences("settings", 0)
        val original = prefs.getString("language", null)
        try {
            check(prefs.edit().putString("language", "zh").commit())
            val words = Words(targetContext)
            val plain = words.summary(sample(0, 500_000), false)
            val measured =
                words.summary(
                    sample(0, 500_000)
                        .copy(
                            adapter =
                                Adapter(
                                    inputVoltageUv = 5_000_000,
                                    inputCurrentUa = 1_000_000,
                                )
                        ),
                    false,
                )
            check(!plain.contains("适配器输入")) {
                "Summary mentions adapter input without a measurement."
            }
            check(measured.contains("适配器输入") && measured.contains("实测")) {
                "Summary is missing the measured adapter input line: $measured"
            }
            check(measured.lines().size == plain.lines().size + 1) {
                "Adapter input must add exactly one line."
            }
        } finally {
            prefs
                .edit()
                .apply {
                    if (original == null) remove("language") else putString("language", original)
                }
                .commit()
        }
    }

    private fun conflictWarningsFollowReadingsAndHistory() = onUiThread {
        val prefs = targetContext.getSharedPreferences("settings", 0)
        val originalLanguage = prefs.getString("language", null)
        try {
            for (language in listOf("zh", "en")) {
                check(prefs.edit().putString("language", language).commit())
                val ui = UiKit(targetContext)
                val words = Words(targetContext)
                // A headline cell power is a magnitude: the arc has always been drawn that way, and on
                // this device a signed headline reads inverted while the phone charges. The signed
                // value has to survive in the model, the chart and the export.
                val negative = sample(0, -1_000_000)
                check(negative.powerW!! < 0) { "The fixture must be negative." }
                check(words.magnitude(negative.powerW) == 4.0) { "Magnitude is not the absolute value." }
                // Direction is stated in words, so the headline number can be a magnitude.
                check(words.cellDirection(negative.powerW) == words.t("电芯放电", "cell discharging"))
                check(words.cellDirection(4.0) == words.t("电芯充电", "cell charging"))
                check(words.cellDirection(0.0) == words.t("电芯净电流为零", "cell current ~0"))
                check(words.cellDirection(null) == null) { "No reading must mean no direction." }
                val gauge = PowerGauge(targetContext, ui)
                // Already inside onUiThread: this test body runs on the main thread.
                gauge.update(negative.powerW, null)
                val reading = labels(gauge).first().text.toString()
                check(!reading.contains('-')) { "The gauge printed a signed headline: $reading" }
                check(reading.startsWith("4.0")) { "The gauge lost the magnitude: $reading" }
                // The badge states the concrete situation, and only on the pages that do not already
                // show both facts as their own pills.
                val dischargingBadge = words.stateAndCellDisagree(2, -4.0)
                checkNotNull(dischargingBadge) { "A charging state with a draining cell must be stated." }
                check(dischargingBadge.length <= 48) {
                    "The live badge grew into a paragraph: $dischargingBadge"
                }
                check(words.signConflictHelp().length >= dischargingBadge.length * 3) {
                    "The explanation behind the badge must live in Help, not on a live page."
                }
                // Nothing to say when the two agree, or when there is no reading.
                check(words.stateAndCellDisagree(2, 4.0) == null)
                check(words.stateAndCellDisagree(3, -4.0) == null)
                check(words.stateAndCellDisagree(2, null) == null)
                check(words.stateAndCellDisagree(null, -4.0) == null)
                // The other order gets its own sentence rather than the same one.
                val chargingBadge = words.stateAndCellDisagree(3, 4.0)
                checkNotNull(chargingBadge)
                check(chargingBadge != dischargingBadge) {
                    "Both orders must not share one sentence: $chargingBadge"
                }

                // Adapter page: the badge appears, states the situation, and follows the reading.
                val adapterPage = ui.column()
                val adapterPanels = LivePanels(ui, words, false)
                adapterPanels.charger(adapterPage)
                // The badge only takes its text once a disagreeing reading arrives, so push one
                // before looking it up.
                adapterPanels.update(sample(0, -1_000_000), emptyList())
                val badge = labels(adapterPage).first { it.text == dischargingBadge }
                for ((reading, expected) in listOf(
                    null to View.GONE,
                    sample(0, 0) to View.GONE,
                    sample(0, 1_000_000) to View.GONE,
                    sample(0, -1_000_000) to View.VISIBLE,
                    sample(0, 1_000_000) to View.GONE,
                )) {
                    adapterPanels.update(reading, emptyList())
                    check(badge.visibility == expected) {
                        "Badge visibility is wrong for $language / $reading."
                    }
                }

                // Power page: the state pill and the cell-direction pill carry it, and the badge is
                // deliberately absent so the page does not say the same thing twice.
                val powerPage = ui.column()
                val powerPanels = LivePanels(ui, words, false)
                powerPanels.power(powerPage)
                powerPanels.update(sample(0, -1_000_000), emptyList())
                val powerTexts = labels(powerPage).map { it.text.toString() }
                check(powerTexts.any { it == words.cellDirection(-4.0) }) {
                    "The power page must state the cell's own direction: $powerTexts"
                }
                check(powerTexts.none { it == dischargingBadge }) {
                    "The power page repeats the disagreement instead of showing both facts: $powerTexts"
                }

                val session = Session(1, 100_000, null, 50, 51, 1, 20_000, 120_000,
                    energyWh = -0.2, coveredMs = 20_000, signConflictObserved = true)
                val history = HistoryPanels(ui, words)
                val list = ui.column()
                history.list(list, listOf(session), emptyMap(), open = {})
                check(labels(list).count { it.text == words.sessionSignConflict() } == 1)
                check(labels(list).count { it.text == words.summarySignConflict() } == 1)
                // A session whose cell net discharged has no "share that reached the cell", and that
                // has to be stated rather than reported as a negative percentage. Both of its own
                // figures are still shown.
                val netDischarged =
                    Session(
                        7,
                        100_000,
                        200_000,
                        50,
                        60,
                        1,
                        100_000,
                        200_000,
                        energyWh = -0.2,
                        coveredMs = 100_000,
                        inputWh = 0.25,
                        inputCoveredMs = 100_000,
                        signConflictObserved = true,
                    )
                check(netDischarged.cellShareOfInputPct == null) {
                    "A net-discharged session must not report a share."
                }
                val dischargedPage = ui.column()
                val dischargedPanels = LivePanels(ui, words, false)
                dischargedPanels.power(dischargedPage)
                dischargedPanels.updateSession(netDischarged)
                val dischargedTexts = labels(dischargedPage).map { it.text.toString() }
                check(dischargedTexts.any { it == words.n(0.25, "Wh", 3) }) {
                    "The measured input energy is missing: $dischargedTexts"
                }
                check(dischargedTexts.any { it == words.n(-0.2, "Wh", 3) }) {
                    "The net cell energy is missing: $dischargedTexts"
                }
                check(
                    dischargedTexts.any {
                        it.contains(words.t("没有「进到电芯的比例」可言", "no share that reached it"))
                    }
                ) { "The withheld share was not explained: $dischargedTexts" }

                // The same session with a net-charging cell does report the share.
                val netCharged = netDischarged.copy(energyWh = 0.2)
                check(abs(netCharged.cellShareOfInputPct!! - 80.0) < 1e-9) {
                    "A net-charging session must report its share."
                }

                val detail = ui.column()
                history.detail(detail, session, false)
                check(labels(detail).count { it.text == words.sessionSignConflict() } == 1)
                // The history detail must show a measured rail instead of the old placeholder, and
                // show the share only for a session that net charged.
                val measured = session.copy(inputWh = 0.25, inputCoveredMs = 20_000, energyWh = 0.2)
                val measuredDetail = ui.column()
                history.detail(measuredDetail, measured, false)
                val measuredTexts = labels(measuredDetail).map { it.text.toString() }
                check(measuredTexts.none { it == "— Wh" }) {
                    "The history detail still shows the adapter-input placeholder: $measuredTexts"
                }
                check(measuredTexts.any { it == words.n(0.25, "Wh", 3) }) {
                    "The history detail did not show the measured adapter input: $measuredTexts"
                }
                check(measuredTexts.any { it == words.n(measured.cellShareOfInputPct, "%", 1) }) {
                    "The history detail did not show the share of a net-charging session: $measuredTexts"
                }
                val dischargedDetail = ui.column()
                history.detail(dischargedDetail, measured.copy(energyWh = -0.2), false)
                check(labels(dischargedDetail).map { it.text.toString() }.count {
                    it == words.t("不可用", "Unavailable")
                } > 0) { "A net-discharged session must read unavailable for its share." }
                val normal = ui.column()
                history.list(
                    normal,
                    listOf(session.copy(signConflictObserved = false)),
                    emptyMap(),
                    open = {},
                )
                check(labels(normal).none {
                    it.text == words.sessionSignConflict() || it.text == words.summarySignConflict()
                })
            }
        } finally {
            val editor = prefs.edit()
            if (originalLanguage == null) editor.remove("language")
            else editor.putString("language", originalLanguage)
            check(editor.commit())
        }
    }

    private fun exportedSessions(store: Store) = StringWriter().let {
        store.export(it, true)
        JSONObject(it.toString()).getJSONArray("sessions")
    }

    private fun fiveTabsOpenTheirOwnScreens() {
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        // Launch through the shell to avoid OEM background-activity restrictions on the runner.
        val launch = uiAutomation.executeShellCommand(
            "am start -W -n ${targetContext.packageName}/${MainActivity::class.java.name}"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
        val activity = waitForMonitorWithTimeout(monitor, 10_000)
            ?: error("MainActivity did not launch within 10 seconds.")
        removeMonitor(monitor)
        try {
            val words = Words(targetContext)
            val expected = listOf(
                words.t("功率", "Power") to words.t("供电路径", "Power path"),
                words.t("发热", "Thermal") to words.t("系统发热状态", "System thermal state"),
                words.t("适配器", "Adapter") to words.t("握手信息", "Handshake"),
                words.t("设备", "Device") to words.t("配件", "Accessories"),
                words.t("历史记录", "History") to words.t("读取本地历史…", "Loading local history…"),
            )
            fun views(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
                else emptyList()
            onUiThread {
                val root = activity.window.decorView
                expected.forEach { (tab, marker) ->
                    val navigation = views(root).single { it.contentDescription?.toString() == tab }
                    check(navigation.performClick())
                    check(navigation.isSelected) { "Tab $tab was not selected." }
                    check(labels(root).any { it.text.toString() == marker }) { "$tab did not open $marker." }
                }
                views(root).single { it.contentDescription?.toString() == words.t("设备", "Device") }.performClick()
                check(views(root).single { it.contentDescription?.toString() == words.t("设置", "Settings") }.visibility == View.INVISIBLE)

                // The adapter page must not advertise a bypass feature. The optional KernelSU module
                // it used to report was never built (see BYPASS_CHARGING_CONCLUSION.md), so the card
                // reported a state that could never arrive and its absence is the contract now.
                views(root).single { it.contentDescription?.toString() == words.t("适配器", "Adapter") }
                    .performClick()
                val adapterText = labels(root).map { it.text.toString() }
                check(adapterText.any { it == words.t("握手信息", "Handshake") }) {
                    "The adapter page did not survive the bypass card removal."
                }
                check(adapterText.any { it == words.t("线缆与连接", "Cable & connection") }) {
                    "The adapter page lost its cable card."
                }
                val bypassWord = words.t("旁路", "Bypass")
                check(adapterText.none { it.contains(bypassWord) }) {
                    "The adapter page still shows a bypass card: " +
                        adapterText.filter { it.contains(bypassWord) }
                }

                // The full conflict explanation has to be reachable: the live pages only carry a badge.
                views(root).single { it.contentDescription?.toString() == words.t("功率", "Power") }.performClick()
                views(root).single { it.contentDescription?.toString() == words.t("设置", "Settings") }.performClick()
                clickLabelOnMain(root, words.t("离线帮助与数据边界", "Offline help & reading boundaries"))
                check(labels(root).any { it.text.toString() == words.signConflictHelp() }) {
                    "The help page does not explain the sign conflict."
                }
            }
        } finally {
            onUiThread { activity.finish() }
        }
    }

    /**
     * Runs the real thermal reader on the device and asserts its contract rather than a fixed zone
     * count, so it stays valid on a device without a readable thermal class while still proving the
     * path end to end on this one.
     */
    private fun thermalZonesAreReadableAndConsistent() {
        val snapshot = ThermalReader().read()
        snapshot.zones.forEach { zone ->
            check(zone.deciC == null || zone.deciC!! in -400..1500) { "${zone.name} out of range" }
            check(Thermal.isTemperature(zone.name)) { "${zone.name} is not a temperature but was kept" }
            check(Thermal.group(zone.name) == zone.group) { "${zone.name} group mismatch" }
            check(zone.name !in snapshot.denied) { "${zone.name} is both readable and refused" }
        }
        snapshot.skipped.forEach { check(!Thermal.isTemperature(it)) { "$it should not be skipped" } }
        check(snapshot.measured.size == snapshot.zones.count { it.deciC != null })
        snapshot.cooling.forEach { check(it.name.isNotBlank()) { "cooling device without a name" } }
        snapshot.cpu.forEach { check(it.name.startsWith("policy")) { "unexpected policy ${it.name}" } }
        if (snapshot.zones.isNotEmpty())
            check(snapshot.measured.size >= snapshot.zones.size / 2) {
                "Most enumerated zones should be readable: ${snapshot.measured.size}/${snapshot.zones.size}"
            }
        android.util.Log.i(
            "MiniWattsRegression",
            "thermal zones=${snapshot.zones.size} measured=${snapshot.measured.size} " +
                "skipped=${snapshot.skipped} denied=${snapshot.denied} " +
                "cooling=${snapshot.cooling.size} active=${snapshot.activeCooling.size} " +
                "cpu=${snapshot.cpu.map { "${it.name}:${it.currentKhz}/${it.maxKhz}" }}",
        )
    }

    /**
     * The v2 -> v3 migration adds the input-energy columns. A session recorded before the migration
     * must survive and must report "never measured" rather than a 0 Wh input, while sessions recorded
     * afterwards accumulate both integrals.
     */
    private fun inputEnergyMigratesAndAccumulates() {
        targetContext.deleteDatabase("miniwatts.db")
        val legacy = targetContext.openOrCreateDatabase("miniwatts.db", 0, null)
        try {
            legacy.execSQL(
                "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER,end INTEGER,startLevel INTEGER,endLevel INTEGER,boot INTEGER,lastMono INTEGER,lastWall INTEGER,wh REAL,covered INTEGER,peak REAL,temp INTEGER,recovery INTEGER DEFAULT 0,signConflict INTEGER NOT NULL DEFAULT 0)"
            )
            legacy.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,session INTEGER,data TEXT)")
            legacy.execSQL("CREATE INDEX samples_session ON samples(session,id)")
            legacy.execSQL(
                "INSERT INTO sessions VALUES(?,100000,120000,50,51,1,20000,120000,0.5,20000,4,300,0,0)",
                arrayOf(11L),
            )
            legacy.version = 2
        } finally {
            legacy.close()
        }
        val store = Store(targetContext)
        try {
            val old = store.sessions().single()
            check(old.id == 11L) { "Migration lost the legacy session." }
            check(old.energyWh == 0.5 && old.coveredMs == 20_000L) { "Migration changed the old totals." }
            check(!old.hasInputCoverage) {
                "A session recorded before the migration must not claim input coverage."
            }
            check(old.cellShareOfInputPct == null) { "No coverage must mean no ratio." }
            val exported = exportedSessions(store).getJSONObject(0)
            check(exported.isNull("inputWh")) { "An unmeasured input must export as null, not 0." }
            check(exported.getLong("inputCoveredMs") == 0L)
            check(exported.isNull("cellShareOfInputPct"))

            // Now record a charging session with a measured input rail: 10 W in, 8 W into the cell.
            val adapter = Adapter(inputVoltageUv = 5_000_000, inputCurrentUa = 2_000_000)
            store.ingest(
                sample(0, 2_000_000)
                    .copy(boot = 7, monoMs = 1_000_000, wallMs = 1_000_000, adapter = adapter),
                60,
            )
            store.ingest(
                sample(0, 2_000_000)
                    .copy(
                        boot = 7,
                        monoMs = 1_002_000,
                        wallMs = 1_002_000,
                        level = 51,
                        adapter = adapter,
                    ),
                60,
            )
            val fresh = store.sessions().first()
            check(fresh.id != old.id) { "The new samples did not open a new session." }
            check(fresh.hasInputCoverage) { "The new session did not record input coverage." }
            check(fresh.inputCoveredMs == 2_000L) { "inputCoveredMs=${fresh.inputCoveredMs}" }
            check(abs(fresh.inputWh - 0.005556) < 1e-5) { "inputWh=${fresh.inputWh}" }
            check(abs(fresh.energyWh - 0.004444) < 1e-5) { "energyWh=${fresh.energyWh}" }
            check(abs(fresh.cellShareOfInputPct!! - 80.0) < 0.1) {
                "share=${fresh.cellShareOfInputPct}"
            }
        } finally {
            store.close()
            targetContext.deleteDatabase("miniwatts.db")
        }
    }

    /**
     * The thermal probe table must line its value column up whatever the group label is. Character
     * padding inside one monospace blob cannot do that, because the platform renders the CJK labels
     * from a proportional fallback font, so the group name is a real left column.
     */
    private fun probeColumnsLineUp() = onUiThread {
        val ui = UiKit(targetContext)
        val words = Words(targetContext)
        val snapshot =
            ThermalSnapshot(
                zones =
                    listOf(
                        Thermal.zone("battery", "27600")!!,
                        Thermal.zone("charger_therm0", "30492")!!,
                        Thermal.zone("cpu-0-1-1", "33600")!!,
                        Thermal.zone("pa_therm0", "31647")!!,
                        Thermal.zone("display_therm", "27625")!!,
                    ),
                cooling = emptyList(),
                cpu = emptyList(),
                skipped = emptyList(),
                denied = emptyList(),
            )
        val page = LinearLayout(targetContext)
        val live = LivePanels(ui, words, false, { snapshot }, {}, { emptyList() })
        live.temperature(page)
        live.update(sample(0, 500_000), emptyList())
        page.measure(
            View.MeasureSpec.makeMeasureSpec(ui.dp(360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ui.dp(2400), View.MeasureSpec.AT_MOST),
        )
        page.layout(0, 0, page.measuredWidth, page.measuredHeight)

        val groupLabels = ThermalGroup.values().map { words.thermalGroup(it) }.toSet()
        val rows =
            views(page)
                .filterIsInstance<LinearLayout>()
                .filter { row ->
                    // A probe row is a group label plus a monospace value cell. The schematic's own
                    // metric cells also have two children and one is titled "Battery", so the
                    // monospace cell is what tells them apart.
                    if (row.childCount != 2) return@filter false
                    val label = (row.getChildAt(0) as? TextView)?.text?.toString()
                    val value = row.getChildAt(1) as? TextView
                    label in groupLabels && value?.typeface == Typeface.MONOSPACE
                }
        check(rows.size == 5) {
            "Expected five probe rows, got ${rows.size}: " +
                rows.map { (it.getChildAt(0) as TextView).text }
        }

        fun columnStart(row: LinearLayout): Int {
            var x = row.getChildAt(1).left
            var parent = row.parent
            while (parent is View && parent !== page) {
                x += parent.left
                parent = parent.parent
            }
            return x
        }
        val starts = rows.map { columnStart(it) }
        check(starts.distinct().size == 1) {
            "Probe value column starts at $starts; every row must share one x."
        }
        // The monospace value cell pads the zone name to a fixed character count, so every
        // temperature also begins at one index.
        val valueCells = rows.map { (it.getChildAt(1) as TextView).text.toString() }
        val degreeIndexes = valueCells.map { it.indexOf("°C") }
        check(degreeIndexes.all { it > 0 } && degreeIndexes.distinct().size == 1) {
            "Temperatures start at $degreeIndexes; expected one index. Cells: $valueCells"
        }
    }

    /**
     * The export centre must reach every document this build can write, and the two documents it
     * assembles outside [Store] must carry the real device identity, the current boundary text and a
     * probe section that keeps its newlines.
     */
    private fun exportCentreListsEveryDocument() {
        val entries = ExportCatalog.catalog()
        check(entries.size == ExportKind.values().size) {
            "The export centre lists ${entries.size} of ${ExportKind.values().size} formats."
        }
        check(entries.map { it.kind }.toSet() == ExportKind.values().toSet()) {
            "The export centre is missing ${ExportKind.values().toSet() - entries.map { it.kind }.toSet()}."
        }
        entries.forEach { entry ->
            check(entry.fileName.contains('.')) { "${entry.kind} has no file extension." }
            check(!entry.fileName.contains(' ')) { "${entry.kind} file name has a space: ${entry.fileName}" }
        }

        val words = Words(targetContext)
        val adapter = Adapter(inputVoltageUv = 5_000_000, inputCurrentUa = 2_000_000)
        val sample = sample(0, 2_000_000).copy(adapter = adapter)
        val privileged =
            JSONObject()
                .apply {
                    put("enabled", true)
                    put("status", "ready")
                    put("uid", 0)
                    put("selinux", "u:r:su:s0")
                    put("refusedNodes", org.json.JSONArray(listOf("usb/type")))
                    put("missingNodes", org.json.JSONArray())
                    put("raw", "usb.voltage_now=5096000\nbattery.charge_full=5337000")
                }
        val thermal =
            JSONObject()
                .apply {
                    put("state", "unavailable")
                    put("zones", org.json.JSONArray())
                }
        val report =
            ExportCenter.diagnosticReport(
                sample,
                words,
                privileged,
                thermal,
                manufacturer = "Acme",
                model = "Fixture",
                api = 34,
                securityPatch = "2026-01-01",
                kernel = "6.6.1-test",
            )
        check(report.getString("manufacturer") == "Acme")
        check(report.getString("model") == "Fixture")
        check(report.getInt("api") == 34)
        check(report.getString("kernel") == "6.6.1-test")
        check(report.getString("boundary") == words.boundary()) {
            "The diagnostics report must carry the current boundary text."
        }
        check(report.getJSONObject("privilegedChannel").getInt("uid") == 0)
        // The sample is embedded through the same JSON the store writes, so the units stay attached.
        check(report.getJSONObject("sample").getDouble("powerW") == sample.powerW!!)
        check(report.getJSONObject("sample").getJSONObject("adapter").getDouble("inputW") == 10.0)

        // A missing sample is null rather than an empty object or a zeroed one.
        val withoutSample = ExportCenter.diagnosticReport(null, words, privileged, thermal)
        check(withoutSample.isNull("sample")) { "A missing sample must export as null." }

        val text = ExportCenter.rawProbeText("line1\nline2", "battery 27600")
        check(text.contains("line1\nline2")) { "The probe section lost its newlines." }
        check(text.contains("battery 27600")) { "The thermal section is missing." }
        val empty = ExportCenter.rawProbeText(null, "none")
        check(!empty.contains("null")) { "An absent probe must not print a Kotlin null." }
    }

    /**
     * The comparison page must show both sessions' real numbers and must keep every unmeasured field
     * unavailable instead of printing a 0, which is the whole point of the two-column layout.
     */
    private fun comparisonRendersBothSessionsWithoutInventingValues() {
        val prefs = targetContext.getSharedPreferences("settings", 0)
        val originalLanguage = prefs.getString("language", null)
        try {
            check(prefs.edit().putString("language", "zh").commit())
            onUiThread {
                val ui = UiKit(targetContext)
                val words = Words(targetContext)
                // A has a measured input rail; B was recorded without the privileged channel.
                val a =
                    Session(
                        id = 21,
                        startMs = 1_000_000,
                        endMs = 4_600_000,
                        startLevel = 40,
                        endLevel = 80,
                        boot = 1,
                        lastMonoMs = 3_600_000,
                        lastWallMs = 4_600_000,
                        energyWh = 12.0,
                        coveredMs = 3_600_000,
                        peakW = 20.0,
                        peakDeciC = 410,
                        inputWh = 15.0,
                        inputCoveredMs = 3_600_000,
                    )
                val b =
                    Session(
                        id = 22,
                        startMs = 2_000_000,
                        endMs = 3_800_000,
                        startLevel = 50,
                        endLevel = 60,
                        boot = 1,
                        lastMonoMs = 1_800_000,
                        lastWallMs = 3_800_000,
                        energyWh = 5.5,
                        coveredMs = 1_800_000,
                        peakW = 9.0,
                        // No peak temperature and no input coverage at all.
                    )
                val page = LinearLayout(targetContext)
                val panels = ComparePanels(ui, words, false)
                panels.page(page, SessionComparison.of(a, b)) {}
                panels.attach(listOf(sample(0, 2_000_000)), listOf(sample(0, 1_000_000)))

                // A comparison row is the title, the title's sub-label (only on an always-unavailable
                // row), then A, B and the delta, in that order. Reading by position inside the row
                // avoids depending on the rendered pixel size, which the display density scales.
                val cells = labels(page)
                fun cellAfter(title: String, offset: Int): String {
                    val index = cells.indexOfFirst { it.text.toString() == title }
                    check(index >= 0) { "Comparison row \"$title\" is missing." }
                    var first = index + 1
                    if (cells[first].textSize == cells[index].textSize) first++
                    val row = cells.drop(first).take(3)
                    check(row.size == 3) { "Comparison row \"$title\" is not a full row." }
                    return row[offset - 1].text.toString()
                }
                fun valueAfter(title: String): String = cellAfter(title, 1)
                fun deltaAfter(title: String): String = cellAfter(title, 3)

                check(valueAfter("电芯净能量") == words.n(12.0, "Wh", 3)) {
                    "A energy cell shows ${valueAfter("电芯净能量")}."
                }
                check(valueAfter("有效覆盖") == words.duration(3_600_000))
                check(deltaAfter("电芯净能量") == String.format(java.util.Locale.getDefault(), "%+.3f %s", 6.5, "Wh")) {
                    "Energy delta shows ${deltaAfter("电芯净能量")}."
                }
                check(deltaAfter("最高电池温度") == "不可用") {
                    "A missing peak temperature must not produce a delta: ${deltaAfter("最高电池温度")}."
                }
                // B never measured the input rail, so it is unavailable rather than 0 Wh.
                check(valueAfter("适配器输入能量") == words.n(15.0, "Wh", 3))
                check(labels(page).none { it.text.toString() == "0.000 Wh" }) {
                    "An unmeasured adapter input was rendered as a zero."
                }
                check(labels(page).count { it.text.toString() == "不可用" } >= 3) {
                    "Expected the unavailable rows to stay unavailable."
                }
                // Half the window is the documented gate, so this pair (1.8:3.6 = 50 %) is comparable.
                check(cells.any { it.text.toString().contains("两侧覆盖时长接近") }) {
                    "A 1:2 window pair sits exactly on the gate and must be reported as comparable."
                }

                // A very different pair must withhold the comparable claim: 100 s against 3 600 s.
                val wider = LinearLayout(targetContext)
                ComparePanels(ui, words, false)
                    .page(wider, SessionComparison.of(a, b.copy(coveredMs = 100_000))) {}
                check(labels(wider).any { it.text.toString().contains("相差较大") }) {
                    "A 36:1 window pair must not be reported as comparable."
                }
            }
        } finally {
            val editor = prefs.edit()
            if (originalLanguage == null) editor.remove("language")
            else editor.putString("language", originalLanguage)
            check(editor.commit())
        }
    }

    /**
     * The whole pick-and-compare journey, on the real activity: open the selection from the history
     * toolbar, pick two sessions, and land on the comparison page with both sides rendered.
     *
     * Driving real clicks matters here. The failure this guards against is the selection being
     * cleared while the comparison page renders, which only appears once the page transition and the
     * history re-read interleave; calling into the activity would hide it. The comparison values
     * themselves are asserted in `comparisonRendersBothSessionsWithoutInventingValues`, where the
     * fixture is deterministic.
     */
    private fun historyOffersTheComparisonSelection() {
        // Instrumented tests run inside the app process, so the engine's own store is the connection
        // the activity will read through. Seeding it here is what makes the fixture visible.
        val store = (targetContext.applicationContext as MiniWattsApp).engine.store
        run {
            // A different boot closes the first session and opens a second one.
            for (boot in listOf(31L, 32L)) {
                store.ingest(
                    sample(0, 1_400_000).copy(boot = boot, monoMs = 1_000, wallMs = 1_000),
                    1500,
                )
                store.ingest(
                    sample(0, 1_400_000)
                        .copy(boot = boot, monoMs = 2_000, wallMs = 2_000, level = 60),
                    1500,
                )
            }
            check(store.sessions().size >= 2) { "The comparison fixture did not create two sessions." }
        }

        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        val launch = uiAutomation.executeShellCommand(
            "am start -W -n ${targetContext.packageName}/${MainActivity::class.java.name}"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
        val activity = waitForMonitorWithTimeout(monitor, 10_000)
            ?: error("MainActivity did not launch within 10 seconds.")
        removeMonitor(monitor)
        try {
            val words = Words(targetContext)
            val historyTab = words.t("历史记录", "History")
            val compareLabel = words.t("对比会话", "Compare sessions")
            val cancelLabel = words.t("取消对比", "Cancel comparison")
            val selectLabel = words.t("选中此会话用于对比", "Select this session for comparison")

            /** Polls the live hierarchy, because every list rebuild happens on the io executor. */
            fun find(
                timeoutMs: Long = 8_000,
                predicate: (View) -> Boolean,
            ): View? {
                val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    var found: View? = null
                    onUiThread { found = views(activity.window.decorView).firstOrNull(predicate) }
                    found?.let { return it }
                    Thread.sleep(60)
                }
                return null
            }

            /**
             * Clicks through the accessibility action, the same path uiautomator and screen readers
             * use. A plain performClick can be lost on a card the io thread has just replaced.
             */
            fun clickView(view: View, label: String) {
                var accepted = false
                onUiThread {
                    accepted =
                        view.performAccessibilityAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                            null,
                        )
                }
                check(accepted) { "$label did not accept the click action." }
            }

            val tab =
                find { it.contentDescription?.toString() == historyTab }
                    ?: error("The history tab is missing.")
            clickView(tab, "the history tab")

            val compare =
                find { it.contentDescription?.toString() == compareLabel }
                    ?: error("The history toolbar has no compare action.")
            val export =
                find { it.contentDescription?.toString() == words.t("导出中心", "Export centre") }
                    ?: error("The history toolbar has no export action.")
            onUiThread {
                check(compare.visibility == View.VISIBLE) { "The compare action is hidden on history." }
                check(export.visibility == View.VISIBLE) { "The export action is hidden on history." }
            }
            clickView(compare, "the compare action")
            onUiThread {
                check(compare.contentDescription?.toString() == cancelLabel) {
                    "The compare action did not enter the selection state."
                }
            }

            // Entering the selection re-renders on the io executor after re-reading the store, and
            // this test runs after several store-heavy ones, so the default wait is not enough.
            find(timeoutMs = 20_000) { it.contentDescription?.toString() == selectLabel }
                ?: error(
                    "The comparison selection offered nothing to pick. On screen: " +
                        labels(activity.window.decorView).map { it.text.toString() }
                )

            repeat(2) { index ->
                val card =
                    find { it.contentDescription?.toString() == selectLabel }
                        ?: error("Only ${index} session(s) could be picked for a comparison.")
                clickView(card, "session ${index + 1}")
                // The first pick re-reads the history on the io thread, so wait for the card to flip
                // before choosing the second. The second pick leaves the page instead.
                if (index == 0)
                    find(timeoutMs = 3_000) {
                        it.contentDescription?.toString() ==
                            words.t("取消选中此会话", "Deselect this session")
                    } ?: error("Pick 1 did not register.")
            }

            // The comparison page is reached only once the pair is complete, which is the regression
            // this test exists for: the selection must survive that page transition.
            find { it is TextView && it.text?.toString() == words.t("会话对比", "Session comparison") }
                ?: error("Picking two sessions did not open the comparison page.")
            find { it is TextView && it.text?.toString() == words.t("逐项对比", "Field by field") }
                ?: error("The comparison page rendered no field table.")
            // Both sides must be real sessions rather than an empty shell. The hero card labels them
            // with a session id, which is what proves the stored pair was resolved.
            listOf("A", "B").forEach { side ->
                find {
                    it is TextView &&
                        it.text?.toString()?.startsWith(
                            words.t("会话 $side · ", "Session $side · ")
                        ) == true
                } ?: error("The comparison page is missing side $side.")
            }
        } finally {
            onUiThread { activity.finish() }
        }
    }

    /**
     * On a configuration change the outgoing instance's onStop runs after the incoming instance's
     * onResume. With foreground managed in onStart/onStop the stale instance then clears
     * [Engine.foreground] and cancels sampling for a visible app; rotation is the reproducible
     * shape. Managing the flag in onResume/onPause keeps it owned by whichever instance is resumed.
     */
    private fun foregroundSurvivesARotation() {
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        val launch = uiAutomation.executeShellCommand(
            "am start -W -n ${targetContext.packageName}/${MainActivity::class.java.name}"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
        val activity = waitForMonitorWithTimeout(monitor, 10_000)
            ?: error("MainActivity did not launch within 10 seconds.")
        removeMonitor(monitor)
        try {
            onUiThread {
                check((targetContext.applicationContext as MiniWattsApp).engine.foreground) {
                    "The resumed activity did not claim the foreground."
                }
            }
            // Rotate and let the recreated instance settle, then require sampling to continue
            // through the real scheduler.
            activity.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Thread.sleep(1_500)
            val engine = (targetContext.applicationContext as MiniWattsApp).engine
            val samplesBefore = engine.recent.size
            val latestAt = engine.latest?.monoMs
            Thread.sleep(2_500)
            check(engine.foreground) {
                "A stale instance cleared the foreground flag after the rotation."
            }
            check(
                engine.recent.size > samplesBefore || engine.latest?.monoMs != latestAt,
            ) { "Sampling stopped after the rotation." }
            activity.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Thread.sleep(1_500)
        } finally {
            onUiThread { activity.finish() }
        }
    }

    private fun conflictSurvivesSampleRetentionAndRestart() {
        targetContext.deleteDatabase("miniwatts.db")
        var store = Store(targetContext)
        try {
            store.ingest(sample(0, -1_000_000), 60)
            for (i in 1..1501) store.ingest(sample(i * 1000L, 1_000_000), 60)
            val session = store.sessions().single()
            val retained = store.samples(session.id)
            check(retained.size == 1500)
            check(retained.none { it.signConflict }) { "Conflict fixture was not pruned." }
            store.close()
            store = Store(targetContext)
            val exported = exportedSessions(store).getJSONObject(0)
            check(exported.getBoolean("signConflictObserved")) {
                "Session lost its conflict warning after the original sample was pruned."
            }
            check(exported.getDouble("netCellWh") == store.sessions().single().energyWh)
            val csv = StringWriter().also { store.export(it, false) }.toString()
            val header = csv.lineSequence().first().split(',')
            val conflictColumn = header.indexOf("session_sign_conflict_observed")
            check(conflictColumn >= 0)
            check(csv.lineSequence().drop(1).filter { it.isNotBlank() }.all {
                it.split(',')[conflictColumn] == "true"
            })
        } finally {
            store.close()
            targetContext.deleteDatabase("miniwatts.db")
        }
    }

    private fun legacyHistoryMigratesWithoutLosingData() {
        targetContext.deleteDatabase("miniwatts.db")
        val legacy = targetContext.openOrCreateDatabase("miniwatts.db", 0, null)
        try {
            legacy.execSQL(
                "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER,end INTEGER,startLevel INTEGER,endLevel INTEGER,boot INTEGER,lastMono INTEGER,lastWall INTEGER,wh REAL,covered INTEGER,peak REAL,temp INTEGER,recovery INTEGER DEFAULT 0)"
            )
            legacy.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,session INTEGER,data TEXT)")
            legacy.execSQL("CREATE INDEX samples_session ON samples(session,id)")
            for ((id, current) in listOf(7 to -1_000_000, 8 to 1_000_000)) {
                legacy.execSQL(
                    "INSERT INTO sessions VALUES(?,100000,120000,50,51,1,20000,120000,-0.2,20000,4,300,0)",
                    arrayOf(id),
                )
                legacy.execSQL(
                    "INSERT INTO samples(session,data) VALUES(?,?)",
                    arrayOf(id, sample(0, current).json().apply { remove("signConflict") }.toString()),
                )
            }
            legacy.version = 1
        } finally {
            legacy.close()
        }
        val store = Store(targetContext)
        try {
            val sessions = store.sessions()
            check(sessions.map { it.id }.toSet() == setOf(7L, 8L))
            check(sessions.all { it.energyWh == -0.2 && it.coveredMs == 20_000L })
            check(store.samples(7).single().currentUa == -1_000_000)
            val exported = exportedSessions(store)
            for (i in 0 until exported.length()) {
                val session = exported.getJSONObject(i)
                check(session.getBoolean("signConflictObserved") == (session.getLong("id") == 7L))
            }
            val original = exported.getJSONObject(1).getJSONArray("samples").getJSONObject(0)
            check(original.getDouble("powerW") == -4.0) { "Migration changed raw power sign." }
            check(original.getBoolean("signConflict"))
        } finally {
            store.close()
            targetContext.deleteDatabase("miniwatts.db")
        }
    }
}
