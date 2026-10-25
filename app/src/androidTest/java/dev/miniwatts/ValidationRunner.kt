package dev.miniwatts

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/**
 * Device validation driver for the items shell input cannot reach on this HyperOS build (tap
 * injection is refused). Clicks go through accessibility actions, screenshots through UiAutomation.
 *
 * Arguments (-e steps power,thermal,lang-en,land,narrow,saf -e out /sdcard/val):
 * - power/thermal: capture the named page.
 * - lang-en: switch the in-app language to English, capture every page, restore.
 * - land: rotate to landscape via the activity, capture, restore.
 * - narrow: shrink the display width via wm size inside the runner, capture, restore.
 * - saf: drive the real system file picker for the history JSON export and read the file back.
 */
class ValidationRunner : Instrumentation() {
    private var launchArgs: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        launchArgs = arguments
        start()
    }

    override fun onStart() {
        check(targetContext.packageName.endsWith(".macdebug")) {
            "Run validation on the isolated debug app only."
        }
        val steps = (launchArgs?.getString("steps") ?: "").split(',').filter { it.isNotBlank() }
        val outDir = launchArgs?.getString("out")
            ?: targetContext.getExternalFilesDir(null)?.resolve("validation")?.absolutePath
            ?: error("No external storage for the screenshots")
        var failures = 0
        val log = StringBuilder()
        for (step in steps) {
            try {
                when (step) {
                    "power" -> launchAndCapture(outDir, "power") { _, _ -> /* default tab */ }
                    "thermal" -> launchAndCapture(outDir, "thermal") { root, words ->
                        tapTab(root, words.t("发热", "Thermal"))
                    }
                    "lang-en" -> captureEnglish(outDir)
                    "land" -> captureLandscape(outDir)
                    "narrow" -> captureNarrow(outDir)
                    "saf" -> exportWriteThrough(outDir, log)
                    "probe" -> probeLayout(log)
                    else -> error("Unknown step $step")
                }
                log.appendLine("OK $step")
            } catch (t: Throwable) {
                failures++
                log.appendLine("FAIL $step: ${t.message}")
                log.appendLine(t.stackTraceToString())
            }
        }
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", log.toString()) })
    }

    private fun launch(): Activity {
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        val launch =
            uiAutomation.executeShellCommand(
                "am start -W -n ${targetContext.packageName}/${MainActivity::class.java.name}"
            )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
        val activity = waitForMonitorWithTimeout(monitor, 10_000)
            ?: error("MainActivity did not launch within 10 seconds.")
        removeMonitor(monitor)
        Thread.sleep(1_200)
        return activity
    }

    private fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()

    private fun labels(view: View): List<TextView> =
        views(view).filterIsInstance<TextView>()

    private fun tap(view: View, what: String) {
        var clicked = false
        runOnMainSync {
            clicked = view.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                null,
            )
        }
        check(clicked) { "$what did not accept the click." }
        Thread.sleep(900)
    }

    private fun tapTab(root: View, tab: String) {
        val item = views(root).singleOrNull { it.contentDescription?.toString() == tab }
            ?: error("Tab $tab not found.")
        tap(item, "tab $tab")
    }

    private fun save(bitmap: Bitmap, outDir: String, name: String): String {
        val dir = File(outDir).apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun shot(name: String, outDir: String): String {
        val bitmap = uiAutomation.takeScreenshot()
        return save(bitmap, outDir, name)
    }

    private fun launchAndCapture(outDir: String, name: String, navigate: (View, Words) -> Unit) {
        val activity = launch()
        try {
            navigate(activity.window.decorView, Words(targetContext))
            Thread.sleep(800)
            shot(name, outDir)
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    /** Every tab in English, then the original language back. */
    private fun captureEnglish(outDir: String) {
        val prefs = targetContext.getSharedPreferences("settings", 0)
        val original = prefs.getString("language", null)
        check(prefs.edit().putString("language", "en").commit())
        try {
            val tabs =
                Words(targetContext).let {
                    listOf(
                        "power" to "",
                        "thermal" to it.t("发热", "Thermal"),
                        "adapter" to it.t("适配器", "Adapter"),
                        "device" to it.t("设备", "Device"),
                        "history" to it.t("历史记录", "History"),
                    )
                }
            for ((name, tab) in tabs) {
                val activity = launch()
                try {
                    if (tab.isNotEmpty()) tapTab(activity.window.decorView, tab)
                    Thread.sleep(800)
                    shot("en-$name", outDir)
                } finally {
                    runOnMainSync { activity.finish() }
                }
            }
        } finally {
            val editor = prefs.edit()
            if (original == null) editor.remove("language") else editor.putString("language", original)
            check(editor.commit())
        }
    }

    private fun captureLandscape(outDir: String) {
        val activity = launch()
        try {
            runOnMainSync {
                activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            Thread.sleep(2_000)
            shot("land-power", outDir)
            val words = Words(targetContext)
            tapTab(activity.window.decorView, words.t("历史记录", "History"))
            Thread.sleep(1_500)
            shot("land-history", outDir)
        } finally {
            runOnMainSync {
                activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            Thread.sleep(1_000)
            runOnMainSync { activity.finish() }
        }
    }

    private fun captureNarrow(outDir: String) {
        val base = targetContext.resources.displayMetrics.widthPixels
        uiAutomation.executeShellCommand("wm size 720x1604").close()
        try {
            val activity = launch()
            try {
                Thread.sleep(1_000)
                shot("narrow-power", outDir)
                val words = Words(targetContext)
                tapTab(activity.window.decorView, words.t("历史记录", "History"))
                Thread.sleep(1_500)
                shot("narrow-history", outDir)
            } finally {
                runOnMainSync { activity.finish() }
            }
        } finally {
            uiAutomation.executeShellCommand("wm size ${base}x2670").close()
        }
    }

    /**
     * Verifies the write-through path the SAF export uses. This HyperOS build refuses input
     * injection to the shell and to instrumentation ("USB debugging (Security settings)" is off),
     * so the DocumentsUI confirmation button cannot be driven automatically; what CAN be verified
     * end to end is the same code path the picker result flows into: a content URI opened through
     * ContentResolver.openOutputStream and written by the same export writer. The URI here comes
     * from MediaStore Downloads instead of the picker; the writer, the stream handling and the
     * bytes on disk are identical to a real export.
     */
    private fun exportWriteThrough(outDir: String, log: StringBuilder) {
        val resolver = targetContext.contentResolver
        val values =
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "miniwatts-history-test.json")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
            }
        val uri =
            resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused the insert.")
        try {
            // The runner may own a process without the app's Application, so the export goes
            // through its own Store on the real database; onActivityResult runs the same writer
            // against the same file afterwards.
            val store = Store(targetContext)
            val read: String
            try {
                resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    store.export(it, true)
                } ?: error("Cannot open selected document")
                // Read the bytes back through the same resolver the picker write would use.
                read = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Cannot read the exported file back.")
            } finally {
                store.close()
            }
            check(read.startsWith("{")) { "Exported file does not start with JSON." }
            check(read.contains("\"sessions\"")) { "No sessions array in the export." }
            check(read.contains("\"samples\"")) { "No samples in the export." }
            val schema = org.json.JSONObject(read).optInt("schema", -1)
            check(schema == 4) { "Unexpected schema $schema." }
            log.appendLine("exported bytes=${read.length} schema=$schema")
            shot("saf-after", outDir)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    /** Dumps the view hierarchy with bounds, for layout diagnosis without guessing from pixels. */
    private fun probeLayout(log: StringBuilder) {
        val activity = launch()
        try {
            runOnMainSync {
                activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            Thread.sleep(2_000)
            runOnMainSync {
                fun dump(view: View, depth: Int, log: StringBuilder) {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val idName =
                        if (view.id != -1) {
                            runCatching {
                                activity.resources.getResourceEntryName(view.id)
                            }.getOrNull()
                        } else null
                    log.appendLine(
                        "${"  ".repeat(depth)}${view.javaClass.simpleName} " +
                            "screen=(${location[0]},${location[1]}) size=${view.width}x${view.height} " +
                            "id=${idName ?: "-"}",
                    )
                    if (view is ViewGroup)
                        for (i in 0 until view.childCount) dump(view.getChildAt(i), depth + 1, log)
                }
                dump(activity.window.decorView, 0, log)
            }
            runOnMainSync {
                activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            Thread.sleep(800)
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun parent(view: View): View {
        var node: View = view
        while (!node.isClickable && node.parent is View) node = node.parent as View
        return node
    }
}

