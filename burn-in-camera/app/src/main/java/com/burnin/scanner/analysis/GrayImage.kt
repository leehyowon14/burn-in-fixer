package com.burnin.scanner.analysis

import android.graphics.ImageFormat
import android.graphics.BitmapFactory
import com.burnin.scanner.camera.CaptureFrame

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

/** 선형 RGB(0~1) 이미지. RGB 단색 패턴은 해당 채널을 직접 분석한다. */
class RgbImage(
    val w: Int,
    val h: Int,
    val r: FloatArray,
    val g: FloatArray,
    val b: FloatArray,
) {
    fun bilinearChannel(fx: Float, fy: Float, channel: Int): Float {
        val data = when (channel) {
            0 -> r
            1 -> g
            2 -> b
            else -> throw IllegalArgumentException("channel must be 0, 1, or 2")
        }
        val x = fx.coerceIn(0f, w - 1.001f)
        val y = fy.coerceIn(0f, h - 1.001f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val dx = x - x0
        val dy = y - y0
        val i = y0 * w + x0
        val a = data[i]
        val bb = data[i + 1]
        val c = data[i + w]
        val d = data[i + w + 1]
        return (a * (1 - dx) + bb * dx) * (1 - dy) + (c * (1 - dx) + d * dx) * dy
    }
}

object ImageOps {

    /** sRGB 역감마(≈2.2) 룩업 테이블 */
    private val LINEAR_LUT = FloatArray(256) { v -> Math.pow(v / 255.0, 2.2).toFloat() }

    fun decodeLinearGray(frame: CaptureFrame, maxLongEdge: Int = 1600): GrayImage =
        when (frame.format) {
            ImageFormat.YUV_420_888 -> decodeYuvLinearGray(frame, maxLongEdge)
            ImageFormat.JPEG -> decodeLinearGray(frame.planes[0].bytes, maxLongEdge)
            else -> decodeLinearGray(frame.planes[0].bytes, maxLongEdge)
        }

    fun decodeLinearRgb(frame: CaptureFrame, maxLongEdge: Int = 1600): RgbImage =
        when (frame.format) {
            ImageFormat.YUV_420_888 -> decodeYuvLinearRgb(frame, maxLongEdge)
            ImageFormat.JPEG -> decodeLinearRgb(frame.planes[0].bytes, maxLongEdge)
            else -> decodeLinearRgb(frame.planes[0].bytes, maxLongEdge)
        }

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

    fun decodeLinearRgb(jpeg: ByteArray, maxLongEdge: Int = 1600): RgbImage {
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

        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            r[i] = LINEAR_LUT[(p shr 16) and 0xFF]
            g[i] = LINEAR_LUT[(p shr 8) and 0xFF]
            b[i] = LINEAR_LUT[p and 0xFF]
        }
        return RgbImage(w, h, r, g, b)
    }

    private fun decodeYuvLinearGray(frame: CaptureFrame, maxLongEdge: Int): GrayImage {
        require(frame.planes.isNotEmpty()) { "YUV plane 없음" }
        val sample = yuvSample(frame.width, frame.height, maxLongEdge)
        val outW = Math.max(1, frame.width / sample)
        val outH = Math.max(1, frame.height / sample)
        val yPlane = frame.planes[0]
        val out = FloatArray(outW * outH)
        var i = 0
        for (oy in 0 until outH) {
            val y = (oy * sample).coerceAtMost(frame.height - 1)
            for (ox in 0 until outW) {
                val x = (ox * sample).coerceAtMost(frame.width - 1)
                out[i++] = yPrimeToLinear(planeByte(yPlane, x, y))
            }
        }
        return GrayImage(outW, outH, out)
    }

    private fun decodeYuvLinearRgb(frame: CaptureFrame, maxLongEdge: Int): RgbImage {
        require(frame.planes.size >= 3) { "YUV plane 부족" }
        val sample = yuvSample(frame.width, frame.height, maxLongEdge)
        val outW = Math.max(1, frame.width / sample)
        val outH = Math.max(1, frame.height / sample)
        val yPlane = frame.planes[0]
        val uPlane = frame.planes[1]
        val vPlane = frame.planes[2]
        val r = FloatArray(outW * outH)
        val g = FloatArray(outW * outH)
        val b = FloatArray(outW * outH)
        var i = 0
        for (oy in 0 until outH) {
            val y = (oy * sample).coerceAtMost(frame.height - 1)
            val cy = y / 2
            for (ox in 0 until outW) {
                val x = (ox * sample).coerceAtMost(frame.width - 1)
                val cx = x / 2
                val yy = videoRangeY(planeByte(yPlane, x, y))
                val cb = (planeByte(uPlane, cx, cy) - 128) / 224f
                val cr = (planeByte(vPlane, cx, cy) - 128) / 224f
                val rp = (yy + 1.5748f * cr).coerceIn(0f, 1f)
                val gp = (yy - 0.1873f * cb - 0.4681f * cr).coerceIn(0f, 1f)
                val bp = (yy + 1.8556f * cb).coerceIn(0f, 1f)
                r[i] = Math.pow(rp.toDouble(), 2.2).toFloat()
                g[i] = Math.pow(gp.toDouble(), 2.2).toFloat()
                b[i] = Math.pow(bp.toDouble(), 2.2).toFloat()
                i++
            }
        }
        return RgbImage(outW, outH, r, g, b)
    }

    private fun yuvSample(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxLongEdge) sample *= 2
        return sample
    }

    private fun planeByte(plane: CaptureFrame.Plane, x: Int, y: Int): Int {
        val index = y * plane.rowStride + x * plane.pixelStride
        return plane.bytes[index.coerceIn(0, plane.bytes.size - 1)].toInt() and 0xFF
    }

    private fun yPrimeToLinear(y: Int): Float =
        Math.pow(videoRangeY(y).toDouble(), 2.2).toFloat()

    private fun videoRangeY(y: Int): Float =
        ((y - 16) / 219f).coerceIn(0f, 1f)

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

    fun averageRgb(frames: List<RgbImage>): RgbImage {
        require(frames.isNotEmpty())
        val w = frames[0].w
        val h = frames[0].h
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (f in frames) {
            require(f.w == w && f.h == h) { "프레임 크기 불일치" }
            for (i in r.indices) {
                r[i] += f.r[i]
                g[i] += f.g[i]
                b[i] += f.b[i]
            }
        }
        val n = frames.size.toFloat()
        for (i in r.indices) {
            r[i] /= n
            g[i] /= n
            b[i] /= n
        }
        return RgbImage(w, h, r, g, b)
    }
}
