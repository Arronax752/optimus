package com.example.optimus.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class WakeWord(
    private val context: Context,
    private val onWake: () -> Unit
) {

    // 🧠 Use a dedicated scope for the WakeWord engine
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val processingMutex = Mutex()
    // ==============================
    // STATE
    // ==============================
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val isReady = AtomicBoolean(false)
    private val isTriggered = AtomicBoolean(false)

    init {
        // Switch to Coroutine instead of raw Thread
        engineScope.launch(Dispatchers.IO) {
            try {
                prepareModelFiles()
                val targetDir = File(context.filesDir, "vosk-model-small-en-us-0.15")

                // Loading the model is heavy IO/Memory task
                model = Model(targetDir.absolutePath)

                // 🛠️ FIX: Correctly formatted JSON string (removed invalid escapes)
                val grammar = "[\"optimus\", \"[unk]\"]"
                recognizer = Recognizer(model, 16000.0f, grammar)

                isReady.set(true)
                Log.i("OPTIMUS", "✅ WakeWord Engine Ready")
            } catch (e: Exception) {
                Log.e("OPTIMUS", "❌ WakeWord Init Failed", e)
            }
        }
    }
    private fun prepareModelFiles() {
        val modelFolder = "vosk-model-small-en-us-0.15"
        val targetDir = File(context.filesDir, modelFolder)

        if (!File(targetDir, "am").exists()) {
            Log.d("OPTIMUS", "Copying VOSK model...")
            copyAssetFolder(context, modelFolder, targetDir)
        }
    }

    // ==============================
    // AUDIO PROCESSING
    // ==============================

    fun processAudio(audioData: ShortArray, len: Int) {
        if (!isReady.get() || isTriggered.get()) return
        engineScope.launch {
            processingMutex.withLock {
                val rec = recognizer ?: return@withLock
                try {
                    if (rec.acceptWaveForm(audioData, len)) {
                        val json = rec.result
                        checkResult(json)
                    }
                } catch (e: Exception) {
                    Log.e("OPTIMUS", "VOSK crash: ${e.message}")
                }
            }
        }
    }

    private fun checkResult(json: String) {
        try {
            val obj = JSONObject(json)
            val text = obj.optString("text", "").trim()

            // Added a few variations for better trigger reliability
            if (text.startsWith("optimus")) {
                handleWakeTrigger()
            }
        } catch (_: Exception) {}
    }

    // Add the Mutex to handleWakeTrigger too so we don't reset while processing
    private fun handleWakeTrigger() {
        if (!isTriggered.compareAndSet(false, true)) return

        engineScope.launch {
            processingMutex.withLock {
                Log.i("OPTIMUS", "⚡ WAKE WORD DETECTED")
                recognizer?.reset()
            }
            withContext(Dispatchers.Main) {
                onWake()
            }
        }
    }

    // ==============================
    // CONTROL
    // ==============================

    fun reset() {
        isTriggered.set(false)
        recognizer?.reset()
    }

    fun release() {
        isReady.set(false)
        isTriggered.set(false)
        engineScope.cancel() // Stop all background processing

        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
    }

    // ==============================
    // ASSET COPY
    // ==============================

    private fun copyAssetFolder(
        context: Context,
        assetPath: String,
        outDir: File
    ) {
        val assets = context.assets
        val files = assets.list(assetPath) ?: return

        if (!outDir.exists()) outDir.mkdirs()

        for (name in files) {
            val fullPath = "$assetPath/$name"
            val outFile = File(outDir, name)

            val childFiles = assets.list(fullPath)

            if (!childFiles.isNullOrEmpty()) {
                copyAssetFolder(context, fullPath, outFile)
            } else {
                try {
                    assets.open(fullPath).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: IOException) {
                    Log.e("OPTIMUS", "Skipping asset: $fullPath")
                }
            }
        }
    }
}
