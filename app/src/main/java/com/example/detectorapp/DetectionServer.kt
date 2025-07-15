package com.example.detectorapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

// Data classes to match Python server JSON format exactly
data class ServerDetection(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val label: String,
    val track_id: Int
)

data class DetectionResponse(
    val detections: List<ServerDetection>
)

data class BottleStatusResponse(
    val active_bottles: Int,
    val bottles: Map<String, Map<String, String>>
)

class DetectionServer(
    private val context: Context,
    port: Int = 8080,
    private val onResponse: (String) -> Unit
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DetectionServer"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.01f // Match Python server threshold
        private const val NMS_IOU_THRESHOLD = 0.45f
    }

    // Object tracker for assigning track IDs
    private val objectTracker = ObjectTracker()

    // Track bottle class index (will be determined from labels)
    private var bottleClassIndex: Int? = null

    // Load interpreter & labels lazily
    private val interpreter: Interpreter by lazy {
        Log.i(TAG, "Loading TFLite model from assets...")
        val model = Interpreter(loadModelFile("model.tflite"))
        Log.i(TAG, "Model loaded successfully.")
        model
    }
    private val labels: List<String> by lazy {
        Log.i(TAG, "Loading labels from labels.txt...")
        val labs = context.assets.open("labels.txt").bufferedReader().use { it.readLines() }
        Log.i(TAG, "Loaded ${labs.size} labels.")

        // Find bottle class index
        bottleClassIndex = labs.indexOfFirst { it.equals("bottle", ignoreCase = true) }
            .takeIf { it >= 0 }
        Log.i(TAG, "Bottle class index: $bottleClassIndex")

        labs
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Incoming request: ${session.method} ${session.uri}")

        return when {
            session.method == Method.POST && session.uri == "/detect" -> {
                handleDetection(session, onlyBottles = false)
            }
            session.method == Method.POST && session.uri == "/detect_bottles_only" -> {
                handleDetection(session, onlyBottles = true)
            }
            session.method == Method.GET && session.uri == "/bottles/status" -> {
                handleBottleStatus()
            }
            session.method == Method.GET && session.uri == "/" -> {
                newFixedLengthResponse(Status.OK, "text/plain", "YOLO Android server is up!")
            }
            else -> {
                newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    private fun handleDetection(session: IHTTPSession, onlyBottles: Boolean): Response {
        try {
            // 1) Read image data from request body
            val lenHeader = session.headers["content-length"] ?: "0"
            val contentLength = lenHeader.toIntOrNull() ?: 0
            val body = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val bytesRead = session.inputStream.read(
                    body, totalRead, contentLength - totalRead
                )
                if (bytesRead < 0) break
                totalRead += bytesRead
            }
            Log.v(TAG, "Read $totalRead/$contentLength bytes from request body")

            // 2) Decode to Bitmap
            val bmp = BitmapFactory.decodeByteArray(body, 0, totalRead)
                ?: return newFixedLengthResponse(
                    Status.BAD_REQUEST, "application/json",
                    "{\"error\": \"Invalid image\"}"
                )
            Log.v(TAG, "Decoded image to Bitmap (${bmp.width}×${bmp.height})")

            // 3) Run YOLO detection using YoloModel
            val rawDetections = YoloModel.detect(bmp)
            Log.v(TAG, "Raw detections: ${rawDetections.size}")

            // 4) Filter for bottles only if requested
            val filteredDetections = if (onlyBottles && bottleClassIndex != null) {
                rawDetections.filter { it.label.equals("bottle", ignoreCase = true) }
            } else {
                rawDetections
            }

            // 5) Apply tracking to assign track IDs
            val trackedDetections = objectTracker.update(filteredDetections)
            Log.i(TAG, "Tracked detections: ${trackedDetections.size}")

            // 6) Convert to server format (match Python server exactly)
            val serverDetections = trackedDetections.map { detection ->
                ServerDetection(
                    x1 = detection.x1,
                    y1 = detection.y1,
                    x2 = detection.x2,
                    y2 = detection.y2,
                    label = detection.label,
                    track_id = detection.track_id
                )
            }

            val response = DetectionResponse(detections = serverDetections)
            val json = Gson().toJson(response)
            Log.d(TAG, "Detection JSON: $json")

            // 7) Callback & respond
            onResponse(json)
            return newFixedLengthResponse(
                Status.OK,
                "application/json",
                json
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection", e)
            val errorJson = "{\"error\": \"Server error: ${e.localizedMessage}\"}"
            return newFixedLengthResponse(
                Status.INTERNAL_ERROR,
                "application/json",
                errorJson
            )
        }
    }

    private fun handleBottleStatus(): Response {
        try {
            val statusData = objectTracker.getTrackingStatus()
            val response = BottleStatusResponse(
                active_bottles = statusData["active_bottles"] as Int,
                bottles = statusData["bottles"] as Map<String, Map<String, String>>
            )
            val json = Gson().toJson(response)
            Log.d(TAG, "Bottle status JSON: $json")

            return newFixedLengthResponse(
                Status.OK,
                "application/json",
                json
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting bottle status", e)
            val errorJson = "{\"error\": \"Server error: ${e.localizedMessage}\"}"
            return newFixedLengthResponse(
                Status.INTERNAL_ERROR,
                "application/json",
                errorJson
            )
        }
    }

    /** Load & memory-map a .tflite from assets */
    private fun loadModelFile(filename: String): MappedByteBuffer {
        Log.d(TAG, "Memory-mapping model file: $filename")
        val afd = context.assets.openFd(filename)
        return FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
        )
    }

    /** Resize & normalize a Bitmap into a [1×3×INPUT_SIZE×INPUT_SIZE] Float32 buffer */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        Log.d(TAG, "Resizing bitmap to $INPUT_SIZE × $INPUT_SIZE")
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
        val bytePerChannel = 4
        val buf = ByteBuffer.allocateDirect(1 * 3 * INPUT_SIZE * INPUT_SIZE * bytePerChannel)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8)  and 0xFF) / 255f)
            buf.putFloat(( p        and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    /** Convert raw `[boxCount][featDim]` into filtered, NMS’d Detection list */
    private fun postProcess(output: Array<FloatArray>): List<Detection> {
        Log.d(TAG, "Starting postProcess with ${output.size} boxes")
        val candidates = mutableListOf<Detection>()
        val attrCount = output[0].size

        for (row in output) {
            val objConf = row[4]
            if (objConf < CONF_THRESHOLD) continue

            // pick best class
            val (clsIdx, clsScore) = row
                .sliceArray(5 until attrCount)
                .mapIndexed { i, score -> i to score }
                .maxByOrNull { it.second }!!

            val confidence = objConf * clsScore
            if (confidence < CONF_THRESHOLD) continue

            // decode XYWH to pixel ints
            val cx = row[0] * INPUT_SIZE
            val cy = row[1] * INPUT_SIZE
            val w  = row[2] * INPUT_SIZE
            val h  = row[3] * INPUT_SIZE

            val x1 = (cx - w/2).toInt().coerceIn(0, INPUT_SIZE)
            val y1 = (cy - h/2).toInt().coerceIn(0, INPUT_SIZE)
            val x2 = (cx + w/2).toInt().coerceIn(0, INPUT_SIZE)
            val y2 = (cy + h/2).toInt().coerceIn(0, INPUT_SIZE)

            val label = labels.getOrNull(clsIdx) ?: clsIdx.toString()
            candidates += Detection(x1, y1, x2, y2, label, 0, confidence) // Fix constructor call
        }

        // NMS to remove overlaps
        val kept = mutableListOf<Detection>()
        val sorted = candidates.sortedByDescending { it.confidence }.toMutableList()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            sorted.removeAll { other -> iou(best, other) > NMS_IOU_THRESHOLD }
        }
        Log.d(TAG, "NMS complete, kept ${kept.size} boxes")
        return kept
    }

    /** Simple IoU */
    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)
        val inter = max(0, x2 - x1) * max(0, y2 - y1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        return inter / (areaA + areaB - inter).toFloat()
    }
}
