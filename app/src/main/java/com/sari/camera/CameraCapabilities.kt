package com.sari.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

 data class CameraCapabilities(
    val cameraId: String,
    val facing: Int,
    val raw: Boolean,
    val yuv: Boolean,
    val jpeg: Boolean,
    val heif: Boolean,
    val hardwareLevel: Int,
    val focalLengths: FloatArray,
    val activeArray: Rect?,
    val sensorSize: android.util.SizeF?,
    val ois: Boolean,
    val eis: Boolean,
    val flash: Boolean,
    val isoRange: android.util.Range<Int>?,
    val exposureRange: android.util.Range<Long>?,
    val focusModes: IntArray,
    val outputSizes: List<Size>,
    val maxDigitalZoom: Float
)

object CapabilityDetector {
    fun detect(manager: CameraManager): List<CameraCapabilities> = manager.cameraIdList.mapNotNull { id ->
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@mapNotNull null
        val outputs = (map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: emptyArray()).toList()
        val formats = map.outputFormats?.toSet() ?: emptySet()
        val facing = c.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
        CameraCapabilities(
            cameraId = id,
            facing = facing,
            raw = android.graphics.ImageFormat.RAW_SENSOR in formats,
            yuv = android.graphics.ImageFormat.YUV_420_888 in formats,
            jpeg = android.graphics.ImageFormat.JPEG in formats,
            heif = android.graphics.ImageFormat.HEIC in formats,
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(),
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            ois = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf())
                .contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON),
            eis = (c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf())
                .contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            focusModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf(),
            outputSizes = outputs,
            maxDigitalZoom = (c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
        )
    }
}
