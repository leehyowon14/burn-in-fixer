package com.burnin.scanner.measure

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.burnin.scanner.R
import com.burnin.scanner.analysis.Analyzer
import com.burnin.scanner.analysis.GrayImage
import com.burnin.scanner.analysis.ImageOps
import com.burnin.scanner.analysis.ScreenDetector
import com.burnin.scanner.camera.CaptureController
import com.burnin.scanner.net.ControlClient
import com.burnin.scanner.net.Protocol
import com.burnin.scanner.net.Session
import com.burnin.scanner.report.ReportStore
import com.burnin.scanner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 측정 → 보정맵 생성 → 반복 보정(수렴 기반) → 평가 → 리포트의 전체 워크플로우
 * (7.2/7.3, MVP 2 + MVP 4).
 *
 * 순서:
 *  1. gray70 표시 → AE 수렴 → AE/AWB 잠금 (세션 내 모든 촬영 동일 노출)
 *  2. gray70 촬영(평균) → 화면 사각형 검출 → 호모그래피 + 시작 기하 왜곡 진단
 *  3. black 촬영 → black offset + 잔여 미광 검증
 *  4. gray70 + gray30 휘도 그리드 + 목표값(하위 10퍼센타일)
 *  5. red70/green70/blue70 채널별 진단 측정 (M-FR-009)
 *  6. gray70/gray30 혼합 초기 보정맵 생성·적용
 *  7. 반복 보정 루프 (M-FR-012): gray70/gray30 재측정 → damping 갱신 → 수렴/발산 감지
 *  8. 최종 gray70/gray30 평가·판정 (M-FR-017)
 *  9. 리포트 저장 (M-FR-014)
 */
class MeasurementActivity : Activity() {

    companion object {
        private const val FRAMES_PER_PATTERN = 3
        private const val LOW_LIGHT_PATTERN = "gray30"
        private const val LOW_LIGHT_FRAMES = 5
        private const val LOW_LIGHT_STRAY_WARN = 0.10f
        private const val LOW_LIGHT_GAIN_WEIGHT = 0.35f
        private const val LOW_LIGHT_WEAK_GAIN_WEIGHT = 0.12f
        private const val MAX_ATTENUATION = 0.05f
        private const val GRID_LONG_EDGE = 512
        private const val PASS_RMS = 0.015f      // 합격: 잔여 RMS ≤ 1.5%
        private const val MAX_ITERATIONS = 10    // 기본 모드 상한 (14.9)
        private const val DAMPING_ALPHA = 0.3f   // 반복 damping
        private const val DRIFT_LIMIT = 0.10f    // 중앙 휘도 드리프트 > 10%면 조건 변화로 무효
        private const val DISTORTION_WARN_SCORE = 0.18f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var capture: CaptureController? = null
    private lateinit var txtStatus: TextView
    private lateinit var txtResult: TextView
    private lateinit var btnStart: Button
    private lateinit var btnToggle: Button
    private var correctionOn = true
    private val resultLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        txtStatus = findViewById(R.id.txtStatus)
        txtResult = findViewById(R.id.txtResult)
        btnStart = findViewById(R.id.btnStart)
        btnToggle = findViewById(R.id.btnToggle)

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            scope.launch { runMeasurementSafely() }
        }
        btnToggle.setOnClickListener { toggleCorrection() }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("카메라 권한 없음 — 홈 화면에서 권한을 허용하세요")
            btnStart.isEnabled = false
            return
        }

        scope.launch {
            try {
                val c = CaptureController(this@MeasurementActivity, findViewById<TextureView>(R.id.preview))
                c.start()
                capture = c
                status("카메라 준비 완료 — 정렬 확인 후 [측정 시작]")
                log("카메라: ${c.jpegSize.width}x${c.jpegSize.height} JPEG, ${c.hardwareLevelText()}")
            } catch (e: Exception) {
                status("카메라 초기화 실패: ${e.message}")
                btnStart.isEnabled = false
            }
        }
    }

    private fun status(msg: String) {
        txtStatus.text = msg
        AppLog.i(msg)
    }

    private fun log(msg: String) {
        resultLog.appendLine(msg)
        txtResult.text = resultLog.toString()
        AppLog.i(msg)
    }

    private suspend fun runMeasurementSafely() {
        try {
            runMeasurement()
        } catch (e: Exception) {
            status("측정 실패: ${e.message}")
            log("!! 중단: ${e.message}")
        } finally {
            btnStart.isEnabled = true
        }
    }

    private suspend fun runMeasurement() {
        val client = Session.client ?: throw IllegalStateException("대상 기기 미연결")
        val cap = capture ?: throw IllegalStateException("카메라 미준비")
        val screenW = Session.screenWidth
        val screenH = Session.screenHeight
        require(screenW > 0 && screenH > 0) { "대상 화면 정보 없음" }
        resultLog.clear()
        log("대상: ${Session.targetName} (${screenW}x${screenH})")

        // ── 1. 기준 패턴 + 노출 잠금 ─────────────────────────────
        status("1/10 gray70 패턴 표시, 노출 수렴 중...")
        withContext(Dispatchers.IO) {
            client.command(Protocol.CMD_DISABLE_CORRECTION)
            client.showPattern("gray70")
        }
        delay(2500)
        cap.lockAeAwb()
        delay(400)

        // ── 2. 기준 촬영 + 화면 검출 ─────────────────────────────
        status("2/10 기준 패턴 촬영 (${FRAMES_PER_PATTERN}장)...")
        val grayAvg = captureAveraged(cap)
        val det = withContext(Dispatchers.Default) { ScreenDetector.detect(grayAvg) }
            ?: throw IllegalStateException("화면 검출 실패 — 차광 상태와 카메라 정렬을 확인하세요")
        log("화면 검출: ${det.quad}  (프레임 점유율 ${(det.areaRatio * 100).toInt()}%)")
        when {
            det.areaRatio < 0.25f ->
                log("경고: 화면이 작게 잡힘 → 측정 신뢰도 낮음. 카메라를 더 가까이 (권장 40~90%)")
            det.areaRatio > 0.95f ->
                log("경고: 화면이 프레임을 넘칠 수 있음. 카메라를 조금 멀리")
        }
        val homography = Analyzer.buildHomography(det.quad, screenW, screenH)
            ?: throw IllegalStateException("호모그래피 계산 실패")
        val geometry = Analyzer.geometryQuality(det.quad, screenW, screenH)
        log(
            "시작 왜곡/정렬 점수 ${fmt(geometry.score)} " +
                "(aspect ${fmt(geometry.aspectError)}, edge ${fmt(geometry.edgeBalance)}, " +
                "diag ${fmt(geometry.diagonalError)}, parallel ${fmt(geometry.parallelErrorDeg)}°)"
        )
        if (geometry.score > DISTORTION_WARN_SCORE) {
            log("경고: 시작 기하 왜곡이 큼 — 카메라를 화면과 더 평행하게 맞추면 고해상도 맵 품질이 좋아집니다")
        }

        // ── 3. 블랙 오프셋 ──────────────────────────────────────
        status("3/10 black 패턴 촬영 (오프셋/미광 검증)...")
        withContext(Dispatchers.IO) { client.showPattern("black") }
        delay(700)
        val blackAvg = captureAveraged(cap)

        // ── 4. 휘도맵 + 목표값 ──────────────────────────────────
        status("4/10 gray70 휘도맵 계산...")
        val gw: Int
        val gh: Int
        if (screenW >= screenH) {
            gw = GRID_LONG_EDGE
            gh = Math.max(8, Math.round(GRID_LONG_EDGE.toFloat() * screenH / screenW))
        } else {
            gh = GRID_LONG_EDGE
            gw = Math.max(8, Math.round(GRID_LONG_EDGE.toFloat() * screenW / screenH))
        }
        val lumaBefore = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(grayAvg, blackAvg, homography, screenW, screenH, gw, gh)
        }
        val blackGrid = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(blackAvg, null, homography, screenW, screenH, 16, 16)
        }
        val blackStats = Analyzer.stats(blackGrid)
        val statsBefore = Analyzer.stats(lumaBefore)
        val target = statsBefore.p10
        val strayRatio = blackStats.median / statsBefore.median
        if (strayRatio > 0.05f) {
            log("경고: 잔여 미광 ${(strayRatio * 100).toInt()}% — 박스 차광을 보완하세요")
        }
        log(
            "보정 전: RMS편차 ${pct(statsBefore.rmsDev)}, P95 ${pct(statsBefore.p95Dev)}, " +
                "최대 ${pct(statsBefore.maxDev)}"
        )

        // ── 5. 저휘도 맵 측정: gray30도 보정맵에 혼합 ─────────────
        status("5/10 $LOW_LIGHT_PATTERN 저휘도 촬영 (${LOW_LIGHT_FRAMES}장)...")
        val lowBeforeAvg = capturePatternAveraged(client, cap, LOW_LIGHT_PATTERN, LOW_LIGHT_FRAMES)
        val lumaLowBefore = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(lowBeforeAvg, blackAvg, homography, screenW, screenH, gw, gh)
        }
        val lowStatsBefore = Analyzer.stats(lumaLowBefore)
        val lowTarget = lowStatsBefore.p10
        val lowStrayRatio = blackStats.median / lowStatsBefore.median
        val lowSignalValid = lowStrayRatio <= LOW_LIGHT_STRAY_WARN
        val lowLightWeight = if (lowSignalValid) LOW_LIGHT_GAIN_WEIGHT else LOW_LIGHT_WEAK_GAIN_WEIGHT
        log(
            "$LOW_LIGHT_PATTERN 보정 전: RMS ${pct(lowStatsBefore.rmsDev)}, " +
                "P95 ${pct(lowStatsBefore.p95Dev)}, median ${fmt(lowStatsBefore.median)}, " +
                "black/gray30 ${pct(lowStrayRatio)}"
        )
        if (!lowSignalValid) {
            log(
                "경고: 저휘도 신호 부족(low_light_signal_weak) — " +
                    "$LOW_LIGHT_PATTERN 보정 반영 가중치를 ${pct(lowLightWeight)}로 낮춤"
            )
        } else {
            log("$LOW_LIGHT_PATTERN 보정 반영 가중치 ${pct(lowLightWeight)}")
        }

        // ── 6. RGB 채널별 진단 측정 (보정 OFF 상태) ───────────────
        status("6/10 RGB 채널별 측정...")
        val rgbStats = LinkedHashMap<String, Analyzer.Stats>()
        val rgbHeatmaps = LinkedHashMap<String, ByteArray>()
        try {
            for (ch in listOf("red70", "green70", "blue70")) {
                withContext(Dispatchers.IO) { client.showPattern(ch) }
                delay(700)
                val avg = captureAveraged(cap)
                val grid = withContext(Dispatchers.Default) {
                    Analyzer.lumaGrid(avg, blackAvg, homography, screenW, screenH, gw, gh)
                }
                rgbStats[ch] = Analyzer.stats(grid)
                rgbHeatmaps["deviation_$ch.png"] =
                    withContext(Dispatchers.Default) { Analyzer.deviationHeatmapPng(grid, gw, gh) }
            }
            log(
                "채널 편차 RMS — R ${pct(rgbStats["red70"]!!.rmsDev)}, " +
                    "G ${pct(rgbStats["green70"]!!.rmsDev)}, B ${pct(rgbStats["blue70"]!!.rmsDev)}"
            )
        } catch (e: Exception) {
            log("RGB 채널 측정 건너뜀: ${e.message}")
        }

        // ── 7~8. 초기 보정맵 + 반복 보정 루프 ────────────────────
        val gray70Gain = withContext(Dispatchers.Default) {
            Analyzer.gainGrid(lumaBefore, gw, gh, MAX_ATTENUATION)
        }
        val gray30Gain = withContext(Dispatchers.Default) {
            Analyzer.gainGrid(lumaLowBefore, gw, gh, MAX_ATTENUATION)
        }
        var gain = withContext(Dispatchers.Default) {
            Analyzer.mixGainGrids(gray70Gain, gray30Gain, lowLightWeight, MAX_ATTENUATION)
        }
        var bestGain = gain
        var bestScore = Float.MAX_VALUE
        var bestRms = Float.MAX_VALUE
        var bestLowRms = Float.MAX_VALUE
        var prevScore = Float.MAX_VALUE
        var divergeCount = 0
        var invalid = false
        var lastAppliedIsBest = true
        val iterRmsList = ArrayList<Float>()
        val iterLowRmsList = ArrayList<Float>()

        withContext(Dispatchers.IO) { client.showPattern("gray70") }
        var iter = 0
        while (iter < MAX_ITERATIONS) {
            iter++
            status("7/10 반복 $iter/$MAX_ITERATIONS — 보정맵 전송·적용...")
            applyGainMap(client, gain, gw, gh, screenW, screenH)
            lastAppliedIsBest = false

            status("8/10 반복 $iter/$MAX_ITERATIONS — gray70/gray30 재촬영·평가...")
            delay(900)
            val afterAvg = captureAveraged(cap)
            val detAfter = withContext(Dispatchers.Default) { ScreenDetector.detect(afterAvg) }
            val hAfter = detAfter?.let { Analyzer.buildHomography(it.quad, screenW, screenH) }
                ?: homography
            if (detAfter != null) {
                val shift = Math.abs(detAfter.quad.tl.x - det.quad.tl.x) +
                    Math.abs(detAfter.quad.tl.y - det.quad.tl.y)
                if (shift > 4f) log("반복 $iter: 카메라 미세 이동 ${shift.toInt()}px → 재정렬 적용")
            }
            val lumaAfter = withContext(Dispatchers.Default) {
                Analyzer.lumaGrid(afterAvg, blackAvg, hAfter, screenW, screenH, gw, gh)
            }
            val st = Analyzer.stats(lumaAfter)
            val lowAfterAvg = capturePatternAveraged(client, cap, LOW_LIGHT_PATTERN, LOW_LIGHT_FRAMES)
            val lumaLowAfter = withContext(Dispatchers.Default) {
                Analyzer.lumaGrid(lowAfterAvg, blackAvg, hAfter, screenW, screenH, gw, gh)
            }
            val lowSt = Analyzer.stats(lumaLowAfter)
            val compositeScore = st.rmsDev * (1f - lowLightWeight) + lowSt.rmsDev * lowLightWeight

            // 측정 조건 변화 감지 (M-FR-013): 중앙 휘도가 예상치에서 크게 벗어나면 무효
            val loss = 1f - mean(gain)
            val drift = st.median / (statsBefore.median * (1f - loss)) - 1f
            if (Math.abs(drift) > DRIFT_LIMIT) {
                invalid = true
                log(
                    "반복 $iter: 중앙 휘도 드리프트 ${pct(drift)} — 반사/이동/노출 변화 의심. " +
                        "반복 중단 (암실 박스 고정 후 재측정 권장)"
                )
                break
            }

            iterRmsList += st.rmsDev
            iterLowRmsList += lowSt.rmsDev
            log(
                "반복 $iter: gray70 RMS ${pct(st.rmsDev)} (P95 ${pct(st.p95Dev)}), " +
                    "$LOW_LIGHT_PATTERN RMS ${pct(lowSt.rmsDev)} (복합 ${pct(compositeScore)})"
            )

            if (compositeScore < bestScore - 1e-6f) {
                bestScore = compositeScore
                bestRms = st.rmsDev
                bestLowRms = lowSt.rmsDev
                bestGain = gain
                lastAppliedIsBest = true
                divergeCount = 0
            } else {
                divergeCount++
            }

            if (st.rmsDev <= PASS_RMS) {
                log("합격 기준(≤ ${pct(PASS_RMS)}) 도달 — 반복 종료")
                break
            }
            if (divergeCount >= 2) {
                log("잔여 오차 증가 2회 연속(발산) — 최적 맵으로 롤백")
                break
            }
            // 수렴 판정: 개선량이 절대 0.1%p + 현재 RMS의 5% 미만이면 종료
            // (14.9의 고정 0.5%p 기준은 약한 번인에서 조기 종료되므로 상대 기준 병행)
            val stopDelta = 0.001f + compositeScore * 0.05f
            if (iter >= 2 && prevScore - compositeScore < stopDelta) {
                log("수렴 판정 (반복당 개선 < ${pct(stopDelta)}) — 반복 종료")
                break
            }
            prevScore = compositeScore

            val refined70 = withContext(Dispatchers.Default) {
                Analyzer.refineGain(gain, lumaAfter, target, DAMPING_ALPHA, gw, gh, MAX_ATTENUATION)
            }
            val refined30 = withContext(Dispatchers.Default) {
                Analyzer.refineGain(gain, lumaLowAfter, lowTarget, DAMPING_ALPHA, gw, gh, MAX_ATTENUATION)
            }
            gain = withContext(Dispatchers.Default) {
                Analyzer.mixGainGrids(refined70, refined30, lowLightWeight, MAX_ATTENUATION)
            }
        }

        // 발산·초과 반복 후에는 최적 시점 맵으로 되돌린다 (14.9 롤백)
        if (!invalid && !lastAppliedIsBest) {
            status("9/10 최적 반복 맵으로 롤백 적용...")
            applyGainMap(client, bestGain, gw, gh, screenW, screenH)
        }

        // ── 8. 최종 평가·판정 ───────────────────────────────────
        var finalLumaAfter: FloatArray? = null
        var finalStatsAfter: Analyzer.Stats? = null
        var finalLowLumaAfter: FloatArray? = null
        var finalLowStatsAfter: Analyzer.Stats? = null
        if (!invalid) {
            status("9/10 최종 gray70/gray30 촬영·평가...")
            withContext(Dispatchers.IO) { client.showPattern("gray70") }
            delay(900)
            val finalAvg = captureAveraged(cap)
            val detFinal = withContext(Dispatchers.Default) { ScreenDetector.detect(finalAvg) }
            val hFinal = detFinal?.let { Analyzer.buildHomography(it.quad, screenW, screenH) }
                ?: homography
            val finalGrid = withContext(Dispatchers.Default) {
                Analyzer.lumaGrid(finalAvg, blackAvg, hFinal, screenW, screenH, gw, gh)
            }
            finalLumaAfter = finalGrid
            finalStatsAfter = Analyzer.stats(finalGrid)

            val lowFinalAvg = capturePatternAveraged(client, cap, LOW_LIGHT_PATTERN, LOW_LIGHT_FRAMES)
            val finalLowGrid = withContext(Dispatchers.Default) {
                Analyzer.lumaGrid(lowFinalAvg, blackAvg, hFinal, screenW, screenH, gw, gh)
            }
            finalLowLumaAfter = finalLowGrid
            finalLowStatsAfter = Analyzer.stats(finalLowGrid)
            withContext(Dispatchers.IO) { client.showPattern("gray70") }
        }

        val finalRms = finalStatsAfter?.rmsDev
            ?: if (bestRms == Float.MAX_VALUE) statsBefore.rmsDev else bestRms
        val finalLowRms = finalLowStatsAfter?.rmsDev
            ?: if (bestLowRms == Float.MAX_VALUE) lowStatsBefore.rmsDev else bestLowRms
        val improvement = 1.0 - finalRms.toDouble() / statsBefore.rmsDev
        val lowImprovement = 1.0 - finalLowRms.toDouble() / lowStatsBefore.rmsDev
        val brightnessLoss = (1f - mean(bestGain)).toDouble()
        val verdict = when {
            invalid -> "평가 무효(측정 조건 변화)"
            finalRms <= PASS_RMS -> "합격"
            else -> "미달(측정 환경 개선 또는 심한 번인)"
        }
        log("──────────────")
        log("반복 ${iterRmsList.size}회, 최종 잔여 RMS ${pct(finalRms)} (보정 전 ${pct(statsBefore.rmsDev)})")
        log(
            "$LOW_LIGHT_PATTERN 보정 후 RMS ${pct(finalLowRms)} " +
                "(보정 전 ${pct(lowStatsBefore.rmsDev)}, 개선율 ${pct(lowImprovement.toFloat())})"
        )
        log("개선율 ${pct(improvement.toFloat())}, 평균 밝기 손실 ${pct(brightnessLoss.toFloat())}")
        log("판정: $verdict (합격 기준 RMS ≤ ${pct(PASS_RMS)})")

        // ── 9. 리포트 저장 ──────────────────────────────────────
        status("10/10 리포트 저장...")
        val bestPng = withContext(Dispatchers.Default) {
            Analyzer.toAlphaPng(bestGain, gw, gh, screenW, screenH, MAX_ATTENUATION)
        }
        val iterations = JSONArray().apply { iterRmsList.forEach { put(it.toDouble()) } }
        val lowIterations = JSONArray().apply { iterLowRmsList.forEach { put(it.toDouble()) } }
        val rgbJson = JSONObject()
        rgbStats.forEach { (ch, st) ->
            rgbJson.put(ch, JSONObject().put("rms", st.rmsDev.toDouble()).put("p95", st.p95Dev.toDouble()))
        }
        val report = JSONObject()
            .put("reportVersion", 2)
            .put("createdAt", java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(java.util.Date()))
            .put("targetDevice", Session.targetName)
            .put("scannerDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("screenResolution", "${screenW}x${screenH}")
            .put("analysisGrid", "${gw}x${gh}")
            .put("frameAreaRatio", det.areaRatio.toDouble())
            .put("geometryScore", geometry.score.toDouble())
            .put("geometryAspectError", geometry.aspectError.toDouble())
            .put("geometryEdgeBalance", geometry.edgeBalance.toDouble())
            .put("geometryDiagonalError", geometry.diagonalError.toDouble())
            .put("geometryParallelErrorDeg", geometry.parallelErrorDeg.toDouble())
            .put("strayLightRatio", strayRatio.toDouble())
            .put("rmsBefore", statsBefore.rmsDev.toDouble())
            .put("p95Before", statsBefore.p95Dev.toDouble())
            .put("maxBefore", statsBefore.maxDev.toDouble())
            .put("rmsAfterBest", finalRms.toDouble())
            .put("iterationRms", iterations)
            .put("iterationGray30Rms", lowIterations)
            .put("iterationCount", iterRmsList.size)
            .put("improvementRatio", improvement)
            .put("gray30RmsBefore", lowStatsBefore.rmsDev.toDouble())
            .put("gray30RmsAfter", finalLowRms.toDouble())
            .put("gray30P95Before", lowStatsBefore.p95Dev.toDouble())
            .put("gray30P95After", (finalLowStatsAfter?.p95Dev ?: lowStatsBefore.p95Dev).toDouble())
            .put("gray30ImprovementRatio", lowImprovement)
            .put("gray30StrayRatio", lowStrayRatio.toDouble())
            .put("gray30SignalValid", lowSignalValid)
            .put("gray30GainWeight", lowLightWeight.toDouble())
            .put("estimatedBrightnessLoss", brightnessLoss)
            .put("maxAttenuation", MAX_ATTENUATION.toDouble())
            .put("rgbChannels", rgbJson)
            .put(
                "evaluationVerdict",
                when {
                    invalid -> "invalid_condition_changed"
                    finalRms <= PASS_RMS -> "pass"
                    else -> "retry"
                },
            )
        val dir = ReportStore.newSessionDir(this)
        withContext(Dispatchers.IO) {
            val files = LinkedHashMap<String, ByteArray>()
            files["correction_alpha_${screenW}x${screenH}.png"] = bestPng
            files["deviation_before.png"] = Analyzer.deviationHeatmapPng(lumaBefore, gw, gh)
            files["deviation_after.png"] =
                Analyzer.deviationHeatmapPng(finalLumaAfter ?: lumaBefore, gw, gh)
            files["deviation_gray30_before.png"] = Analyzer.deviationHeatmapPng(lumaLowBefore, gw, gh)
            files["deviation_gray30_after.png"] =
                Analyzer.deviationHeatmapPng(finalLowLumaAfter ?: lumaLowBefore, gw, gh)
            files.putAll(rgbHeatmaps)
            ReportStore.save(dir, report, files)
        }
        log("리포트 저장: ${dir.absolutePath}")
        status("측정 완료 — $verdict, 개선율 ${pct(improvement.toFloat())}")
        btnToggle.isEnabled = true
        correctionOn = true
    }

    /** gain 그리드 → 네이티브 알파 PNG → 전송 → 보정 ON → gray70 표시 유지 */
    private suspend fun applyGainMap(
        client: ControlClient,
        gain: FloatArray,
        gw: Int,
        gh: Int,
        screenW: Int,
        screenH: Int,
    ) {
        val png = withContext(Dispatchers.Default) {
            Analyzer.toAlphaPng(gain, gw, gh, screenW, screenH, MAX_ATTENUATION)
        }
        withContext(Dispatchers.IO) {
            client.request(
                JSONObject()
                    .put("cmd", Protocol.CMD_APPLY_MAP)
                    .put("width", screenW)
                    .put("height", screenH)
                    .put("maxAttenuation", MAX_ATTENUATION.toDouble())
                    .put("defaultStrength", 100)
                    .put("checksumMd5", md5(png))
                    .put("sourceDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("data", Base64.encodeToString(png, Base64.NO_WRAP)),
                timeoutMs = 120_000,
            )
            client.command(Protocol.CMD_ENABLE_CORRECTION, "strength" to 100)
            client.showPattern("gray70")
        }
    }

    private fun mean(a: FloatArray): Float {
        var s = 0.0
        for (v in a) s += v.toDouble()
        return (s / a.size).toFloat()
    }

    private suspend fun capturePatternAveraged(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        settleMs: Long = 900,
    ): GrayImage {
        withContext(Dispatchers.IO) { client.showPattern(pattern) }
        delay(settleMs)
        return captureAveraged(cap, frameCount)
    }

    private suspend fun captureAveraged(
        cap: CaptureController,
        frameCount: Int = FRAMES_PER_PATTERN,
    ): GrayImage {
        val frames = cap.captureFrames(frameCount)
        return withContext(Dispatchers.Default) {
            ImageOps.average(frames.map { ImageOps.decodeLinearGray(it) })
        }
    }

    /** 대상 화면의 보정을 켜고 끄며 육안 비교 (보정 전/후 비교 UI의 원격 버전). */
    private fun toggleCorrection() {
        val client = Session.client ?: return
        correctionOn = !correctionOn
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (correctionOn) client.command(Protocol.CMD_ENABLE_CORRECTION, "strength" to 100)
                    else client.command(Protocol.CMD_DISABLE_CORRECTION)
                }
                status("대상 화면 보정 ${if (correctionOn) "ON" else "OFF"}")
            } catch (e: Exception) {
                log("토글 실패: ${e.message}")
            }
        }
    }

    private fun pct(v: Float): String = String.format(java.util.Locale.US, "%.2f%%", v * 100)

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.4f", v)

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun onDestroy() {
        scope.cancel()
        capture?.close()
        capture = null
        super.onDestroy()
    }
}
