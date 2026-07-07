package com.burnin.target.correction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.util.Base64
import com.burnin.target.util.AppLog
import org.json.JSONObject
import java.io.File

/**
 * 기존 번인 보정맵 위에 추가로 적용되는 전역/저주파 RGB 감쇠 레이어.
 * 화면을 밝게 올릴 수는 없으므로 기준 색좌표에 맞추기 위해 밝은 채널을 낮춘다.
 */
object WhiteBalanceStore {

    data class Meta(
        val width: Int,
        val height: Int,
        val maxAttenuation: Double,
        val checksumMd5: String,
        val createdAt: String,
        val sourceDevice: String,
        val redGain: Double,
        val greenGain: Double,
        val blueGain: Double,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("profileVersion", 1)
            .put("width", width)
            .put("height", height)
            .put("maxAttenuation", maxAttenuation)
            .put("checksumMd5", checksumMd5)
            .put("createdAt", createdAt)
            .put("sourceDevice", sourceDevice)
            .put("redGain", redGain)
            .put("greenGain", greenGain)
            .put("blueGain", blueGain)

        companion object {
            fun fromJson(o: JSONObject) = Meta(
                width = o.getInt("width"),
                height = o.getInt("height"),
                maxAttenuation = o.getDouble("maxAttenuation"),
                checksumMd5 = o.optString("checksumMd5", ""),
                createdAt = o.optString("createdAt", ""),
                sourceDevice = o.optString("sourceDevice", ""),
                redGain = o.optDouble("redGain", 1.0),
                greenGain = o.optDouble("greenGain", 1.0),
                blueGain = o.optDouble("blueGain", 1.0),
            )
        }
    }

    @Volatile var meta: Meta? = null
        private set
    @Volatile var rgbAttenuationBitmap: Bitmap? = null
        private set

    private fun dir(context: Context): File =
        File(context.filesDir, "profiles/white_balance").apply { mkdirs() }

    fun applyFromBase64(
        context: Context,
        width: Int,
        height: Int,
        maxAttenuation: Double,
        checksumMd5: String,
        dataBase64: String,
        sourceDevice: String,
        redGain: Double,
        greenGain: Double,
        blueGain: Double,
    ): String? {
        val png = try {
            Base64.decode(dataBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return "화이트밸런스 base64 디코드 실패"
        }
        if (checksumMd5.isNotEmpty() && !CorrectionStore.md5(png).equals(checksumMd5, ignoreCase = true)) {
            return "화이트밸런스 체크섬 불일치"
        }
        if (maxAttenuation !in 0.0..0.30) {
            return "화이트밸런스 maxAttenuation 범위 오류: $maxAttenuation"
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, opts)
        if (opts.outWidth != width || opts.outHeight != height) {
            return "화이트밸런스 PNG 크기(${opts.outWidth}x${opts.outHeight})가 메타데이터(${width}x${height})와 다름"
        }
        val screen: Point = CorrectionStore.realScreenSize(context)
        if (screen.x != width || screen.y != height) {
            return "화이트밸런스 해상도 불일치: 화면 ${screen.x}x${screen.y}, 맵 ${width}x${height}"
        }

        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?.copy(Bitmap.Config.ARGB_8888, false)
            ?: return "화이트밸런스 PNG 디코드 실패"
        val m = Meta(
            width = width,
            height = height,
            maxAttenuation = maxAttenuation,
            checksumMd5 = checksumMd5,
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
                .format(java.util.Date()),
            sourceDevice = sourceDevice,
            redGain = redGain,
            greenGain = greenGain,
            blueGain = blueGain,
        )

        val d = dir(context)
        File(d, "white_balance_rgb.png").writeBytes(png)
        File(d, "metadata.json").writeText(m.toJson().toString(2))

        rgbAttenuationBitmap?.recycle()
        rgbAttenuationBitmap = bmp
        meta = m
        AppLog.i(
            "화이트밸런스 적용 준비 완료: gain R ${fmt(redGain)}, " +
                "G ${fmt(greenGain)}, B ${fmt(blueGain)}"
        )
        return null
    }

    fun loadFromDisk(context: Context): Boolean {
        return try {
            val d = dir(context)
            val metaFile = File(d, "metadata.json")
            val pngFile = File(d, "white_balance_rgb.png")
            if (!metaFile.exists() || !pngFile.exists()) return false
            val m = Meta.fromJson(JSONObject(metaFile.readText()))
            val bmp = BitmapFactory.decodeFile(pngFile.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false)
                ?: return false
            rgbAttenuationBitmap?.recycle()
            rgbAttenuationBitmap = bmp
            meta = m
            AppLog.i("저장된 화이트밸런스 적재: R ${fmt(m.redGain)}, G ${fmt(m.greenGain)}, B ${fmt(m.blueGain)}")
            true
        } catch (e: Exception) {
            AppLog.i("화이트밸런스 적재 실패: ${e.message}")
            false
        }
    }

    fun clear(context: Context) {
        rgbAttenuationBitmap?.recycle()
        rgbAttenuationBitmap = null
        meta = null
        runCatching { dir(context).deleteRecursively() }
        AppLog.i("화이트밸런스 삭제")
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.4f", v)
}
