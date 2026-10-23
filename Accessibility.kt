package com.example.optimus.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.optimus.voice.CommandProcessor
import com.example.optimus.voice.LLM
import com.example.optimus.voice.Response


@SuppressLint("AccessibilityPolicy")
class OptimusAccessibility : AccessibilityService() {

    companion object { @Volatile var instance: OptimusAccessibility? = null }
    private var isAutomationRunning = false
    private var isAlarmAutomationRunning = false
    private val hardwareHandler = Handler(Looper.getMainLooper())

    private var lastVolumeUpTime = 0L
    private val DOUBLE_PRESS_DELAY = 400L

    // 🔋 HARDWARE RECEIVER (Battery & Temperature)
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                LLM.triggerPowerConnected(context) // Trigger recovery
                return
            }
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                LLM.triggerBattEvent(context, (level * 100 / scale.toFloat()).toInt())
            }
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (rawTemp != -1) {
                val tempC = rawTemp / 10
                LLM.triggerTemperatureEvent(context, tempC)
            }
        }
    }

    // ==============================
    // SERVICE LIFECYCLE
    // ==============================
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Wrap initialization in try-catch so an init error doesn't kill the service
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED) // 🔌 Add this
            }
            registerReceiver(batteryReceiver, filter)

            Handler(Looper.getMainLooper()).post {
                // Check if instance is still valid before starting loops
                if (instance != null) {
                    LLM.startBackgroundSummaryLoop(this)
                    startLocalHardwareTicker()
                    LLM.startProactiveAwarenessLoop(this)
                }
            }
        } catch (e: Exception) {
            Log.e("OPTIMUS", "Failed to init service safely", e)
        }

        Log.i("OPTIMUS", "✅ Accessibility Service connected")
    }

    private fun startLocalHardwareTicker() {
        hardwareHandler.post(object : Runnable {
            override fun run() {
                if (DeviceInfo.isScreenOn(this@OptimusAccessibility)) {
                    DeviceInfo.getFreshLocation(this@OptimusAccessibility) { lat, lon ->
                        LLM.triggerLocationWarning(this@OptimusAccessibility, lat, lon)
                    }
                }
                hardwareHandler.postDelayed(this, 5 * 60 * 1000)
            }
        })
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LLM.stopBackgroundSummaryLoop()
        LLM.stopAwarenessLoop()
        Log.d("OPTIMUS", "Service Unbinding - Cleaning up")
        instance = null
        hardwareHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        isAutomationRunning = false
        isAlarmAutomationRunning = false
    }

    // ==============================
    // GLOBAL ACTIONS
    // ==============================
    fun homeScreen() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun lockDevice() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun takeScreenshot() = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    @RequiresApi(Build.VERSION_CODES.R)
    fun toggleQuickSetting(keywords: List<String>, label: String) {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

        // Safety: ensure we don't crash if the user closes the app during this delay
        hardwareHandler.postDelayed({
            if (instance == null) return@postDelayed
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            hardwareHandler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed
                val target = findNodeFuzzy(root, keywords)
                if (target == null) {
                    Response.speak("$label switch not found, sir.")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return@postDelayed
                }
                val isCurrentlyOn = checkIsOn(target)
                clickNode(target) { success ->
                    if (success) {
                        val newState = if (isCurrentlyOn) "off" else "on"
                        Response.speak("$label $newState")
                    }
                    hardwareHandler.postDelayed({
                        if (instance != null) performGlobalAction(GLOBAL_ACTION_BACK)
                        hardwareHandler.postDelayed({
                            if (instance != null) performGlobalAction(GLOBAL_ACTION_BACK)
                        }, 300)
                    }, 800)
                }
                // Target node is used, recycle it now
                target.recycle()
            }, 1200)
        }, 400)
    }

    private fun findNodeFuzzy(root: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = mutableListOf<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val text = node.text?.toString()?.lowercase() ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

            if (keywords.any { text.contains(it.lowercase()) || contentDesc.contains(it.lowercase()) }) {
                queue.forEach { it.recycle() }
                return node // Caller must recycle
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkIsOn(node: AccessibilityNodeInfo): Boolean {
        if (node.isChecked || node.isSelected) return true

        // Android 11+ uses stateDescription (e.g., "On", "Off", "Connected")
        val state = node.stateDescription?.toString()?.lowercase() ?: ""
        if (state.contains("on") || state.contains("connected") || state.contains("enabled")) return true

        // Fallback: Check content description for the word "on"
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        return desc.contains(" on ") || desc.endsWith(" on")
    }

    private fun clickNode(node: AccessibilityNodeInfo, callback: (Boolean) -> Unit) {
        // 1. Try finding a clickable parent and clicking it programmatically
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                callback(true)
                return
            }
            current = current.parent
        }

        // 2. FALLBACK: Simulated Touch Gesture (Works on almost all devices)
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()

        val path = Path().apply {
            moveTo(centerX, centerY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false)
            }
        }, null)

        if (!result) callback(false)
    }

    // ==============================
    // GESTURES
    // ==============================

    fun longPress(x: Float, y: Float) {

        val path = Path()
        path.moveTo(x, y)

        val gestureBuilder = GestureDescription.Builder()

        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            700   // long press duration (ms)
        )

        gestureBuilder.addStroke(stroke)

        dispatchGesture(
            gestureBuilder.build(),
            null,
            null
        )
    }

    fun performClick(x: Float, y: Float) {

        val path = Path()
        path.moveTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    80
                )
            )
            .build()

        dispatchGesture(gesture, null, null)
    }

    fun scrollUp() {
        val path = Path().apply {
            moveTo(500f, 1000f)
            lineTo(500f, 500f)
        }
        dispatchSwipe(path)
    }

    fun scrollDown() {
        val path = Path().apply {
            moveTo(500f, 500f)
            lineTo(500f, 1000f)
        }
        dispatchSwipe(path)
    }

    fun swipeRight() {
        val path = Path().apply {
            moveTo(100f, 1000f)
            lineTo(900f, 1000f)
        }
        dispatchSwipe(path)
    }

    fun swipeLeft() {
        val path = Path().apply {
            moveTo(900f, 1000f)
            lineTo(100f, 1000f)
        }
        dispatchSwipe(path)
    }

    private fun dispatchSwipe(path: Path) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ==============================
    // Simple ACCESSIBILITY
    // ==============================

    @RequiresApi(Build.VERSION_CODES.R)
    fun toggleWifi() {
        toggleQuickSetting(
            listOf("Wi-Fi","WiFi","Wifi"),
            "WiFi"
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun toggleBluetooth() {
        toggleQuickSetting(
            listOf("Bluetooth"),
            "Bluetooth"
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun toggleMobileData() {
        toggleQuickSetting(
            listOf("Mobile data","Data","Internet","Cellular data"),
            "Mobile data"
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun toggleHotspot() {
        toggleQuickSetting(
            listOf(
                "Hotspot",
                "Portable hotspot",
                "Wi-Fi hotspot",
                "Mobile hotspot",
                "Tethering"
            ),
            "Hotspot"
        )
    }

    fun smartClose() {
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 800)
        }
        else {
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 500)
        }
    }


    private fun findBestInputField(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val className = node.className?.toString()?.lowercase() ?: ""
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            val score =
                (if (node.isEditable) 5 else 0) +
                        (if (node.isFocused) 4 else 0) +
                        (if (className.contains("edit")) 3 else 0) +
                        (if (className.contains("text")) 2 else 0) +
                        (if (hint.contains("message") || hint.contains("search")) 4 else 0) +
                        (if (desc.contains("message") || desc.contains("search")) 3 else 0) +
                        (if (id.contains("input") || id.contains("search")) 3 else 0)

            if (score > bestScore) {
                bestScore = score
                best = node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return best
    }

    private fun focusInput(node: AccessibilityNodeInfo) {
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun clearText(node: AccessibilityNodeInfo) {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                ""
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun smartType(text: String) {
        val root = rootInActiveWindow ?: return

        Log.d("OPTIMUS", "⌨️ Smart typing: $text")

        val input = findBestInputField(root)

        if (input == null) {
            Log.e("OPTIMUS", "❌ No input field found")
            return
        }

        // Step 1: Focus
        focusInput(input)

        Handler(Looper.getMainLooper()).postDelayed({

            // Step 2: Clear old text
            clearText(input)

            Handler(Looper.getMainLooper()).postDelayed({

                // Step 3: Type new text
                val success = setText(input, text)

                Log.d("OPTIMUS", "✍️ Text set success: $success")

            }, 100)

        }, 150)
    }


    // ==============================
    // ALARM EVENT
    // ==============================
    fun cancelAllAlarmsUniversal() {

        if (isAlarmAutomationRunning) return
        isAlarmAutomationRunning = true

        try {
            val launchIntent =
                packageManager.getLaunchIntentForPackage("com.google.android.deskclock")
                    ?: packageManager.getLaunchIntentForPackage("com.sec.android.app.clockpackage")
                    ?: packageManager.getLaunchIntentForPackage("com.android.deskclock")
                    ?: run {
                        resetAlarmAutomation()
                        return
                    }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)

            // Wait for UI to load
            Handler(Looper.getMainLooper()).postDelayed({
                openAlarmTabThenDisable()
            }, 2000)

        } catch (e: Exception) {
            Log.e("OPTIMUS", "Clock launch failed", e)
            resetAlarmAutomation()
        }
    }

    private fun openAlarmTabThenDisable() {

        val root = rootInActiveWindow ?: run {
            resetAlarmAutomation()
            return
        }

        // Try clicking Alarm tab
        val alarmTabs = root.findAccessibilityNodeInfosByText("Alarm")

        alarmTabs?.firstOrNull()?.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        )

        // Wait for tab switch
        Handler(Looper.getMainLooper()).postDelayed({
            disableAllAlarmSwitches()
        }, 1500)
    }

    private fun disableAllAlarmSwitches() {
        val root = rootInActiveWindow ?: run {
            resetAlarmAutomation()
            return
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var switchesDisabled = 0

        // 1. DEPTH-FIRST SEARCH (Detect by State and Class)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            // Detect if the node is a Toggle/Switch or just "Checkable" (Common in custom UIs)
            val isSwitch = node.className?.contains("Switch", true) == true ||
                    node.className?.contains("ToggleButton", true) == true ||
                    node.className?.contains("CheckBox", true) == true

            // If it's a switch/checkable and it's currently ON
            if ((isSwitch || node.isCheckable) && (node.isChecked || node.isSelected))
            {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    switchesDisabled++
                    Log.d("OPTIMUS", "✅ Toggled off via Class/State: ${node.viewIdResourceName}")
                }
            }

            // Recursive traversal
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // 2. FALLBACK: TARGETED SEARCH (If DFS found nothing, target specific Brand IDs)
        if (switchesDisabled == 0) {
            val commonIds = arrayOf(
                "com.google.android.deskclock:id/onoff",              // Google / Pixel
                "com.sec.android.app.clockpackage:id/alarm_item_switch", // Samsung
                "com.android.deskclock:id/onoff",                     // Generic / Xiaomi
                "com.coloros.alarm:id/switch_view"                    // Oppo / Realme
            )

            for (id in commonIds) {
                root.findAccessibilityNodeInfosByViewId(id)?.forEach { node ->
                    if (node.isChecked || node.isSelected)                    {
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            switchesDisabled++
                            Log.d("OPTIMUS", "✅ Toggled off via Resource ID: $id")
                        }
                    }
                }
            }
        }

        // 3. FINAL WRAP-UP
        Handler(Looper.getMainLooper()).postDelayed({
            // Only go home if we actually did something or after a timeout
            smartClose()
            resetAlarmAutomation()

            Log.d("OPTIMUS", "🏁 Total alarms disabled: $switchesDisabled")

            if (switchesDisabled == 0) {
                Log.w("OPTIMUS", "⚠️ No active alarms were detected on screen.")
            }
        }, 1000)
    }

    // ==============================
    // ACCESSIBILITY EVENT
    // ==============================
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Only react to important UI changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) { return }
        // Ensure event is from WhatsApp
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp") return
        // Ensure we have a pending automation request
        val target = CommandProcessor.AutomationController.pendingTargetName ?: return
        val action = CommandProcessor.AutomationController.pendingAction ?: return
        if (action != "VOICE_CALL") return
        // Ensure we are on WhatsApp main screen
        val className = event.className?.toString() ?: return
        if (!className.contains("Main", ignoreCase = true)) return
        // Prevent duplicate automation
        if (isAutomationRunning) return
        isAutomationRunning = true
        Log.d("OPTIMUS", "🚀 WhatsApp Fallback Automation for $target")
        Handler(Looper.getMainLooper()).postDelayed({
            performFallbackCall(target)
        }, 800)
    }


    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {

            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                handleVolumeShortcut()
            }
        }
        return super.onKeyEvent(event) // DON'T block
    }
    private fun handleVolumeShortcut() {
        val now = System.currentTimeMillis()

        if (now - lastVolumeUpTime < DOUBLE_PRESS_DELAY) {
            // DOUBLE PRESS DETECTED 🔥
            toggleVisionFromHardware()
        }

        lastVolumeUpTime = now
    }
    private fun toggleVisionFromHardware() {
        CommandProcessor(this).handleVisionToggle {
            Log.d("OPTIMUS", "Vision toggled via hardware")
        }
    }


    // ==============================
    // WHATSAPP FALLBACK AUTOMATION
    // ==============================
    private fun performFallbackCall(searchTerm: String) {
        val root = rootInActiveWindow ?: run { resetAutomation(); return }

        // Safety timeout: If automation doesn't finish in 10s, force reset
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAutomationRunning) resetAutomation()
        }, 10000)

        val searchNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/menuitem_search")
            .takeIf { !it.isNullOrEmpty() } ?: root.findAccessibilityNodeInfosByText("Search")

        if (searchNodes.isNullOrEmpty()) {
            Log.e("OPTIMUS", "Search button not found")
            resetAutomation()
            return
        }

        searchNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)

        Handler(Looper.getMainLooper()).postDelayed({
            val currentRoot = rootInActiveWindow ?: return@postDelayed
            val input = currentRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: currentRoot.findAccessibilityNodeInfosByViewId("com.whatsapp:id/search_src_text").firstOrNull()

            if (input != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, searchTerm)
                }
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                Handler(Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK) // Hide keyboard

                    Handler(Looper.getMainLooper()).postDelayed({
                        val resultsRoot = rootInActiveWindow ?: return@postDelayed
                        // Search 
                        val nicknames = listOf("Locked chats")
                        var targetNode: AccessibilityNodeInfo? = null

                        for (nick in nicknames) {
                            targetNode = resultsRoot.findAccessibilityNodeInfosByText(nick).firstOrNull()
                            if (targetNode != null) break
                        }

                        if (targetNode != null) {
                            val clickable = if (targetNode.isClickable) targetNode else targetNode.parent
                            clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                            // Final step: Click Call Button
                            Handler(Looper.getMainLooper()).postDelayed({
                                val chatRoot = rootInActiveWindow ?: return@postDelayed
                                val callBtn = chatRoot.findAccessibilityNodeInfosByViewId("com.whatsapp:id/menuitem_voice_call")
                                callBtn?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                resetAutomation() // Always clean up
                            }, 1000)
                        } else {
                            resetAutomation()
                        }
                    }, 1000)
                }, 500)
            } else {
                resetAutomation()
            }
        }, 800)
    }

    // ==============================
    // RESET
    // ==============================
    private fun resetAutomation() {

        val callback = CommandProcessor.AutomationController.onAutomationComplete

        CommandProcessor.AutomationController.pendingTargetName = null
        CommandProcessor.AutomationController.pendingAction = null
        CommandProcessor.AutomationController.onAutomationComplete = null

        isAutomationRunning = false

        // 🔥 Notify voice system
        callback?.invoke()
    }
    private fun resetAlarmAutomation() {
        isAlarmAutomationRunning = false
    }
}

