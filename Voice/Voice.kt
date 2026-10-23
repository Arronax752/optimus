@file:Suppress("DEPRECATION")

package com.example.optimus.voice

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.optimus.R
import com.example.optimus.helper.DeviceInfo
import com.example.optimus.helper.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class Voice : Service() {

    // =========================================================
    // 🧠 CORE STATE & COMPONENTS
    // =========================================================
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var mainHandler: Handler
    private val commandProcessor = CommandProcessor(this)
    private var isServiceRunning = false
    private var isWakeLoopActive = false
    private var isListening = false
    private var restarting = false

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wakeTriggered = false
    private val wakeLockObj = Any()

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Audio & Speech components
    private lateinit var wakeWord: WakeWord
    private var audioRecord: AudioRecord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent

    // 🛠️ FIX: Store telephony listeners so we can unregister them later
    @RequiresApi(Build.VERSION_CODES.S)
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    // =========================================================
    // 🔁 SERVICE LIFECYCLE
    // =========================================================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mainHandler = Handler(Looper.getMainLooper())
        Response.init(this)
        mainHandler.post { setupSpeechRecognizer() }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Optimus::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)
        State.VoiceController.service = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            registerCallListener()
        } else {
            Log.e("OPTIMUS", "READ_PHONE_STATE not granted")
        }

        if (isServiceRunning) return START_STICKY
        isServiceRunning = true

        observeStatusToggles()

        if (!::wakeWord.isInitialized) {
            wakeWord = WakeWord(this) { onWakeDetected() }
        }

        if (State.currentStatus.value != "IDLE") {
            startWakeWordLoop()
        }

        maintainWakeLock()
        startMicWatchdog()

        return START_STICKY
    }

    private fun observeStatusToggles() {
        scope.launch {
            State.currentStatus.collect { status ->
                when (status) {

                    "IDLE" -> {
                        Log.d("OPTIMUS", "🔴 STATE → IDLE")

                        stopWakeWordLoop()

                        mainHandler.post { resetSpeechRecognizer() }

                        isListening = false
                        wakeTriggered = false
                    }

                    "WAITING" -> {
                        Log.d("OPTIMUS", "🟢 STATE → WAITING")

                        // ❌ DO NOT start here automatically
                        // Let restartWakeWord() or manual recovery handle it
                    }
                }
            }
        }
    }

    fun forceMicRecovery() {
        Log.w("OPTIMUS", "🛠️ FORCING FULL MIC RECOVERY")

        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager

            // 1. STOP everything FIRST
            State.currentStatus.value = "IDLE"
            stopWakeWordLoop()

            // 2. Reset audio system
            am.mode = AudioManager.MODE_NORMAL
            am.isMicrophoneMute = false
            am.isSpeakerphoneOn = false

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {}

            audioRecord = null

            mainHandler.post {
                resetSpeechRecognizer()
            }

            isListening = false
            wakeTriggered = false

            // 3. START CLEAN
            scope.launch {
                delay(500.milliseconds) // MIUI fix
                State.currentStatus.value = "WAITING"
                restartWakeWord()
            }

            Log.d("OPTIMUS", "✅ MIC RECOVERY COMPLETE")

        } catch (e: Exception) {
            Log.e("OPTIMUS", "❌ Mic recovery failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        isWakeLoopActive = false
        isListening = false

        scope.cancel()

        mainHandler.post {
            speechRecognizer?.destroy()
        }

        if (::wakeWord.isInitialized) {
            wakeWord.release()
        }
        Response.shutdown()

        // 🛠️ FIX: Safely release WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        unregisterCallListener()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("OPTIMUS", "System attempt to clear RAM detected. Scheduling revival...")
        val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            this, 1, restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun maintainWakeLock() {
        scope.launch {
            while (isServiceRunning) {
                delay((5 * 60 * 1000L).milliseconds)
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                    Log.d("OPTIMUS", "🔋 WakeLock renewed")
                }
            }
        }
    }

    // =========================================================
    // 🔔 FOREGROUND & SYSTEM MANAGEMENT
    // =========================================================

    private fun startForegroundSafe() {
        val channelId = "OptimusVoiceService"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Voice")
            .setContentText("Voice system active")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("OptimusVoiceService", "Voice", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startMicWatchdog() {
        scope.launch {
            while (isServiceRunning) {
                delay(8000.milliseconds)
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val isVoipActive = am.mode == AudioManager.MODE_IN_COMMUNICATION

                if (isVoipActive && State.currentStatus.value != "IN_CALL") {
                    Log.d("OPTIMUS", "Detecting VoIP Call (WhatsApp/etc) via AudioMode")
                    State.currentStatus.value = "IN_CALL"
                    stopWakeWordLoop()
                }
                if (!isVoipActive && State.currentStatus.value == "IN_CALL") {
                    Log.d("OPTIMUS", "VoIP Call ended, recovering mic...")
                    onCallEndedCleanup()
                }
                if (State.currentStatus.value == "WAITING" && !isWakeLoopActive && !restarting && !isListening && !wakeTriggered){
                    Log.w("OPTIMUS", "Watchdog: WakeWord was dead. Reviving...")
                    startWakeWordLoop()
                }
            }
        }
    }

    // =========================================================
    // 👂 WAKE WORD ENGINE
    // =========================================================

    private fun startWakeWordLoop() {

        synchronized(wakeLockObj) {
            if (isWakeLoopActive) return
            isWakeLoopActive = true
        }

        scope.launch {
            Log.i("OPTIMUS", "👂 Waiting for wake word (VOSK)...")
            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

            if (ContextCompat.checkSelfPermission(this@Voice, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                stopWakeWordLoop()
                return@launch
            }

            // 🛠️ REMOVED: AudioFocusRequest here was causing the "Reviving" loop.
            // Vosk can listen in the background without holding Focus.

            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                return@launch
            }

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("OPTIMUS", "❌ AudioRecord init failed")
                    restartWakeWord()
                    return@launch
                }

                audioRecord?.startRecording()
                val buffer = ShortArray(1024)
                var silentCounter = 0
                while (isWakeLoopActive && isServiceRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (read > 0) {
                        silentCounter = 0
                        wakeWord.processAudio(buffer, read)
                    } else {
                        silentCounter++
                    }
                    if (silentCounter > 50) {
                        Log.w("OPTIMUS", "⚠️ VOSK seems stuck → resetting")
                        wakeWord.reset()
                        silentCounter = 0
                    }
                }
            } catch (e: Exception) {
                Log.e("OPTIMUS", "Mic crash: ${e.message}")
                restartWakeWord()
            }
        }
    }

    private fun stopWakeWordLoop() {
        synchronized(wakeLockObj) {
            if (!isWakeLoopActive) return
            isWakeLoopActive = false
        }
        try {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
        try {
            audioRecord?.let { ar ->
                if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) ar.stop()
                ar.release()
            }
        } catch (e: Exception) {
            Log.e("OPTIMUS", "Error stopping mic: ${e.message}")
        }
        audioRecord = null
        Log.d("OPTIMUS", "🛑 WakeWord loop stopped safely")
    }

    private fun restartWakeWord() {

        if (restarting || State.currentStatus.value == "IDLE") return
        restarting = true
        // 1. Force state reset
        State.currentStatus.value = "WAITING"
        wakeTriggered = false
        isListening = false

        // 2. Reset the engine flags
        if (::wakeWord.isInitialized) wakeWord.reset()

        // 3. Stop current loop
        stopWakeWordLoop()

        scope.launch {
            // 🛠️ INCREASE DELAY: Give TTS time to fully release the hardware
            delay(100.milliseconds)
            if (State.currentStatus.value != "IDLE") {
                Log.d("OPTIMUS", "♻️ WakeWord loop reviving now...")
                startWakeWordLoop()
            }
            restarting = false
        }
    }

    private fun onWakeDetected() {
        if (wakeTriggered) return
        wakeTriggered = true
        stopWakeWordLoop()
        mainHandler.post { startGoogleListening() }
    }

    // =========================================================
    // 🎤 SPEECH RECOGNITION (STT)
    // =========================================================

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
        }
        speechRecognizer?.setRecognitionListener(recognitionListener)
    }

    private fun startGoogleListening() {
        State.aiResponse.value = ""
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        audioManager.requestAudioFocus(audioFocusRequest!!)

        mainHandler.post {
            try {
                Log.d("OPTIMUS", "🔔 Google Listening...")
                State.currentStatus.value = "LISTENING"
                isListening = true
                resetSpeechRecognizer()
                speechRecognizer?.startListening(speechIntent)
            } catch (e: Exception) {
                Log.e("OPTIMUS", "Google STT failed: ${e.message}")
                restartWakeWord()
            }
        }
    }

    private fun resetSpeechRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = null
        setupSpeechRecognizer()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.d("OPTIMUS", "🎤 Ready for speech") }
        override fun onBeginningOfSpeech() { Log.d("OPTIMUS", "🗣️ User started speaking") }
        override fun onEndOfSpeech() {
            if (!isListening) return
            Log.d("OPTIMUS", "🛑 User stopped speaking")
            isListening = false
        }
        override fun onError(error: Int) {
            isListening = false
            if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                Log.d("OPTIMUS", "🤷 No speech detected")
                restartWakeWord()
                return
            }
            Log.e("OPTIMUS", "❌ STT Error: $error")
            restartWakeWord()
        }
        @RequiresApi(Build.VERSION_CODES.R)
        override fun onResults(results: Bundle?) {
            isListening = false
            val userText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!userText.isNullOrBlank()) {
                Log.d("OPTIMUS", "👂 User said: $userText")
                processUserCommand(userText)
            } else {
                restartWakeWord()
            }
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // =========================================================
    // 🧠 COMMAND FLOW & LOGIC
    // =========================================================

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processUserCommand(text: String) {
        State.currentStatus.value = "EXECUTING"
        if (text.isBlank()) {
            restartWakeWord()
            return
        }
        Log.d("OPTIMUS", "Processing: $text")
        val hasInternet = DeviceInfo.isInternetAvailable(this)
        if (!hasInternet) State.isConversationLocked.value = false

        if (State.isWaitingForContactSelection.value) {
            mainHandler.post {
                commandProcessor.handleContactSelection(text) { restartWakeWord() }
            }
            return
        }

        val isLocal = commandProcessor.hasTriggerWord(text)
        if (isLocal) {
            Log.d("OPTIMUS", "⚡ LOCAL COMMAND (priority)")
            mainHandler.post {
                commandProcessor.executeLocalAction(text) {
                    if (State.isWaitingForContactSelection.value) {
                        mainHandler.postDelayed({
                            isListening = false
                            wakeTriggered = true
                            startGoogleListening()
                        }, 600)
                    } else {
                        restartWakeWord()
                    }
                }
            }
            return
        }

        if (!hasInternet) {
            Log.e("OPTIMUS", "🚫 No Internet → AI BLOCKED")
            Response.speak("No internet sir. Using offline mode.")
            Response.markStreamingComplete {
                restartWakeWord()
            }
            return
        }

        Log.d("OPTIMUS", "📡 Sending to LLM...")
        LLM.processStreamingStructured(
            context = this,
            userText = text,
            onConversationComplete = { fullReply ->
                mainHandler.post {
                    val normalized = fullReply.trim()
                    if (normalized.isEmpty()) {
                        Response.speak("I didn't catch that sir.")
                        Response.markStreamingComplete {
                            restartWakeWord()
                        }
                        return@post
                    }
                    Log.d("OPTIMUS", "🧠 RAW LLM OUTPUT $normalized")
                    val handled = commandProcessor.performAIIntent(normalized) {
                        if (State.isConversationLocked.value) {
                            mainHandler.postDelayed({
                                isListening = false
                                wakeTriggered = true
                                startGoogleListening()
                            }, 500)
                        } else {
                            restartWakeWord()
                        }
                    }
                    if (!handled) {
                        Response.markStreamingComplete {
                            if (normalized.contains("?")) {
                                State.isConversationLocked.value = true
                                wakeTriggered = true
                                mainHandler.postDelayed({ startGoogleListening() }, 400)
                            } else {
                                State.isConversationLocked.value = false
                                restartWakeWord()
                            }
                        }
                    }
                }
            },
            onError = {
                mainHandler.post {
                    Log.e("OPTIMUS", "⚠️ RECOVERY MODE ACTIVE")
                    State.currentStatus.value = "WAITING" // Reset UI color
                    State.isConversationLocked.value = false
                    Response.speak("Internet issue sir. Offline mode ready.")
                    restartWakeWord()
                }
            }
        )
    }

    // =========================================================
    // 📴 CALL HANDLING
    // =========================================================

    private fun registerCallListener() {
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback!!)
        } else {
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            telephonyManager.listen(phoneStateListener!!, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterCallListener() {
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        } else {
            phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d("OPTIMUS", "📲 Incoming Call")
                State.currentStatus.value = "INCOMING_CALL"
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (State.currentStatus.value != "IN_CALL") {
                    Log.d("OPTIMUS", "📞 Call Active")
                    State.currentStatus.value = "IN_CALL"
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d("OPTIMUS", "📴 Call Idle")
                if (State.currentStatus.value in listOf("IN_CALL", "CALLING", "INCOMING_CALL")) {
                    onCallEndedCleanup()
                }
            }
        }
    }

    private fun onCallEndedCleanup() {
        Log.d("OPTIMUS", "🔄 FULL AUDIO RESET")
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager

            // 🛠️ FIX: Force UI Thread for SpeechRecognizer destroy
            mainHandler.post {
                resetSpeechRecognizer()
            }

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {}

            audioRecord = null
            am.mode = AudioManager.MODE_NORMAL
            am.isMicrophoneMute = false
            am.isSpeakerphoneOn = false

            isListening = false
            wakeTriggered = false
            State.currentStatus.value = "WAITING"

            mainHandler.postDelayed({
                Log.d("OPTIMUS", "🎤 Restarting WakeWord Engine")
                restartWakeWord()
            }, 1500)

            Log.d("OPTIMUS", "✅ Audio Recovered")
        } catch (e: Exception) {
            Log.e("OPTIMUS", "❌ Recovery failed: ${e.message}")
            restartWakeWord()
        }
    }
}
