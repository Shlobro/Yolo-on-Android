package com.example.detectorapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamProcessor(
    private val context: Context,
    private val serverUrl: String = "http://localhost:8080"
) {
    companion object {
        private const val TAG = "CameraStreamProcessor"
        private const val MAX_FRAME_RATE = 10 // Limit to 10 FPS to avoid overwhelming the server
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isStreaming = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private var frameSkipInterval = 1000L / MAX_FRAME_RATE // milliseconds between frames

    fun startStreaming() {
        if (isStreaming.compareAndSet(false, true)) {
            Log.i(TAG, "Started camera streaming to $serverUrl")
        }
    }

    fun stopStreaming() {
        if (isStreaming.compareAndSet(true, false)) {
            Log.i(TAG, "Stopped camera streaming")
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        if (!isStreaming.get()) {
            imageProxy.close()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < frameSkipInterval) {
            imageProxy.close()
            return
        }
        lastFrameTime = currentTime

        coroutineScope.launch {
            try {
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    sendFrameToServer(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> {
                    val yBuffer = imageProxy.planes[0].buffer
                    val uBuffer = imageProxy.planes[1].buffer
                    val vBuffer = imageProxy.planes[2].buffer

                    val ySize = yBuffer.remaining()
                    val uSize = uBuffer.remaining()
                    val vSize = vBuffer.remaining()

                    val nv21 = ByteArray(ySize + uSize + vSize)
                    yBuffer.get(nv21, 0, ySize)
                    vBuffer.get(nv21, ySize, vSize)
                    uBuffer.get(nv21, ySize + vSize, uSize)

                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 50, out)
                    val imageBytes = out.toByteArray()
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                }
                else -> {
                    Log.w(TAG, "Unsupported image format: ${imageProxy.format}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
            null
        }
    }

    private suspend fun sendFrameToServer(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                val imageBytes = stream.toByteArray()

                val url = URL("$serverUrl/stream/frame")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "image/jpeg")
                    setRequestProperty("Content-Length", imageBytes.size.toString())
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                connection.outputStream.use { outputStream ->
                    outputStream.write(imageBytes)
                    outputStream.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Server returned response code: $responseCode")
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending frame to server", e)
            }
        }
    }

    fun shutdown() {
        stopStreaming()
        coroutineScope.cancel()
    }
}
