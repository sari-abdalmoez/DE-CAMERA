package com.sari.camera

object NativeBridge {
    init { System.loadLibrary("sari_camera") }

    external fun detectStars(gray: ByteArray, width: Int, height: Int, threshold: Float): FloatArray
    external fun alignAndStack(frames: Array<ByteArray>, width: Int, height: Int, sigma: Float): ByteArray
    external fun enhance(gray: ByteArray, width: Int, height: Int, profile: Int): ByteArray
}
