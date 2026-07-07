package com.burnin.scanner.analysis

import android.graphics.BitmapFactory

/** 선형 휘도(0~1) 그레이스케일 이미지. */
class GrayImage(val w: Int, val h: Int, val data: FloatArray) {

    operator fun get(x: Int, y: Int): Float = data[y * w + x]

    /** 이중선형 보간 샘플 (범위 밖은 가장자리 클램프) */
    fun bilinear(fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, w - 1.001f)
        val y = fy.coerceIn(0f, h - 1.001f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val dx = x - x0
        val dy = y - y0
        val i = y0 * w + x0
        val a = data[i]
        val b = data[i + 1]
        val c = data[i + w]
        val d = data[i + w + 1]
        return (a * (1 - dx) + b * dx) * (1 - dy) + (c * (1 - dx) + d * dx) * dy
    }
}

object ImageOps {

    /** sRGB 역감마(≈2.2) 룩업 테이블 */
    private val LINEAR_LUT = FloatArray(256) { v -> Math.pow(v / 255.0, 2.2).toFloat() }

    /**
     * JPEG 바이트 → 선형 휘도 GrayImage.
     * 메모리 절약을 위해 긴 변이 maxLongEdge 이하가 되도록 2^n 다운샘플 디코드한다.
     * (분석 그리드가 저주파이므로 충분한 해상도)
     */
    fun decodeLinearGray(jpeg: ByteArray, maxLongEdge: Int = 1600): GrayImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongEdge) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: throw IllegalStateException("JPEG 디코드 실패")
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        val out = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = LINEAR_LUT[(p shr 16) and 0xFF]
            val g = LINEAR_LUT[(p shr 8) and 0xFF]
            val b = LINEAR_LUT[p and 0xFF]
            out[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        return GrayImage(w, h, out)
    }

    /** 프레임 평균화 (M-FR-005). 모든 프레임은 같은 크기여야 한다. */
    fun average(frames: List<GrayImage>): GrayImage {
        require(frames.isNotEmpty())
        val w = frames[0].w
        val h = frames[0].h
        val acc = FloatArray(w * h)
        for (f in frames) {
            require(f.w == w && f.h == h) { "프레임 크기 불일치" }
            for (i in acc.indices) acc[i] += f.data[i]
        }
        val n = frames.size.toFloat()
        for (i in acc.indices) acc[i] /= n
        return GrayImage(w, h, acc)
    }
}
