package com.burnin.target.net

import android.content.Context
import android.graphics.Point
import android.os.Build
import com.burnin.target.correction.CorrectionStore
import com.burnin.target.overlay.OverlayService
import com.burnin.target.pattern.PatternBus
import com.burnin.target.pattern.PatternSpec
import com.burnin.target.util.AppLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 측정 기기(Scanner)의 명령을 받는 TCP 서버 (12장 통신 요구사항).
 * newline 구분 JSON 프로토콜. 클라이언트는 한 번에 하나만 처리한다.
 */
object ControlServer {

    private val started = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile var clientAddress: String? = null
        private set

    fun start(context: Context) {
        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        Thread({ serverLoop() }, "control-server").apply { isDaemon = true }.start()
    }

    private fun serverLoop() {
        try {
            val server = ServerSocket(Protocol.PORT)
            AppLog.i("서버 대기 중: 포트 ${Protocol.PORT}")
            while (true) {
                val socket = server.accept()
                clientAddress = socket.inetAddress.hostAddress
                AppLog.i("측정 기기 연결됨: $clientAddress")
                try {
                    handleClient(socket)
                } catch (e: Exception) {
                    AppLog.i("연결 종료: ${e.message}")
                } finally {
                    clientAddress = null
                    runCatching { socket.close() }
                    AppLog.i("측정 기기 연결 해제")
                }
            }
        } catch (e: Exception) {
            AppLog.i("서버 오류: ${e.message}")
            started.set(false)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 1 shl 16)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), 1 shl 16)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val reply = try {
                handleMessage(JSONObject(line))
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "요청 처리 오류: ${e.message}")
            }
            writer.write(reply.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    private fun handleMessage(msg: JSONObject): JSONObject {
        val context = appContext ?: return err("HELLO", "컨텍스트 없음")
        val cmd = msg.optString("cmd")
        val reply = JSONObject().put("ok", true).put("cmd", cmd)

        when (cmd) {
            Protocol.CMD_HELLO, Protocol.CMD_SCREEN_INFO -> {
                val p: Point = CorrectionStore.realScreenSize(context)
                reply.put("device", JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE))
                reply.put("screen", JSONObject()
                    .put("width", p.x)
                    .put("height", p.y))
                val m = CorrectionStore.meta
                reply.put("correction", JSONObject()
                    .put("loaded", m != null)
                    .put("width", m?.width ?: 0)
                    .put("height", m?.height ?: 0))
                if (cmd == Protocol.CMD_HELLO) AppLog.i("HELLO 수신 (화면 ${p.x}x${p.y})")
            }

            Protocol.CMD_SHOW_PATTERN -> {
                val name = msg.optString("pattern")
                val spec = PatternSpec.parse(name)
                    ?: return err(cmd, "알 수 없는 패턴: $name")
                val latch = CountDownLatch(1)
                PatternBus.showPattern(context, spec) { latch.countDown() }
                val applied = latch.await(7, TimeUnit.SECONDS)
                if (!applied) return err(cmd, "패턴 표시 시간 초과")
                AppLog.i("패턴 표시: $name")
                reply.put("pattern", name)
            }

            Protocol.CMD_APPLY_MAP -> {
                val error = CorrectionStore.applyFromBase64(
                    context = context,
                    width = msg.getInt("width"),
                    height = msg.getInt("height"),
                    maxAttenuation = msg.optDouble("maxAttenuation", 0.05),
                    defaultStrengthPct = msg.optInt("defaultStrength", 100),
                    checksumMd5 = msg.optString("checksumMd5", ""),
                    dataBase64 = msg.getString("data"),
                    sourceDevice = msg.optString("sourceDevice", "scanner"),
                )
                if (error != null) {
                    AppLog.i("보정맵 거부: $error")
                    return err(cmd, error)
                }
            }

            Protocol.CMD_ENABLE_CORRECTION -> {
                if (CorrectionStore.bakedBitmap == null) return err(cmd, "적재된 보정맵 없음")
                PatternBus.setCorrection(true, msg.optInt("strength", 100))
                AppLog.i("앱 내부 보정 ON (강도 ${PatternBus.strengthPct}%)")
            }

            Protocol.CMD_DISABLE_CORRECTION -> {
                PatternBus.setCorrection(false, null)
                AppLog.i("앱 내부 보정 OFF")
            }

            Protocol.CMD_ENABLE_OVERLAY -> {
                val error = OverlayService.requestStart(context, msg.optInt("strength", 100))
                if (error != null) return err(cmd, error)
            }

            Protocol.CMD_DISABLE_OVERLAY -> OverlayService.requestStop(context)

            Protocol.CMD_END_SESSION -> {
                PatternBus.endSession()
                AppLog.i("세션 종료")
            }

            else -> return err(cmd, "알 수 없는 명령")
        }
        return reply
    }

    private fun err(cmd: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("cmd", cmd).put("error", message)
}
