package com.trill.trillviz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var outputImage: ImageView
    private lateinit var btnPickPhoto: Button
    private lateinit var btnStart: Button
    private lateinit var btnCapture: Button
    private lateinit var btnRecord: ToggleButton
    private lateinit var btnStartStream: Button
    private lateinit var btnSettings: Button
    private lateinit var tvStatus: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var sourcePhotoBitmap: Bitmap? = null
    private lateinit var frameProcessor: FrameProcessor
    private var mjpegServer: MJPEGServer? = null
    private var videoRecorder: VideoRecorder? = null

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let {
                val b = MediaStore.Images.Media.getBitmap(contentResolver, it)
                sourcePhotoBitmap = b
                Toast.makeText(this, "Source photo loaded", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        outputImage = findViewById(R.id.outputImage)
        btnPickPhoto = findViewById(R.id.btnPickPhoto)
        btnStart = findViewById(R.id.btnStart)
        btnCapture = findViewById(R.id.btnCapture)
        btnRecord = findViewById(R.id.btnRecord)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnSettings = findViewById(R.id.btnSettings)
        tvStatus = findViewById(R.id.tvStatus)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.INTERNET), 0)

        frameProcessor = FrameProcessor(this) { processedBitmap ->
            runOnUiThread {
                outputImage.setImageBitmap(processedBitmap)
                tvStatus.text = "Live"
            }
            mjpegServer?.latestFrame = processedBitmap
            videoRecorder?.pushFrame(processedBitmap)
        }

        btnPickPhoto.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.type = "image/*"
            pickPhotoLauncher.launch(intent)
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnStart.setOnClickListener {
            startCameraAndAnalysis()
        }

        btnCapture.setOnClickListener {
            // save last displayed image
            val bmp = (outputImage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null) {
                SaveUtils.saveBitmapToGallery(this, bmp, "TrillViz_capture_${System.currentTimeMillis()}.jpg")
                Toast.makeText(this, "Saved image", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No processed frame available yet", Toast.LENGTH_SHORT).show()
            }
        }

        btnRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // start recording
                val outFileName = "TrillViz_${System.currentTimeMillis()}.mp4"
                videoRecorder = VideoRecorder(this)
                videoRecorder?.startRecording(outFileName)
                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
            } else {
                // stop recording
                uiScope.launch {
                    videoRecorder?.stopRecording()
                    Toast.makeText(this@MainActivity, "Recording saved", Toast.LENGTH_SHORT).show()
                    videoRecorder = null
                }
            }
        }

        btnStartStream.setOnClickListener {
            if (mjpegServer == null) {
                mjpegServer = MJPEGServer(8080)
                mjpegServer?.start()
                Toast.makeText(this, "MJPEG stream started on port 8080", Toast.LENGTH_SHORT).show()
            } else {
                mjpegServer?.stop()
                mjpegServer = null
                Toast.makeText(this, "MJPEG stream stopped", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCameraAndAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().setTargetResolution(android.util.Size(1280, 720)).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(android.util.Size(640, 480))
                .build()

            analysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                try {
                    val bmp = imageProxy.toBitmapRGBA()
                    val src = sourcePhotoBitmap
                    if (src != null) {
                        frameProcessor.queueFrame(bmp, src)
                    } else {
                        // If no source is selected, still show camera preview
                        runOnUiThread {
                            outputImage.setImageBitmap(bmp)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    imageProxy.close()
                }
            })

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        frameProcessor.close()
        cameraExecutor.shutdown()
        mjpegServer?.stop()
        videoRecorder?.release()
        uiScope.cancel()
    }
}