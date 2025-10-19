package com.trill.trillviz

import android.content.Context
import android.graphics.Bitmap

/**
 * Backend manager provides mlkit, tflite, remote and hybrid options.
 */
class BackendManager private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var instance: BackendManager? = null
        fun get(ctx: Context): BackendManager {
            return instance ?: synchronized(this) {
                instance ?: BackendManager(ctx.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs by lazy { ctx.getSharedPreferences("trillviz_prefs", Context.MODE_PRIVATE) }
    private val mlkit = MLKitBackend(ctx)
    private val tflite = ReenactTFLiteBackend(ctx)
    private val remote by lazy { RemoteApiBackend(ctx, prefs.getString("api_url", "") ?: "") }

    init {
        mlkit.init()
        tflite.init()
    }

    suspend fun process(live: Bitmap, source: Bitmap): Bitmap? {
        val backendName = prefs.getString("backend", "MLKit (on-device)") ?: "MLKit (on-device)"
        return when {
            backendName.contains("TFLite", ignoreCase = true) -> tflite.process(live, source)
            backendName.contains("Remote", ignoreCase = true) -> remote.process(live, source)
            backendName.contains("Hybrid", ignoreCase = true) -> {
                val out = tflite.process(live, source)
                out ?: remote.process(live, source)
            }
            else -> mlkit.process(live, source)
        }
    }

    fun close() {
        mlkit.close()
        tflite.close()
    }
}