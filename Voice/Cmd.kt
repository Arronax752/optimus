package com.example.optimus.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.optimus.helper.DeviceInfo
import com.example.optimus.helper.OptimusAccessibility
import com.example.optimus.helper.State
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommandProcessor(private val context: Context) {

    private val accessibility get() = OptimusAccessibility.instance

    private val localTriggerWords = setOf(
        "open", "close", "exit", "lock", "notification",
        "capture", "screenshot", "data", "wifi", "wi-fi",
        "hotspot", "brightness", "bluetooth", "cancel",
        "volume", "mute",
        "call", "play", "pause", "stop", "resume",
        "video", "media", "vision", "type", "message",
        "time", "date", "home screen","status","search", "alert on", "alert off", "alerts on", "alerts off"
    )

    fun hasTriggerWord(userText: String): Boolean {
        val text = userText.lowercase().trim()
        // Improved: check if any trigger word exists anywhere in the first two words
        return localTriggerWords.any { text.contains(it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun executeLocalAction(userText: String, onComplete: () -> Unit) {
        val fullText = userText.lowercase().trim()

        // Split commands using "and"
        val commands = fullText.split(" and ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        executeSequentially(commands, 0, onComplete)
    }

    private fun needsAccessibility(text: String): Boolean {
        return listOf(
            "open", "close", "exit", "lock", "notification",
            "capture", "screenshot", "data", "wifi", "wi-fi",
            "hotspot", "brightness", "bluetooth", "cancel",
            "volume", "mute",
            "call", "play", "pause", "stop", "resume",
            "video", "media", "type", "message",
            "time", "date", "home screen","search", "alert on", "alert off", "alerts on", "alerts off"
        ).any { text.contains(it) }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = context.packageName + "/" + OptimusAccessibility::class.java.name
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return setting.split(":").any { it.equals(service, true) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun executeSequentially(commands: List<String>, index: Int, finalCallback: () -> Unit) {

        if (index >= commands.size) {
            finalCallback()
            return
        }

        var hasMoved = false
        val next = fun() {
            if (hasMoved) return
            hasMoved = true
            executeSequentially(commands, index + 1, finalCallback)
        }

        val text = commands[index]

        if (needsAccessibility(text) && !isAccessibilityEnabled()) {
            Response.speak("I need accessibility permission sir.")
            openIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            next()
            return
        }

        // ================= CALL (fallback important)
        if (text == "call") {
            Response.speak("Who should I call?")
            State.isConversationLocked.value = true
            next()
            return
        }
        // ================= ROUTING
        when {
            text.contains("time") -> {
                val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                Response.speak("It's $time")
                next()
            }

            text.startsWith("date") -> {
                val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
                Response.speak("It's $date")
                next()
            }


            text.startsWith("open ") -> {
                val appName = text.removePrefix("open ").trim()
                val success = openAppSmart(appName)
                if (success) {
                    next()
                } else {
                    Response.speak("I couldn't find $appName")
                    next()
                }
            }

            text.contains("exit") || text.contains("close") -> {
                accessibility?.smartClose()
                Handler(Looper.getMainLooper()).postDelayed(next, 700)
            }

            text.contains("lock") || text.contains("lock screen") -> {
                accessibility?.lockDevice()
                next()
            }

            text.contains("capture") || text.contains("screenshot") -> {
                accessibility?.takeScreenshot()
                Handler(Looper.getMainLooper()).postDelayed({
                    next()
                }, 700)
            }

            text.contains("wifi") || text.contains("wi-fi") -> {
                accessibility?.toggleWifi()
                next()
            }

            text.contains("data") -> {
                accessibility?.toggleMobileData()
                next()
            }

            text.contains("hotspot") || text.contains("hot spot")-> {
                accessibility?.toggleHotspot()
                next()
            }

            text.contains("bluetooth") -> {
                accessibility?.toggleBluetooth()
                next()
            }

            text.startsWith("volume") || text.contains("mute") -> {
                handleVolumeAction(text)
                next()
            }

            text.startsWith("messages") || text.contains("notification") -> {
                handleNotificationAction { next() }
            }

            text.startsWith("call ") -> {
                handleCallAction(text.removePrefix("call ").trim(), next)
            }

            text.startsWith("play ") -> {
                playOnYouTube(text.removePrefix("play ").trim())
                next()
            }

            text.contains("vision") -> {
                handleVisionToggle {
                    next()
                }
            }

            text.startsWith("type ") -> {
                val content = text.removePrefix("type ").trim()
                accessibility?.smartType(content)
                next()
            }

            text.contains("cancel") && text.contains("alarm") -> {
                accessibility?.cancelAllAlarmsUniversal()
                Response.speak("All alarms cancelled Sir.")
                next()
            }

            text.startsWith("status") -> {
                handleStatusReport()
                next()
            }

            text.startsWith("search ") -> {
                val query = text.removePrefix("search ").trim()
                LLM.executeDirectSearch(context, query) {
                    next()
                }
            }

            text.contains("alert off") || text.contains("alerts off") -> {
                LLM.stopAwarenessLoop()
                LLM.stopBackgroundSummaryLoop()
                Response.speak("Proactive alerts disabled, Sir.")
                next()
            }

            text.contains("alert on") || text.contains("alerts on") -> {
                LLM.startProactiveAwarenessLoop(context)
                LLM.startBackgroundSummaryLoop(context)
                Response.speak("Proactive alerts enabled.")
                next()
            }
            
            else -> {
                Log.w("OPTIMUS", "Unknown command chunk: $text")
                next()
            }
        }
    }


    fun handleVisionToggle(onComplete: () -> Unit = {})  {

        val battery = DeviceInfo.getBatteryLevel(context)
            .replace("%", "")
            .trim()
            .toIntOrNull() ?: 100

        if (battery < 25) {
            Response.speak("I cannot initialize vision, Sir. Battery is too low.")
            onComplete()
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Response.speak("I require overlay permissions.")

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            onComplete()
            return
        }

        val visionIntent = Intent(context, com.example.optimus.vision.Gesture::class.java)

        if (!State.visionEnabled.value) {
            context.startForegroundService(visionIntent)
            Response.speak("Vision systems online.")
        } else {
            context.stopService(visionIntent)
            Response.speak("Vision offline.")
        }

        onComplete()
    }

    private fun handleStatusReport() {
        val battery = DeviceInfo.getBatteryLevel(context)
        val tempC = DeviceInfo.getTemperature(context).filter { it.isDigit() || it == '.' }.toIntOrNull() ?: 0
        val visionStatus = if (State.visionEnabled.value) "online" else "offline"
        val micStatus = if (State.isMicActive) "active" else "disabled"
        val report = "Sir system status are. " +
                "Battery is at $battery. " +
                "Core temperature is $tempC degrees. " +
                "Vision is $visionStatus, and Microphone is $micStatus. "
        Response.speak(report)
    }

    private fun handleCallAction(name: String, onComplete: () -> Unit) {
        // Special logic for "Anushka/Puchki"
        if (name.contains("anushka", true) || name.contains("puchki", true)) {
            Response.speak("Connecting to her via WhatsApp.")
            fallbackToAccessibility("2711", onComplete)
            return
        }

        val matches = getPhoneMatches(name)
        when {
            matches.isEmpty() -> {
                Response.speak("I couldn't find $name in your contacts.")
                onComplete()
            }
            matches.size == 1 -> {
                routeCall(matches[0].first, matches[0].second, onComplete)
            }
            else -> {
                State.pendingContacts = matches
                State.isWaitingForContactSelection.value = true
                val namesString = matches.take(3).joinToString(", ") { it.first }
                Response.speak("Multiple matches found: $namesString. Which one?")
                State.isConversationLocked.value = true
                onComplete()
            }
        }
    }

    // Helper to extract numbers for volume or brightness
    private fun extractVolumePercent(text: String) = Regex("""(\d{1,3})""").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun handleVolumeAction(text: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val percent = extractVolumePercent(text)

        when {
            percent != null -> setVolumePercent(percent)
            text.contains("up") || text.contains("increase") -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            text.contains("down") || text.contains("decrease") -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            text.contains("mute") -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun setVolumePercent(percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val finalVol = (percent.coerceIn(0, 100) / 100f * max).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, finalVol, AudioManager.FLAG_SHOW_UI)
    }

    private val appAliases = mapOf(
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "insta" to "com.instagram.android",
        "instagram" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "google" to "com.google.android.googlequicksearchbox",
        "maps" to "com.google.android.apps.maps",
        "gallery" to "com.google.android.apps.photos",
        "photos" to "com.google.android.apps.photos",
        "camera" to "com.android.camera",
        "chatgpt" to "com.android.chatgpt",
        "calculator" to "com.google.android.calculator"
    )

    private fun resolvePackageName(appName: String): String? {
        val pm = context.packageManager
        val query = appName.lowercase().trim()

        // 🔹 1. Alias match (fastest)
        appAliases[query]?.let { return it }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = pm.queryIntentActivities(intent, 0)

        // 🔹 2. Exact match (best)
        apps.firstOrNull {
            it.loadLabel(pm).toString().equals(query, ignoreCase = true)
        }?.let {
            return it.activityInfo.packageName
        }

        // 🔹 3. Starts with (good)
        apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().startsWith(query)
        }?.let {
            return it.activityInfo.packageName
        }

        // 🔹 4. Contains (fallback)
        apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(query)
        }?.let {
            return it.activityInfo.packageName
        }

        return null
    }

    fun openAppSmart(appName: String): Boolean {
        val packageName = resolvePackageName(appName)
        if (packageName == null) {
            Log.w("OPTIMUS", "❌ No package found for: $appName")
            return false
        }
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d("OPTIMUS", "🚀 Opened $appName ($packageName)")
                true
            } else {
                Log.w("OPTIMUS", "⚠️ Launch intent null for: $packageName")
                false
            }

        } catch (e: Exception) {
            Log.e("OPTIMUS", "💥 Failed to open $packageName", e)
            false
        }
    }

    private fun openIntent(action: String) {
        try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (_: Exception) { Log.e("OPTIMUS", "Intent failed: $action") }
    }

    private fun playOnYouTube(query: String) {
        val intent = Intent(Intent.ACTION_VIEW,
            "https://www.youtube.com/results?search_query=$query".toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun getPhoneMatches(name: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)

        context.contentResolver.query(uri, projection, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val cName = cursor.getString(0)
                val cNumber = cursor.getString(1)
                if (list.none { it.first == cName }) list.add(cName to cNumber)
            }
        }
        return list
    }

    private fun routeCall(name: String, number: String, onComplete: () -> Unit) {
        if (name.contains("Anushka", true) || name.contains("puchki", true)) {
            fallbackToAccessibility("2711", onComplete)
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(Intent.ACTION_CALL, "tel:$number".toUri())
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                State.currentStatus.value = "CALLING"
                onComplete()
            } else {
                Response.speak("I don't have permission to make calls.")
                onComplete()
            }
        }
    }

    private fun fallbackToAccessibility(name: String, onComplete: () -> Unit) {
        val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AutomationController.pendingTargetName = name
            AutomationController.pendingAction = "VOICE_CALL"
            AutomationController.onAutomationComplete = onComplete
        } else {
            Response.speak("WhatsApp is not installed.")
            onComplete()
        }
    }

    private fun handleNotificationAction(onComplete: () -> Unit) {
        val summary = State.latestSummary.value
        if (summary.isBlank() || summary == "No new updates.") {
            Response.speak("No new updates, sir.")
            onComplete()
        } else {
            Response.speak(summary)
            State.summaryConsumed = true
            onComplete()

        }
    }

    fun handleContactSelection(userInput: String, onComplete: () -> Unit): Boolean {
        val selected = State.pendingContacts.firstOrNull { it.first.contains(userInput.trim(), true) }
        State.isWaitingForContactSelection.value = false
        return if (selected != null) {
            routeCall(selected.first, selected.second, onComplete)
            true
        } else {
            Response.speak("I didn't recognize that name. Call cancelled.")
            onComplete()
            true
        }
    }

    fun performAIIntent(llmOutput: String, onIntentHandled: (() -> Unit)? = null): Boolean {
        val text = llmOutput.trim()
        if (text.isEmpty()) return false

        // 1. Alarms
        val alarmRegex = Regex("""Set Alarm\s+(\d{1,2}:\d{2})\s*(AM|PM)""", RegexOption.IGNORE_CASE)
        val alarmMatch = alarmRegex.find(text)

        if (alarmMatch != null) {
            val timeStr = alarmMatch.groupValues[1]
            val ampm = alarmMatch.groupValues[2].uppercase()
            val (hourRaw, minute) = timeStr.split(":").map { it.toInt() }
            var hour = if (ampm == "PM" && hourRaw < 12) hourRaw + 12 else hourRaw
            if (ampm == "AM" && hour == 12) hour = 0

            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Response.speak("Alarm set for $timeStr $ampm.")
            onIntentHandled?.invoke()
            return true
        }

        return false
    }

    object AutomationController {
        var pendingTargetName: String? = null
        var pendingAction: String? = null
        var onAutomationComplete: (() -> Unit)? = null
    }
}
