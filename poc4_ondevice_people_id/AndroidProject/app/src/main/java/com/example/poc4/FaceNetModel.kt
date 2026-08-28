package com.example.poc4

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class FaceNetModel(context: Context) {

    private var interpreter: Interpreter? = null
    private var inputSize = 160 // Default InceptionResnetV1 (FaceNet 512D) resolution 160x160
    private var embeddingDim = 512 // Default 512D embedding vector dimension

    init {
        try {
            // Priority 1: Load FaceNet 512D model (InceptionResnetV1 architecture converted from PyTorch)
            // Priority 2: Fallback to MobileFaceNet if 512D asset is absent
            val modelName = if (assetExists(context, "facenet_512.tflite")) "facenet_512.tflite" else "mobile_facenet.tflite"
            Log.d("FaceNetModel", "Carregando modelo TFLite de assets: $modelName")

            val modelFile = loadModelFile(context, modelName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelFile, options)

            // Dynamically inspect model input and output shapes from interpreter metadata
            val inputTensor = interpreter?.getInputTensor(0)
            if (inputTensor != null && inputTensor.shape().size >= 3) {
                // E.g. [1, 160, 160, 3] or [1, 3, 160, 160]
                val shape = inputTensor.shape()
                inputSize = if (shape[1] == 3 || shape[1] == 1) shape[2] else shape[1]
            }

            val outputTensor = interpreter?.getOutputTensor(0)
            if (outputTensor != null && outputTensor.shape().size >= 2) {
                embeddingDim = outputTensor.shape()[1] // E.g. 512 dimensions
            }

            Log.d("FaceNetModel", "✅ Modelo FaceNet TFLite ativo! Resolução de Entrada: ${inputSize}x${inputSize}, Vetor Embedding: ${embeddingDim}D")
        } catch (e: Exception) {
            Log.e("FaceNetModel", "❌ Erro ao carregar modelo TFLite: ${e.message}", e)
        }
    }

    private fun assetExists(context: Context, filename: String): Boolean {
        return try {
            context.assets.openFd(filename).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun getEmbedding(bitmap: Bitmap): FloatArray {
        val interp = interpreter ?: return FloatArray(embeddingDim)

        // Resize cropped face bitmap to model's exact input resolution (160x160)
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

        val outputArray = Array(1) { FloatArray(embeddingDim) }
        interp.run(inputBuffer, outputArray)

        // Return L2 normalized embedding vector (512D)
        return l2Normalize(outputArray[0])
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val imgData = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        imgData.order(ByteOrder.nativeOrder())
        imgData.rewind()

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val valPixel = intValues[pixel++]
                // Standard FaceNet PyTorch normalization: (pixel - 127.5) / 128.0
                imgData.putFloat((((valPixel shr 16) and 0xFF) - 127.5f) / 128.0f)
                imgData.putFloat((((valPixel shr 8) and 0xFF) - 127.5f) / 128.0f)
                imgData.putFloat(((valPixel and 0xFF) - 127.5f) / 128.0f)
            }
        }
        return imgData
    }

    private fun l2Normalize(embeddings: FloatArray): FloatArray {
        var sumSquare = 0.0f
        for (v in embeddings) {
            sumSquare += v * v
        }
        val norm = Math.sqrt(sumSquare.toDouble()).toFloat()
        val result = FloatArray(embeddings.size)
        if (norm > 0f) {
            for (i in embeddings.indices) {
                result[i] = embeddings[i] / norm
            }
        }
        return result
    }

    companion object {
        fun cosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
            if (emb1.size != emb2.size) return 0f
            var dot = 0.0f
            var normA = 0.0f
            var normB = 0.0f
            for (i in emb1.indices) {
                dot += emb1[i] * emb2[i]
                normA += emb1[i] * emb1[i]
                normB += emb2[i] * emb2[i]
            }
            if (normA == 0f || normB == 0f) return 0f
            return (dot / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble()))).toFloat()
        }
    }
}
