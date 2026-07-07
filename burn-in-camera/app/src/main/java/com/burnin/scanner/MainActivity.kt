package com.burnin.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.burnin.scanner.measure.MeasurementActivity
import com.burnin.scanner.net.ControlClient
import com.burnin.scanner.net.Protocol
import com.burnin.scanner.net.Session
import com.burnin.scanner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 측정 기기 앱 홈 화면: 대상 기기 IP 입력 → 연결(HELLO) → 측정 화면으로 이동.
 * (12.1 페어링 중 "수동 IP 입력" 경로의 MVP 구현. QR/자동 탐색은 후속.)
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var editIp: EditText
    private lateinit var txtTarget: TextView
    private lateinit var btnMeasure: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editIp = findViewById(R.id.editIp)
        txtTarget = findViewById(R.id.txtTarget)
        btnMeasure = findViewById(R.id.btnMeasure)
        val txtLog = findViewById<TextView>(R.id.txtLog)
        val scrollLog = findViewById<ScrollView>(R.id.scrollLog)

        AppLog.listener = { text ->
            txtLog.text = text
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        editIp.setText(prefs.getString("last_ip", ""))

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
        btnMeasure.setOnClickListener {
            startActivity(Intent(this, MeasurementActivity::class.java))
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
        AppLog.i("측정 기기 앱 시작")
    }

    private fun connect() {
        val ip = editIp.text.toString().trim()
        if (ip.isEmpty()) {
            AppLog.i("IP를 입력하세요")
            return
        }
        getSharedPreferences("app", MODE_PRIVATE).edit().putString("last_ip", ip).apply()
        txtTarget.text = "대상 기기: 연결 중..."
        btnMeasure.isEnabled = false

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    Session.client?.close()
                    val c = ControlClient(ip)
                    c.connect()
                    val r = c.request(JSONObject().put("cmd", Protocol.CMD_HELLO))
                    Session.client = c
                    r
                }
                val dev = reply.getJSONObject("device")
                val screen = reply.getJSONObject("screen")
                Session.screenWidth = screen.getInt("width")
                Session.screenHeight = screen.getInt("height")
                Session.targetName = "${dev.optString("manufacturer")} ${dev.optString("model")}"
                txtTarget.text =
                    "대상 기기: ${Session.targetName} (Android ${dev.optString("android")}, " +
                        "화면 ${Session.screenWidth}x${Session.screenHeight})"
                btnMeasure.isEnabled = true
                AppLog.i("연결 성공: $ip — 화면 ${Session.screenWidth}x${Session.screenHeight}")
            } catch (e: Exception) {
                txtTarget.text = "대상 기기: 연결 실패"
                AppLog.i("연결 실패: ${e.message} (대상 앱 실행/같은 Wi-Fi 여부 확인)")
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1 && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            AppLog.i("카메라 권한이 거부되어 측정할 수 없습니다")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
