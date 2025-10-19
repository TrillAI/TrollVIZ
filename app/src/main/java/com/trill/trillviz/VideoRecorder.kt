package com.trill.trillviz

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Lightweight MP4 recorder: uses MediaCodec encoder with input Surface is not used here,
 * instead we encode raw frames by converting ARGB -> NV21/I420 then feed to encoder.
 *
 * This is a simplistic implementation: for production use choose an EGL-based path to render
 * bitmaps to encoder input surface (GPU path) or use native libs (libyuv) for speed.
 *
 * This implementation queues bitmaps and encodes them on a worker thread.
 */
class VideoRecorder(private val ctx: Context) {
    private val TAG = "VideoRecorder"
    private val queue = LinkedBlockingQueue<Bitmap>(10)
    private var encoderThread: Thread? = null
    private var running = false

    // encoder objects
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false

    fun startRecording(outputFileName: String, width: Int = 640, height: Int = 480, bitrate: Int = 2_000_000, fps: Int = 20) {
        try {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            codec = MediaCodec.createEncoderByType(mime)
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()

            // Prepare output file via MediaStore
            val outFile = createFileInMovies(ctx, outputFileName)
            val fd = ctx.contentResolver.openFileDescriptor(outFile, "rw")?.fileDescriptor
            // MediaMuxer cannot accept file descriptor reliably on all devices; use temp file fallback
            val tmpFile = File(ctx.cacheDir, outputFileName)
            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            running = true
            encoderThread = thread {
                encodeLoop(width, height, fps)
                // finalize muxer
                try {
                    muxer?.stop()
                    muxer?.release()
                    // move tmp file to final location
                    tmpFile.inputStream().use { input ->
                        ctx.contentResolver.openOutputStream(outFile)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    tmpFile.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "muxer finalize error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}")
        }
    }

    fun pushFrame(bmp: Bitmap) {
        // Offer, drop oldest if full to keep responsiveness
        if (!queue.offer(bmp)) {
            queue.poll()
            queue.offer(bmp)
        }
    }

    fun stopRecording() {
        running = false
        try {
            encoderThread?.join(2000)
        } catch (e: InterruptedException) { /* ignore */ }
        encoderThread = null
        codec?.stop()
        codec?.release()
        codec = null
        muxer?.release()
        muxer = null
    }

    fun release() {
        running = false
    }

    private fun encodeLoop(width: Int, height: Int, fps: Int) {
        val localCodec = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var frameCount = 0L
        while (running) {
            val bmp = queue.poll() ?: run {
                Thread.sleep(10)
                continue
            }
            // Resize to encoder size
            val resized = Bitmap.createScaledBitmap(bmp, width, height, true)
            // Convert to NV21 (or I420) byte array - very naive and slow; replace with libyuv for speed
            val yuv = YuvUtils.bitmapToI420(resized)
            // Get input buffers and queue to codec via ByteBuffer if supported
            // Here we use COLOR_FormatYUV420Flexible: many devices accept raw YUV via ByteBuffer input only in special modes.
            // For broad compatibility you must render to encoder input surface (via EGL). This is a placeholder and may fail on some devices.
            val inputBufIndex = localCodec.dequeueInputBuffer(10000)
            if (inputBufIndex >= 0) {
                val inputBuffer: ByteBuffer? = localCodec.getInputBuffer(inputBufIndex)
                inputBuffer?.clear()
                inputBuffer?.put(yuv)
                val pts = computePresentationTimeUs(frameCount, fps)
                localCodec.queueInputBuffer(inputBufIndex, 0, yuv.size, pts, 0)
                frameCount++
            }

            // Drain output
            var outIndex = localCodec.dequeueOutputBuffer(bufferInfo, 0)
            while (outIndex >= 0) {
                val encoded = localCodec.getOutputBuffer(outIndex)
                if (bufferInfo.size != 0 && encoded != null) {
                    encoded.position(bufferInfo.offset)
                    encoded.limit(bufferInfo.offset + bufferInfo.size)
                    if (!muxerStarted) {
                        // get output format and start muxer
                        val outFormat = localCodec.outputFormat
                        try {
                            trackIndex = muxer?.addTrack(outFormat) ?: -1
                            muxer?.start()
                            muxerStarted = true
                        } catch (e: IllegalStateException) {
                            e.printStackTrace()
                        }
                    }
                    if (muxerStarted) {
                        muxer?.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                }
                localCodec.releaseOutputBuffer(outIndex, false)
                outIndex = localCodec.dequeueOutputBuffer(bufferInfo, 0)
            }
        }
    }

    private fun computePresentationTimeUs(frameIndex: Long, fps: Int): Long {
        return frameIndex * 1_000_000L / fps
    }

    private fun createFileInMovies(ctx: Context, displayName: String): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/TrillViz")
            }
        }
        return ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
    }
}