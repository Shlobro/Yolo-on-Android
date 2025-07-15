package com.example.detectorapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.InputStream

/**
 * Activity that receives images from other apps and processes them for detection
 */
class ImageProcessingActivity : ComponentActivity() {

    private lateinit var imageView: ImageView
    private lateinit var resultsText: TextView
    private lateinit var overlay: GraphicOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_processing)

        imageView = findViewById(R.id.imageView)
        resultsText = findViewById(R.id.resultsText)
        overlay = findViewById(R.id.overlay)

        // Initialize the model
        try {
            YoloModel.init(this)
            resultsText.text = "✅ Model loaded successfully"
            resultsText.setTextColor(Color.GREEN)
        } catch (e: Exception) {
            resultsText.text = "❌ Failed to load model: ${e.message}"
            resultsText.setTextColor(Color.RED)
            Log.e("ImageProcessing", "Failed to initialize YoloModel", e)
            return
        }

        // Handle the incoming intent
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun handleIncomingIntent(intent: Intent) {
        when {
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true -> {
                handleSingleImage(intent)
            }
            intent.action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("image/") == true -> {
                handleMultipleImages(intent)
            }
            else -> {
                resultsText.text = "❌ No image received"
                resultsText.setTextColor(Color.RED)
            }
        }
    }

    private fun handleSingleImage(intent: Intent) {
        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (imageUri != null) {
            processImage(imageUri)
        } else {
            resultsText.text = "❌ No image URI found"
            resultsText.setTextColor(Color.RED)
        }
    }

    private fun handleMultipleImages(intent: Intent) {
        val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (!imageUris.isNullOrEmpty()) {
            // Process the first image for now
            processImage(imageUris[0])
            if (imageUris.size > 1) {
                Toast.makeText(this, "Processing first of ${imageUris.size} images", Toast.LENGTH_SHORT).show()
            }
        } else {
            resultsText.text = "❌ No image URIs found"
            resultsText.setTextColor(Color.RED)
        }
    }

    private fun processImage(imageUri: Uri) {
        try {
            // Load the bitmap from URI
            val inputStream: InputStream? = contentResolver.openInputStream(imageUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                resultsText.text = "❌ Failed to load image"
                resultsText.setTextColor(Color.RED)
                return
            }

            // Display the image
            imageView.setImageBitmap(bitmap)

            // Process with YOLO model
            resultsText.text = "🔍 Processing image..."
            resultsText.setTextColor(Color.BLUE)

            val detections = YoloModel.detect(bitmap)

            // Display results
            displayResults(detections, bitmap.width, bitmap.height)

        } catch (e: Exception) {
            Log.e("ImageProcessing", "Error processing image", e)
            resultsText.text = "❌ Error processing image: ${e.message}"
            resultsText.setTextColor(Color.RED)
        }
    }

    private fun displayResults(detections: List<Detection>, originalWidth: Int, originalHeight: Int) {
        if (detections.isEmpty()) {
            resultsText.text = "🔍 No bottles detected in this image"
            resultsText.setTextColor(Color.YELLOW)
            overlay.clear()
        } else {
            resultsText.text = "🎯 Found ${detections.size} bottle(s)!"
            resultsText.setTextColor(Color.GREEN)

            // Calculate scaling factors
            val imageViewWidth = imageView.width.toFloat()
            val imageViewHeight = imageView.height.toFloat()

            // Account for ImageView scaling (CENTER_INSIDE)
            val scaleX = imageViewWidth / YoloModel.inputWidth
            val scaleY = imageViewHeight / YoloModel.inputHeight

            overlay.clear()

            for (detection in detections) {
                // Scale detection coordinates to ImageView coordinates
                val scaledX1 = detection.x1 * scaleX
                val scaledY1 = detection.y1 * scaleY
                val scaledX2 = detection.x2 * scaleX
                val scaledY2 = detection.y2 * scaleY

                overlay.addBox(
                    scaledX1, scaledY1, scaledX2, scaledY2,
                    detection.label, detection.confidence
                )

                Log.i("Detection", "Found: ${detection.label} (${(detection.confidence * 100).toInt()}%)")
            }
        }

        overlay.invalidate()
    }
}
