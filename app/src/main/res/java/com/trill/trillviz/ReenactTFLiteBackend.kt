package com.trill.trillviz

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * ReenactTFLiteBackend:
 * - Loads a TFLite reenactment model from assets/models/renderer.tflite by default.
 * - Expects an image-to-image model with single input (live driver) or two inputs (live + source).
 * - For generality, this implementation assumes single-input model that takes the live frame and returns an output image,
 *   but you can adapt to multiple input tensors (live+source+landmarks) using runForMultipleInputsOutputs.
 *
 * IMPORTANT: to get realistic reenactment you need a model trained specifically for this purpose.
 */
class ReenactTFLiteBackend(private val ctx: Context) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val defaultModelPath = "models/renderer.tflite"

    fun init() {
        try {
            val modelBuffer = loadModelFileFromAssets(ctx, defaultModelPath)
            // try GPU delegate
            try {
                gpuDelegate = GpuDelegate()
                val options = Interpreter.Options().addDelegate(gpuDelegate).setNumThreads(4)
                interpreter = Interpreter(modelBuffer, options)
            } catch (e: Exception) {
                gpuDelegate?.close()
                gpuDelegate = null
                val options = Interpreter.Options().setNumThreads(4)
                interpreter = Interpreter(modelBuffer, options)
            }
            Log.i("ReenactTFLite", "Model loaded")
        } catch (e: Exception) {
            Log.w("ReenactTFLite", "Failed to load model: ${e.message}")
            interpreter = null
        }
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
        } catch (e: Exception) { /* ignore */ }
    }

    suspend fun process(liveBmp: Bitmap, sourceBmp: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val interp = interpreter ?: return@withContext null
            // Get input/output shapes
            val inputShape = interp.getInputTensor(0).shape() // [1,H,W,3]
            val modelH = inputShape[1]
            val modelW = inputShape[2]
            val modelC = inputShape[3]

            // Resize live (driver) to model input
            val liveIn = Bitmap.createScaledBitmap(liveBmp, modelW, modelH, true)
            val inputBuffer = convertBitmapToFloatBuffer(liveIn, modelH, modelW, modelC)

            // Prepare output buffer
            val outputShape = interp.getOutputTensor(0).shape()
            val outH = outputShape[1]; val outW = outputShape[2]; val outC = outputShape[3]
            val outputBuffer = Array(1) { Array(outH) { Array(outW) { FloatArray(outC) } } }

            // Run inference
            interp.run(inputBuffer, outputBuffer)

            // Convert output to Bitmap (assuming [-1,1] range)
            val outBmp = floatArrayToBitmap(outputBuffer, outW, outH, outC)
            // Optionally blend/mask over live frame to preserve background
            return@withContext outBmp
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    // --- helpers: loading & conversion (very similar to prior TFLiteBackend)
    private fun loadModelFileFromAssets(ctx: Context, assetPath: String): ByteBuffer {
        val am = ctx.assets
        val fd = am.openFd(assetPath)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        val start = fd.startOffset
        val len = fd.declaredLength
        val mapped = channel.map(FileChannel.MapMode.READ_ONLY, start, len)
        mapped.order(ByteOrder.nativeOrder())
        return mapped
    }

    private fun convertBitmapToFloatBuffer(bitmap: Bitmap, height: Int, width: Int, channels: Int): ByteBuffer {
        val floatSize = 4
        val buffer = ByteBuffer.allocateDirect(1 * height * width * channels * floatSize)
        buffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(height * width)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)
        var offset = 0
        // Normalize to [-1,1]
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = intValues[offset++]
                val r = ((v shr 16) and 0xFF).toFloat()
                val g = ((v shr 8) and 0xFF).toFloat()
                val b = (v and 0xFF).toFloat()
                buffer.putFloat((r / 127.5f) - 1.0f)
                buffer.putFloat((g / 127.5f) - 1.0f)
                buffer.putFloat((b / 127.5f) - 1.0f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun floatArrayToBitmap(outputBuffer: Any, width: Int, height: Int, channels: Int): Bitmap {
        val outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val px = IntArray(width * height)
        val arr = outputBuffer as Array<Array<Array<FloatArray>>>
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val f = arr[0][y][x]
                val r = ((clamp((f[0] + 1f) * 0.5f, 0f, 1f) * 255f)).toInt()
                val g = ((clamp((f[1] + 1f) * 0.5f, 0f, 1f) * 255f)).toInt()
                val b = ((clamp((f[2] + 1f) * 0.5f, 0f, 1f) * 255f)).toInt()
                px[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        outBmp.setPixels(px, 0, width, 0, 0, width, height)
        return outBmp
    }

    private fun clamp(v: Float, a: Float, b: Float): Float {
        if (v < a) return a
        if (v > b) return b
        return v
    }
}