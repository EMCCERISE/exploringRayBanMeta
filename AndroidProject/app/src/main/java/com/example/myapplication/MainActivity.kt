package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.myapplication.databinding.ActivityMainBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String = ""

    // IP do Backend (Para emulador Android apontando para o PC local, use 10.0.2.2.
    // Para celular real, use o IP do computador na rede Wi-Fi, ex: 192.168.1.X)
    private val BACKEND_URL = "http://10.0.2.2:8000/upload"
    
    private val client = OkHttpClient()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true &&
            permissions[Manifest.permission.CAMERA] == true) {
            binding.logText.text = "Permissões concedidas. Pronto!"
        } else {
            Toast.makeText(this, "Permissões são necessárias", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioFilePath = "${externalCacheDir?.absolutePath}/test_audio.wav"

        checkPermissions()

        binding.btnCapture.setOnClickListener {
            if (isRecording) {
                stopRecordingAndSend()
            } else {
                startRecording()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startRecording() {
        binding.logText.text = "Gravando áudio... (No futuro, o Meta SDK cuidará disso)"
        binding.btnCapture.text = "PARAR\nE ENVIAR"
        binding.btnCapture.setBackgroundColor(android.graphics.Color.RED)
        
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(audioFilePath)
            prepare()
            start()
        }
        isRecording = true
    }

    private fun stopRecordingAndSend() {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        isRecording = false
        
        binding.btnCapture.text = "INICIAR\nGRAVAÇÃO"
        binding.btnCapture.setBackgroundColor(android.graphics.Color.parseColor("#3b82f6"))
        binding.logText.text = "Enviando para o backend..."
        
        // Simular a captura da câmera criando uma imagem falsa pequena
        // Quando integrar o Meta SDK, você pegará o byte[] da câmera do óculos.
        val dummyImageFile = createDummyImage()
        val audioFile = File(audioFilePath)

        sendDataToBackend(dummyImageFile, audioFile)
    }

    private fun createDummyImage(): File {
        val file = File(externalCacheDir, "dummy_image.jpg")
        FileOutputStream(file).use {
            // Apenas escrevemos um byte array correspondente a um pequeno JPEG (mesmo usado no simulador Python)
            val jpegHex = "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffdb0043010909090c0b0c180d0d1832211c213232323232323232323232323232323232323232323232323232323232323232323232323232323232323232323232323232ffc00011080001000103012200021101031101ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f928a28afcff003f"
            val bytes = jpegHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            it.write(bytes)
        }
        return file
    }

    private fun sendDataToBackend(imageFile: File, audioFile: File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("glasses_id", "RAYBAN_ANDROID_APP_123")
            .addFormDataPart(
                "image", 
                "capture.jpg",
                imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            )
            .addFormDataPart(
                "audio", 
                "record.m4a", // Usando .m4a porque gravamos em MPEG_4/AAC
                audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url(BACKEND_URL)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    binding.logText.text = "Erro no envio: ${e.message}\nVerifique o IP e servidor local."
                    Log.e("Upload", "Failed", e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        binding.logText.text = "Sucesso! Dado enviado para o computador."
                    } else {
                        binding.logText.text = "Erro servidor: ${response.code}"
                    }
                }
            }
        })
    }
}