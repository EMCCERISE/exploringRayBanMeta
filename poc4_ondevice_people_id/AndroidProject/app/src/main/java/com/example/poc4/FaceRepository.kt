package com.example.poc4

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PersonRecord(
    val name: String,
    val relationship: String,
    val embedding: FloatArray,
    val imagePath: String
)

sealed class MatchResult {
    data class Match(val name: String, val relationship: String, val similarity: Float) : MatchResult()
    data class Unknown(val maxSimilarity: Float) : MatchResult()
}

class FaceRepository(
    private val context: Context,
    private val faceDetector: FaceDetectorEngine,
    private val faceNet: FaceNetModel
) {

    private val enrolledPersons = mutableListOf<PersonRecord>()
    private val knownFacesDir = File(context.filesDir, "known_faces").apply { mkdirs() }

    suspend fun initDatabase() = withContext(Dispatchers.IO) {
        enrolledPersons.clear()
        Log.d("FaceRepository", "Inicializando banco de dados de rostos em: ${knownFacesDir.absolutePath}")

        val files = knownFacesDir.listFiles { _, name ->
            name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".png", ignoreCase = true)
        } ?: emptyArray()

        for (file in files) {
            try {
                // Filename format: "Carlos__filho__1700000.jpg" or legacy "Carlos_1.jpg"
                val nameWithoutExt = file.nameWithoutExtension
                val (formattedName, formattedRelationship) = parseFilename(nameWithoutExt)

                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val detectedFaces = faceDetector.detectFaces(bitmap)
                    val faceBitmap = if (detectedFaces.isNotEmpty()) detectedFaces[0].croppedBitmap else bitmap
                    val embedding = faceNet.getEmbedding(faceBitmap)

                    enrolledPersons.add(PersonRecord(formattedName, formattedRelationship, embedding, file.absolutePath))
                    Log.d("FaceRepository", "  [+] Pessoa carregada: $formattedName ($formattedRelationship) [${file.name}]")
                }
            } catch (e: Exception) {
                Log.e("FaceRepository", "Erro ao carregar imagem de referência: ${file.name}", e)
            }
        }
        Log.d("FaceRepository", "Indexação finalizada. Total de pessoas cadastradas: ${enrolledPersons.size}")
    }

    private fun parseFilename(nameWithoutExt: String): Pair<String, String> {
        if (nameWithoutExt.contains("__")) {
            val parts = nameWithoutExt.split("__")
            val name = parts.getOrNull(0)?.replace("_", " ")?.trim()?.lowercase()?.capitalize() ?: "Desconhecido"
            val relationship = parts.getOrNull(1)?.replace("_", " ")?.trim()?.lowercase() ?: ""
            return Pair(name, relationship)
        }
        // Legacy fallback
        val rawName = nameWithoutExt.replace(Regex("[-_]?\\d+$"), "")
        val name = rawName.replace("_", " ").trim().lowercase().capitalize()
        return Pair(name, "")
    }

    suspend fun registerPerson(name: String, relationship: String, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val sanitizedName = name.trim().replace(" ", "_")
            val sanitizedRel = relationship.trim().replace(" ", "_")
            val timestamp = System.currentTimeMillis()
            
            // Filename: "Carlos__filho__17000000.jpg"
            val fileName = if (sanitizedRel.isNotEmpty()) {
                "${sanitizedName}__${sanitizedRel}__$timestamp.jpg"
            } else {
                "${sanitizedName}__$timestamp.jpg"
            }

            val file = File(knownFacesDir, fileName)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            val detectedFaces = faceDetector.detectFaces(bitmap)
            val faceBitmap = if (detectedFaces.isNotEmpty()) detectedFaces[0].croppedBitmap else bitmap
            val embedding = faceNet.getEmbedding(faceBitmap)

            val formattedName = name.trim().lowercase().capitalize()
            val formattedRel = relationship.trim().lowercase()
            enrolledPersons.add(PersonRecord(formattedName, formattedRel, embedding, file.absolutePath))
            Log.d("FaceRepository", "Nova pessoa cadastrada: $formattedName ($formattedRel)")
            true
        } catch (e: Exception) {
            Log.e("FaceRepository", "Erro ao cadastrar pessoa: ${e.message}", e)
            false
        }
    }

    suspend fun deletePerson(record: PersonRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(record.imagePath)
            if (file.exists()) {
                file.delete()
            }
            enrolledPersons.remove(record)
            Log.d("FaceRepository", "Pessoa removida: ${record.name}")
            true
        } catch (e: Exception) {
            Log.e("FaceRepository", "Erro ao remover pessoa: ${e.message}", e)
            false
        }
    }

    fun matchFace(embedding: FloatArray, threshold: Float = 0.50f): MatchResult {
        if (enrolledPersons.isEmpty()) {
            return MatchResult.Unknown(0f)
        }

        var maxSim = -1.0f
        var bestMatch: PersonRecord? = null

        for (person in enrolledPersons) {
            val sim = FaceNetModel.cosineSimilarity(embedding, person.embedding)
            if (sim > maxSim) {
                maxSim = sim
                bestMatch = person
            }
        }

        return if (bestMatch != null && maxSim >= threshold) {
            MatchResult.Match(bestMatch.name, bestMatch.relationship, maxSim)
        } else {
            MatchResult.Unknown(Math.max(0f, maxSim))
        }
    }

    fun getEnrolledCount(): Int = enrolledPersons.size

    fun getEnrolledPersons(): List<PersonRecord> = enrolledPersons.toList()

    private fun String.capitalize(): String =
        this.split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}
