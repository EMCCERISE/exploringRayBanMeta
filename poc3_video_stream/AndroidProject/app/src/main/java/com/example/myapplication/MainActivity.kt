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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var isStreaming = false
    private val WEBSOCKET_URL = "ws://192.168.15.8:8000/ws/stream"

    // Meta Wearables DAT SDK
    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var streamingJob: kotlinx.coroutines.Job? = null

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

    // 2. Permissão Específica do Wearables DAT (Câmera do óculos)
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

        // Ajusta o botão para Iniciar/Parar Stream
        binding.btnStream.text = "INICIAR STREAM"
        binding.btnStream.setBackgroundColor(android.graphics.Color.parseColor("#4F46E5"))

        binding.btnStream.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
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
            binding.logText.text = "Ligando câmera do óculos..."
            session?.addStream(
                StreamConfiguration(
                    videoQuality = VideoQuality.HIGH,
                    frameRate = 15,
                    compressVideo = true
                )
            )?.onSuccess { addedStream ->
                stream = addedStream
                stream?.start()
                binding.logText.text = "Óculos Conectado! Câmera Pronta (15 FPS, HEVC)."
            }?.onFailure { error, _ ->
                binding.logText.text = "Erro no stream: ${error.description}"
            }
        }
    }

    private fun startStreaming() {
        if (stream == null) {
            binding.logText.text = "Erro: Câmera do óculos não está pronta."
            return
        }
        isStreaming = true
        binding.btnStream.text = "PARAR STREAM"
        binding.btnStream.setBackgroundColor(android.graphics.Color.parseColor("#EF4444"))
        binding.logText.text = "Conectando ao backend..."
        connectWebSocket()
    }

    private fun stopStreaming() {
        isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
        webSocket?.close(1000, "User stopped stream")
        webSocket = null
        binding.btnStream.text = "INICIAR STREAM"
        binding.btnStream.setBackgroundColor(android.graphics.Color.parseColor("#4F46E5"))
        binding.logText.text = "Stream interrompido pelo usuário."
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(WEBSOCKET_URL).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@MainActivity.webSocket = webSocket
                runOnUiThread {
                    binding.logText.text = "Conectado! Transmitindo vídeo a 15 FPS..."
                }

                // Coleta frames do óculos e envia via WebSocket
                streamingJob = lifecycleScope.launch {
                    try {
                        stream?.videoStream?.collect { videoFrame ->
                            if (!isStreaming) return@collect

                            // Copia os bytes do ByteBuffer de forma segura
                            val buffer = videoFrame.buffer.duplicate()
                            buffer.rewind()
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)

                            // Prepara mensagem com prefixo: 0 = CodecConfig, 1 = Frame comum
                            val messageBytes = ByteArray(bytes.size + 1)
                            messageBytes[0] = if (videoFrame.isCodecConfig) 0.toByte() else 1.toByte()
                            System.arraycopy(bytes, 0, messageBytes, 1, bytes.size)

                            // Envia bytes binários
                            webSocket.send(messageBytes.toByteString(0, messageBytes.size))
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Erro no processamento do videoStream", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    binding.logText.text = "Erro de conexão: ${t.message}"
                    stopStreaming()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    binding.logText.text = "Desconectado pelo servidor."
                    stopStreaming()
                }
            }
        }
        client.newWebSocket(request, listener)
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        streamingJob?.cancel()
        stream?.stop()
        session?.stop()
        webSocket?.close(1000, "App destroyed")
        webSocket = null
    }
}