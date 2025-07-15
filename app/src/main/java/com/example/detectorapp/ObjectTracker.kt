package com.example.detectorapp

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Simple object tracker that assigns track IDs to detections based on IoU overlap
 * Mimics the tracking behavior of your Python server
 */
class ObjectTracker {
    companion object {
        private const val TAG = "ObjectTracker"
        private const val IOU_THRESHOLD = 0.3f
        private const val LOST_TIMEOUT = 10000L // 10 seconds in milliseconds
    }

    private val trackedObjects = mutableMapOf<Int, TrackedObject>()
    private var nextTrackId = 1

    data class TrackedObject(
        var lastDetection: Detection,
        var firstSeen: Long,
        var lastSeen: Long
    )

    /**
     * Update tracking with new detections and return detections with assigned track IDs
     */
    fun update(newDetections: List<Detection>): List<Detection> {
        val currentTime = System.currentTimeMillis()
        val trackedDetections = mutableListOf<Detection>()

        // Remove expired tracks
        val expiredTracks = trackedObjects.filter { (_, obj) ->
            currentTime - obj.lastSeen > LOST_TIMEOUT
        }
        expiredTracks.forEach { (trackId, _) ->
            trackedObjects.remove(trackId)
            Log.d(TAG, "Removed expired track ID: $trackId")
        }

        // Match new detections to existing tracks
        val assignedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Detection>()

        for (detection in newDetections) {
            var bestMatch: Pair<Int, Float>? = null

            // Find best matching existing track
            for ((trackId, trackedObj) in trackedObjects) {
                if (assignedTracks.contains(trackId)) continue
                if (trackedObj.lastDetection.label != detection.label) continue

                val iou = calculateIoU(detection, trackedObj.lastDetection)
                if (iou > IOU_THRESHOLD && (bestMatch == null || iou > bestMatch.second)) {
                    bestMatch = trackId to iou
                }
            }

            if (bestMatch != null) {
                // Update existing track
                val trackId = bestMatch.first
                val trackedObj = trackedObjects[trackId]!!
                trackedObj.lastDetection = detection
                trackedObj.lastSeen = currentTime
                assignedTracks.add(trackId)
                matchedDetections.add(detection)

                trackedDetections.add(detection.copy(track_id = trackId))
                Log.v(TAG, "Updated track ID $trackId for ${detection.label}")
            } else {
                // Create new track
                val newTrackId = nextTrackId++
                trackedObjects[newTrackId] = TrackedObject(
                    lastDetection = detection,
                    firstSeen = currentTime,
                    lastSeen = currentTime
                )

                trackedDetections.add(detection.copy(track_id = newTrackId))
                Log.d(TAG, "Created new track ID $newTrackId for ${detection.label}")
            }
        }

        return trackedDetections
    }

    /**
     * Get status of all active tracks (similar to Python server's /bottles/status endpoint)
     */
    fun getTrackingStatus(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val activeBottles = mutableMapOf<String, Map<String, Any>>()

        trackedObjects.forEach { (trackId, obj) ->
            val duration = (currentTime - obj.firstSeen) / 1000.0 // Convert to seconds
            activeBottles[trackId.toString()] = mapOf(
                "duration" to String.format("%.2f", duration)
            )
        }

        return mapOf(
            "active_bottles" to trackedObjects.size,
            "bottles" to activeBottles
        )
    }

    private fun calculateIoU(det1: Detection, det2: Detection): Float {
        val x1 = max(det1.x1, det2.x1)
        val y1 = max(det1.y1, det2.y1)
        val x2 = min(det1.x2, det2.x2)
        val y2 = min(det1.y2, det2.y2)

        val intersectionArea = max(0, x2 - x1) * max(0, y2 - y1)
        val area1 = (det1.x2 - det1.x1) * (det1.y2 - det1.y1)
        val area2 = (det2.x2 - det2.x1) * (det2.y2 - det2.y1)
        val unionArea = area1 + area2 - intersectionArea

        return if (unionArea > 0) intersectionArea.toFloat() / unionArea else 0f
    }
}
