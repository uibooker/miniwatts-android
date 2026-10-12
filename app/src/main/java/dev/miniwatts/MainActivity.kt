package dev.miniwatts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import dev.miniwatts.core.Sample
import dev.miniwatts.core.Session
import org.json.JSONObject

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
    private lateinit var scroll: ScrollView
    private val navItems = mutableListOf<LinearLayout>()
    private var panels: LivePanels? = null
    private var pageGeneration = 0
    private var sessionId: Long? = null
    private var historyLoading = false
    private var lastHistoryRefresh = 0L
    private var historyFingerprint: String? = null
    private var active = true
    private var exportFormat = "json"
    private var diagnosticExport = false
    private val listener: (Sample) -> Unit = { s ->
        renderLive(s)
        keepAwake(s)
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
        exportFormat = savedInstanceState?.getString("exportFormat") ?: "json"
        diagnosticExport = savedInstanceState?.getBoolean("diagnosticExport") ?: false
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
        val heading =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(12))
            }
        pageTitle = ui.label("MiniWatts", 30f, ui.ink, true)
        heading.addView(pageTitle, LinearLayout.LayoutParams(0, -2, 1f))
        settingsButton =
            ui.iconButton("settings", t("设置", "Settings")) {
                page = if (page >= 4) 0 else 4
                showPage()
            }
        heading.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
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
                elevation = dp(4).toFloat()
                setPadding(dp(6), dp(6), dp(6), dp(6))
                gravity = Gravity.CENTER_VERTICAL
            }
        val labels =
            listOf(t("功率", "Power"), t("温度", "Thermal"), t("充电器", "Charger"), t("历史", "History"))
        val icons = listOf("power", "thermal", "charger", "history")
        labels.forEachIndexed { index, label ->
            val item =
                ui.column().apply {
                    gravity = Gravity.CENTER
                    minimumHeight = dp(60)
                    setPadding(dp(2), dp(6), dp(2), dp(6))
                    contentDescription = label
                    isFocusable = true
                    setOnClickListener {
                        page = index
                        showPage()
                    }
                }
            item.addView(
                IconView(this, icons[index], ui.muted),
                LinearLayout.LayoutParams(dp(24), dp(24)),
            )
            item.addView(ui.label(label, 12f, ui.muted, true).apply { gravity = Gravity.CENTER })
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
        out.putString("exportFormat", exportFormat)
        out.putBoolean("diagnosticExport", diagnosticExport)
        super.onSaveInstanceState(out)
    }

    override fun onStart() {
        super.onStart()
        active = true
        engine.foreground = true
        engine.listeners.add(listener)
        engine.updateSampling()
        if (page == 3) showPage() else renderLive(engine.latest)
        keepAwake(engine.latest)
    }

    override fun onStop() {
        active = false
        engine.listeners.remove(listener)
        engine.foreground = false
        engine.updateSampling()
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
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        liveText = null
        panels = null
        pageTitle.text =
            listOf(
                "MiniWatts",
                t("温度", "Thermal"),
                t("充电器", "Charger"),
                t("历史", "History"),
                t("设置", "Settings"),
                t("诊断", "Diagnostics"),
                t("帮助", "Help"),
            )[page]
        settingsButton.contentDescription =
            if (page >= 4) t("返回功率", "Back to power") else t("设置", "Settings")
        navItems.forEachIndexed { index, item ->
            val selected = index == page
            item.isSelected = selected
            item.background = ui.ripple(if (selected) ui.tint(ui.blue) else ui.surface, 28)
            (item.getChildAt(0) as IconView).apply {
                color = if (selected) ui.blue else ui.muted
                invalidate()
            }
            (item.getChildAt(1) as TextView).setTextColor(if (selected) ui.blue else ui.muted)
        }
        when (page) {
            0,
            1,
            2 -> {
                panels =
                    LivePanels(ui, words, fahrenheit).also {
                        when (page) {
                            0 -> it.power(body)
                            1 -> it.temperature(body)
                            else -> it.charger(body)
                        }
                    }
                renderLive(engine.latest)
            }
            3 -> showHistory()
            4 -> showSettings()
            5 -> {
                liveText = text("", 14f).apply { typeface = Typeface.MONOSPACE }
                text(words.capabilities())
                ui.action(body, t("导出诊断 JSON", "Export diagnostics JSON"), "export") {
                    exportFormat = "json"
                    diagnosticExport = true
                    chooseExport()
                }
                renderLive(engine.latest)
            }
            6 -> showHelp()
        }
    }

    private fun renderLive(s: Sample?) {
        panels?.update(s, engine.recent.toList())
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
                "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${words.time(s.wallMs)} · ${t("采样时间", "Sampled")}\n${s.json().toString(2)}\n${t("健康状态", "Health status")}: ${words.health(s.health)}\n${if (s.signConflict) t("电流符号与状态冲突；原始值已保留。", "Current sign conflicts with status; raw value preserved.") else ""}\n${t("记录", "Recording")}: ${engine.recording}\n${t("最近采样错误", "Latest sample error")}: ${engine.lastError ?: t("无", "None") }"
    }

    private fun showHistory() {
        ui.action(body, t("历史操作", "History actions"), "export") {
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
                    exportFormat = if (i == 0) "json" else "csv"
                    diagnosticExport = false
                    chooseExport()
                }
            }
        }
        val list = ui.column()
        body.addView(list)
        historyBody = list
        historyFingerprint = null
        ui.note(list, t("读取本地历史…", "Loading local history…"))
        refreshHistory()
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
                        val fingerprint = rows.toString()
                        if (historyFingerprint != fingerprint) {
                            historyFingerprint = fingerprint
                            target.removeAllViews()
                            HistoryPanels(ui, words).list(target, rows, previews, ::showSession)
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
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        ui.action(body, t("返回历史", "Back to history"), "back") { showPage() }
        val (power, temp) = HistoryPanels(ui, words).detail(body, s, fahrenheit)
        ui.action(body, t("导出历史", "Export history"), "export") {
            choice(t("导出格式", "Export format"), arrayOf("JSON", "CSV")) { i ->
                exportFormat = if (i == 0) "json" else "csv"
                diagnosticExport = false
                chooseExport()
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

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        navigateBack()
    }

    private fun navigateBack() {
        if (page >= 4) {
            page = if (page == 4) 0 else 4
            showPage()
        } else if (sessionId != null) showPage() else finish()
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

    private fun chooseExport() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (exportFormat == "json") "application/json" else "text/csv"
                putExtra(
                    Intent.EXTRA_TITLE,
                    if (diagnosticExport) "miniwatts-diagnostics.json"
                    else "miniwatts-history.$exportFormat",
                )
            },
            43,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 43 || resultCode != RESULT_OK) return
        val json = exportFormat == "json"
        val diagnostic = diagnosticExport
        val sample = engine.latest
        engine.io.execute {
            val r = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    writer ->
                    if (diagnostic)
                        writer.write(
                            JSONObject()
                                .apply {
                                    put("manufacturer", Build.MANUFACTURER)
                                    put("model", Build.MODEL)
                                    put("api", Build.VERSION.SDK_INT)
                                    put("sample", sample?.json() ?: JSONObject.NULL)
                                    put("capabilities", words.capabilities())
                                    put("boundary", words.boundary())
                                }
                                .toString(2)
                        )
                    else engine.store.export(writer, json)
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

    private fun showHelp() {
        text(t("离线帮助", "Offline help"), 24f)
        text(words.boundary())
        text(words.capabilities())
        text(
            t(
                "读数规则\n电流 µA ÷ 1000 = mA；电压 mV ÷ 1000 = V；电流 µA × 电压 mV ÷ 10⁹ = W。温度原始单位 0.1°C。电量来自 level/scale。charge counter 为 µAh，energy counter 为 nWh，不等同于本会话积分能量。循环次数仅 API 34+ 实际存在且有效时显示。电池健康是系统状态，不是容量健康百分比。\n每个样本保存墙钟时间、单调时间、启动编号、来源与单位；原始数据见诊断和导出。无 API 值为 null/不可用，CSV 空字段。W 及 Wh 为推算。厂商符号冲突保留原始值并提示。",
                "Reading rules\nµA / 1000 = mA; mV / 1000 = V; µA × mV / 10⁹ = W. Raw temperature is 0.1°C. Level is level/scale. Charge counter is µAh, energy counter is nWh, not session-integrated energy. Cycle count appears only if valid on API 34+. Battery health is a status, not capacity percent.\nSamples retain wall clock, monotonic time, boot ID, sources and units; see Raw and exports. Missing values are null/unavailable, empty in CSV. W and Wh are estimated. Conflicting vendor signs are preserved with a warning.",
            )
        )
        text(
            t(
                "会话与积分\n仅对同一次启动、单调 dt>0、dt≤3×采样间隔且≤60 秒、两端功率都有效的样本作梯形积分。符号变化保持带符号积分，不取绝对值；平均功率使用有效覆盖时长。采样中断断开曲线，不跨缺口积分。\n同一次系统启动保留进行中会话；重启关闭于最后已测时间并重新创建。App 停止或休眠期间的插拔无法获知，恢复时不会声称离线期间有连续测量。无持续记录时离开 App 停止采样；持续记录尽力工作，系统强杀后需用户重新启动。",
                "Sessions & integration\nTrapezoidal integration requires same boot, monotonic dt>0, dt≤3×interval and ≤60s, valid power at both ends. Sign changes remain signed; average uses covered time. Gaps disconnect charts and never integrate.\nOpen sessions persist within the same system boot; reboot closes at last measured time and creates a new session. Plug events while stopped/asleep cannot be observed; resuming does not imply continuous measurement. Without recording, leaving the app pauses sampling. Recording is best effort; after system termination restart it manually.",
            )
        )
        text(
            t(
                "隐私与权限\n全部数据保存在本机，不申请网络、广泛存储、位置或设备标识权限；无广告、账户和埋点。导出仅在你主动选择文件后写入。前台服务权限用于持续记录；Android 13+ 请求通知权限；悬浮窗权限只在主动开启时进入系统设置。默认常亮/记录/悬浮窗均关闭。\n灵感：ResistanceTo/MiniWatts，https://github.com/ResistanceTo/MiniWatts（Apache-2.0）。本项目独立实现，未复用其源代码、资源、商标或截图。",
                "Privacy & permissions\nAll data stays on device. No network, broad storage, location or identifier permissions; no ads, accounts or analytics. Export writes only to a document you select. Foreground service permission supports recording; Android 13+ requests notification permission; overlay access opens settings only on your action. Keep awake, recording and overlay default off.\nInspired by ResistanceTo/MiniWatts, https://github.com/ResistanceTo/MiniWatts (Apache-2.0). Independent implementation; no copied code, resources, trademarks or screenshots.",
            )
        )
    }
}
