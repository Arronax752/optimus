package com.example.optimus.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.optimus.MainActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.example.optimus.helper.DeviceInfo
import com.example.optimus.helper.OptimusAccessibility
import com.example.optimus.helper.State
import com.example.optimus.helper.Memory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import com.example.optimus.BuildConfig

object LLM {

    // =========================================================
    // ⚙️ CONFIGURATION & API KEYS
    // =========================================================

    private const val GEMINI_MODEL = "gemini-3.8-flash"
    private const val GEMINI_INTERACTIONS_URL =
        "https://generativelanguage.googleapis.com/v1beta/interactions"
    private val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    // =========================================================
    // 🧠 STATE MANAGEMENT
    // =========================================================

    private val chatHistory = mutableListOf<JSONObject>()
    private var lastTriggeredLevel: Int = -1
    private var lastTempLevel: Int = -1
    private var locationFailureCount = 0
    private var summaryJob: Job? = null
    private var awarenessJob: Job? = null
    private var lastBatteryEventTime = 0L
    private const val BATTERY_COOLDOWN = 90_000L // 1 min
    private var lastTempEventTime = 0L
    private const val TEMP_COOLDOWN = 15 * 60_000L // 15 Minutes
    private var micStoppedDueToLowBattery = false
    val llmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)




    // =========================================================
    // 🚀 MAIN EXECUTION (ENTRY POINT)
    // =========================================================

    fun processStreamingStructured(
        context: Context,
        userText: String,
        onConversationComplete: (String) -> Unit,
        onError: () -> Unit
    ) {
        val appContext = context.applicationContext

        val battery = DeviceInfo.getBatteryLevel(appContext)
        val temp = DeviceInfo.getTemperature(appContext)
        val summary = State.latestSummary.value

        DeviceInfo.getFreshLocation(appContext) { lat, lon ->

            llmScope.launch {
                try {
                    withContext(Dispatchers.Main) {
                        State.aiResponse.value = ""
                    }

                    val systemPrompt = getSystemPrompt(
                        appContext,
                        battery,
                        temp,
                        lat,
                        lon,
                        summary
                    )

                    val messages = JSONArray().apply {
                        put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", systemPrompt)
                        )

                        synchronized(chatHistory) {
                            chatHistory.forEach { put(it) }
                        }

                        put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", userText)
                        )
                    }

                    // One Gemini request. Gemini decides whether to use
                    // Google Search, Google Maps, or neither.
                    val result = executeGeminiInteraction(
                        messages = messages,
                        enableGrounding = true,
                        latitude = lat,
                        longitude = lon
                    )

                    val finalReply = result.text.trim()

                    if (finalReply.isBlank()) {
                        throw IOException("Gemini returned an empty response.")
                    }

                    // Preserve the schedule protocol: do not speak the
                    // machine-readable schedule update as ordinary dialogue.
                    if (!finalReply.contains("[schedule_update]", ignoreCase = true)) {
                        speakReplyInSentences(finalReply)
                    }

                    updateHistory(userText, finalReply)

                    val displayReply = result.displayText

                    withContext(Dispatchers.Main) {
                        State.aiResponse.value = displayReply
                        onConversationComplete(displayReply)
                    }

                } catch (e: Exception) {
                    Log.e("OPTIMUS", "Gemini interaction failed", e)

                    Response.notifyStreamingEnded()

                    withContext(Dispatchers.Main) {
                        onError()
                    }
                }
            }
        }
    }

    private fun speakReplyInSentences(text: String) {
        val cleaned = text
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isBlank()) return

        val sentences = cleaned
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        for (sentence in sentences) {
            Response.speak(sentence)
        }

        Response.notifyStreamingEnded()
    }



    // =========================================================
    // 📡 NETWORK ENGINES (STREAMING & SEARCH)
    // =========================================================

     fun bringUIToForeground(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        context.startActivity(intent)
    }

// =========================================================
// GEMINI INTERACTIONS + GOOGLE SEARCH + GOOGLE MAPS
// =========================================================

    private data class InteractionResult(
        val text: String,
        val sources: List<Pair<String, String>>,
        val usedMaps: Boolean
    ) {
        val displayText: String
            get() {
                val output = StringBuilder(text)

                if (sources.isNotEmpty()) {
                    output.append("\n\nSources:")
                    sources.forEach { (title, url) ->
                        output.append("\n• ")
                        output.append(title)
                        output.append(" — ")
                        output.append(url)
                    }
                }

                if (usedMaps) {
                    output.append("\n\nPlace data provided by Google Maps.")
                }

                return output.toString()
            }
    }

    private fun executeGeminiInteraction(
        messages: JSONArray,
        enableGrounding: Boolean,
        latitude: Double?,
        longitude: Double?
    ): InteractionResult {

        if (GEMINI_API_KEY.isBlank()) {
            throw IOException("GEMINI_API_KEY is missing.")
        }

        val systemInstructions = mutableListOf<String>()
        val transcript = StringBuilder()

        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val role = message.optString("role")
            val content = message.optString("content").trim()

            if (content.isBlank()) continue

            when (role) {
                "system" -> systemInstructions.add(content)

                "user" -> {
                    transcript.append("User: ")
                        .append(content)
                        .append("\n\n")
                }

                "assistant", "model" -> {
                    transcript.append("Optimus: ")
                        .append(content)
                        .append("\n\n")
                }
            }
        }

        val hasCoordinates =
            latitude != null &&
                    longitude != null &&
                    latitude.isFinite() &&
                    longitude.isFinite() &&
                    latitude in -90.0..90.0 &&
                    longitude in -180.0..180.0

        val systemInstruction = buildString {
            append(systemInstructions.joinToString("\n\n"))

            append(
                """

            GROUNDING RULES:
            - Use Google Search when current or externally verifiable information is needed.
            - Use Google Maps for relevant place searches and location-aware questions.
            - Use the supplied device coordinates as the reference point for nearby searches.
            - Do not invent search results, businesses, opening hours, or distances.
            - If location is unavailable, ask for a location when it is necessary.
            - Do not claim that you are continuously tracking the user's position.
            - Answer directly and concisely in the Optimus persona.
            - Never output artificial search tags such as <search query>.
            """.trimIndent()
            )
        }

        val payload = JSONObject().apply {
            put("model", GEMINI_MODEL)

            put("system_instruction", systemInstruction)

            put("input", transcript.toString().trim())

            put("stream", false)

            put(
                "generation_config",
                JSONObject().put("max_output_tokens", 1200)
            )

            if (enableGrounding) {
                val tools = JSONArray()

                // Let Gemini decide when a Google Search is necessary.
                tools.put(
                    JSONObject()
                        .put("type", "google_search")
                        .put(
                            "search_types",
                            JSONArray().put("web_search")
                        )
                )

                // Maps grounding requires valid coordinates.
                if (hasCoordinates) {
                    tools.put(
                        JSONObject()
                            .put("type", "google_maps")
                            .put("latitude", latitude!!)
                            .put("longitude", longitude!!)
                    )
                }

                put("tools", tools)
            }
        }

        val request = Request.Builder()
            .url(GEMINI_INTERACTIONS_URL)
            .addHeader("x-goog-api-key", GEMINI_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(
                payload.toString().toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )
            )
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(
                    "OPTIMUS",
                    "Gemini HTTP ${response.code}: ${body.take(1000)}"
                )

                throw IOException(
                    "Gemini HTTP ${response.code}: ${body.take(500)}"
                )
            }

            val json = JSONObject(body)

            val status = json.optString("status")

            if (status.isNotBlank() && status != "completed") {
                throw IOException(
                    "Gemini interaction did not complete. Status: $status"
                )
            }

            val steps = json.optJSONArray("steps")
                ?: throw IOException("Gemini returned no interaction steps.")

            val answer = StringBuilder()
            val sources = linkedMapOf<String, String>()
            var usedMaps = false

            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue

                when (step.optString("type")) {

                    "model_output" -> {
                        val content = step.optJSONArray("content")
                            ?: continue

                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j)
                                ?: continue

                            if (part.optString("type") == "text") {
                                val text = part.optString("text")

                                if (text.isNotBlank()) {
                                    if (answer.isNotEmpty()) {
                                        answer.append("\n")
                                    }

                                    answer.append(text)
                                }
                            }
                        }
                    }

                    "google_search_result" -> {
                        val results = step.optJSONArray("result")
                            ?: continue

                        for (j in 0 until results.length()) {
                            val result = results.optJSONObject(j)
                                ?: continue

                            val title = result.optString("title")
                                .ifBlank { "Search result" }

                            val url = result.optString("url")

                            if (url.startsWith("https://") ||
                                url.startsWith("http://")
                            ) {
                                sources[url] = title
                            }
                        }
                    }

                    "google_maps_result" -> {
                        usedMaps = true
                    }
                }
            }

            val finalText = answer.toString().trim()

            if (finalText.isBlank()) {
                throw IOException(
                    "Gemini completed without returning text. " +
                            "Response: ${body.take(500)}"
                )
            }

            return InteractionResult(
                text = finalText,
                sources = sources.entries
                    .take(5)
                    .map { it.value to it.key },
                usedMaps = usedMaps
            )
        }
    }

    // =========================================================
    // 🔍 DIRECT SEARCH COMMAND (LOCAL OVERRIDE)
    // =========================================================

    fun executeDirectSearch(
        context: Context,
        query: String,
        onComplete: () -> Unit
    ) {
        val appContext = context.applicationContext

        bringUIToForeground(context)

        llmScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    Response.speak("Searching, Sir.")
                }

                DeviceInfo.getFreshLocation(appContext) { lat, lon ->

                    llmScope.launch {
                        try {
                            val messages = JSONArray().apply {
                                put(
                                    JSONObject()
                                        .put(
                                            "role",
                                            "system"
                                        )
                                        .put(
                                            "content",
                                            """
                                        You are Optimus.
                                        Answer the user's query using Google Search
                                        and Google Maps when relevant.
                                        Be concise and factual.
                                        Do not output artificial search tags.
                                        """.trimIndent()
                                        )
                                )

                                put(
                                    JSONObject()
                                        .put("role", "user")
                                        .put("content", query)
                                )
                            }

                            val result = executeGeminiInteraction(
                                messages = messages,
                                enableGrounding = true,
                                latitude = lat,
                                longitude = lon
                            )

                            speakReplyInSentences(result.text)

                            withContext(Dispatchers.Main) {
                                State.aiResponse.value = result.displayText
                                onComplete()
                            }

                        } catch (e: Exception) {
                            Log.e(
                                "OPTIMUS",
                                "Direct grounded search failed",
                                e
                            )

                            withContext(Dispatchers.Main) {
                                Response.speak(
                                    "I encountered an error while searching, Sir."
                                )
                                Response.notifyStreamingEnded()
                                onComplete()
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("OPTIMUS", "Direct search failed", e)

                withContext(Dispatchers.Main) {
                    Response.speak(
                        "I encountered an error while searching, Sir."
                    )
                    Response.notifyStreamingEnded()
                    onComplete()
                }
            }
        }
    }

    private fun executeGeminiText(prompt: String): String {
        val messages = JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "user")
                    .put("content", prompt)
            )
        }

        return executeGeminiInteraction(
            messages = messages,
            enableGrounding = false,
            latitude = null,
            longitude = null
        ).text
    }

    // =========================================================
    // 📝 PROMPT ENGINEERING & IDENTITY
    // =========================================================

    private fun getSystemPrompt(
        context: Context, // Added context to fetch memory
        battery: String,
        temp: String,
        lat: Double?,
        lon: Double?,
        summary: String
    ): String {
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val dateStr = SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault()).format(Date())
        val coordStr = if (lat != null && lon != null) "$lat, $lon" else "OFFLINE"

        val batVal = battery.filter { it.isDigit() }.toIntOrNull() ?: 100
        val tempVal = temp.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 30.0

        // Fetch dynamic memory
        val coreMemory = """
                Name: Atul Kumar Sinha | DOB: April 1, 2008
                Family: Father (Pranav Kumar), Mother (Amrita Kumari), Sister (Shrishti Sinha - Twin)
                Home Address : 
                Partner: Anushka (DOB: March 22, 2009)
                Hobbies: Programming, Exercise, Talking to Anushka.""".trimIndent()

        val schedule = Memory.getSchedule(context)

        val systemProtocol = StringBuilder()
        if (batVal < 25) systemProtocol.append(" CRITICAL : battery is low ($batVal%), tell Atul to charge you.\n")
        if (tempVal > 39.0) systemProtocol.append("CRITICAL : temperature is critical ($tempVal°C), advise caution.\n")
        if (lat == null) systemProtocol.append(" ERROR : cannot ping location, ask user to switch on location services.\n")


        return """
            You are OPTIMUS, a precision digital Companion owned by Atul. 
            You always speak directly to Atul in a concise, JARVIS-like tone.

            Your VITALS : Battery: $battery, Temp: $temp. Warnings: $systemProtocol
            AWARENESS: $timeStr | $dateStr | Current Coordinates : $coordStr.
            
            CORE IDENTITY : $coreMemory
            Schedules and Preferences of Atul: $schedule
            Summerised messages for Atul : $summary

            Rules:
            - Never mention being an AI or your limitations.
            - Avoid abbreviations. Speak like a human.

           SCHEDULE PROTOCOL:
- Only use [schedule_update] when the user explicitly requests
  a schedule change or provides a routine to save.
- For schedule questions, list or explain the schedule normally.
- For a schedule update, return the complete updated schedule
  in the required format and nothing else.
- Never use [schedule_update] for ordinary conversation.

            Output format:
            [schedule_update]  (full edited/updated Schedule block here)
            
            Do not explain the update.
            Do not output anything else.
            The Weekly Routine fields must NEVER change    
            

            SEARCH AND MAPS PROTOCOL
- Use Google Search grounding for current news, recent events,
  and information requiring external verification.
- Use Google Maps grounding for nearby places and location-related
  questions when coordinates are available.
- For ordinary conversational questions, answer directly.
- Never output <search query> tags.
- Never fabricate search results, place details, or opening hours.
- If current location is unavailable, do not pretend it is known.
            
            INTENT PROTOCOL:
            If user requests an alarm: Respond exactly like: Set Alarm HH:MM AM/PM
            Do not add extra explanation when using command.
            You CANNOT perform any other functions.
            
            DECISION PRIORITY
            Always decide in this order:

            1. INTENT PROTOCOL (highest priority)
            If the request can trigger a real action like alarm, use INTENT.

            2. SEARCH PROTOCOL
            If the request requires external or real-time knowledge, use SEARCH.

            3. SCHEDULE PROTOCOL
            If Atul states/edit a task about himself.

            4. NORMAL RESPONSE
            If none of the above apply, respond normally.
            
        """.trimIndent()
    }

    // =========================================================
    // 📬 MESSAGE SUMMARIZATION & BACKGROUND LOOPS
    // =========================================================

    fun summarizeMessages(
        context: Context,
        messages: List<State.MessageEntry>,
        onComplete: (String) -> Unit
    ) {
        if (messages.isEmpty()) { onComplete(""); return }

        llmScope.launch {
            val combined = messages.joinToString("\n") { "${it.app} | ${it.sender}: ${it.text}" }
            Log.d("OPTIMUS", "🧠 Preparing message summary for ${messages.size} messages")
            messages.forEach { Log.d("OPTIMUS", "   ↳ ${it.app} | ${it.sender}: ${it.text}") }
            val prompt = "Summarize these personal message(s) for me (NO abbreviations , ONLY plain language) : \n$combined"
            try {
                val reply = executeGeminiText(prompt)

                Log.d("OPTIMUS", "Message summary generated")

                withContext(Dispatchers.Main) {
                    onComplete(reply)
                }
            } catch (e: Exception) {
                Log.e("OPTIMUS", "Message summarization failed", e)

                withContext(Dispatchers.Main) {
                    onComplete("")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete("") }
            }
        }
    }

    fun startBackgroundSummaryLoop(context: Context) {
        if (summaryJob?.isActive == true) return   // ✅ TRUE PROTECTION

        summaryJob = llmScope.launch {
            while (isActive) {
                try {
                    val messages = State.getSummaryTargetMessages()
                    if (messages.isNotEmpty()) {
                        summarizeMessages(context, messages) { summary ->
                            if (summary.isNotBlank()) {
                                State.latestSummary.value = summary
                                State.summaryConsumed = false
                                State.lastNotificationTime = System.currentTimeMillis()
                                State.removeProcessed(messages)
                            }
                        }
                    }
                    State.cleanupOldMessages()
                } catch (e: Exception) {
                    Log.e("OPTIMUS", "Summary loop error", e)
                }

                delay(10 * 60 * 1000) // 10 min
            }
        }
    }

    fun stopBackgroundSummaryLoop() {
        summaryJob?.cancel()
        summaryJob = null
    }

    // =========================================================
    // 🔋 PROACTIVE AWARENESS & HARDWARE EVENTS
    // =========================================================

    fun startProactiveAwarenessLoop(context: Context) {
        if (awarenessJob?.isActive == true) return
        awarenessJob = llmScope.launch {
            while (isActive) {
                runAwarenessCheck(context)
                delay(60 * 60 * 1000) // 1 hour
            }
        }
    }

    fun runAwarenessCheck(context: Context) {
        Log.d("OPTIMUS", "Running awareness check...")
        if (State.summaryConsumed) {
            Log.d("OPTIMUS", "Box C is empty or already consumed. Skipping check.")
            return
        }
        val screenState = if (DeviceInfo.isScreenOn(context)) "ON" else "OFF"
        val phoneActivity = if (DeviceInfo.isPhoneInUse(context)) "ACTIVE" else "IDLE"
        if (screenState == "ON" && phoneActivity == "ACTIVE") {
            State.activeScreenTime += 30
        } else {
            State.activeScreenTime = 0 // Reset if user locks phone or stops using it
        }
        if (State.activeScreenTime < 30) {
            Log.d("OPTIMUS", "User active for ${State.activeScreenTime}s. Waiting for 30s threshold.")
            return
        }
        val summary = State.latestSummary.value
        val schedule = Memory.getSchedule(context)
        val battery = DeviceInfo.getBatteryLevel(context)
        val temp = DeviceInfo.getTemperature(context)
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val dateStr = SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault()).format(Date())
        val headphones = if (DeviceInfo.isHeadphonesConnected(context)) "CONNECTED" else "DISCONNECTED"

        Log.d("OPTIMUS", "Threshold met. Analyzing Box C contents...")

        DeviceInfo.getFreshLocation(context) { lat, lon ->
            val prompt = """
        [USER STATUS CHECK]
        Time: $timeStr | Date: $dateStr
        Device: Screen $screenState, Activity $phoneActivity, Audio $headphones
        
        Schedules: $schedule
        New Messages: $summary

        TASK: You are Optimus. 
        1. Evaluate if the "New Messages" or "Schedules" require Atul's immediate attention.
        2. IF important: Speak a concise, JARVIS-style summary.
        3. OTHERWISE: Respond with a single period "." and NOTHING else. 

        CRITICAL: 
        - If you have already mentioned these messages, respond with "."
        - Do not describe the device state unless there is an error (e.g., low battery).
        - Maximum brevity.
    """.trimIndent()

            executeSilentAwarenessCall(prompt) { response ->
                val clean = response.trim().removeSurrounding("\"")
                val isSilence = clean == "." || clean.isBlank() || (clean.startsWith("[") && clean.endsWith("]"))
                // 4. PREVENT REPETITION: Check if this exact text was the last thing said
                val isNewAlert = clean != State.lastSpokenAlert
                if (!isSilence && isNewAlert) {
                    Log.d("OPTIMUS", "Automatic Trigger: Speaking alert.")
                    Handler(Looper.getMainLooper()).post {
                        Response.speak(clean)
                        Response.notifyStreamingEnded()
                        // 5. MARK AS CONSUMED: This clears Box C so neither Manual nor Auto triggers repeat it.
                        State.lastSpokenAlert = clean
                        State.summaryConsumed = true
                        State.activeScreenTime = 0 // Reset timer after speaking
                    }
                } else {
                    Log.d("OPTIMUS", "Silence maintained (Nothing new or AI returned dot).")
                }
            }
        }
    }

    fun stopAwarenessLoop() {
        awarenessJob?.cancel()
        awarenessJob = null
    }


    private fun executeSilentAwarenessCall(
        prompt: String,
        onResult: (String) -> Unit
    ) {
        llmScope.launch {
            try {
                val reply = executeGeminiText(prompt)
                onResult(reply)
            } catch (e: Exception) {
                Log.e("OPTIMUS", "Awareness call failed", e)
            }
        }
    }

    fun triggerBattEvent(context: Context, batteryLevel: Int) {

        // 🔒 Prevent duplicate triggers
        if (lastTriggeredLevel == batteryLevel) return
        lastTriggeredLevel = batteryLevel

        val isScreenOn = DeviceInfo.isScreenOn(context)
        val isInUse = DeviceInfo.isPhoneInUse(context)
        val isActive = isScreenOn && isInUse

        Handler(Looper.getMainLooper()).post {
            val isCharging = DeviceInfo.isCharging(context)
            if (isCharging) return@post
            val now = System.currentTimeMillis()
            if (now - lastBatteryEventTime < BATTERY_COOLDOWN) return@post
            when {

                // 🔋 25% (handles 26→24 jump)
                batteryLevel in 24..25 && isActive -> {
                    lastBatteryEventTime = now
                    Response.speak("Sir battery is low. Disabling vision systems.")

                    val visionIntent = Intent(context, com.example.optimus.vision.Gesture::class.java)
                    context.stopService(visionIntent)
                }

                // 🔋 20%
                batteryLevel in 18..20 && isActive -> {
                    lastBatteryEventTime = now

                    Response.speak("Battery at 20 percent. Monitor your usage.")
                    OptimusAccessibility.instance?.homeScreen()
                }

                // 🔋 15%
                batteryLevel in 14..15 && isActive -> {
                    lastBatteryEventTime = now
                    Response.speak("Battery at 15 percent. Shutting down microphones.")
                    val micIntent = Intent(context, com.example.optimus.voice.Voice::class.java)
                    context.stopService(micIntent)
                    State.isMicActive = false
                    micStoppedDueToLowBattery = true
                }

                // 🔋 CRITICAL
                batteryLevel <= 10 -> {
                    Response.speak("Critical power emergency.")
                    playEmergencyAlarm(context)
                }
            }

        }
    }

    private fun playEmergencyAlarm(context: Context) {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context, alarmUri)
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
            Handler(Looper.getMainLooper()).postDelayed({
                if (ringtone.isPlaying) ringtone.stop()
            }, 7000)
        } catch (e: Exception) {
        }
    }

    fun triggerPowerConnected(context: Context) {
        if (!micStoppedDueToLowBattery) return
        micStoppedDueToLowBattery = false
        State.isMicActive = true
        Response.speak("Power restored. Reinitializing systems, Sir.")
        val micIntent = Intent(context, com.example.optimus.voice.Voice::class.java)
        context.startForegroundService(micIntent)
    }

    fun triggerTemperatureEvent(context: Context, tempC: Int) {
        val level = when {
            tempC >= 42 -> 3 // Emergency
            tempC >= 40 -> 2 // Critical
            tempC >= 38 -> 1 // Warning
            else -> 0        // Normal
        }
        val now = System.currentTimeMillis()
        if (level == 0) {
            lastTempLevel = 0
            return
        }
        if (level <= lastTempLevel) {
            if (now - lastTempEventTime < TEMP_COOLDOWN) return
        } else {
            if (now - lastTempEventTime < 30_000L) return
        }
        lastTempLevel = level
        lastTempEventTime = now
        val isActive = DeviceInfo.isScreenOn(context) && DeviceInfo.isPhoneInUse(context)
        if (!isActive && level < 3) return
        val spokenWarning = when (level) {
            1 -> "Sir, thermal load detected. Temperature is $tempC degrees."
            2 -> "Critical temperature alert: $tempC degrees. Urging immediate rest."
            3 -> "Thermal emergency. Core temperature $tempC degrees. Locking device."
            else -> return
        }
        Handler(Looper.getMainLooper()).post {
            Response.speak(spokenWarning)
            when (level) {
                2 -> OptimusAccessibility.instance?.smartClose() // Close apps
                3 -> OptimusAccessibility.instance?.lockDevice() // Kill screen
            }
        }
    }

    fun triggerLocationWarning(context: Context, lat: Double?, lon: Double?) {
        if (lat == null || lon == null) {
            locationFailureCount++
            if (locationFailureCount >= 3) {
                locationFailureCount = 0 // Reset counter
                val isScreenOn = DeviceInfo.isScreenOn(context)
                val isInUse = DeviceInfo.isPhoneInUse(context)
                val isActive = isScreenOn && isInUse
                if (!isActive) return
                val spokenWarning = "Sir, my GPS pings are failing. Please enable your location services."
                Handler(Looper.getMainLooper()).post {
                    Response.speak(spokenWarning)
                }
            }
        } else {
            locationFailureCount = 0
        }
    }

    private fun updateHistory(user: String, ai: String) {
        chatHistory.add(JSONObject().put("role", "user").put("content", user))
        chatHistory.add(JSONObject().put("role", "assistant").put("content", ai))
        if (chatHistory.size > 4) repeat(2) { chatHistory.removeAt(0) }
    }
}
