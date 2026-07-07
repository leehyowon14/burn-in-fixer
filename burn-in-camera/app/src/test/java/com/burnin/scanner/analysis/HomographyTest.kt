package com.burnin.scanner.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomographyTest {

    /** 알려진 원근 변환을 4점으로 복원해 내부 점 사상이 일치하는지 확인. */
    @Test
    fun solvesPerspectiveTransform() {
        val t = doubleArrayOf(1.2, 0.1, 30.0, 0.05, 1.1, 20.0, 2e-4, 1e-4, 1.0)
        fun trueMap(u: Double, v: Double): DoubleArray {
            val w = t[6] * u + t[7] * v + t[8]
            return doubleArrayOf(
                (t[0] * u + t[1] * v + t[2]) / w,
                (t[3] * u + t[4] * v + t[5]) / w,
            )
        }

        val src = arrayOf(Vec2(0f, 0f), Vec2(200f, 0f), Vec2(200f, 100f), Vec2(0f, 100f))
        val dst = src.map {
            val p = trueMap(it.x.toDouble(), it.y.toDouble())
            Vec2(p[0].toFloat(), p[1].toFloat())
        }.toTypedArray()

        val h = Homography.from4Points(src, dst)
        assertNotNull(h)

        val out = DoubleArray(2)
        var maxErr = 0.0
        for (u in 0..200 step 20) {
            for (v in 0..100 step 20) {
                h!!.map(u.toDouble(), v.toDouble(), out)
                val expect = trueMap(u.toDouble(), v.toDouble())
                maxErr = maxOf(
                    maxErr,
                    Math.abs(out[0] - expect[0]),
                    Math.abs(out[1] - expect[1]),
                )
            }
        }
        assertTrue("최대 사상 오차 $maxErr px (허용 0.02)", maxErr < 0.02)
    }

    /** 축 정렬 직사각형 대응(정합의 기본 경우) 확인. */
    @Test
    fun mapsRectangleExactly() {
        val src = arrayOf(Vec2(0f, 0f), Vec2(640f, 0f), Vec2(640f, 400f), Vec2(0f, 400f))
        val dst = arrayOf(Vec2(50f, 40f), Vec2(450f, 40f), Vec2(450f, 340f), Vec2(50f, 340f))
        val h = Homography.from4Points(src, dst)!!
        val out = DoubleArray(2)
        h.map(320.0, 200.0, out)
        assertTrue(Math.abs(out[0] - 250.0) < 1e-6)
        assertTrue(Math.abs(out[1] - 190.0) < 1e-6)
    }

    @Test
    fun solvesLeastSquaresFromManyPointPairs() {
        val src = ArrayList<Vec2>()
        val dst = ArrayList<Vec2>()
        for (y in 0..100 step 25) {
            for (x in 0..200 step 25) {
                src += Vec2(x.toFloat(), y.toFloat())
                dst += Vec2((30 + x * 1.3f + y * 0.08f), (20 + x * 0.04f + y * 1.1f))
            }
        }

        val h = Homography.fromPointPairs(src, dst)
        assertNotNull(h)
        val out = DoubleArray(2)
        h!!.map(125.0, 75.0, out)

        assertTrue(Math.abs(out[0] - (30 + 125 * 1.3 + 75 * 0.08)) < 0.05)
        assertTrue(Math.abs(out[1] - (20 + 125 * 0.04 + 75 * 1.1)) < 0.05)
    }

    @Test
    fun markerDisambiguatesVerticalFlip() {
        val screenW = 200
        val screenH = 300
        val targetMarker = renderMarker(screenW, screenH)
        val flipped = FloatArray(targetMarker.data.size)
        for (y in 0 until screenH) {
            for (x in 0 until screenW) {
                flipped[(screenH - 1 - y) * screenW + x] = targetMarker.data[y * screenW + x]
            }
        }
        val quad = fullFrameQuad(screenW, screenH)
        val oriented = Analyzer.buildHomographyWithMarker(
            quad,
            GrayImage(screenW, screenH, flipped),
            screenW,
            screenH,
        )

        assertNotNull(oriented)
        assertTrue(oriented!!.mapping.label.contains("image BL"))
        assertTrue(oriented.confidence > 0.5f)

        val out = DoubleArray(2)
        oriented.homography.map(0.0, 0.0, out)
        assertTrue("target TL should map near image bottom, got y=${out[1]}", out[1] > screenH * 0.90)
    }

    private fun fullFrameQuad(w: Int, h: Int): ScreenDetector.Quad =
        ScreenDetector.Quad(
            arrayOf(
                Vec2(0f, 0f),
                Vec2((w - 1).toFloat(), 0f),
                Vec2((w - 1).toFloat(), (h - 1).toFloat()),
                Vec2(0f, (h - 1).toFloat()),
            ),
        )

    private fun renderMarker(w: Int, h: Int): GrayImage {
        val data = FloatArray(w * h) { 0.002f }
        val m = minOf(w, h).toFloat()
        val size = m * 0.10f
        val inset = m * 0.04f
        drawRect(data, w, h, inset, inset, inset + size, inset + size, 0.6f)
        drawRect(data, w, h, w - inset - size, inset, w - inset, inset + size, 0.6f)
        drawRect(data, w, h, w - inset - size, h - inset - size, w - inset, h - inset, 0.6f)
        drawRect(data, w, h, inset, h - inset - size, inset + size, h - inset, 0.6f)

        val q = size * 0.4f
        drawRect(
            data,
            w,
            h,
            inset + q,
            inset + q,
            inset + size - q * 0.5f,
            inset + size - q * 0.5f,
            0.002f,
        )
        return GrayImage(w, h, data)
    }

    private fun drawRect(
        data: FloatArray,
        w: Int,
        h: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        value: Float,
    ) {
        val x0 = Math.floor(left.toDouble()).toInt().coerceIn(0, w)
        val y0 = Math.floor(top.toDouble()).toInt().coerceIn(0, h)
        val x1 = Math.ceil(right.toDouble()).toInt().coerceIn(0, w)
        val y1 = Math.ceil(bottom.toDouble()).toInt().coerceIn(0, h)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                data[y * w + x] = value
            }
        }
    }
}
