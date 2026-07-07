package com.burnin.scanner.report

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 세션 결과 저장 (M-FR-014, 18.2 저장 구조).
 * 위치: Android/data/com.burnin.scanner/files/sessions/<타임스탬프>/
 */
object ReportStore {

    fun newSessionDir(context: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(context.getExternalFilesDir(null), "sessions/$ts").apply { mkdirs() }
    }

    fun save(dir: File, report: JSONObject, files: Map<String, ByteArray>) {
        File(dir, "report.json").writeText(report.toString(2))
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
    }
}
