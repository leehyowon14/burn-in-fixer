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
}
