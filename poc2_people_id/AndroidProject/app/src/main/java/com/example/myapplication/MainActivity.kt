package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()
    private var mediaPlayer: android.media.MediaPlayer? = null

    // IP do Backend da POC 2 - Altere para o IP do seu computador na rede
    private val BACKEND_URL = "http://192.168.15.8:8000/identify"

    // Meta Wearables DAT SDK
    private var session: DeviceSession? = null
    private var stream: Stream? = null

    // 1. Permissões do Android (Câmera, Microfone, Bluetooth)
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value == true }
        if (allGranted) {
            binding.logText.text = "Permissões concedidas. Inicializando SDK..."
            initializeWearablesSdk()
        } else {
            Toast.makeText(this, "Permissões de Câmera/Mic/Bluetooth são necessárias", Toast.LENGTH_LONG).show()
        }
    }

    // 2. Permissão Específica do Wearables DAT (Câmera do Óculos)
    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        if (status == PermissionStatus.Granted) {
            setupDeviceSession()
        } else {
            binding.logText.text = "Permissão da câmera do óculos negada."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()

        // Ajusta o botão para Identificar Pessoas
        binding.btnCapture.text = "IDENTIFICAR\nPESSOAS"
        binding.btnCapture.setBackgroundColor(android.graphics.Color.parseColor("#3b82f6"))

        binding.btnCapture.setOnClickListener {
            captureAndIdentify()
        }

        binding.btnRegister.setOnClickListener {
            showRegisterDialog()
        }
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
                    binding.logText.text = "App Registrado. Checando permissão..."
                    val result = Wearables.checkPermissionStatus(Permission.CAMERA)
                    val status = result.getOrNull()
                    
                    if (status == PermissionStatus.Granted) {
                        setupDeviceSession()
                    } else {
                        wearablesPermissionLauncher.launch(Permission.CAMERA)
                    }
                } else if (regState != com.meta.wearable.dat.core.types.RegistrationState.REGISTERING) {
                    binding.logText.text = "Registrando App na Meta..."
                    Wearables.startRegistration(this@MainActivity)
                }
            }
        }
    }

    private fun setupDeviceSession() {
        binding.logText.text = "Buscando óculos..."
        lifecycleScope.launch {
            Wearables.devices.collect { devices ->
                if (devices.isNotEmpty() && session == null) {
                    binding.logText.text = "Óculos encontrado! Conectando..."
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
                            binding.logText.text = "Erro ao conectar óculos: ${error.description}"
                        }
                }
            }
        }
    }

    private fun setupStream() {
        lifecycleScope.launch {
            binding.logText.text = "Ligando câmera do óculos em background..."
            session?.addStream(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 2))
                ?.onSuccess { addedStream ->
                    stream = addedStream
                    stream?.start()
                    binding.logText.text = "Óculos Conectado e Câmera Pronta!"
                }
                ?.onFailure { error, _ ->
                    binding.logText.text = "Erro no stream: ${error.description}"
                }
        }
    }

    private fun captureAndIdentify() {
        binding.logText.text = "📸 Capturando visão..."
        binding.btnCapture.isEnabled = false
        
        lifecycleScope.launch {
            if (stream == null) {
                binding.logText.text = "Erro: Câmera do óculos não está pronta."
                binding.btnCapture.isEnabled = true
                return@launch
            }
            
            val result = stream?.capturePhoto()
            val photoData = result?.getOrNull()
            val photoFile = if (photoData != null) {
                processPhotoData(photoData)
            } else {
                Log.e("MainActivity", "Falha ao capturar a foto do stream")
                null
            }
            
            if (photoFile != null) {
                binding.logText.text = "Imagem capturada. Identificando pessoas..."
                sendImageToBackend(photoFile)
            } else {
                binding.logText.text = "Erro: Câmera falhou. Usando dummy fallback..."
                sendImageToBackend(createDummyImage())
            }
        }
    }

    // Converte os dados brutos da câmera (seja Bitmap direto ou HEIC comprimido) para um JPEG
    private fun processPhotoData(photo: PhotoData): File? {
        val bitmap = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> {
                val byteArray = ByteArray(photo.data.remaining())
                photo.data.get(byteArray)
                val exifInfo = getExifInfo(byteArray)
                val transform = getTransform(exifInfo)
                decodeHeic(byteArray, transform)
            }
        }

        return try {
            val file = File(externalCacheDir, "glasses_capture.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            file
        } catch (e: Exception) {
            Log.e("MainActivity", "Erro ao salvar foto no disco", e)
            null
        }
    }

    private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
        return applyTransform(bitmap, transform)
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
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
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

    // Mantido apenas como fallback
    private fun createDummyImage(): File {
        val file = File(externalCacheDir, "dummy_image.jpg")
        FileOutputStream(file).use {
            val jpegHex = "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffdb0043010909090c0b0c180d0d1832211c213232323232323232323232323232323232323232323232323232323232323232323232323232323232323232323232323232ffc00011080001000103012200021101031101ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f928a28afcff003f"
            val bytes = jpegHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            it.write(bytes)
        }
        return file
    }

    private fun sendImageToBackend(imageFile: File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("glasses_id", "RAYBAN_ANDROID_APP_POC2")
            .addFormDataPart("image", "capture.jpg", imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url(BACKEND_URL)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    binding.logText.text = "Erro no envio: ${e.message}"
                    binding.btnCapture.isEnabled = true
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val jsonString = try {
                    response.body?.string() ?: ""
                } catch (e: IOException) {
                    ""
                }

                runOnUiThread {
                    binding.btnCapture.isEnabled = true
                    if (response.isSuccessful && jsonString.isNotEmpty()) {
                        try {
                            val json = org.json.JSONObject(jsonString)
                            val description = json.getString("text")
                            val audioUrl = json.getString("audio_url")
                            
                            binding.logText.text = description
                            
                            // Reconstrói URL completa do áudio
                            val fullAudioUrl = if (audioUrl.startsWith("http")) {
                                audioUrl
                            } else {
                                val baseUri = java.net.URI(BACKEND_URL)
                                "${baseUri.scheme}://${baseUri.host}:${baseUri.port}$audioUrl"
                            }
                            
                            playAudio(fullAudioUrl)
                        } catch (e: Exception) {
                            binding.logText.text = "Erro ao ler resposta: ${e.message}"
                        }
                    } else {
                        binding.logText.text = "Erro servidor ou corpo vazio: ${response.code}"
                    }
                }
            }
        })
    }

    private fun playAudio(url: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                setDataSource(url)
                setOnPreparedListener {
                    it.start()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("MainActivity", "Erro no MediaPlayer: what=$what, extra=$extra")
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Falha ao tocar áudio", e)
        }
    }

    private fun showRegisterDialog() {
        val context = this
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("Cadastrar Nova Pessoa")

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val nameInput = android.widget.EditText(context).apply {
            hint = "Nome (ex: João)"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }

        val relationInput = android.widget.EditText(context).apply {
            hint = "Parentesco/Relação (ex: Neto)"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(nameInput)
        layout.addView(relationInput)
        builder.setView(layout)

        builder.setPositiveButton("Tirar Foto") { dialog, _ ->
            val name = nameInput.text.toString().trim()
            val relationship = relationInput.text.toString().trim()
            if (name.isNotEmpty() && relationship.isNotEmpty()) {
                dialog.dismiss()
                captureAndRegister(name, relationship)
            } else {
                Toast.makeText(context, "Nome e parentesco são obrigatórios.", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    private fun captureAndRegister(name: String, relationship: String) {
        binding.logText.text = "📸 Capturando foto para cadastro..."
        binding.btnCapture.isEnabled = false
        binding.btnRegister.isEnabled = false

        lifecycleScope.launch {
            if (stream == null) {
                binding.logText.text = "Erro: Câmera do óculos não está pronta."
                binding.btnCapture.isEnabled = true
                binding.btnRegister.isEnabled = true
                return@launch
            }

            val result = stream?.capturePhoto()
            val photoData = result?.getOrNull()
            val photoFile = if (photoData != null) {
                processPhotoData(photoData)
            } else {
                Log.e("MainActivity", "Falha ao capturar a foto do stream")
                null
            }

            if (photoFile != null) {
                binding.logText.text = "Foto capturada! Enviando cadastro..."
                sendRegistrationToBackend(photoFile, name, relationship)
            } else {
                binding.logText.text = "Erro: Falha ao capturar foto para cadastro."
                binding.btnCapture.isEnabled = true
                binding.btnRegister.isEnabled = true
            }
        }
    }

    private fun sendRegistrationToBackend(imageFile: File, name: String, relationship: String) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "capture.jpg", imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .addFormDataPart("name", name)
            .addFormDataPart("relationship", relationship)
            .build()

        val registerUrl = BACKEND_URL.replace("/identify", "/register")

        val request = Request.Builder()
            .url(registerUrl)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    binding.logText.text = "Erro ao enviar cadastro: ${e.message}"
                    binding.btnCapture.isEnabled = true
                    binding.btnRegister.isEnabled = true
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val jsonString = try {
                    response.body?.string() ?: ""
                } catch (e: IOException) {
                    ""
                }

                runOnUiThread {
                    binding.btnCapture.isEnabled = true
                    binding.btnRegister.isEnabled = true
                    if (response.isSuccessful && jsonString.isNotEmpty()) {
                        try {
                            val json = org.json.JSONObject(jsonString)
                            val message = json.getString("message")
                            binding.logText.text = message
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            binding.logText.text = "Erro ao ler resposta de cadastro: ${e.message}"
                        }
                    } else {
                        val errorMsg = try {
                            org.json.JSONObject(jsonString).getString("detail")
                        } catch (e: Exception) {
                            "Erro servidor: ${response.code}"
                        }
                        binding.logText.text = errorMsg
                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        stream?.stop()
        session?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}