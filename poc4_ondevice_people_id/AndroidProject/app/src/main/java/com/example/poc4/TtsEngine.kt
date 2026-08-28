package com.example.poc4

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsEngine(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("pt", "BR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to default locale if Portuguese is not available
                tts?.language = Locale.getDefault()
            }
            isReady = true
            Log.d("TtsEngine", "TextToSpeech inicializado com sucesso!")
        } else {
            Log.e("TtsEngine", "Falha ao inicializar TextToSpeech. Status: $status")
        }
    }

    fun speak(text: String) {
        if (isReady && !text.isNull_or_empty()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID_${System.currentTimeMillis()}")
        } else {
            Log.w("TtsEngine", "TTS ainda não está pronto para falar: $text")
        }
    }

    private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
