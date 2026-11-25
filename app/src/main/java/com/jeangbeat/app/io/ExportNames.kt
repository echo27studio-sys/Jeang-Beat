package com.jeangbeat.app.io

import com.jeangbeat.core.audio.OfflineRenderer

/** File name (without extension) for an export: "<Project>", "<Project> (pattern)" or "<Project> (loop)". */
fun exportBaseName(projectName: String, options: ExportOptions): String {
    val base = projectName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Jeang Beat" }
    return base + when (options.range) {
        OfflineRenderer.Range.SONG -> ""
        OfflineRenderer.Range.PATTERN -> " (pattern)"
        OfflineRenderer.Range.LOOP -> " (loop)"
    }
}
