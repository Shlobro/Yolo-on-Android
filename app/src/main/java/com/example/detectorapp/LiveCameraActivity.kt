package com.example.detectorapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Enhanced Live Camera Activity with clear detection feedback and proper permissions
 */
class LiveCameraActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: GraphicOverlay
    private lateinit var statusText: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var frameCount = 0
    private var detectionCount = 0

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            statusText.text = "✅ Camera permission granted"
            statusText.setTextColor(Color.GREEN)
            initializeModel()
        } else {
            statusText.text = "❌ Camera permission denied. Please grant camera permission to use this app."
            statusText.setTextColor(Color.RED)
            Toast.makeText(this, "Camera permission is required for this app", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_camera)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)

        // Check and request camera permission
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                statusText.text = "✅ Camera permission available"
                statusText.setTextColor(Color.GREEN)
                initializeModel()
            }
            else -> {
                // Request permission
                statusText.text = "🔒 Requesting camera permission..."
                statusText.setTextColor(Color.YELLOW)
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun initializeModel() {
        try {
            YoloModel.init(this)
            statusText.text = "✅ Model loaded successfully"
            statusText.setTextColor(Color.GREEN)
            Log.i("LiveCamera", "YoloModel initialized successfully")
            startCamera()
        } catch (e: Exception) {
            statusText.text = "❌ Failed to load model: ${e.message}"
            statusText.setTextColor(Color.RED)
            Log.e("LiveCamera", "Failed to initialize YoloModel", e)
        }
    }

    /** Sets up Preview + ImageAnalysis use-cases */
    private fun startCamera() {
        statusText.text = "📸 Starting camera..."
        statusText.setTextColor(Color.BLUE)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ::processImage) }

                // Select back camera as default
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, analysis
                )

                runOnUiThread {
                    statusText.text = "📸 Camera ready - Looking for detections..."
                    statusText.setTextColor(Color.BLUE)
                }

                Log.i("LiveCamera", "Camera started successfully")

            } catch (exc: Exception) {
                Log.e("LiveCamera", "Camera startup failed", exc)
                runOnUiThread {
                    statusText.text = "❌ Camera startup failed: ${exc.message}"
                    statusText.setTextColor(Color.RED)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Called for every camera frame that survives back-pressure */
    private fun processImage(image: ImageProxy) {
        frameCount++

        val bitmap = image.toBitmap() ?: run {
            image.close()
            return
        }

        try {
            val detections = YoloModel.detect(bitmap)

            if (detections.isNotEmpty()) {
                detectionCount++
            }

            // Update UI on main thread
            runOnUiThread {
                updateDetectionDisplay(detections)
            }

        } catch (e: Exception) {
            Log.e("LiveCamera", "Detection error", e)
            runOnUiThread {
                statusText.text = "❌ Detection error: ${e.message}"
                statusText.setTextColor(Color.RED)
            }
        }

        image.close()
    }

    private fun updateDetectionDisplay(detections: List<Detection>) {
        // Translate model coordinates → PreviewView coordinates
        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()
        val scaleX = viewW / YoloModel.inputWidth
        val scaleY = viewH / YoloModel.inputHeight

        overlay.clear()

        if (detections.isEmpty()) {
            // Show "no detections" status
            statusText.text = "🔍 Scanning... (Frame: $frameCount, Found: $detectionCount)"
            statusText.setTextColor(Color.YELLOW)
        } else {
            // Show detections found
            statusText.text = "🎯 Found ${detections.size} detection(s)! (Total found: $detectionCount)"
            statusText.setTextColor(Color.GREEN)

            for (d in detections) {
                overlay.addBox(
                    d.x1 * scaleX, d.y1 * scaleY,
                    d.x2 * scaleX, d.y2 * scaleY,
                    d.label, d.confidence
                )
                Log.i("Detection", "Found: ${d.label} (${(d.confidence * 100).toInt()}%)")
            }
        }

        overlay.invalidate()
    }

    /* ---------- tiny helper to convert CameraX YUV → Bitmap ---------- */
    private fun ImageProxy.toBitmap(): Bitmap? {
        val nv21 = planes[0].buffer.toByteArray() +
                planes[2].buffer.toByteArray() +
                planes[1].buffer.toByteArray()

        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }
    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind(); val data = ByteArray(remaining()); get(data); return data
    }
}

/* ───────────────────────── GraphicOverlay & simple RectGraphic ───────────────────────── */

class GraphicOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Simple holder for a detection box + label */
    private data class Box(
        val left: Float, val top: Float,
        val right: Float, val bottom: Float,
        val label: String, val score: Float
    )

    private val boxes = mutableListOf<Box>()

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 36f
        style = Paint.Style.FILL
    }

    fun addBox(l: Float, t: Float, r: Float, b: Float, label: String, score: Float) {
        boxes += Box(l, t, r, b, label, score)
    }
    fun clear() = boxes.clear()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (b in boxes) {
            canvas.drawRect(b.left, b.top, b.right, b.bottom, boxPaint)
            canvas.drawText("${b.label} %.2f".format(b.score), b.left, b.top - 8f, textPaint)
        }
    }
}
