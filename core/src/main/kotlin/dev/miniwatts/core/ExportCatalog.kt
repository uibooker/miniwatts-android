package dev.miniwatts.core

/**
 * The kind of document an export entry writes. The UI turns this into a MIME type and the builder
 * decides which writer runs, so "what we offer" and "what we can actually write" cannot drift apart.
 */
enum class ExportKind {
    /** Stored sessions and samples. */
    HISTORY_JSON,
    HISTORY_CSV,
    /** Device/build identity, the latest sample, the capability matrix, Shizuku state and thermal. */
    DIAGNOSTICS_JSON,
    /** Privileged probe and thermal raw text, for on-device forensics. */
    RAW_PROBE_TXT,
}

/**
 * One entry in the export centre.
 *
 * The centre exists so every export the app can produce is reachable from one place: history used to
 * be the only screen with an export control, while diagnostics lived inside the settings sub-page.
 * Adding an [ExportKind] here and writing it in the exporter is the whole change.
 */
data class ExportEntry(
    val kind: ExportKind,
    val titleZh: String,
    val titleEn: String,
    val detailZh: String,
    val detailEn: String,
    val icon: String,
    val fileName: String,
    val mimeType: String,
) {
    companion object {
        /** The complete catalogue, in the order the export centre lists it. */
        val ALL: List<ExportEntry> =
            listOf(
                ExportEntry(
                    ExportKind.HISTORY_JSON,
                    "导出历史 JSON",
                    "Export history JSON",
                    "全部会话与保留样本，保留原始单位与来源，schema 3。",
                    "All sessions and retained samples with raw units and sources, schema 3.",
                    "export",
                    "miniwatts-history.json",
                    "application/json",
                ),
                ExportEntry(
                    ExportKind.HISTORY_CSV,
                    "导出历史 CSV",
                    "Export history CSV",
                    "逐样本一行，列名带单位；缺失值为空字段，不补 0。",
                    "One row per sample, units in the column names; missing values are empty, never 0.",
                    "export",
                    "miniwatts-history.csv",
                    "text/csv",
                ),
                ExportEntry(
                    ExportKind.DIAGNOSTICS_JSON,
                    "导出诊断 JSON",
                    "Export diagnostics JSON",
                    "厂家/型号/API、最新样本、能力矩阵、Shizuku 状态、完整热区表与 cooling 表。",
                    "Manufacturer/model/API, latest sample, capability matrix, Shizuku state, the full thermal zone and cooling tables.",
                    "info",
                    "miniwatts-diagnostics.json",
                    "application/json",
                ),
                ExportEntry(
                    ExportKind.RAW_PROBE_TXT,
                    "导出 probe 原文",
                    "Export raw probe text",
                    "特权 probe 原文与控制字符已剔除的 SELinux 上下文，便于离线比对节点。",
                    "Raw privileged probe output and the control-character-stripped SELinux context, for offline node comparison.",
                    "info",
                    "miniwatts-probe.txt",
                    "text/plain",
                ),
            )
    }
}

/**
 * The export centre's contract: an ordered, complete list of the formats this build can write.
 *
 * It is deliberately total over [ExportKind]. If a new kind is added without an entry, [catalog]
 * throws during development instead of silently dropping an export from the UI.
 */
object ExportCatalog {
    val entries: List<ExportEntry> = ExportEntry.ALL

    /** The entry for [kind]. Total over [ExportKind], so an exporter can never pick a missing one. */
    fun of(kind: ExportKind): ExportEntry = entries.first { it.kind == kind }

    /** Guards the catalogue: every kind must appear exactly once, and be writable. */
    fun catalog(): List<ExportEntry> {
        val listed = entries.map { it.kind }
        require(listed.size == listed.toSet().size) { "Duplicate export kinds: $listed" }
        require(listed.toSet() == ExportKind.values().toSet()) {
            "Export centre is missing ${ExportKind.values().toSet() - listed.toSet()}"
        }
        entries.forEach { entry ->
            require(entry.fileName.isNotBlank()) { "Export ${entry.kind} has no file name" }
            require(entry.mimeType.isNotBlank()) { "Export ${entry.kind} has no MIME type" }
        }
        return entries
    }

    /** True for the history document exports, which write stored sessions rather than diagnostics. */
    fun isHistory(kind: ExportKind): Boolean =
        kind == ExportKind.HISTORY_JSON || kind == ExportKind.HISTORY_CSV
}
