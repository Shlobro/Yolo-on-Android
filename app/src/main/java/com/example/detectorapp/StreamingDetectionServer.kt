package com.example.detectorapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class StreamingDetectionServer(
    private val context: Context,
    port: Int = 8080,
    private val onDetection: (String) -> Unit
) : NanoWSD(port) {

    companion object {
        private const val TAG = "StreamingDetectionServer"
        private const val MAX_CLIENTS = 10
        private const val STREAM_FRAME_RATE = 30 // FPS
    }

    private val gson = Gson()
    private val connectedClients = ConcurrentHashMap<String, StreamingWebSocket>()
    private val streamingExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private var isStreaming = false
    private var currentFrame: Bitmap? = null
    private var frameCounter = 0L

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "HTTP Request: ${session.method} ${session.uri}")

        return when {
            session.uri == "/ws" -> {
                // WebSocket upgrade will be handled automatically
                super.serve(session)
            }
            session.method == Method.POST && session.uri == "/detect" -> {
                handleSingleImageDetection(session)
            }
            session.method == Method.POST && session.uri == "/stream/frame" -> {
                handleStreamFrame(session)
            }
            session.method == Method.GET && session.uri == "/stream/start" -> {
                startStreaming()
                newFixedLengthResponse(Response.Status.OK, "application/json",
                    """{"status":"streaming_started","clients":${connectedClients.size}}""")
            }
            session.method == Method.GET && session.uri == "/stream/stop" -> {
                stopStreaming()
                newFixedLengthResponse(Response.Status.OK, "application/json",
                    """{"status":"streaming_stopped"}""")
            }
            session.method == Method.GET && session.uri == "/stream/status" -> {
                newFixedLengthResponse(Response.Status.OK, "application/json",
                    """{"streaming":$isStreaming,"clients":${connectedClients.size},"frame_count":$frameCounter}""")
            }
            session.uri == "/" -> {
                // Serve a simple HTML page for testing
                newFixedLengthResponse(Response.Status.OK, "text/html", getTestPage())
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val clientId = UUID.randomUUID().toString()
        Log.i(TAG, "New WebSocket connection: $clientId")

        if (connectedClients.size >= MAX_CLIENTS) {
            Log.w(TAG, "Maximum clients reached, rejecting connection")
            throw IOException("Maximum clients reached")
        }

        return StreamingWebSocket(clientId, handshake).also { ws ->
            connectedClients[clientId] = ws
        }
    }

    private fun handleSingleImageDetection(session: IHTTPSession): Response {
        return try {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val imageData = ByteArray(contentLength)
            var totalRead = 0

            while (totalRead < contentLength) {
                val bytesRead = session.inputStream.read(imageData, totalRead, contentLength - totalRead)
                if (bytesRead < 0) break
                totalRead += bytesRead
            }

            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, totalRead)
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid image")

            val startTime = System.currentTimeMillis()
            val detections = YoloModel.detect(bitmap)
            val processingTime = System.currentTimeMillis() - startTime

            val result = StreamingDetectionResult(
                detections = detections,
                frameId = "single_${System.currentTimeMillis()}",
                processingTimeMs = processingTime,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )

            val json = gson.toJson(result)
            onDetection(json)

            newFixedLengthResponse(Response.Status.OK, "application/json", json)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing single image", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Processing error: ${e.message}")
        }
    }

    private fun handleStreamFrame(session: IHTTPSession): Response {
        return try {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val imageData = ByteArray(contentLength)
            var totalRead = 0

            while (totalRead < contentLength) {
                val bytesRead = session.inputStream.read(imageData, totalRead, contentLength - totalRead)
                if (bytesRead < 0) break
                totalRead += bytesRead
            }

            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, totalRead)
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid image")

            // Update current frame for streaming
            currentFrame = bitmap
            processAndBroadcastFrame(bitmap)

            newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"frame_received"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing stream frame", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Processing error: ${e.message}")
        }
    }

    private fun processAndBroadcastFrame(bitmap: Bitmap) {
        streamingExecutor.execute {
            try {
                val startTime = System.currentTimeMillis()
                val detections = YoloModel.detect(bitmap)
                val processingTime = System.currentTimeMillis() - startTime
                frameCounter++

                val result = StreamingDetectionResult(
                    detections = detections,
                    frameId = "stream_$frameCounter",
                    processingTimeMs = processingTime,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )

                val json = gson.toJson(result)
                onDetection(json)

                // Broadcast to all connected WebSocket clients
                broadcastToClients(json)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            }
        }
    }

    private fun broadcastToClients(message: String) {
        val clientsToRemove = mutableListOf<String>()

        connectedClients.forEach { (clientId, client) ->
            try {
                client.send(message)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to client $clientId, removing", e)
                clientsToRemove.add(clientId)
            }
        }

        clientsToRemove.forEach { clientId ->
            connectedClients.remove(clientId)
        }
    }

    private fun startStreaming() {
        if (!isStreaming) {
            isStreaming = true
            Log.i(TAG, "Streaming started")
        }
    }

    private fun stopStreaming() {
        if (isStreaming) {
            isStreaming = false
            Log.i(TAG, "Streaming stopped")
        }
    }

    fun shutdown() {
        stopStreaming()
        streamingExecutor.shutdown()
        try {
            if (!streamingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                streamingExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            streamingExecutor.shutdownNow()
        }
        stop()
    }

    private fun getTestPage(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <title>Detector App Stream</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; }
        .status { margin: 10px 0; padding: 10px; background: #f0f0f0; }
        .detection { margin: 5px 0; padding: 5px; background: #e8f5e8; }
        #messages { height: 400px; overflow-y: scroll; border: 1px solid #ccc; padding: 10px; }
    </style>
</head>
<body>
    <h1>Detector App Streaming Interface</h1>
    <div class="status" id="status">Connecting...</div>
    <button onclick="connectWebSocket()">Connect</button>
    <button onclick="disconnectWebSocket()">Disconnect</button>
    <button onclick="clearMessages()">Clear</button>
    <div id="messages"></div>

    <script>
        let ws = null;
        
        function updateStatus(message) {
            document.getElementById('status').textContent = message;
        }
        
        function addMessage(message) {
            const messagesDiv = document.getElementById('messages');
            const messageDiv = document.createElement('div');
            messageDiv.className = 'detection';
            messageDiv.textContent = new Date().toLocaleTimeString() + ': ' + message;
            messagesDiv.appendChild(messageDiv);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
        }
        
        function connectWebSocket() {
            if (ws) {
                ws.close();
            }
            
            ws = new WebSocket('ws://' + window.location.host + '/ws');
            
            ws.onopen = function() {
                updateStatus('Connected to stream');
                addMessage('WebSocket connected');
            };
            
            ws.onmessage = function(event) {
                try {
                    const data = JSON.parse(event.data);
                    addMessage(`Frame ${data.frameId}: ${data.totalDetections} detections (${data.processingTimeMs}ms)`);
                } catch (e) {
                    addMessage('Received: ' + event.data);
                }
            };
            
            ws.onclose = function() {
                updateStatus('Disconnected');
                addMessage('WebSocket disconnected');
            };
            
            ws.onerror = function(error) {
                updateStatus('Error: ' + error);
                addMessage('WebSocket error: ' + error);
            };
        }
        
        function disconnectWebSocket() {
            if (ws) {
                ws.close();
                ws = null;
            }
        }
        
        function clearMessages() {
            document.getElementById('messages').innerHTML = '';
        }
        
        // Auto-connect on page load
        connectWebSocket();
    </script>
</body>
</html>
        """.trimIndent()
    }

    inner class StreamingWebSocket(
        private val clientId: String,
        handshake: IHTTPSession
    ) : WebSocket(handshake) {

        override fun onOpen() {
            Log.i(TAG, "WebSocket opened for client: $clientId")
            send("""{"type":"connection","status":"connected","clientId":"$clientId"}""")
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            Log.i(TAG, "WebSocket closed for client: $clientId (reason: $reason)")
            connectedClients.remove(clientId)
        }

        override fun onMessage(message: WebSocketFrame) {
            try {
                val text = message.textPayload
                Log.d(TAG, "Received WebSocket message from $clientId: $text")

                // Handle client commands
                val json = JSONObject(text)
                when (json.optString("command")) {
                    "ping" -> send("""{"type":"pong","timestamp":${System.currentTimeMillis()}}""")
                    "status" -> send("""{"type":"status","streaming":$isStreaming,"frameCount":$frameCounter}""")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error processing WebSocket message from $clientId", e)
            }
        }

        override fun onPong(pong: WebSocketFrame) {
            Log.d(TAG, "Received pong from client: $clientId")
        }

        override fun onException(exception: IOException) {
            Log.e(TAG, "WebSocket exception for client: $clientId", exception)
            connectedClients.remove(clientId)
        }
    }
}
