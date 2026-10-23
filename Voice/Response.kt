package com.example.optimus.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.optimus.helper.State
import com.example.optimus.voice.LLM.llmScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

object Response {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var speaking = false
    private var activeUtterances = 0

    private var pendingSpeech: String? = null
    private var streamingFinished = false
    private var streamingCompleteCallback: (() -> Unit)? = null
    private var lastStreamingUtteranceId: String? = null
    private val typeWriterChannel = Channel<String>(Channel.UNLIMITED)
    private var typeWriterJob: Job? = null
    private var streamEnded = false


    private fun ensureTypewriterRunning() {
        if (typeWriterJob?.isActive == true) return // Don't restart if already running
        typeWriterJob = llmScope.launch(Dispatchers.Main) {
            for (chunk in typeWriterChannel) {
                val safeChunk = chunk.replace("\r", "")
                safeChunk.forEach { char ->
                    State.aiResponse.value = State.aiResponse.value + char
                    delay(42)
                }
            }
        }
    }


    fun notifyStreamingEnded() {
        streamEnded = true
    }

    fun init(context: Context) {
        if (tts != null) return

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("OPTIMUS", "TTS: Language not supported")
                } else {
                    configureTTS()
                    setUtteranceListener()
                    isReady = true
                    Log.d("OPTIMUS", "✅ TTS Initialized & Ready")

                    pendingSpeech?.let {
                        speak(it)
                        pendingSpeech = null
                    }
                }
            } else {
                Log.e("OPTIMUS", "TTS: Initialization failed")
            }
        }
    }

    private fun configureTTS() {
        tts?.setPitch(1.0f)
        tts?.setSpeechRate(1.0f)
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("""#+"""), "")         // Removes # and ## (headers)
            .replace(Regex("""\*+"""), "")         // Removes ** (bold)
            .replace(Regex("""[`]"""), "")          // Removes backticks
            .replace(Regex("""https?://\S+"""), "") // Removes URLs
            .replace(Regex("""[\p{So}\p{Cn}]"""), "") // Removes Emojis/Symbols
            .trim()
    }

    private fun setUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                activeUtterances++
                speaking = true
            }

            override fun onDone(utteranceId: String?) {
                activeUtterances--
                if (activeUtterances <= 0) {
                    activeUtterances = 0
                    speaking = false
                }

                handleStreamingCompletion(utteranceId)

                // Only invoke callback for standard 'speak' calls (optimus_voice_id)

            }

            override fun onError(utteranceId: String?) {
                activeUtterances = 0
                speaking = false
                Log.e("OPTIMUS", "TTS Error on: $utteranceId")
            }
        })
    }

    // =========================================================
    // 🗣️ SPEECH OPERATIONS
    // =========================================================

    fun speak(text: String) {
        val cleaned = cleanText(text)
        if (cleaned.isBlank()) return

        if (!isReady) {
            pendingSpeech = cleaned
            return
        }

        ensureTypewriterRunning()

        val id = "opt_${System.currentTimeMillis()}"
        lastStreamingUtteranceId = id

        tts?.speak(cleaned, TextToSpeech.QUEUE_ADD, null, id)

        typeWriterChannel.trySend("$cleaned ")
    }


    // =========================================================
    // 📡 STREAMING LOGIC (Preserved & Optimized)
    // =========================================================

    fun markStreamingComplete(onComplete: () -> Unit) {
        streamingFinished = true
        streamingCompleteCallback = onComplete
    }

    private fun handleStreamingCompletion(utteranceId: String?) {
        if (streamEnded && utteranceId == lastStreamingUtteranceId) {
            streamEnded = false

            val callback = streamingCompleteCallback
            streamingCompleteCallback = null

            Handler(Looper.getMainLooper()).post {
                callback?.invoke()
            }
        }
    }

    fun isSpeaking(): Boolean = speaking

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
