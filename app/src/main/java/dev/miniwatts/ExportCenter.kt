package dev.miniwatts

import android.os.Build
import dev.miniwatts.core.Sample
import org.json.JSONObject

/**
 * The single place the diagnostics and raw-probe documents are assembled.
 *
 * Both used to be built inline in the activity's `onActivityResult`, which made them impossible to
 * assert on without driving the system file picker. Keeping the assembly here means the export centre
 * and the regression suite render the same bytes.
 */
object ExportCenter {
    /** Device identity is the only place build data reaches a file; nothing personal is included. */
    fun diagnosticReport(
        sample: Sample?,
        words: Words,
        privileged: JSONObject,
        thermal: JSONObject,
        manufacturer: String = Build.MANUFACTURER,
        model: String = Build.MODEL,
        api: Int = Build.VERSION.SDK_INT,
        securityPatch: String? = Build.VERSION.SECURITY_PATCH,
        kernel: String? = System.getProperty("os.version"),
    ): JSONObject =
        JSONObject().apply {
            put("manufacturer", manufacturer)
            put("model", model)
            put("api", api)
            put("securityPatch", securityPatch ?: JSONObject.NULL)
            put("kernel", kernel ?: JSONObject.NULL)
            put("sample", sample?.json() ?: JSONObject.NULL)
            put("capabilities", words.capabilities())
            put("boundary", words.boundary())
            put("privilegedChannel", privileged)
            put("thermal", thermal)
        }

    /**
     * The raw text document: what the privileged probe actually printed, then the thermal table.
     *
     * Both segments are newline-preserving. The app strips control characters at the reader, so this
     * document stays valid UTF-8 text and never carries a raw NUL into a viewer or a diff.
     */
    fun rawProbeText(probeRaw: String?, thermalText: String): String =
        buildString {
            append("# MiniWatts raw probe\n")
            append("# privileged probe output\n")
            append(probeRaw?.takeIf { it.isNotBlank() } ?: "(no probe output; the channel is off or refused)")
            append("\n\n# thermal zones\n")
            append(thermalText)
            append("\n")
        }
}
