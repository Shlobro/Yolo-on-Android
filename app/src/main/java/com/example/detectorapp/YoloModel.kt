package com.example.detectorapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

object YoloModel {

    /* ───────────────────────── constants / config ───────────────────────── */

    private const val TAG             = "YoloModel"
    private const val MODEL_FILENAME  = "best-demo-day-roof.tflite"
    private const val LABELS_FILENAME = "labels.txt"

    /** confidence threshold for a candidate BEFORE non-max suppression */
    private var CONF_THRESHOLD  = 0.10f          // start low so you see candidates
    private const val NMS_IOU_THRESHOLD = 0.45f

    /* ───────────────────────── runtime state ────────────────────────────── */

    private lateinit var interpreter: Interpreter
    private lateinit var labels: List<String>
    private var useNNAPI = false  // Track whether NNAPI is being used
    private var contextRef: WeakReference<Context>? = null  // Use WeakReference to avoid memory leak

    /*  actual input resolution that the network expects
        (filled in after inspecting the first tensor)                        */
    private var _inputWidth  = 640
    private var _inputHeight = 640

    /** read-only accessors for callers (used by LiveCameraActivity) */
    val inputWidth : Int get() = _inputWidth
    val inputHeight: Int get() = _inputHeight

    /* how many classes the model predicts (taken from labels.txt)           */
    private var numClasses = 80

    /* ───────────────────────── public API ───────────────────────────────── */

    /**
     * Call *once* (on app start) before you call [detect].
     * Loads the TFLite model and reads the input tensor shape.
     */
    fun init(context: Context) {
        this.contextRef = WeakReference(context)  // Store weak reference to context

        /* ── load the .tflite file from assets ─────────────────────────────── */
        val modelBuffer = try {
            loadModelFile(context)
        } catch (e: Exception) {
            throw RuntimeException(
                "Missing asset \"$MODEL_FILENAME\". " +
                        "Copy it to  app/src/main/assets  and rebuild.", e
            )
        }

        /* ── create TensorFlow Lite interpreter with fallback mechanism ─── */
        interpreter = createInterpreterWithFallback(modelBuffer)
        Log.i(TAG, "TensorFlow Lite interpreter ready (NNAPI: $useNNAPI)")

        /* ── inspect input tensor shape so we can resize bitmaps correctly ─ */
        val inputShape = interpreter.getInputTensor(0).shape()
        Log.i(TAG, "Input tensor shape: ${inputShape.contentToString()}")

        // Expecting either NCHW (1,3,h,w) or NHWC (1,h,w,3)
        when {
            inputShape.size == 4 && inputShape[1] == 3 -> {
                _inputHeight = inputShape[2]
                _inputWidth = inputShape[3]
            }
            inputShape.size == 4 && inputShape[3] == 3 -> {
                _inputHeight = inputShape[1]
                _inputWidth = inputShape[2]
            }
            else -> {
                Log.w(TAG, "Unexpected input shape ${inputShape.contentToString()} – using default 640×640")
            }
        }
        Log.i(TAG, "Model input size: ${_inputWidth}×${_inputHeight}")

        /* ── read class labels ───────────────────────────────────────────── */
        labels = try {
            context.assets.open(LABELS_FILENAME).bufferedReader().useLines { it.toList() }
        } catch (e: Exception) {
            throw RuntimeException(
                "Missing asset \"$LABELS_FILENAME\". " +
                        "Copy it to  app/src/main/assets  and rebuild.", e
            )
        }
        numClasses = labels.size
        Log.i(TAG, "Loaded $numClasses labels")
    }

    /**
     * Creates interpreter with NNAPI first, falls back to CPU if NNAPI fails
     */
    private fun createInterpreterWithFallback(modelBuffer: MappedByteBuffer): Interpreter {
        // First try with NNAPI
        try {
            val nnapiOptions = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            val nnapiInterpreter = Interpreter(modelBuffer, nnapiOptions)
            useNNAPI = true
            Log.i(TAG, "Successfully initialized with NNAPI acceleration")
            return nnapiInterpreter
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI initialization failed, falling back to CPU: ${e.message}")
        }

        // Fallback to CPU-only execution
        try {
            val cpuOptions = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(false)
                setUseXNNPACK(true) // Use XNNPACK for CPU optimization
            }
            val cpuInterpreter = Interpreter(modelBuffer, cpuOptions)
            useNNAPI = false
            Log.i(TAG, "Successfully initialized with CPU execution")
            return cpuInterpreter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize interpreter even with CPU fallback", e)
            throw RuntimeException("Could not initialize TensorFlow Lite interpreter", e)
        }
    }

    /**
     * Run inference on one [bitmap] and return a list of [Detection]s
     * in *model coordinates* (0‥inputWidth / 0‥inputHeight).
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        return try {
            runInference(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")

            // If using NNAPI and it fails, try to recreate interpreter with CPU fallback
            if (useNNAPI && (e.message?.contains("ANEURALNETWORKS_DEAD_OBJECT") == true ||
                           e.message?.contains("TfLiteNnapiDelegate") == true)) {
                Log.w(TAG, "NNAPI delegate failed, attempting to recreate interpreter with CPU fallback")
                try {
                    contextRef?.get()?.let { ctx ->
                        val modelBuffer = loadModelFile(ctx)
                        interpreter = createInterpreterWithFallback(modelBuffer)
                        Log.i(TAG, "Successfully recreated interpreter, retrying inference")
                        return runInference(bitmap)
                    } ?: run {
                        Log.e(TAG, "Context not available for interpreter recreation")
                    }
                } catch (recreateException: Exception) {
                    Log.e(TAG, "Failed to recreate interpreter", recreateException)
                }
            }

            // Return empty list if all recovery attempts fail
            emptyList()
        }
    }

    private fun runInference(bitmap: Bitmap): List<Detection> {
        /* 1️⃣  Pre-process ─ resize → normalized float32 ByteBuffer           */
        val scaled = Bitmap.createScaledBitmap(bitmap, _inputWidth, _inputHeight, false)
        val inputBuffer = ByteBuffer.allocateDirect(4 * 1 * 3 * _inputHeight * _inputWidth)
        inputBuffer.order(ByteOrder.nativeOrder())

        val px = IntArray(_inputWidth * _inputHeight)
        scaled.getPixels(px, 0, _inputWidth, 0, 0, _inputWidth, _inputHeight)

        for (i in px.indices) {
            val p = px[i]
            // RGB order, normalized to [0, 1]
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((p and 0xFF) / 255.0f)          // B
        }

        /* 2️⃣  Inference                                                    */
        val outputShape = interpreter.getOutputTensor(0).shape()
        val outputSize = outputShape.fold(1) { acc, dim -> acc * dim }
        val outputBuffer = ByteBuffer.allocateDirect(4 * outputSize)
        outputBuffer.order(ByteOrder.nativeOrder())

        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        /* 3️⃣  Parse output and convert to detections                       */
        Log.d(TAG, "Output tensor shape: ${outputShape.contentToString()}, size: $outputSize")

        val detections = mutableListOf<Detection>()

        // Convert ByteBuffer to FloatArray for easier processing
        val outputArray = FloatArray(outputSize)
        outputBuffer.asFloatBuffer().get(outputArray)

        // Handle different output formats
        return when {
            outputShape.size == 3 && outputShape[0] == 1 -> {
                // Format: [1, features, predictions] - typical YOLOv8/v11 format
                parseYoloOutput(outputArray, outputShape, detections)
            }
            outputShape.size == 2 -> {
                // Format: [predictions, features] - alternative format
                parseAlternativeOutput(outputArray, outputShape, detections)
            }
            else -> {
                Log.w(TAG, "Unsupported output format: ${outputShape.contentToString()}")
                emptyList()
            }
        }
    }

    private fun parseYoloOutput(outputArray: FloatArray, outputShape: IntArray, detections: MutableList<Detection>): List<Detection> {
        val numFeatures = outputShape[1]  // e.g., 84
        val numPredictions = outputShape[2]  // e.g., 8400

        Log.d(TAG, "Parsing YOLO format: $numFeatures features, $numPredictions predictions")

        // Validate array bounds
        if (outputArray.size < numFeatures * numPredictions) {
            Log.e(TAG, "Output array too small: ${outputArray.size} < ${numFeatures * numPredictions}")
            return emptyList()
        }

        for (i in 0 until numPredictions) {
            try {
                val cx = outputArray[i] * _inputWidth                    // center x
                val cy = outputArray[numPredictions + i] * _inputHeight  // center y
                val w = outputArray[2 * numPredictions + i] * _inputWidth    // width
                val h = outputArray[3 * numPredictions + i] * _inputHeight   // height

                // Find best class - with bounds checking
                var bestCls = -1
                var bestClsConf = 0f
                val maxClassesToCheck = min(numClasses, numFeatures - 4)

                for (c in 0 until maxClassesToCheck) {
                    val confIndex = (4 + c) * numPredictions + i
                    if (confIndex < outputArray.size) {
                        val conf = outputArray[confIndex]
                        if (conf > bestClsConf) {
                            bestClsConf = conf
                            bestCls = c
                        }
                    }
                }

                if (bestClsConf < CONF_THRESHOLD) continue

                val x1 = max(0, (cx - w / 2).toInt())
                val y1 = max(0, (cy - h / 2).toInt())
                val x2 = min(_inputWidth, (cx + w / 2).toInt())
                val y2 = min(_inputHeight, (cy + h / 2).toInt())

                detections += Detection(
                    x1, y1, x2, y2,
                    labels.getOrElse(bestCls) { bestCls.toString() },
                    bestClsConf.toInt() // Convert confidence to track_id
                )
            } catch (e: IndexOutOfBoundsException) {
                Log.e(TAG, "Index out of bounds at prediction $i: ${e.message}")
                break
            }
        }

        return applyNonMaxSuppression(detections)
    }

    private fun parseAlternativeOutput(outputArray: FloatArray, outputShape: IntArray, detections: MutableList<Detection>): List<Detection> {
        val numPredictions = outputShape[0]
        val numFeatures = outputShape[1]

        Log.d(TAG, "Parsing alternative format: $numPredictions predictions, $numFeatures features")

        for (i in 0 until numPredictions) {
            try {
                val baseIndex = i * numFeatures
                if (baseIndex + 4 >= outputArray.size) break

                val cx = outputArray[baseIndex] * _inputWidth
                val cy = outputArray[baseIndex + 1] * _inputHeight
                val w = outputArray[baseIndex + 2] * _inputWidth
                val h = outputArray[baseIndex + 3] * _inputHeight

                // Find best class
                var bestCls = -1
                var bestClsConf = 0f
                val maxClassesToCheck = min(numClasses, numFeatures - 4)

                for (c in 0 until maxClassesToCheck) {
                    val confIndex = baseIndex + 4 + c
                    if (confIndex < outputArray.size) {
                        val conf = outputArray[confIndex]
                        if (conf > bestClsConf) {
                            bestClsConf = conf
                            bestCls = c
                        }
                    }
                }

                if (bestClsConf < CONF_THRESHOLD) continue

                val x1 = max(0, (cx - w / 2).toInt())
                val y1 = max(0, (cy - h / 2).toInt())
                val x2 = min(_inputWidth, (cx + w / 2).toInt())
                val y2 = min(_inputHeight, (cy + h / 2).toInt())

                detections += Detection(
                    x1, y1, x2, y2,
                    labels.getOrElse(bestCls) { bestCls.toString() },
                    bestClsConf.toInt() // Convert confidence to track_id
                )
            } catch (e: IndexOutOfBoundsException) {
                Log.e(TAG, "Index out of bounds at prediction $i: ${e.message}")
                break
            }
        }

        return applyNonMaxSuppression(detections)
    }

    private fun applyNonMaxSuppression(detections: List<Detection>): List<Detection> {
        /* 4️⃣  Non-max-suppression (hard NMS)                                */
        val kept = mutableListOf<Detection>()
        val todo = detections.sortedByDescending { it.confidence }.toMutableList()
        while (todo.isNotEmpty()) {
            val best = todo.removeAt(0)
            kept += best
            todo.removeAll { other -> iou(best, other) > NMS_IOU_THRESHOLD }
        }
        return kept
    }

    /* ───────────────────────── helpers ─────────────────────────────────── */

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILENAME)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun iou(a: Detection, b: Detection): Float {
        val xx1 = max(a.x1, b.x1)
        val yy1 = max(a.y1, b.y1)
        val xx2 = min(a.x2, b.x2)
        val yy2 = min(a.y2, b.y2)

        val interW = max(0, xx2 - xx1)
        val interH = max(0, yy2 - yy1)
        val inter  = interW * interH

        val areaA  = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB  = (b.x2 - b.x1) * (b.y2 - b.y1)

        return if (inter == 0) 0f else inter.toFloat() / (areaA + areaB - inter)
    }
}

/*  Enhanced data holder for streaming detection results                     */
data class Detection(
    val x1: Int, val y1: Int,
    val x2: Int, val y2: Int,
    val label: String,
    val track_id: Int,
    val confidence: Float = 0.0f, // Keep internally but don't expose in JSON
    val timestamp: Long = System.currentTimeMillis(),
    val frameId: String? = null,
    val imageWidth: Int = 640,
    val imageHeight: Int = 640
)

data class StreamingDetectionResult(
    val detections: List<Detection>,
    val timestamp: Long = System.currentTimeMillis(),
    val frameId: String,
    val processingTimeMs: Long,
    val totalDetections: Int = detections.size,
    val imageWidth: Int,
    val imageHeight: Int
)
