package com.burnin.scanner.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 기반 측정 촬영 (M-FR-002/003/005의 MVP 구현).
 * - 후면 카메라, 지원 최대 스틸 해상도 JPEG 촬영
 * - 프리뷰로 AE를 수렴시킨 뒤 AE/AWB를 잠가 모든 패턴을 동일 노출로 촬영
 *   (풀 수동 ISO/셔터 제어는 후속 단계 — 잠금 방식은 LEGACY 기기에서도 대체로 동작)
 * - 프레임 평균화를 위해 같은 패턴을 여러 장 연속 촬영
 */
class CaptureController(context: Context, private val textureView: TextureView) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private lateinit var previewBuilder: CaptureRequest.Builder

    lateinit var cameraId: String
        private set
    lateinit var characteristics: CameraCharacteristics
        private set
    lateinit var jpegSize: Size
        private set

    var aeLocked = false
        private set

    fun hardwareLevelText(): String {
        val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val name = when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY(자동 위주)"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL(수동 지원)"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3(수동+RAW)"
            else -> "UNKNOWN"
        }
        val aeLockAvailable =
            characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        return "$name, AE잠금 ${if (aeLockAvailable) "가능" else "불가(정확도 저하)"}"
    }

    @SuppressLint("MissingPermission")
    suspend fun start() {
        val texture = awaitSurfaceTexture()

        cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        } ?: throw IllegalStateException("후면 카메라 없음")
        characteristics = manager.getCameraCharacteristics(cameraId)

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("스트림 설정 없음")
        jpegSize = map.getOutputSizes(ImageFormat.JPEG)
            .maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("JPEG 미지원")

        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1280 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: map.getOutputSizes(SurfaceTexture::class.java).first()
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val pSurface = Surface(texture)
        previewSurface = pSurface

        reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)

        device = openCamera()
        session = createSession(listOf(pSurface, reader!!.surface))

        previewBuilder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(pSurface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
        session!!.setRepeatingRequest(previewBuilder.build(), null, handler)
    }

    /** AE/AWB 잠금. 이후 모든 촬영이 같은 노출·화이트밸런스로 이루어진다. */
    fun lockAeAwb() {
        previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        previewBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        session?.setRepeatingRequest(previewBuilder.build(), null, handler)
        aeLocked = true
    }

    suspend fun captureFrames(count: Int, interFrameDelayMs: Long = 150): List<ByteArray> {
        val list = ArrayList<ByteArray>(count)
        repeat(count) {
            list += withTimeout(12_000) { captureJpeg() }
            delay(interFrameDelayMs)
        }
        return list
    }

    private suspend fun captureJpeg(): ByteArray = suspendCancellableCoroutine { cont ->
        val r = reader ?: return@suspendCancellableCoroutine cont.resumeWithException(
            IllegalStateException("카메라 미시작")
        )
        r.setOnImageAvailableListener({ rd ->
            val image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            image.close()
            rd.setOnImageAvailableListener(null, null)
            if (cont.isActive) cont.resume(bytes)
        }, handler)

        val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(r.surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            set(CaptureRequest.CONTROL_AWB_LOCK, aeLocked)
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            set(CaptureRequest.JPEG_QUALITY, 98.toByte())
        }
        session!!.capture(req.build(), null, handler)
    }

    private suspend fun awaitSurfaceTexture(): SurfaceTexture =
        suspendCancellableCoroutine { cont ->
            val existing = textureView.surfaceTexture
            if (textureView.isAvailable && existing != null) {
                cont.resume(existing)
                return@suspendCancellableCoroutine
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    textureView.surfaceTextureListener = null
                    if (cont.isActive) cont.resume(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("카메라 연결 끊김"))
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("카메라 오류 $error"))
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            device!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("세션 구성 실패"))
                }
            }, handler)
        }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { previewSurface?.release() }
        session = null
        device = null
        reader = null
        thread.quitSafely()
    }
}
