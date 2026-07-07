package com.burnin.scanner.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면 로그 창과 logcat 양쪽으로 내보내는 간단한 로그 버스. */
object AppLog {
    private const val TAG = "BurninScanner"
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun i(msg: String) {
        Log.i(TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        main.post {
            lines.addLast(line)
            while (lines.size > 300) lines.removeFirst()
            listener?.invoke(dump())
        }
    }

    fun dump(): String = lines.joinToString("\n")
}
