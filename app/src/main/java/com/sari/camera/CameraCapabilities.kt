package com.sari.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

data class CameraCapabilities(
    val cameraId: String,
    val raw: Boolean,
    val yuv: Boolean,
    val jpeg: Boolean,
    val heif: Boolean,
    val hardwareLevel: Int,
    val focalLengths: FloatArray,
    val activeArray: android.graphics.Rect?,
    val sensorSize: android.util.SizeF?,
    val ois: Boolean,
    val eis: Boolean,
    val isoRange: android.util.Range<Int>?,
    val exposureRange: android.util.Range<Long>?,
    val focusModes: IntArray,
    val outputSizes: List<Size>
)

object CapabilityDetector {
    fun detect(manager: CameraManager): List<CameraCapabilities> = manager.cameraIdList.mapNotNull { id ->
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@mapNotNull null
        val outputs = (map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: emptyArray()).toList()
        val formats = map.outputFormats?.toSet() ?: emptySet()
        val physical = c.get(CameraCharacteristics.LENS_FACING)
        if (physical == null) return@mapNotNull null
        CameraCapabilities(id,
            android.graphics.ImageFormat.RAW_SENSOR in formats,
            android.graphics.ImageFormat.YUV_420_888 in formats,
            android.graphics.ImageFormat.JPEG in formats,
            android.graphics.ImageFormat.HEIC in formats,
            c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(),
            c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()).contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON),
            (c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()).contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf(), outputs
        )
    }
}
