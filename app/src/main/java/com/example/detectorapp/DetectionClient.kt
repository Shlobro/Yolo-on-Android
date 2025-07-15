package com.example.detectorapp

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Simple client helper for other apps to send images to the Android YOLO detection server
 * This replaces your Python server - other apps can use this to communicate with your Android app
 */
class DetectionClient(private val serverUrl: String = "http://localhost:8080") {

    companion object {
        private const val TAG = "DetectionClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Send image for detection of all objects
     * Returns JSON in format: {"detections": [{"x1": int, "y1": int, "x2": int, "y2": int, "label": string, "track_id": int}]}
     */
    suspend fun detect(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        sendImageToEndpoint(bitmap, "/detect")
    }

    /**
     * Send image for detection of bottles only
     * Returns JSON in same format as detect() but filtered to bottles only
     */
    suspend fun detectBottlesOnly(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        sendImageToEndpoint(bitmap, "/detect_bottles_only")
    }

    /**
     * Get status of all tracked bottles
     * Returns JSON: {"active_bottles": int, "bottles": {"track_id": {"duration": "seconds"}}}
     */
    suspend fun getBottleStatus(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/bottles/status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                Log.e(TAG, "Error getting bottle status: ${response.code}")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error getting bottle status", e)
            null
        }
    }

    /**
     * Health check - returns "YOLO Android server is up!" if server is running
     */
    suspend fun healthCheck(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                Log.e(TAG, "Health check failed: ${response.code}")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during health check", e)
            null
        }
    }

    private fun sendImageToEndpoint(bitmap: Bitmap, endpoint: String): String? {
        try {
            // Convert bitmap to JPEG bytes
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val imageBytes = outputStream.toByteArray()

            // Create request body
            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())

            // Build request
            val request = Request.Builder()
                .url("$serverUrl$endpoint")
                .post(requestBody)
                .build()

            // Execute request
            val response = client.newCall(request).execute()

            return if (response.isSuccessful) {
                val jsonResponse = response.body?.string()
                Log.d(TAG, "Detection response: ${jsonResponse?.take(200)}...")
                jsonResponse
            } else {
                Log.e(TAG, "Detection request failed: ${response.code}")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during detection", e)
            return null
        }
    }
}

// Example usage for other apps:
/*
class ExampleUsage {
    private val detectionClient = DetectionClient("http://192.168.1.100:8080") // Use actual IP

    suspend fun processImage(bitmap: Bitmap) {
        // Detect all objects
        val allDetections = detectionClient.detect(bitmap)
        println("All detections: $allDetections")

        // Detect bottles only
        val bottleDetections = detectionClient.detectBottlesOnly(bitmap)
        println("Bottle detections: $bottleDetections")

        // Get tracking status
        val status = detectionClient.getBottleStatus()
        println("Bottle status: $status")

        // Health check
        val health = detectionClient.healthCheck()
        println("Server health: $health")
    }
}
*/
