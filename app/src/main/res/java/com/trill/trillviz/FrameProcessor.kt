package com.trill.trillviz

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FrameProcessor coordinates backend selection and queues frames for processing.
 * Uses busy flag to drop frames when overloaded.
 */
class FrameProcessor(private val ctx: Context, private val onResult: (Bitmap) -> Unit) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val busy = AtomicBoolean(false)
    private val backend = BackendManager.get(ctx)

    fun queueFrame(liveBmp: Bitmap, sourceBmp: Bitmap) {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val result = backend.process(liveBmp, sourceBmp)
                if (result != null) onResult(result)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // throttle slightly to avoid CPU saturation
                delay(16)
                busy.set(false)
            }
        }
    }

    fun close() {
        scope.cancel()
        backend.close()
    }
}