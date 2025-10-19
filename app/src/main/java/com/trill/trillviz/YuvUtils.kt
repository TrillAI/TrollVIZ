package com.trill.trillviz

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Naive bitmap -> I420 converter (CPU). Very slow for high-res frames.
 * For production, replace with libyuv JNI or render to encoder input Surface (EGL) for GPU path.
 */
object YuvUtils {
    fun bitmapToI420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val frameSize = width * height
        val qFrame = frameSize / 4
        val out = ByteArray(frameSize + 2 * qFrame)

        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + qFrame

        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val rgb = argb[index++]
                val r = (rgb shr 16) and 0xff
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                out[yIndex++] = clampByte(y)
                if ((j % 2 == 0) && (i % 2 == 0)) {
                    out[uIndex++] = clampByte(u)
                    out[vIndex++] = clampByte(v)
                }
            }
        }
        return out
    }

    private fun clampByte(v: Int): Byte {
        val vv = if (v < 0) 0 else if (v > 255) 255 else v
        return vv.toByte()
    }
}