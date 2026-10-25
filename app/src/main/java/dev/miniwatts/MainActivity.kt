package dev.miniwatts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.miniwatts.core.Capacity
import dev.miniwatts.core.ExportCatalog
import dev.miniwatts.core.ExportKind
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Session
import dev.miniwatts.core.SessionComparison
import dev.miniwatts.core.Thermal
import dev.miniwatts.core.ThermalSnapshot
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val engine
        get() = (application as MiniWattsApp).engine

    private lateinit var words: Words
    private lateinit var body: LinearLayout
    private var page = 0
    private var liveText: TextView? = null
    private lateinit var ui: UiKit
    private lateinit var pageTitle: TextView
    private lateinit var settingsButton: FrameLayout
    private lateinit var exportButton: FrameLayout
    private lateinit var compareButton: FrameLayout
    private lateinit var scroll: ScrollView
    private val navItems = mutableListOf<LinearLayout>()
    private val navPages = listOf(0, 1, 2, 7, 3)

    /**
     * Sub-screens are not in the bottom navigation; the system back button returns to the parent.
     * 9 is the export centre (from history and settings) and 10 is the session comparison, which the
     * history page opens once two sessions are picked.
     */
    private val subPages = setOf(4, 5, 6, 8, 9, 10)
    private var lastSessionRefresh = 0L
    private var capacityLoadedAt = 0L
    private var sessionLoading = false
    private var panels: LivePanels? = null
    private var pageGeneration = 0
    private var sessionId: Long? = null
    private var historyLoading = false
    private var lastHistoryRefresh = 0L
    private var historyFingerprint: String? = null
    private var active = true
    private var exportKind = ExportKind.HISTORY_JSON

    /** Non-null while the history page is picking the two sessions to compare. */
    private var compareMode = false
    private var compareIds = linkedSetOf<Long>()

    /** The picker's running count; rebuilt with the page and updated on every pick. */
    private var compareIndicator: TextView? = null
    private val listener: (Sample) -> Unit = { s ->
        renderLive(s)
        keepAwake(s)
    }

    /**
     * Shizuku callbacks. The binder is delivered by the Shizuku app, so the optional channel has to
     * be re-evaluated when it arrives or dies. Nothing else in this app depends on it.
     */
    private val shizukuPermission =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST) {
                engine.prefs
                    .edit()
                    .putBoolean("shizuku", grantResult == PackageManager.PERMISSION_GRANTED)
                    .apply()
                engine.updateSampling()
                if (active) showPage()
            }
        }
    private val shizukuReceived =
        Shizuku.OnBinderReceivedListener {
            engine.updateSampling()
            if (active && page == 4) showPage()
        }
    private val shizukuDead =
        Shizuku.OnBinderDeadListener {
            engine.updateSampling()
            if (active && page == 4) showPage()
        }
    private val fahrenheit
        get() = engine.prefs.getBoolean("fahrenheit", false)

    private fun t(zh: String, en: String) = words.t(zh, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        val choice = engine.prefs.getString("theme", "system")
        val dark =
            choice == "dark" ||
                (choice == "system" &&
                    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (dark) R.style.DarkTheme else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        words = Words(this)
        page = savedInstanceState?.getInt("page") ?: 0
        exportKind =
            savedInstanceState
                ?.getString("exportKind")
                ?.let { name -> ExportKind.values().firstOrNull { it.name == name } }
                ?: ExportKind.HISTORY_JSON
        savedInstanceState
            ?.getLongArray("compareIds")
            ?.forEach { compareIds.add(it) }
        ui = UiKit(this)
        @Suppress("DEPRECATION")
        window.statusBarColor =
            if (ui.dark) android.graphics.Color.rgb(11, 34, 43)
            else android.graphics.Color.rgb(239, 253, 253)
        @Suppress("DEPRECATION")
        window.navigationBarColor =
            if (ui.dark) ui.surface else android.graphics.Color.rgb(241, 243, 249)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (ui.dark) 0
            else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root =
            ui.column().apply {
                background = GridBackground(ui)
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
        if (Build.VERSION.SDK_INT >= 35)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(
                    dp(16) + bars.left,
                    bars.top + dp(8),
                    dp(16) + bars.right,
                    bars.bottom + dp(8),
                )
                insets
            }
        val heading = ui.column()
        val toolbar =
            LinearLayout(this).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        pageTitle = ui.label("MiniWatts", 34f, ui.ink, true).apply {
            setPadding(0, dp(10), 0, dp(14))
        }
        settingsButton =
            ui.iconButton("settings", t("设置", "Settings")) {
                page = if (page in subPages) 0 else 4
                showPage()
            }
        toolbar.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        exportButton = ui.iconButton("export", t("导出中心", "Export centre")) { showExportCenter() }
        toolbar.addView(exportButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        compareButton = ui.iconButton("history", t("对比会话", "Compare sessions")) { runCompare() }
        toolbar.addView(compareButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        heading.addView(toolbar, LinearLayout.LayoutParams(-1, dp(44)))
        heading.addView(pageTitle)
        root.addView(heading)
        body = ui.column().apply { setPadding(0, 0, 0, dp(8)) }
        scroll =
            ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(body)
            }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav =
            LinearLayout(this).apply {
                background = ui.shape(radius = 32)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                gravity = Gravity.CENTER_VERTICAL
            }
        val labels =
            listOf(t("功率", "Power"), t("发热", "Thermal"), t("适配器", "Adapter"), t("设备", "Device"), t("历史记录", "History"))
        val icons = listOf("power", "thermal", "charger", "device", "history")
        labels.forEachIndexed { index, label ->
            val item =
                ui.column().apply {
                    gravity = Gravity.CENTER
                    minimumHeight = dp(56)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    contentDescription = label
                    isFocusable = true
                    setOnClickListener {
                        page = navPages[index]
                        showPage()
                    }
                }
            item.addView(
                IconView(this, icons[index], ui.ink),
                LinearLayout.LayoutParams(dp(27), dp(27)),
            )
            item.addView(ui.label(label, 10f, ui.ink, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            })
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            navItems.add(item)
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                navigateBack()
            }
        }
        showPage()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page", page)
        out.putString("exportKind", exportKind.name)
        out.putLongArray("compareIds", compareIds.toLongArray())
        super.onSaveInstanceState(out)
    }

    override fun onStart() {
        super.onStart()
        active = true
        sessionLoading = false
        lastSessionRefresh = 0L
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
            Shizuku.addBinderDeadListener(shizukuDead)
            Shizuku.addRequestPermissionResultListener(shizukuPermission)
        }
        if (page == 3) showPage() else renderLive(engine.latest)
        keepAwake(engine.latest)
    }

    /**
     * Foreground ownership lives here, not in onStart/onStop: on a configuration change the outgoing
     * instance's onStop runs after the new instance's onResume, so a stop-managed flag would be
     * cleared by the stale instance and sampling would halt for a visible app. Paused always precedes
     * the next resume, which keeps the flag with the instance the user actually sees.
     */
    override fun onResume() {
        super.onResume()
        engine.foreground = true
        engine.listeners.add(listener)
        engine.updateSampling()
    }

    override fun onPause() {
        engine.listeners.remove(listener)
        engine.foreground = false
        engine.updateSampling()
        super.onPause()
    }

    override fun onStop() {
        active = false
        runCatching {
            Shizuku.removeBinderReceivedListener(shizukuReceived)
            Shizuku.removeBinderDeadListener(shizukuDead)
            Shizuku.removeRequestPermissionResultListener(shizukuPermission)
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun keepAwake(s: Sample?) {
        if (engine.prefs.getBoolean("awake", false) && s?.plugged != null && s.plugged != 0)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 16f): TextView =
        ui.label(value, size, if (size >= 20) ui.ink else ui.muted, size >= 20).apply {
            setTextIsSelectable(true)
            ui.card(body).addView(this)
        }

    private fun button(label: String, action: () -> Unit) {
        ui.action(body, label, action = action)
    }

    private fun showPage() {
        pageGeneration++
        sessionId = null
        historyLoading = false
        sessionLoading = false
        lastSessionRefresh = 0L
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        liveText = null
        panels = null
        // The comparison selection spans the history page and the comparison page it opens. Any
        // other destination ends it; re-rendering one of those two (which every pick does) must not,
        // or the pair would be wiped exactly when the comparison is about to render.
        if (page != 3 && page != 10) clearCompareSelection()
        pageTitle.text =
            listOf(
                "MiniWatts",
                t("发热", "Thermal"),
                t("适配器", "Adapter"),
                t("历史记录", "History"),
                t("设置", "Settings"),
                t("诊断", "Diagnostics"),
                t("帮助", "Help"),
                t("设备", "Device"),
                t("性能与热区", "Performance & thermal"),
                t("导出中心", "Export centre"),
                t("会话对比", "Session comparison"),
            )[page]
        settingsButton.contentDescription =
            if (page in subPages) t("返回功率", "Back to power") else t("设置", "Settings")
        settingsButton.visibility = if (page == 0 || page == 3 || page in subPages) View.VISIBLE else View.INVISIBLE
        exportButton.visibility = if (page == 3 || page == 9) View.VISIBLE else View.GONE
        compareButton.visibility = if (page == 3 || page == 10) View.VISIBLE else View.GONE
        compareButton.contentDescription =
            if (compareMode) t("取消对比", "Cancel comparison") else t("对比会话", "Compare sessions")
        val historyActions = page == 3 && !compareMode
        settingsButton.removeAllViews()
        settingsButton.addView(IconView(this,
            if (page == 3 && historyActions) "delete" else if (page in subPages) "back" else "settings",
            if (page == 3 && historyActions) ui.red else ui.blue), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        settingsButton.setOnClickListener {
            if (page == 3 && historyActions) confirmDelete(sessionId)
            else { page = if (page in subPages) 0 else 4; showPage() }
        }
        if (page == 3 && historyActions) settingsButton.contentDescription = t("删除历史", "Delete history")
        navItems.forEachIndexed { index, item ->
            val selected = navPages[index] == page
            item.isSelected = selected
            item.background = ui.ripple(if (selected) ui.selection else ui.surface, 28, false)
            (item.getChildAt(0) as IconView).apply {
                color = if (selected) ui.blue else ui.ink
                invalidate()
            }
            (item.getChildAt(1) as TextView).setTextColor(if (selected) ui.blue else ui.ink)
        }
        when (page) {
            0,
            1,
            2,
            7,
            8 -> {
                panels =
                    LivePanels(
                            ui,
                            words,
                            fahrenheit,
                            { engine.thermal },
                            onOpenThermalDetail = {
                                page = 8
                                showPage()
                            },
                            perfHistory = { engine.perfHistory() },
                        )
                        .also {
                            when (page) {
                                0 -> it.power(body)
                                1 -> it.temperature(body)
                                2 -> it.charger(body)
                                8 -> it.thermalDetail(body)
                                else -> it.device(body)
                            }
                        }
                renderLive(engine.latest)
                if (page == 7) refreshCapacity()
            }
            3 -> showHistory()
            4 -> showSettings()
            9 -> showExportCentre()
            10 -> showComparison()
            5 -> {
                liveText = text("", 14f).apply { typeface = Typeface.MONOSPACE }
                text(words.capabilities())
                ui.action(body, t("打开导出中心", "Open the export centre"), "export") {
                    exportKind = ExportKind.DIAGNOSTICS_JSON
                    showExportCenter()
                }
                renderLive(engine.latest)
            }
            6 -> showHelp()
        }
    }

    private fun renderLive(s: Sample?) {
        panels?.update(s, engine.recent.toList())
        if (page == 0 && !sessionLoading && android.os.SystemClock.elapsedRealtime() - lastSessionRefresh > 5000) {
            sessionLoading = true
            lastSessionRefresh = android.os.SystemClock.elapsedRealtime()
            val generation = pageGeneration
            engine.io.execute {
                val session = runCatching { engine.store.sessions().firstOrNull { it.endMs == null } }.getOrNull()
                engine.main.post {
                    if (!active || page != 0 || generation != pageGeneration) return@post
                    sessionLoading = false
                    panels?.updateSession(session)
                }
            }
        }
        if (
            page == 3 &&
                sessionId == null &&
                !historyLoading &&
                android.os.SystemClock.elapsedRealtime() - lastHistoryRefresh > 5000
        )
            refreshHistory()
        if (page != 5) return
        liveText?.text =
            if (s == null) t("暂无样本；前台采样后显示。", "No samples; waiting for foreground sampling.")
            else
                "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${words.time(s.wallMs)} · ${t("采样时间", "Sampled")}\n${s.json().toString(2)}\n${t("健康状态", "Health status")}: ${words.health(s.health)}\n${if (s.signConflict) t("电流符号与状态冲突；原始值已保留。", "Current sign conflicts with status; raw value preserved.") else ""}\n${t("记录", "Recording")}: ${engine.recording}\n${t("最近采样错误", "Latest sample error")}: ${engine.lastError ?: t("无", "None") }\n\n${thermalDiagnostics()}\n\n${privilegedDiagnostics()}"
    }

    /**
     * On-screen diagnostics stay short: the thermal groups plus counts. The full zone list is written
     * by the diagnostics export instead, so an 80-row table is never rebuilt on every sample.
     *
     * [snapshot] lets the raw-probe export render the same text from a freshly forced read, so the
     * exported table cannot be a stale throttled one.
     */
    private fun thermalDiagnostics(snapshot: ThermalSnapshot? = engine.thermal): String {
        val snapshot = snapshot ?: return t("热区：尚未读取。", "Thermal zones: not read yet.")
        val groups =
            snapshot.presentGroups.joinToString("\n") { group ->
                val zone = snapshot.hottest(group)
                "  ${group.name.padEnd(9)}${zone?.name ?: "-"}  ${zone?.let { Thermal.formatDeci(it.deciC ?: 0) } ?: "-"}  (${zone?.raw ?: "-"})"
            }
        val active = snapshot.activeCooling.joinToString(", ") { "${it.name} ${it.current}/${it.max}" }
        return buildString {
            append(t("热区", "Thermal zones"))
            append(": ")
            append(
                t(
                    "${snapshot.measured.size} 可读 / ${snapshot.skipped.size} 非温度 / ${snapshot.denied.size} 被拒\n",
                    "${snapshot.measured.size} readable / ${snapshot.skipped.size} not temperature / ${snapshot.denied.size} refused\n",
                )
            )
            append(groups.ifEmpty { t("  无", "  none") })
            append("\n")
            append(t("热缓解生效", "Active mitigation"))
            append(": ")
            append(active.ifEmpty { t("无", "none") })
            append("\n")
            append(t("CPU 频率", "CPU frequency"))
            append(": ")
            append(
                snapshot.cpu.joinToString(", ") { "${it.name} ${it.currentKhz ?: -1}/${it.maxKhz ?: -1} kHz" }
                    .ifEmpty { t("不可用", "unavailable") }
            )
        }
    }

    private fun privilegedDiagnostics(): String {
        val reader = engine.privileged
        val probe = reader.latest
        return buildString {
            append(t("特权通道", "Privileged channel"))
            append(": ")
            append(words.privilegedStatus(reader.statusKey))
            append(" · enabled=")
            append(engine.privilegedEnabled)
            if (probe != null) {
                append("\n  uid=")
                append(probe.uid ?: "?")
                append(" selinux=")
                append(probe.selinux ?: "?")
                append(" refused=")
                append(probe.refused.joinToString(",").ifEmpty { "-" })
                append(" missing=")
                append(probe.missing.joinToString(",").ifEmpty { "-" })
                append("\n--- raw probe ---\n")
                append(probe.raw)
            }
        }
    }

    private fun privilegedJson(): JSONObject =
        JSONObject().apply {
            put("enabled", engine.privilegedEnabled)
            put("status", engine.privileged.statusKey)
            val probe = engine.privileged.latest
            put("uid", probe?.uid ?: JSONObject.NULL)
            put("selinux", probe?.selinux ?: JSONObject.NULL)
            put("refusedNodes", org.json.JSONArray(probe?.refused ?: emptyList<String>()))
            put("missingNodes", org.json.JSONArray(probe?.missing ?: emptyList<String>()))
            put("rootBacked", probe?.rootBacked ?: JSONObject.NULL)
            put("raw", probe?.raw ?: JSONObject.NULL)
        }

    /**
     * Full thermal dump for the export. The on-screen page shows per-group maxima only, so the raw
     * zone table (with the kernel's own milli-degree value) belongs in the diagnostics file.
     */
    private fun thermalJson(snapshot: ThermalSnapshot?): JSONObject {
        if (snapshot == null) return JSONObject().put("state", "unavailable")
        return JSONObject().apply {
            put("state", if (snapshot.available) "measured" else "unavailable")
            put("unit", "deciC is 0.1 C for temperature zones; raw keeps the kernel value")
            put("readableZones", snapshot.measured.size)
            put("skippedNodes", org.json.JSONArray(snapshot.skipped))
            put("refusedNodes", org.json.JSONArray(snapshot.denied))
            put(
                "zones",
                org.json.JSONArray().apply {
                    for (zone in snapshot.zones) {
                        put(
                            JSONObject().apply {
                                put("name", zone.name)
                                put("group", zone.group.name)
                                put("deciC", zone.deciC ?: JSONObject.NULL)
                                put("raw", zone.raw)
                            }
                        )
                    }
                },
            )
            put(
                "cooling",
                org.json.JSONArray().apply {
                    for (device in snapshot.cooling) {
                        put(
                            JSONObject().apply {
                                put("name", device.name)
                                put("current", device.current ?: JSONObject.NULL)
                                put("max", device.max ?: JSONObject.NULL)
                                put("active", device.active)
                            }
                        )
                    }
                },
            )
            put(
                "cpu",
                org.json.JSONArray().apply {
                    for (policy in snapshot.cpu) {
                        put(
                            JSONObject().apply {
                                put("name", policy.name)
                                put("currentKhz", policy.currentKhz ?: JSONObject.NULL)
                                put("maxKhz", policy.maxKhz ?: JSONObject.NULL)
                            }
                        )
                    }
                },
            )
        }
    }

    private fun showHistory() {
        val list = ui.column()
        // The selection prompt belongs on the history page itself, because that is where the picking
        // happens; the comparison page is only ever reached once a pair is complete.
        if (compareMode) {
            val card = ui.card(body, t("选择两个会话", "Pick two sessions"), "history")
            compareIndicator =
                ui.label("", 13f, ui.muted).also { card.addView(it) }
            ui.action(body, t("取消对比", "Cancel comparison"), "back") { exitCompareMode() }
        }
        body.addView(list)
        historyBody = list
        historyFingerprint = null
        updateCompareIndicator()
        ui.note(list, t("读取本地历史…", "Loading local history…"))
        refreshHistory()
    }

    /** The picker's running count. It is one label, so a re-render cannot leave a stale number. */
    private fun updateCompareIndicator() {
        val indicator = compareIndicator ?: return
        indicator.text =
            t(
                "已选中 ${compareIds.size}/$COMPARE_SLOTS 个会话。在下方列表里点选，选满两个即进入并列对比。",
                "${compareIds.size} of $COMPARE_SLOTS sessions selected. Tap them in the list below; the side-by-side comparison opens once both are picked.",
            )
        indicator.setTextColor(if (compareIds.isEmpty()) ui.muted else ui.blue)
    }

    private fun showHistoryActions() {
        choice(
            t("历史操作", "History actions"),
            arrayOf(
                t("导出 JSON", "Export JSON"),
                t("导出 CSV", "Export CSV"),
                t("删除全部历史", "Delete all history"),
            ),
        ) { i ->
            if (i == 2) confirmDelete(null)
            else {
                exportKind =
                    if (i == 0) ExportKind.HISTORY_JSON else ExportKind.HISTORY_CSV
                export()
            }
        }
    }

    private var historyBody: LinearLayout? = null

    private fun refreshHistory() {
        val target = historyBody ?: return
        val generation = pageGeneration
        historyLoading = true
        lastHistoryRefresh = android.os.SystemClock.elapsedRealtime()
        engine.io.execute {
            val result = runCatching {
                val rows = engine.store.sessions()
                rows to rows.associate { it.id to engine.store.samples(it.id, 120) }
            }
            engine.main.post {
                if (!active || page != 3 || sessionId != null || generation != pageGeneration)
                    return@post
                historyLoading = false
                result
                    .onSuccess { (rows, previews) ->
                        // The selection state is part of the fingerprint: entering or leaving the
                        // comparison selection has to redraw the cards without re-reading the store.
                        val fingerprint = "$compareMode|${compareIds.joinToString()}|$rows"
                        if (historyFingerprint != fingerprint) {
                            historyFingerprint = fingerprint
                            target.removeAllViews()
                            HistoryPanels(ui, words).list(
                                target,
                                rows,
                                previews,
                                ::showSession,
                                if (compareMode) compareIds else null,
                                ::toggleCompare,
                            )
                        }
                    }
                    .onFailure { ui.note(target, t("读取失败：", "Read failed: ") + it.message) }
            }
        }
    }

    private fun showSession(s: Session) {
        pageGeneration++
        val generation = pageGeneration
        sessionId = s.id
        pageTitle.text = t("充电会话", "Charging session")
        // The comparison picks two rows out of the history list, so its action does not apply while a
        // single session is open. `showPage` restores it on the way back.
        compareButton.visibility = View.GONE
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        ui.action(body, t("返回历史", "Back to history"), "back") { showPage() }
        val (power, temp) = HistoryPanels(ui, words).detail(body, s, fahrenheit)
        ui.action(body, t("导出历史", "Export history"), "export") {
            choice(t("导出格式", "Export format"), arrayOf("JSON", "CSV")) { i ->
                exportKind =
                    if (i == 0) ExportKind.HISTORY_JSON else ExportKind.HISTORY_CSV
                export()
            }
        }
        ui.action(body, t("删除本会话", "Delete session"), "delete") { confirmDelete(s.id) }
        engine.io.execute {
            val values = runCatching { engine.store.samples(s.id) }
            engine.main.post {
                if (!active || page != 3 || generation != pageGeneration) return@post
                values
                    .onSuccess {
                        power.samples = it
                        temp.samples = it
                    }
                    .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun navigateBack() {
        if (page in subPages) {
            page =
                when (page) {
                    4 -> 0
                    5, 6 -> 4
                    9 -> if (navPages.contains(previousPage)) previousPage else 4
                    // The comparison returns to the history page that produced the selection; page 3
                    // is the only root, and it keeps the picked sessions so the pairing can be redone.
                    10 -> 3
                    // The performance detail belongs to the thermal page it was opened from.
                    else -> 1
                }
            showPage()
        } else if (sessionId != null) showPage()
        // Back leaves the comparison selection before it leaves the app: the selection is a state of
        // this page and there is nothing else to dismiss.
        else if (compareMode) exitCompareMode()
        else finish()
    }

    /** Remembers where the export centre was opened from, because it has two entry points. */
    private var previousPage = 4

    private fun showExportCenter() {
        previousPage = page
        page = 9
        showPage()
    }

    /**
     * The export centre. Every document this build can write is listed here, and the assembly for the
     * diagnostics and raw text documents lives in [ExportCenter] so the same bytes are testable.
     */
    private fun showExportCentre() {
        val entries =
            runCatching { ExportCatalog.catalog() }
                .getOrElse {
                    ui.note(body, t("导出目录不可用：", "Export catalogue unavailable: ") + it.message)
                    return
                }
        entries.forEach { entry ->
            ui.action(body, t(entry.titleZh, entry.titleEn), entry.icon) {
                exportKind = entry.kind
                export()
            }
            ui.note(body, t(entry.detailZh, entry.detailEn))
        }
        ui.note(
            body,
            t(
                "导出只在你于系统文件选择器里选定位置后写入，取消不产生任何副作用。历史导出为 session 与样本；诊断与 probe 原文含厂家、型号、系统 API、内核版本、热区原文与特权节点清单，不含序列号、Android ID 或账户标识。",
                "Nothing is written until you pick a location in the system file picker, and cancelling has no effect. History exports sessions and samples; the diagnostics and raw probe documents include manufacturer, model, system API, kernel version, raw thermal data and the privileged node lists, but no serial number, Android ID or account identifier.",
            ),
        )
    }

    /** Starts the comparison selection on the history page. */
    private fun runCompare() {
        if (compareMode) {
            clearCompareSelection()
            page = 3
            showPage()
            return
        }
        compareMode = true
        compareIds.clear()
        page = 3
        showPage()
    }

    /** Ends the comparison selection without navigating; the history page stays put. */
    private fun clearCompareSelection() {
        compareMode = false
        compareIds.clear()
    }

    private fun exitCompareMode() {
        clearCompareSelection()
        page = 3
        showPage()
    }

    private fun toggleCompare(s: Session) {
        if (!compareIds.add(s.id)) {
            compareIds.remove(s.id)
            updateCompareIndicator()
            historyFingerprint = null
            refreshHistory()
            return
        }
        if (compareIds.size > COMPARE_SLOTS) {
            // A third pick starts a new pairing rather than silently ignoring the tap.
            val first = compareIds.first()
            compareIds.clear()
            compareIds.add(first)
            compareIds.add(s.id)
        }
        updateCompareIndicator()
        if (compareIds.size == COMPARE_SLOTS) {
            page = 10
            showPage()
        } else {
            historyFingerprint = null
            refreshHistory()
        }
    }

    private fun showComparison() {
        val generation = pageGeneration
        engine.io.execute {
            val found =
                runCatching {
                    compareIds.mapNotNull { id -> engine.store.sessions().firstOrNull { it.id == id } }
                }
            engine.main.post {
                if (!active || page != 10 || generation != pageGeneration) return@post
                val sessions =
                    found.getOrElse {
                        ui.note(body, t("读取会话失败：", "Could not read sessions: ") + it.message)
                        return@post
                    }
                if (sessions.size < COMPARE_SLOTS) {
                    ui.note(body, t("选中的会话已被删除。", "A selected session was deleted."))
                    ui.action(body, t("返回历史记录", "Back to history"), "back") {
                        page = 3
                        showPage()
                    }
                    return@post
                }
                renderComparison(sessions[0], sessions[1], generation)
            }
        }
    }

    private fun renderComparison(left: Session, right: Session, generation: Int) {
        val comparison = SessionComparison.of(left, right)
        val panels = ComparePanels(ui, words, fahrenheit)
        panels.page(body, comparison) { exitCompareMode() }
        engine.io.execute {
            val values =
                runCatching { engine.store.samples(left.id) to engine.store.samples(right.id) }
            engine.main.post {
                if (!active || page != 10 || generation != pageGeneration) return@post
                values
                    .onSuccess { (a, b) -> panels.attach(a, b) }
                    .onFailure {
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        navigateBack()
    }

    /**
     * The capacity estimate is derived from stored history, so it runs on the io thread, and it is
     * only recomputed when the device page is opened again after a minute.
     */
    private fun refreshCapacity() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - capacityLoadedAt < 60_000) return
        capacityLoadedAt = now
        val generation = pageGeneration
        engine.io.execute {
            val estimates = runCatching { engine.capacityEstimates() }.getOrDefault(emptyList())
            engine.main.post {
                if (!active || page != 7 || generation != pageGeneration) return@post
                val value = panels?.capacityValue ?: return@post
                val note = panels?.capacityNote ?: return@post
                val median = Capacity.median(estimates.map { it.fullUah })
                if (median == null) {
                    value.text = "—"
                    value.setTextColor(ui.muted)
                    note.text =
                        t(
                            "历史里还没有可用的充电记录。需要一次电量上升 ≥20%、且期间电荷计数器可读的充电；不满足条件时不显示数字，而不是显示 0。",
                            "No usable charging session in the history yet. One needs a state-of-charge rise of at least 20% with a readable charge counter; when nothing qualifies this shows no number rather than a zero.",
                        )
                } else {
                    value.text = words.n(median / 1000.0, "mAh", 0)
                    value.setTextColor(ui.ink)
                    val widest = estimates.maxByOrNull { it.levelDelta }
                    note.text =
                        t(
                            "${estimates.size} 次充电记录的中位数；最大一次跨度为 ${widest?.levelDelta}%（充入 ${words.n(widest?.chargeDeltaUah?.div(1000.0), "mAh", 0)}）。估算值，误差较大。",
                            "Median of ${estimates.size} charging sessions; the widest spanned ${widest?.levelDelta}% (${words.n(widest?.chargeDeltaUah?.div(1000.0), "mAh", 0)} added). An estimate with a wide error bar.",
                        )
                }
            }
        }
    }

    private fun confirmDelete(id: Long?) {
        AlertDialog.Builder(this)
            .setMessage(
                t(
                    "确认删除？此操作不可撤销。进行中的会话若被删除，下次采样会创建新会话。",
                    "Delete permanently? An active deleted session starts anew on the next sample.",
                )
            )
            .setNegativeButton(t("取消", "Cancel"), null)
            .setPositiveButton(t("删除", "Delete")) { _, _ ->
                engine.io.execute {
                    val r = runCatching { engine.store.delete(id) }
                    engine.main.post {
                        r.onSuccess { if (active && page == 3) showPage() }
                            .onFailure {
                                Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                            }
                    }
                }
            }
            .show()
    }

    private fun showSettings() {
        ui.action(body, t("导出中心", "Export centre"), "export") { showExportCenter() }
        ui.action(body, t("原始读数与诊断", "Raw readings & diagnostics"), "info") {
            page = 5
            showPage()
        }
        ui.action(body, t("离线帮助与数据边界", "Offline help & reading boundaries"), "info") {
            page = 6
            showPage()
        }
        text(
            t(
                "持续记录由你主动启动，锁屏后尽力采样；不持有唤醒锁，深度休眠/OEM 限制会产生缺口。通知和此处均可停止。",
                "Recording starts only on your action, samples during screen-off on a best-effort basis. No wake lock: deep sleep/OEM limits can create gaps. Stop here or in the notification.",
            )
        )
        button(
            if (engine.recording) t("停止持续记录", "Stop recording") else t("开启持续记录", "Start recording")
        ) {
            if (engine.recording) {
                stopService(Intent(this, RecordingService::class.java))
                engine.main.postDelayed({ showPage() }, 150)
            } else startRecording()
        }
        if (Build.VERSION.SDK_INT >= 33)
            text(
                if (
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                )
                    t("通知权限已允许", "Notifications allowed")
                else
                    t(
                        "通知权限未允许：记录仍可启动，通知可能仅出现在系统活动应用中；可在此停止。",
                        "Notifications denied: recording can start, but the notification may only appear in system active apps; stop here.",
                    )
            )
        button(
            t("采样间隔：${engine.interval/1000} 秒", "Sample interval: ${engine.interval/1000} seconds")
        ) {
            choice(t("采样间隔", "Interval"), arrayOf("1s", "2s", "5s", "10s", "30s")) { i ->
                engine.prefs.edit().putInt("interval", intArrayOf(1, 2, 5, 10, 30)[i]).apply()
                engine.updateSampling()
                showPage()
            }
        }
        button(t("主题", "Theme") + ": " + engine.prefs.getString("theme", "system")) {
            choice(
                t("主题", "Theme"),
                arrayOf(t("跟随系统", "System"), t("浅色", "Light"), t("深色", "Dark")),
            ) { i ->
                engine.prefs
                    .edit()
                    .putString("theme", arrayOf("system", "light", "dark")[i])
                    .apply()
                recreate()
            }
        }
        button(t("语言", "Language") + ": " + engine.prefs.getString("language", "system")) {
            choice("语言 / Language", arrayOf("跟随系统 / System", "中文", "English")) { i ->
                engine.prefs.edit().putString("language", arrayOf("system", "zh", "en")[i]).apply()
                recreate()
            }
        }
        switch(t("温度使用 °F（曲线坐标仍为 °C）", "Use °F (chart axis remains °C)"), "fahrenheit") {
            showPage()
        }
        switch(t("充电时屏幕常亮（仅 App 前台）", "Keep screen on while plugged (app foreground)"), "awake") {
            keepAwake(engine.latest)
        }
        button(
            t(
                "最多保留 ${engine.prefs.getInt("retention",60)} 会话",
                "Retain up to ${engine.prefs.getInt("retention",60)} sessions",
            )
        ) {
            choice(
                t("保留上限（调低将删除旧数据）", "Retention (lowering deletes old data)"),
                arrayOf("10", "30", "60"),
            ) { i ->
                val n = intArrayOf(10, 30, 60)[i]
                engine.prefs.edit().putInt("retention", n).apply()
                engine.io.execute { engine.store.trim(n) }
                showPage()
            }
        }
        val permission = Settings.canDrawOverlays(this)
        text(
            if (permission) t("悬浮窗权限已允许", "Overlay permission allowed")
            else
                t(
                    "悬浮窗权限未允许；拒绝不影响其他页面。",
                    "Overlay permission not granted; other pages remain usable.",
                )
        )
        button(t("开启悬浮仪表（同时开启持续记录）", "Enable floating meter (also starts recording)")) {
            if (!Settings.canDrawOverlays(this))
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            else {
                engine.prefs.edit().putBoolean("overlay", true).apply()
                startRecording()
            }
        }
        button(t("关闭悬浮仪表", "Disable floating meter")) {
            engine.prefs.edit().putBoolean("overlay", false).apply()
            if (engine.recording) startService(Intent(this, RecordingService::class.java))
            showPage()
        }
        text(
            t(
                "授予悬浮窗权限后返回，再次点击开启。拖动读数区域移动，悬浮窗有关闭入口；关闭仪表不停止记录。Widget 由桌面添加，每 30 分钟系统尽力刷新；记录时最多每分钟更新。",
                "Return after granting overlay access and tap Enable again. Drag the readings to move; close from the overlay. Closing it does not stop recording. Add the widget from your launcher: system updates about every 30 minutes, at most once a minute while sampling.",
            )
        )
        val privileged = engine.privileged
        text(t("Shizuku 增强（可选）", "Shizuku enhancement (optional)"), 20f)
        text(
            t(
                "状态：${words.privilegedStatus(privileged.statusKey)}",
                "Status: ${words.privilegedStatus(privileged.statusKey)}",
            )
        )
        button(
            if (engine.privilegedEnabled) t("关闭 Shizuku 增强", "Disable Shizuku enhancement")
            else t("开启 Shizuku 增强", "Enable Shizuku enhancement")
        ) {
            when {
                engine.privilegedEnabled -> {
                    engine.prefs.edit().putBoolean("shizuku", false).apply()
                    engine.updateSampling()
                    showPage()
                }
                !privileged.installed() ->
                    Toast.makeText(
                            this,
                            t("未检测到 Shizuku 应用。", "The Shizuku app was not found."),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                !privileged.reachable() ->
                    Toast.makeText(
                            this,
                            t("Shizuku 未运行，请先启动它。", "Shizuku is not running; start it first."),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                privileged.permitted() -> {
                    engine.prefs.edit().putBoolean("shizuku", true).apply()
                    engine.updateSampling()
                    showPage()
                }
                else ->
                    runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST) }
                        .onFailure {
                            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                        }
            }
        }
        privileged.latest?.let { probe ->
            text(
                t(
                    "本次特权进程：uid=${probe.uid ?: "?"}，SELinux ${probe.selinux ?: "未知"}；被策略拒绝 ${probe.refused.size} 个节点，本机不存在 ${probe.missing.size} 个。",
                    "Privileged process: uid=${probe.uid ?: "?"}, SELinux ${probe.selinux ?: "unknown"}; ${probe.refused.size} nodes refused by policy, ${probe.missing.size} absent on this device.",
                )
            )
        }
        text(words.privilegedNote())
    }

    private fun switch(label: String, key: String, changed: () -> Unit) {
        ui.card(body)
            .addView(
                Switch(this).apply {
                    text = label
                    isChecked = engine.prefs.getBoolean(key, false)
                    setTextColor(ui.ink)
                    minimumHeight = dp(48)
                    setPadding(0, dp(8), 0, dp(8))
                    setOnCheckedChangeListener { _, v ->
                        engine.prefs.edit().putBoolean(key, v).apply()
                        changed()
                    }
                }
            )
    }

    private fun choice(title: String, values: Array<String>, onChoice: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(values) { _, i -> onChoice(i) }.show()
    }

    private fun startRecording() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        else launchService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 42) launchService()
    }

    private fun launchService() {
        runCatching { startForegroundService(Intent(this, RecordingService::class.java)) }
            .onFailure {
                Toast.makeText(
                        this,
                        t("记录启动失败：", "Could not start recording: ") + it.message,
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        engine.main.postDelayed({ if (active && page == 4) showPage() }, 200)
    }

    /** Writes the document the user picked. Diagnostics and raw text share [ExportCenter]. */
    private fun export() {
        val entry = runCatching { ExportCatalog.of(exportKind) }.getOrNull() ?: return
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = entry.mimeType
                putExtra(Intent.EXTRA_TITLE, entry.fileName)
            },
            43,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 43 || resultCode != RESULT_OK) return
        val kind = exportKind
        val sample = engine.latest
        engine.io.execute {
            val r = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    writer ->
                    when (kind) {
                        ExportKind.HISTORY_JSON -> engine.store.export(writer, true)
                        ExportKind.HISTORY_CSV -> engine.store.export(writer, false)
                        ExportKind.DIAGNOSTICS_JSON ->
                            writer.write(
                                ExportCenter
                                    .diagnosticReport(sample, words, privilegedJson(), thermalJson(forceThermal()))
                                    .toString(2)
                            )
                        ExportKind.RAW_PROBE_TXT ->
                            writer.write(
                                ExportCenter.rawProbeText(
                                    engine.privileged.latest?.raw,
                                    thermalDiagnostics(forceThermal()),
                                )
                            )
                    }
                } ?: error("Cannot open selected document")
            }
            engine.main.post {
                Toast.makeText(
                        this,
                        r.fold(
                            { t("已导出", "Exported") },
                            { t("导出失败：", "Export failed: ") + it.message },
                        ),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    /** An export is the one place a fresh kernel snapshot is forced, because it must be complete. */
    private fun forceThermal(): ThermalSnapshot? =
        engine.refreshThermal(android.os.SystemClock.elapsedRealtime(), force = true)

    private fun showHelp() {
        text(t("离线帮助", "Offline help"), 24f)
        text(words.boundary())
        text(words.capabilities())
        text(
            t(
                "读数规则\n电流 µA ÷ 1000 = mA；电压 mV ÷ 1000 = V；电流 µA × 电压 mV ÷ 10⁹ = W。温度原始单位 0.1°C。电量来自 level/scale。charge counter 为 µAh，energy counter 为 nWh，不等同于本会话积分能量。循环次数仅 API 34+ 实际存在且有效时显示。电池健康是系统状态，不是容量健康百分比。\n每个样本保存墙钟时间、单调时间、启动编号、来源与单位；原始数据见诊断和导出。无 API 值为 null/不可用，CSV 空字段。W 及 Wh 为推算。\n表盘、供电卡与通知上的电芯净功率显示**绝对值**，方向由充放电状态给出；带符号的原始值保留在趋势曲线、电芯电流与全部导出里。厂商符号冲突时保留原始值并给出徽标提示。",
                "Reading rules\nµA / 1000 = mA; mV / 1000 = V; µA × mV / 10⁹ = W. Raw temperature is 0.1°C. Level is level/scale. Charge counter is µAh, energy counter is nWh, not session-integrated energy. Cycle count appears only if valid on API 34+. Battery health is a status, not capacity percent.\nSamples retain wall clock, monotonic time, boot ID, sources and units; see Raw and exports. Missing values are null/unavailable, empty in CSV. W and Wh are estimated.\nThe cell power shown on the gauge, the power-path card and the notification is a magnitude; direction is given by the charge state, and the signed value is kept in the trend chart, the cell current and every export. Conflicting vendor signs are preserved with a badge.",
            )
        )
        // The full conflict explanation lives here rather than on a live page, which only carries a badge.
        text(words.signConflictHelp())
        text(
            t(
                "会话与积分\n仅对同一次启动、单调 dt>0、dt≤3×采样间隔且≤60 秒、两端功率都有效的样本作梯形积分。符号变化保持带符号积分，不取绝对值；平均功率使用有效覆盖时长。采样中断断开曲线，不跨缺口积分。\n同一次系统启动保留进行中会话；重启关闭于最后已测时间并重新创建。App 停止或休眠期间的插拔无法获知，恢复时不会声称离线期间有连续测量。无持续记录时离开 App 停止采样；持续记录尽力工作，系统强杀后需用户重新启动。",
                "Sessions & integration\nTrapezoidal integration requires same boot, monotonic dt>0, dt≤3×interval and ≤60s, valid power at both ends. Sign changes remain signed; average uses covered time. Gaps disconnect charts and never integrate.\nOpen sessions persist within the same system boot; reboot closes at last measured time and creates a new session. Plug events while stopped/asleep cannot be observed; resuming does not imply continuous measurement. Without recording, leaving the app pauses sampling. Recording is best effort; after system termination restart it manually.",
            )
        )
        text(
            t(
                "隐私与权限\n全部数据保存在本机，不申请网络、广泛存储、位置或设备标识权限；无广告、账户和埋点。导出仅在你主动选择文件后写入。前台服务权限用于持续记录；Android 13+ 请求通知权限；悬浮窗权限只在主动开启时进入系统设置。默认常亮/记录/悬浮窗均关闭。\n灵感：ResistanceTo/MiniWatts，https://github.com/ResistanceTo/MiniWatts（Apache-2.0）。本项目独立实现，未复用其源代码、资源、商标或截图。",
                "Privacy & permissions\nAll data stays on device. No network, broad storage, location or identifier permissions; no ads, accounts or analytics. Export writes only to a document you select. Foreground service permission supports recording; Android 13+ requests notification permission; overlay access opens settings only on your action. Keep awake, recording and overlay default off.\nThe optional Shizuku channel is off by default. When you enable it, a read-only service runs inside the Shizuku process and reads a fixed whitelist of power-supply nodes; the app never calls su and never writes to a node, and no reading leaves the device. If Shizuku runs with ADB identity, system policy refuses those nodes on many devices and the readings stay unavailable.\nInspired by ResistanceTo/MiniWatts, https://github.com/ResistanceTo/MiniWatts (Apache-2.0). Independent implementation; no copied code, resources, trademarks or screenshots.",
            )
        )
    }

    private companion object {
        const val SHIZUKU_REQUEST = 51

        /** Two sessions, one each side of the comparison. */
        const val COMPARE_SLOTS = 2
    }
}
