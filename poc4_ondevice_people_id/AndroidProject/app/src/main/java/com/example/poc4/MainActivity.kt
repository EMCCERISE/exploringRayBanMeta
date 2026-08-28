package com.example.poc4

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.poc4.databinding.ActivityMainBinding
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Meta Wearables DAT SDK
    private var session: DeviceSession? = null
    private var stream: Stream? = null

    // On-Device Processing Engines
    private lateinit var faceDetector: FaceDetectorEngine
    private lateinit var faceNet: FaceNetModel
    private lateinit var repository: FaceRepository
    private lateinit var ttsEngine: TtsEngine

    private var pendingRegistrationBitmap: Bitmap? = null
    private var registrationDialogPreview: ImageView? = null

    // Activity Result Launchers
    private val pickGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                pendingRegistrationBitmap = bitmap
                registrationDialogPreview?.setImageBitmap(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao carregar imagem da galeria", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value == true }
        if (allGranted) {
            binding.statusText.text = "Permissões concedidas. Inicializando SDK Meta..."
            initializeWearablesSdk()
        } else {
            Toast.makeText(this, "Permissões de Câmera/Bluetooth/Áudio são necessárias", Toast.LENGTH_LONG).show()
        }
    }

    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        if (status == PermissionStatus.Granted) {
            setupDeviceSession()
        } else {
            binding.statusText.text = "Permissão da câmera dos óculos negada."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Engines
        faceDetector = FaceDetectorEngine()
        faceNet = FaceNetModel(this)
        repository = FaceRepository(this, faceDetector, faceNet)
        ttsEngine = TtsEngine(this)

        // Index registered faces in background
        lifecycleScope.launch {
            binding.statusText.text = "Status: Indexando banco de dados de rostos local..."
            repository.initDatabase()
            updateEnrolledBadge()
            val count = repository.getEnrolledCount()
            binding.statusText.text = "Status: $count pessoas cadastradas on-device. Pronto!"
        }

        binding.btnIdentify.setOnClickListener {
            captureAndIdentifyOnDevice()
        }

        binding.btnRegister.setOnClickListener {
            showRegisterDialog()
        }

        binding.btnViewFacesHeader.setOnClickListener {
            showViewFacesDialog()
        }

        checkPermissions()
    }

    private fun updateEnrolledBadge() {
        val count = repository.getEnrolledCount()
        binding.btnViewFacesHeader.text = "Ver Rostos ($count)"
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (android.os.Build.VERSION.SDK_INT <= 30) {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            initializeWearablesSdk()
        }
    }

    private fun initializeWearablesSdk() {
        Wearables.initialize(this)

        lifecycleScope.launch {
            Wearables.registrationState.collect { regState ->
                if (regState == com.meta.wearable.dat.core.types.RegistrationState.REGISTERED) {
                    binding.statusText.text = "App Registrado no SDK Meta. Checando permissão da câmera..."
                    val result = Wearables.checkPermissionStatus(Permission.CAMERA)
                    val status = result.getOrNull()

                    if (status == PermissionStatus.Granted) {
                        setupDeviceSession()
                    } else {
                        wearablesPermissionLauncher.launch(Permission.CAMERA)
                    }
                } else if (regState != com.meta.wearable.dat.core.types.RegistrationState.REGISTERING) {
                    binding.statusText.text = "Registrando App na Meta..."
                    Wearables.startRegistration(this@MainActivity)
                }
            }
        }
    }

    private fun setupDeviceSession() {
        binding.statusText.text = "Buscando óculos Meta Ray-Ban..."
        lifecycleScope.launch {
            Wearables.devices.collect { devices ->
                if (devices.isNotEmpty() && session == null) {
                    binding.statusText.text = "Óculos encontrado! Conectando..."
                    Wearables.createSession(AutoDeviceSelector())
                        .onSuccess { createdSession ->
                            session = createdSession
                            session?.start()

                            lifecycleScope.launch {
                                session?.state?.collect { state ->
                                    if (state == com.meta.wearable.dat.core.session.DeviceSessionState.STARTED) {
                                        if (stream == null) {
                                            setupStream()
                                        }
                                    }
                                }
                            }
                        }
                        .onFailure { error, _ ->
                            binding.statusText.text = "Erro ao conectar óculos: ${error.description}"
                        }
                }
            }
        }
    }

    private fun setupStream() {
        lifecycleScope.launch {
            binding.statusText.text = "Ligando câmera dos óculos..."
            session?.addStream(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 2))
                ?.onSuccess { addedStream ->
                    stream = addedStream
                    stream?.start()
                    val count = repository.getEnrolledCount()
                    binding.statusText.text = "🟢 Óculos Conectado! On-Device Ready ($count cadastros)"
                }
                ?.onFailure { error, _ ->
                    binding.statusText.text = "Erro no stream dos óculos: ${error.description}"
                }
        }
    }

    private fun captureAndIdentifyOnDevice() {
        binding.statusText.text = "📸 Capturando imagem dos óculos..."
        binding.btnIdentify.isEnabled = false
        binding.emptyStateText.visibility = View.GONE
        binding.overlayView.clear()

        lifecycleScope.launch {
            var bitmapToProcess: Bitmap? = null

            if (stream != null) {
                val result = stream?.capturePhoto()
                val photoData = result?.getOrNull()
                if (photoData != null) {
                    bitmapToProcess = processPhotoDataToBitmap(photoData)
                }
            }

            if (bitmapToProcess == null) {
                Log.w("MainActivity", "Câmera dos óculos indisponível. Usando imagem de teste sintética...")
                binding.statusText.text = "⚠️ Câmera do óculos offline. Usando imagem de teste..."
                bitmapToProcess = createTestFaceBitmap("Carlos")
            }

            // Display captured image on screen
            binding.capturedImageView.setImageBitmap(bitmapToProcess)

            // Execute On-Device Face Pipeline
            processImageOnDevice(bitmapToProcess)

            binding.btnIdentify.isEnabled = true
        }
    }

    private suspend fun processImageOnDevice(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // 1. On-Device Face Detection (Google ML Kit)
        val detectedFaces = faceDetector.detectFaces(bitmap)
        val durationMs = System.currentTimeMillis() - startTime

        if (detectedFaces.isEmpty()) {
            withContext(Dispatchers.Main) {
                binding.statusText.text = "Nenhum rosto detectado na imagem ($durationMs ms)."
                binding.overlayView.clear()
                ttsEngine.speak("Nenhum rosto encontrado.")
            }
            return@withContext
        }

        // 2. Sort faces horizontally from left to right across the screen
        val sortedFaces = detectedFaces.sortedBy { it.boundingBox.left }

        val recognizedList = mutableListOf<RecognizedFace>()
        val identifiedNames = mutableListOf<String>()

        // 3. On-Device Feature Extraction & Matching for each detected face
        for (face in sortedFaces) {
            val embedding = faceNet.getEmbedding(face.croppedBitmap)
            val match = repository.matchFace(embedding, threshold = 0.50f)

            when (match) {
                is MatchResult.Match -> {
                    recognizedList.add(RecognizedFace(face.boundingBox, match.name, match.relationship, match.similarity))
                    identifiedNames.add(match.name)
                }
                is MatchResult.Unknown -> {
                    recognizedList.add(RecognizedFace(face.boundingBox, "Desconhecido", "", match.maxSimilarity))
                }
            }
        }

        val totalTime = System.currentTimeMillis() - startTime
        val audioPhrase = buildSpatialAudioPhrase(recognizedList)

        withContext(Dispatchers.Main) {
            // Draw visual bounding boxes on screen overlay
            binding.overlayView.setFaces(recognizedList, bitmap.width, bitmap.height)

            if (identifiedNames.isNotEmpty()) {
                val namesText = identifiedNames.joinToString(", ")
                binding.statusText.text = "✅ Identificado: $namesText ($totalTime ms | Toque no rosto)"
                ttsEngine.speak(audioPhrase)
            } else {
                val maxScore = recognizedList.map { it.confidence }.maxOrNull() ?: 0f
                val scorePct = String.format(java.util.Locale.US, "%.1f%%", maxScore * 100)
                binding.statusText.text = "🔍 Rosto não reconhecido (Maior sim: $scorePct | $totalTime ms)"
                ttsEngine.speak("Rosto não reconhecido.")
            }
        }
    }

    private fun buildSpatialAudioPhrase(faces: List<RecognizedFace>): String {
        val knownFaces = faces.filter { it.name != "Desconhecido" }
        if (knownFaces.isEmpty()) {
            return "Rosto não reconhecido."
        }

        val descriptions = knownFaces.map { formatPersonDescription(it.name, it.relationship) }

        return if (descriptions.size == 1) {
            "À sua frente está ${descriptions[0]}."
        } else {
            val allButLast = descriptions.dropLast(1).joinToString(", ")
            val last = descriptions.last()
            "Da esquerda para a direita, aparecem $allButLast e $last."
        }
    }

    private fun formatPersonDescription(name: String, relationship: String): String {
        if (relationship.isBlank()) return name
        val rel = relationship.trim().lowercase()
        if (rel.startsWith("seu ") || rel.startsWith("sua ") || rel.startsWith("o ") || rel.startsWith("a ")) {
            return "$rel $name"
        }
        if (rel.contains("piloto") || rel.contains("professor") || rel.contains("doutor") || rel.contains("diretor")) {
            return "o $rel $name"
        }
        return if (rel.endsWith("a")) "sua $rel $name" else "seu $rel $name"
    }

    private fun showRegisterDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_person, null)
        val etName = dialogView.findViewById<EditText>(R.id.etPersonName)
        val etRelationship = dialogView.findViewById<EditText>(R.id.etRelationship)
        val btnGallery = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickGallery)
        val btnCapturePhoto = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCapturePhoto)
        registrationDialogPreview = dialogView.findViewById(R.id.ivPreview)

        pendingRegistrationBitmap = null

        btnGallery.setOnClickListener {
            pickGalleryLauncher.launch("image/*")
        }

        btnCapturePhoto.setOnClickListener {
            lifecycleScope.launch {
                var captured: Bitmap? = null
                if (stream != null) {
                    val result = stream?.capturePhoto()
                    val photoData = result?.getOrNull()
                    if (photoData != null) {
                        captured = processPhotoDataToBitmap(photoData)
                    }
                }
                if (captured == null) {
                    captured = createTestFaceBitmap("Novo Cadastro")
                }
                pendingRegistrationBitmap = captured
                registrationDialogPreview?.setImageBitmap(captured)
            }
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Salvar") { _, _ ->
                val name = etName.text.toString()
                val relationship = etRelationship.text.toString()
                val bmp = pendingRegistrationBitmap
                if (name.isNotBlank() && bmp != null) {
                    lifecycleScope.launch {
                        val success = repository.registerPerson(name, relationship, bmp)
                        if (success) {
                            updateEnrolledBadge()
                            val count = repository.getEnrolledCount()
                            binding.statusText.text = "Cadastro de '$name' realizado com sucesso! Total: $count"
                            Toast.makeText(this@MainActivity, "Rosto de $name cadastrado!", Toast.LENGTH_SHORT).show()
                            ttsEngine.speak("$name cadastrado com sucesso!")
                        } else {
                            Toast.makeText(this@MainActivity, "Erro ao cadastrar rosto", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Por favor, digite o nome e escolha uma foto.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showViewFacesDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_view_faces, null)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tvCountSummary)
        val rvFaces = dialogView.findViewById<RecyclerView>(R.id.rvRegisteredFaces)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvEmptyState)

        val enrolledList = repository.getEnrolledPersons()
        tvSummary.text = "Total de ${enrolledList.size} pessoa(s) cadastrada(s) localmente."

        if (enrolledList.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvFaces.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvFaces.visibility = View.VISIBLE
        }

        var adapter: RegisteredFacesAdapter? = null
        adapter = RegisteredFacesAdapter(enrolledList) { recordToDelete ->
            AlertDialog.Builder(this)
                .setTitle("Remover Cadastro")
                .setMessage("Deseja remover o rosto cadastrado de '${recordToDelete.name}'?")
                .setPositiveButton("Excluir") { _, _ ->
                    lifecycleScope.launch {
                        repository.deletePerson(recordToDelete)
                        updateEnrolledBadge()
                        val updatedList = repository.getEnrolledPersons()
                        adapter?.updateData(updatedList)
                        tvSummary.text = "Total de ${updatedList.size} pessoa(s) cadastrada(s) localmente."
                        if (updatedList.isEmpty()) {
                            tvEmpty.visibility = View.VISIBLE
                            rvFaces.visibility = View.GONE
                        }
                        Toast.makeText(this@MainActivity, "Cadastro de '${recordToDelete.name}' removido.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        rvFaces.layoutManager = LinearLayoutManager(this)
        rvFaces.adapter = adapter

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun processPhotoDataToBitmap(photo: PhotoData): Bitmap {
        return when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> {
                val byteArray = ByteArray(photo.data.remaining())
                photo.data.get(byteArray)
                val exifInfo = getExifInfo(byteArray)
                val transform = getTransform(exifInfo)
                val rawBitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                applyTransform(rawBitmap, transform)
            }
        }
    }

    private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
        return try {
            ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
        } catch (e: IOException) {
            null
        }
    }

    private fun getTransform(exifInfo: ExifInterface?): Matrix {
        val matrix = Matrix()
        if (exifInfo == null) return matrix
        when (exifInfo.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        return matrix
    }

    private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
        if (matrix.isIdentity) return bitmap
        return try {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed != bitmap) bitmap.recycle()
            transformed
        } catch (e: OutOfMemoryError) {
            bitmap
        }
    }

    private fun createTestFaceBitmap(label: String): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#1E293B"))

        val paint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Draw face head circle
        canvas.drawCircle(300f, 260f, 130f, paint)

        // Draw eyes
        paint.color = Color.BLACK
        canvas.drawCircle(250f, 240f, 16f, paint)
        canvas.drawCircle(350f, 240f, 16f, paint)

        // Draw mouth
        paint.strokeWidth = 8f
        paint.style = Paint.Style.STROKE
        canvas.drawArc(240f, 260f, 360f, 320f, 0f, 180f, false, paint)

        // Text
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 32f
        canvas.drawText("Teste Synth: $label", 140f, 500f, paint)

        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsEngine.shutdown()
        stream?.stop()
        session?.stop()
    }
}
