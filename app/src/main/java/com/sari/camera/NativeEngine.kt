package com.sari.camera

import java.nio.ByteBuffer

object NativeEngine {
    init { System.loadLibrary("sari_camera") }
    external fun nativeVersion(): String
    external fun estimateBlur(yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int): Float
    external fun processTileRGBA(input: ByteBuffer, output: ByteBuffer, width: Int, height: Int, strength: Float): Int
    external fun alignAndStackRGBA(frames: Array<ByteBuffer>, output: ByteBuffer, width: Int, height: Int, sigma: Float): Int
    external fun detectStars(gray: ByteBuffer, width: Int, height: Int, stride: Int, thresholdSigma: Float): Int
}
