package com.example.poc4

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class DetectedFace(
    val boundingBox: Rect,
    val croppedBitmap: Bitmap
)

class FaceDetectorEngine {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val detector: FaceDetector = FaceDetection.getClient(options)

    suspend fun detectFaces(bitmap: Bitmap): List<DetectedFace> = suspendCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)

        detector.process(image)
            .addOnSuccessListener { faces ->
                val detectedFaces = mutableListOf<DetectedFace>()
                for (face in faces) {
                    val rect = face.boundingBox

                    // Apply a 20% padding margin around detected face box to include full facial geometry (chin, forehead, ears)
                    val marginX = (rect.width() * 0.20f).toInt()
                    val marginY = (rect.height() * 0.20f).toInt()

                    val left = Math.max(0, rect.left - marginX)
                    val top = Math.max(0, rect.top - marginY)
                    val right = Math.min(bitmap.width, rect.right + marginX)
                    val bottom = Math.min(bitmap.height, rect.bottom + marginY)

                    val width = right - left
                    val height = bottom - top

                    if (width > 20 && height > 20) {
                        try {
                            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                            detectedFaces.add(DetectedFace(rect, cropped))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                continuation.resume(detectedFaces)
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
                continuation.resume(emptyList())
            }
    }
}
