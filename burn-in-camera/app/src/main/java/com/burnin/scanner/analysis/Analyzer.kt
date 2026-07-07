package com.burnin.scanner.analysis

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * 측정 파이프라인의 수치 부분 (14장 알고리즘 요구사항의 MVP 구현).
 *
 *  촬영(선형화·평균) → black offset 제거 → 호모그래피 정합 →
 *  분석 그리드 휘도맵 → 통계 → gain 맵(정상 영역 낮춤) → 스무딩 →
 *  네이티브 해상도 알파 감쇠 PNG
 *
 * 렌즈/카메라 기인 성분은 dot-grid 다점 정합과 radial flat-field로 줄이고,
 * SNR/미광/클리핑 confidence를 gain 계산에 곱해 과보정을 억제한다.
 */
object Analyzer {

    data class Stats(
        val median: Float,
        val p10: Float,
        val rmsDev: Float,   // 중앙값 대비 상대 편차 RMS
        val p95Dev: Float,   // 상대 편차 절대값의 P95
        val maxDev: Float,   // 상대 편차 절대값의 최대
        val mean: Float,
    )

    data class GeometryQuality(
        val score: Float,
        val aspectError: Float,
        val edgeBalance: Float,
        val diagonalError: Float,
        val parallelErrorDeg: Float,
    )

    /** 화면 4모서리(스크린 좌표)와 검출 사각형(이미지 좌표)로 호모그래피 생성.
     *  카메라가 90° 돌아가 있으면 가로/세로 비율로 감지해 모서리 대응을 회전시킨다. */
    fun buildHomography(quad: ScreenDetector.Quad, screenW: Int, screenH: Int): Homography? {
        val src = arrayOf(
            Vec2(0f, 0f),
            Vec2(screenW.toFloat(), 0f),
            Vec2(screenW.toFloat(), screenH.toFloat()),
            Vec2(0f, screenH.toFloat()),
        )
        val screenLandscape = screenW >= screenH
        val quadLandscape = quad.topLen() >= quad.sideLen()
        val dst = if (screenLandscape == quadLandscape) {
            arrayOf(quad.tl, quad.tr, quad.br, quad.bl)
        } else {
            // 카메라가 화면 대비 90° 회전: 화면 TL → 이미지 TR 방향으로 한 칸 회전
            arrayOf(quad.tr, quad.br, quad.bl, quad.tl)
        }
        return Homography.from4Points(src, dst)
    }

    /**
     * 시작 시점의 기하 왜곡/정렬 품질 진단.
     * 현재는 화면 경계 사각형만으로 perspective/skew/aspect 왜곡을 추정한다.
     * 렌즈의 국소 왜곡은 dotgrid 검출이 들어오면 이 값과 별도 항목으로 더해야 한다.
     */
    fun geometryQuality(quad: ScreenDetector.Quad, screenW: Int, screenH: Int): GeometryQuality {
        val top = quad.topLen().coerceAtLeast(1e-5f)
        val bottom = dist(quad.bl, quad.br).coerceAtLeast(1e-5f)
        val left = quad.sideLen().coerceAtLeast(1e-5f)
        val right = dist(quad.tr, quad.br).coerceAtLeast(1e-5f)
        val avgW = (top + bottom) * 0.5f
        val avgH = (left + right) * 0.5f

        val observedAspect = avgW / avgH
        val screenLandscape = screenW >= screenH
        val quadLandscape = avgW >= avgH
        val expectedAspectRaw = if (screenLandscape == quadLandscape) {
            screenW.toFloat() / screenH
        } else {
            screenH.toFloat() / screenW
        }
        val expectedAspect = expectedAspectRaw.coerceAtLeast(1e-5f)
        val aspectError = Math.abs(Math.log((observedAspect / expectedAspect).toDouble())).toFloat()

        val edgeBalance = maxOf(
            Math.abs(top - bottom) / avgW,
            Math.abs(left - right) / avgH,
        )
        val diagonalA = dist(quad.tl, quad.br).coerceAtLeast(1e-5f)
        val diagonalB = dist(quad.tr, quad.bl).coerceAtLeast(1e-5f)
        val diagonalError = Math.abs(diagonalA - diagonalB) / ((diagonalA + diagonalB) * 0.5f)
        val parallelError = maxOf(
            parallelAngleErrorDeg(quad.tl, quad.tr, quad.bl, quad.br),
            parallelAngleErrorDeg(quad.tl, quad.bl, quad.tr, quad.br),
        )

        val score = maxOf(
            aspectError,
            edgeBalance,
            diagonalError,
            parallelError / 20f,
        )
        return GeometryQuality(score, aspectError, edgeBalance, diagonalError, parallelError)
    }

    /**
     * 분석 그리드 휘도맵. 각 셀 중심 주변 3x3 지점을 호모그래피로 이미지에 투영해
     * 평균 샘플하고, black offset 맵(있으면)을 같은 위치에서 빼준다.
     * 화면 가장자리 2% 는 베젤 blending 오차가 커서 제외(클램프)한다.
     */
    fun lumaGrid(
        gray: GrayImage,
        black: GrayImage?,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): FloatArray {
        val grid = FloatArray(gw * gh)
        val out = DoubleArray(2)
        val margin = 0.02f
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val u = (margin + (1 - 2 * margin) * (gx + 0.5f) / gw) * screenW
                val v = (margin + (1 - 2 * margin) * (gy + 0.5f) / gh) * screenH
                var acc = 0f
                var accB = 0f
                for (sy in -1..1) {
                    for (sx in -1..1) {
                        h.map(
                            (u + sx * screenW * 0.15 / gw).toDouble(),
                            (v + sy * screenH * 0.15 / gh).toDouble(),
                            out,
                        )
                        acc += gray.bilinear(out[0].toFloat(), out[1].toFloat())
                        if (black != null) accB += black.bilinear(out[0].toFloat(), out[1].toFloat())
                    }
                }
                val value = (acc - accB) / 9f
                grid[gy * gw + gx] = value.coerceAtLeast(1e-5f)
            }
        }
        return grid
    }

    fun channelGrid(
        rgb: RgbImage,
        black: RgbImage?,
        channel: Int,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): FloatArray {
        val grid = FloatArray(gw * gh)
        val out = DoubleArray(2)
        val margin = 0.02f
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val u = (margin + (1 - 2 * margin) * (gx + 0.5f) / gw) * screenW
                val v = (margin + (1 - 2 * margin) * (gy + 0.5f) / gh) * screenH
                var acc = 0f
                var accB = 0f
                for (sy in -1..1) {
                    for (sx in -1..1) {
                        h.map(
                            (u + sx * screenW * 0.15 / gw).toDouble(),
                            (v + sy * screenH * 0.15 / gh).toDouble(),
                            out,
                        )
                        acc += rgb.bilinearChannel(out[0].toFloat(), out[1].toFloat(), channel)
                        if (black != null) {
                            accB += black.bilinearChannel(out[0].toFloat(), out[1].toFloat(), channel)
                        }
                    }
                }
                val value = (acc - accB) / 9f
                grid[gy * gw + gx] = value.coerceAtLeast(1e-5f)
            }
        }
        return grid
    }

    fun stats(grid: FloatArray): Stats {
        val sorted = grid.clone().apply { sort() }
        val median = sorted[sorted.size / 2]
        val p10 = sorted[(sorted.size * 0.10f).toInt().coerceIn(0, sorted.size - 1)]
        var sumSq = 0.0
        var sum = 0.0
        val absDev = FloatArray(grid.size)
        for (i in grid.indices) {
            val d = grid[i] / median - 1f
            absDev[i] = Math.abs(d)
            sumSq += (d * d).toDouble()
            sum += grid[i].toDouble()
        }
        absDev.sort()
        return Stats(
            median = median,
            p10 = p10,
            rmsDev = Math.sqrt(sumSq / grid.size).toFloat(),
            p95Dev = absDev[(absDev.size * 0.95f).toInt().coerceIn(0, absDev.size - 1)],
            maxDev = absDev.last(),
            mean = (sum / grid.size).toFloat(),
        )
    }

    /**
     * 카메라 비네팅/플랫필드 근사. 별도 적분구/무한균일 광원이 없는 현장 측정이므로,
     * gray 기준 프레임에서 반지름별 저주파 평균만 추정한다. 국소 번인 패턴은 radial bin에서
     * 희석되고, 화면 자체의 고주파/국소 편차는 보정맵 계산에 남는다.
     */
    fun radialFlatField(luma: FloatArray, gw: Int, gh: Int, bins: Int = 64): FloatArray {
        require(luma.size == gw * gh) { "grid size mismatch" }
        val median = stats(luma).median.coerceAtLeast(1e-5f)
        val sum = DoubleArray(bins)
        val count = IntArray(bins)
        val cx = (gw - 1) * 0.5f
        val cy = (gh - 1) * 0.5f
        val maxR = Math.sqrt((cx * cx + cy * cy).toDouble()).coerceAtLeast(1e-5)
        for (y in 0 until gh) {
            val dy = y - cy
            for (x in 0 until gw) {
                val dx = x - cx
                val b = ((Math.sqrt((dx * dx + dy * dy).toDouble()) / maxR) * (bins - 1))
                    .toInt()
                    .coerceIn(0, bins - 1)
                sum[b] += (luma[y * gw + x] / median).toDouble()
                count[b]++
            }
        }
        val profile = FloatArray(bins) { i ->
            if (count[i] > 0) (sum[i] / count[i]).toFloat() else 1f
        }
        repeat(3) {
            val copy = profile.clone()
            for (i in profile.indices) {
                var acc = 0f
                var n = 0
                for (d in -2..2) {
                    val j = i + d
                    if (j in profile.indices) {
                        acc += copy[j]
                        n++
                    }
                }
                profile[i] = acc / n
            }
        }
        val out = FloatArray(luma.size)
        for (y in 0 until gh) {
            val dy = y - cy
            for (x in 0 until gw) {
                val dx = x - cx
                val f = (Math.sqrt((dx * dx + dy * dy).toDouble()) / maxR * (bins - 1))
                    .toFloat()
                    .coerceIn(0f, bins - 1.001f)
                val i0 = f.toInt()
                val t = f - i0
                val a = profile[i0]
                val b = profile[(i0 + 1).coerceAtMost(bins - 1)]
                out[y * gw + x] = (a * (1 - t) + b * t).coerceIn(0.70f, 1.30f)
            }
        }
        return out
    }

    fun applyFlatField(grid: FloatArray, flatField: FloatArray?): FloatArray {
        if (flatField == null) return grid
        require(grid.size == flatField.size) { "flat-field grid size mismatch" }
        val out = FloatArray(grid.size)
        for (i in grid.indices) out[i] = (grid[i] / flatField[i].coerceAtLeast(0.50f)).coerceAtLeast(1e-5f)
        return out
    }

    fun flatFieldStrength(flatField: FloatArray): Float {
        var maxDev = 0f
        for (v in flatField) maxDev = maxOf(maxDev, Math.abs(v - 1f))
        return maxDev
    }

    /**
     * SNR/미광/클리핑 confidence. 1.0은 그대로 반영, 0.0은 gain 갱신을 막는다.
     * signal은 black-subtracted grid, black은 같은 좌표계의 black raw grid다.
     */
    fun confidenceGrid(
        signal: FloatArray,
        black: FloatArray?,
        strayWarn: Float,
        clipHigh: Float = 0.92f,
    ): FloatArray {
        if (black != null) require(signal.size == black.size) { "black grid size mismatch" }
        val out = FloatArray(signal.size)
        for (i in signal.indices) {
            val s = signal[i].coerceAtLeast(1e-5f)
            val b = black?.get(i)?.coerceAtLeast(0f) ?: 0f
            val strayRatio = if (black == null) 0f else b / s
            val strayConf = confidenceRamp(strayRatio, 0.03f, strayWarn.coerceAtLeast(0.031f))
            val snr = s / (b + 1e-5f)
            val snrConf = ((snr - 4f) / 16f).coerceIn(0f, 1f)
            val clipConf = confidenceRamp(s, clipHigh, 0.995f)
            out[i] = (strayConf * snrConf * clipConf).coerceIn(0f, 1f)
        }
        return out
    }

    fun meanConfidence(confidence: FloatArray?): Float {
        if (confidence == null || confidence.isEmpty()) return 1f
        var sum = 0.0
        for (v in confidence) sum += v.toDouble()
        return (sum / confidence.size).toFloat()
    }

    fun combineConfidence(primary: FloatArray, secondary: FloatArray): FloatArray {
        require(primary.size == secondary.size) { "confidence grid size mismatch" }
        val out = FloatArray(primary.size)
        for (i in primary.indices) out[i] = (primary[i] * secondary[i]).coerceIn(0f, 1f)
        return out
    }

    /**
     * 같은 패턴을 연속 촬영한 그리드들의 프레임 간 변화량을 confidence로 변환한다.
     * 플리커/롤링밴드처럼 매 프레임 위치나 밝기가 달라지는 영역은 gain 갱신에서 제외된다.
     */
    fun temporalStabilityConfidence(
        frameGrids: List<FloatArray>,
        gridW: Int,
        gridH: Int,
        outW: Int,
        outH: Int,
        okRelativeRange: Float,
        zeroRelativeRange: Float,
    ): FloatArray {
        if (frameGrids.size < 2) return FloatArray(outW * outH) { 1f }
        val size = gridW * gridH
        for (grid in frameGrids) require(grid.size == size) { "temporal grid size mismatch" }

        val low = FloatArray(size)
        for (i in 0 until size) {
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var sum = 0.0
            for (grid in frameGrids) {
                val v = grid[i]
                min = minOf(min, v)
                max = maxOf(max, v)
                sum += v.toDouble()
            }
            val mean = (sum / frameGrids.size).toFloat().coerceAtLeast(1e-5f)
            val relativeRange = (max - min) / mean
            low[i] = confidenceRamp(relativeRange, okRelativeRange, zeroRelativeRange)
        }

        val smoothed = boxBlur(boxBlur(low, gridW, gridH), gridW, gridH)
        return if (gridW == outW && gridH == outH) {
            smoothed
        } else {
            resampleGrid(smoothed, gridW, gridH, outW, outH)
        }
    }

    /**
     * gain 맵 생성 (14.5/14.6):
     *  target = 하위 10퍼센타일, gain = clamp(target/측정값, 1-maxAtt, 1)
     *  → 밝은(정상) 영역일수록 gain < 1 (낮춤), 번인(어두운) 영역은 1 (유지)
     * 3x3 박스 블러 2회로 측정 노이즈를 죽인다 (MVP smoothing).
     */
    fun gainGrid(
        luma: FloatArray,
        gw: Int,
        gh: Int,
        maxAtt: Float,
        confidence: FloatArray? = null,
    ): FloatArray {
        if (confidence != null) require(confidence.size == luma.size) { "confidence grid size mismatch" }
        val st = stats(luma)
        val target = st.p10
        val gain = FloatArray(luma.size)
        for (i in luma.indices) {
            val raw = (target / luma[i]).coerceIn(1f - maxAtt, 1f)
            val conf = confidence?.get(i) ?: 1f
            gain[i] = (1f - (1f - raw) * conf).coerceIn(1f - maxAtt, 1f)
        }
        var g = boxBlur(gain, gw, gh)
        g = boxBlur(g, gw, gh)
        applyEdgeRamp(g, gw, gh)
        return g
    }

    /**
     * 밝기별 gain 맵 혼합. gain 자체가 아니라 attenuation(1-gain)을 섞어
     * gray25 같은 저휘도에서만 보이는 자국도 실제 보정량에 반영한다.
     */
    fun mixGainGrids(
        primary: FloatArray,
        secondary: FloatArray,
        secondaryWeight: Float,
        maxAtt: Float,
    ): FloatArray {
        require(primary.size == secondary.size) { "gain grid size mismatch" }
        val w = secondaryWeight.coerceIn(0f, 1f)
        val out = FloatArray(primary.size)
        for (i in primary.indices) {
            val primaryAtt = 1f - primary[i]
            val secondaryAtt = 1f - secondary[i]
            val att = primaryAtt * (1f - w) + secondaryAtt * w
            out[i] = (1f - att).coerceIn(1f - maxAtt, 1f)
        }
        return out
    }

    fun averageGainGrids(gains: List<FloatArray>, maxAtt: Float): FloatArray? {
        if (gains.isEmpty()) return null
        val size = gains[0].size
        val out = FloatArray(size)
        for (g in gains) {
            require(g.size == size) { "gain grid size mismatch" }
            for (i in out.indices) out[i] += 1f - g[i]
        }
        val n = gains.size.toFloat()
        for (i in out.indices) {
            val att = out[i] / n
            out[i] = (1f - att).coerceIn(1f - maxAtt, 1f)
        }
        return out
    }

    /**
     * 반복 보정 갱신 (M-FR-012, 14.9):
     *   newGain = clamp(oldGain * (target / measuredAfter)^alpha, 1-maxAtt, 1)
     * alpha(기본 0.3)는 damping 계수. 갱신 후 노이즈 억제 블러와 가장자리 ramp를 재적용한다.
     */
    fun refineGain(
        gain: FloatArray,
        measuredAfter: FloatArray,
        target: Float,
        alpha: Float,
        gw: Int,
        gh: Int,
        maxAtt: Float,
        confidence: FloatArray? = null,
    ): FloatArray {
        if (confidence != null) require(confidence.size == gain.size) { "confidence grid size mismatch" }
        val out = FloatArray(gain.size)
        for (i in gain.indices) {
            val ratio = target.toDouble() / measuredAfter[i].coerceAtLeast(1e-5f)
            val localAlpha = alpha * (confidence?.get(i) ?: 1f)
            out[i] = (gain[i] * Math.pow(ratio, localAlpha.toDouble()).toFloat())
                .coerceIn(1f - maxAtt, 1f)
        }
        val g = boxBlur(out, gw, gh)
        applyEdgeRamp(g, gw, gh)
        return g
    }

    /** 가장자리 confidence 감쇠: 렌즈 왜곡/비네팅 잔차가 큰 바깥 5% 영역은 보정 강도를 줄인다 */
    private fun applyEdgeRamp(g: FloatArray, gw: Int, gh: Int) {
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val ex = Math.min(x, gw - 1 - x) / (gw * 0.05f)
                val ey = Math.min(y, gh - 1 - y) / (gh * 0.05f)
                val conf = Math.min(1f, Math.min(ex, ey))
                val i = y * gw + x
                g[i] = 1f + (g[i] - 1f) * conf
            }
        }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        val dst = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        acc += src[yy * w + xx]
                        n++
                    }
                }
                dst[y * w + x] = acc / n
            }
        }
        return dst
    }

    private fun resampleGrid(src: FloatArray, srcW: Int, srcH: Int, outW: Int, outH: Int): FloatArray {
        val image = GrayImage(srcW, srcH, src)
        val out = FloatArray(outW * outH)
        val sx = srcW / outW.toFloat()
        val sy = srcH / outH.toFloat()
        for (y in 0 until outH) {
            val gy = (y + 0.5f) * sy - 0.5f
            for (x in 0 until outW) {
                out[y * outW + x] = image.bilinear((x + 0.5f) * sx - 0.5f, gy)
            }
        }
        return out
    }

    private fun confidenceRamp(value: Float, okAt: Float, zeroAt: Float): Float {
        if (value <= okAt) return 1f
        if (value >= zeroAt) return 0f
        return (1f - (value - okAt) / (zeroAt - okAt)).coerceIn(0f, 1f)
    }

    private fun dist(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun parallelAngleErrorDeg(a0: Vec2, a1: Vec2, b0: Vec2, b1: Vec2): Float {
        val aa = Math.atan2((a1.y - a0.y).toDouble(), (a1.x - a0.x).toDouble())
        val bb = Math.atan2((b1.y - b0.y).toDouble(), (b1.x - b0.x).toDouble())
        var d = Math.abs(aa - bb)
        while (d > Math.PI) d -= Math.PI
        if (d > Math.PI * 0.5) d = Math.PI - d
        return Math.toDegrees(d).toFloat()
    }

    /**
     * gain 그리드 → 네이티브 해상도 알파 감쇠 PNG (M-FR-010).
     * 픽셀값 v = (1-gain)/maxAtt * 255  (0=보정 없음, 255=최대 감쇠), 그레이 PNG.
     */
    fun toAlphaPng(gain: FloatArray, gw: Int, gh: Int, outW: Int, outH: Int, maxAtt: Float): ByteArray {
        val pixels = IntArray(outW * outH)
        if (gw == outW && gh == outH) {
            for (i in pixels.indices) {
                val v = Math.round((1f - gain[i]) / maxAtt * 255f).coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        } else {
            val sx = gw / outW.toFloat()
            val sy = gh / outH.toFloat()
            val gridImg = GrayImage(gw, gh, gain)
            for (y in 0 until outH) {
                val gy = (y + 0.5f) * sy - 0.5f
                for (x in 0 until outW) {
                    val g = gridImg.bilinear((x + 0.5f) * sx - 0.5f, gy)
                    val v = Math.round((1f - g) / maxAtt * 255f).coerceIn(0, 255)
                    pixels[y * outW + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
        val bmp = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    /**
     * 채널별 gain 그리드 → RGB attenuation PNG.
     * R/G/B 픽셀값은 각 채널의 감쇠량이며, 대상 앱은 solid pattern 렌더링 때 채널별로 곱한다.
     */
    fun toRgbAttenuationPng(
        redGain: FloatArray,
        greenGain: FloatArray,
        blueGain: FloatArray,
        gw: Int,
        gh: Int,
        outW: Int,
        outH: Int,
        maxAtt: Float,
    ): ByteArray {
        require(redGain.size == greenGain.size && redGain.size == blueGain.size) {
            "RGB gain grid size mismatch"
        }
        val pixels = IntArray(outW * outH)
        if (gw == outW && gh == outH) {
            for (i in pixels.indices) {
                pixels[i] = (0xFF shl 24) or
                    (attenuationByte(redGain[i], maxAtt) shl 16) or
                    (attenuationByte(greenGain[i], maxAtt) shl 8) or
                    attenuationByte(blueGain[i], maxAtt)
            }
        } else {
            val sx = gw / outW.toFloat()
            val sy = gh / outH.toFloat()
            val rImg = GrayImage(gw, gh, redGain)
            val gImg = GrayImage(gw, gh, greenGain)
            val bImg = GrayImage(gw, gh, blueGain)
            for (y in 0 until outH) {
                val gy = (y + 0.5f) * sy - 0.5f
                for (x in 0 until outW) {
                    val gx = (x + 0.5f) * sx - 0.5f
                    pixels[y * outW + x] = (0xFF shl 24) or
                        (attenuationByte(rImg.bilinear(gx, gy), maxAtt) shl 16) or
                        (attenuationByte(gImg.bilinear(gx, gy), maxAtt) shl 8) or
                        attenuationByte(bImg.bilinear(gx, gy), maxAtt)
                }
            }
        }
        val bmp = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    private fun attenuationByte(gain: Float, maxAtt: Float): Int =
        Math.round((1f - gain) / maxAtt * 255f).coerceIn(0, 255)

    /**
     * 상대 편차 히트맵 PNG (평가 시각화, M-FR-017).
     * 파랑(-range) ~ 검정(0) ~ 빨강(+range), range 기본 ±5%.
     */
    fun deviationHeatmapPng(grid: FloatArray, gw: Int, gh: Int, range: Float = 0.05f): ByteArray {
        val st = stats(grid)
        val pixels = IntArray(gw * gh)
        for (i in grid.indices) {
            val d = (grid[i] / st.median - 1f) / range
            val r = (d.coerceIn(0f, 1f) * 255).toInt()
            val b = ((-d).coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or b
        }
        val bmp = Bitmap.createBitmap(pixels, gw, gh, Bitmap.Config.ARGB_8888)
        val scale = if (maxOf(gw, gh) <= 1024) 4 else 1
        val scaled = if (scale > 1) {
            Bitmap.createScaledBitmap(bmp, gw * scale, gh * scale, false).also { bmp.recycle() }
        } else {
            bmp
        }
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
        scaled.recycle()
        return bos.toByteArray()
    }
}
