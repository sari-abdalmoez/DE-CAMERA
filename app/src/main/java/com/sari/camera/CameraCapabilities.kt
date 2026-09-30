package com.sari.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import android.util.Range
import android.util.Size

/** Immutable runtime capability snapshot. */
data class CameraCapabilities(
    val cameraId: String,
    val facing: Int,
    val orientation: Int,
    val hardwareLevel: Int,
    val supportsRaw: Boolean,
    val supportsManualExposure: Boolean,
    val supportsManualFocus: Boolean,
    val supportsManualWhiteBalance: Boolean,
    val supportsLongExposure: Boolean,
    val supportsBurst: Boolean,
    val supportsOis: Boolean,
    val supportsEis: Boolean,
    val supportsFlash: Boolean,
    val iso: Range<Int>?,
    val exposureNs: Range<Long>?,
    val fps: Array<Range<Int>>,
    val jpegSizes: Array<Size>,
    val rawSizes: Array<Size>,
    val yuvSizes: Array<Size>,
    val focalLengths: FloatArray,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val maxDigitalZoom: Float,
    val minFocusDistance: Float,
    val activeArray: android.graphics.Rect?
) {
    companion object {
        fun inspect(manager: CameraManager, id: String): CameraCapabilities {
            val c = manager.getCameraCharacteristics(id)
            val req = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpeg = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
            val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val manualSensor = req.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val manualPost = req.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
            val expMax = exp?.upper ?: 0L
            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            return CameraCapabilities(
                id,
                c.get(CameraCharacteristics.LENS_FACING) ?: -1,
                c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
                req.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) && raw.isNotEmpty(),
                manualSensor,
                manualSensor && c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { it > 0f } == true,
                manualPost,
                manualSensor && expMax >= 1_000_000_000L,
                fps.any { it.upper >= 30 },
                ois,
                eis,
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                sensor,
                exp,
                fps,
                jpeg,
                raw,
                yuv,
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(),
                sensorSize?.width ?: 0f,
                sensorSize?.height ?: 0f,
                c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            )
        }
    }
}
