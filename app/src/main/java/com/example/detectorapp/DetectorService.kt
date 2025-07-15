package com.example.detectorapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class DetectorService : Service() {
    companion object {
        private const val TAG = "DetectorService"
    }

    private lateinit var detectionServer: DetectionServer

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate – setting up notification and detection server")
        createNotificationChannel()

        val notif = NotificationCompat.Builder(this, "detector")
            .setContentTitle("YOLO Detection Server")
            .setContentText("Background detection server running on port 8080")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true) // Make it persistent
            .build()
        startForeground(1, notif)

        // Initialize YOLO model
        YoloModel.init(this)

        // Create detection server with Python server compatibility
        detectionServer = DetectionServer(this, 8080) { json ->
            Log.v(TAG, "Detection result: ${json.take(200)}...")
            // Broadcast detection results for other components if needed
            Intent("com.example.detectorapp.DETECTION").also { intent ->
                intent.putExtra("json", json)
                sendBroadcast(intent)
            }
        }

        try {
            detectionServer.start()
            Log.i(TAG, "Detection HTTP server started on port 8080")
            Log.i(TAG, "Available endpoints:")
            Log.i(TAG, "  POST /detect - detect all objects")
            Log.i(TAG, "  POST /detect_bottles_only - detect bottles only")
            Log.i(TAG, "  GET /bottles/status - get tracking status")
            Log.i(TAG, "  GET / - health check")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start detection server", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called (startId=$startId, flags=$flags)")
        return START_STICKY // Restart service if killed
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy – stopping server and removing notification")
        try {
            detectionServer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping detection server", e)
        }
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        Log.d(TAG, "Creating notification channel")
        val chan = NotificationChannel(
            "detector",
            "Detector Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background YOLO detection service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(chan)
    }
}
