package com.example.optimus.helper

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.example.optimus.voice.LLM
import com.example.optimus.voice.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections

object State {

    // ==============================
    // SYSTEM STATES
    // ==============================
    val currentStatus = MutableStateFlow("IDLE")
    var isMicActive = true

    val visionEnabled = MutableStateFlow(false)
    val aiResponse = MutableStateFlow("")
    @Volatile var lastSpokenAlert = ""
    @Volatile var summaryConsumed = true
    var lastSummaryTime: Long = 0L
    var lastNotificationTime: Long = 0L
    var activeScreenTime: Int = 0
    @Volatile var isProcessing = false


    // ==============================
    // CONVERSATION CONTROL
    // ==============================
    val isConversationLocked = MutableStateFlow(false)
    val isWaitingForContactSelection = mutableStateOf(false)
    var pendingContacts: List<Pair<String, String>> = emptyList()

    object VoiceController {
        var service: Voice? = null

        fun forceMicRecovery() {
            service?.forceMicRecovery()
        }
    }


    // ==============================
    // STATUS COLORS
    // ==============================
    fun getStatusColor(status: String): Color {
        return when (status) {
            "LISTENING" -> Color(0xFF00FF88)
            "EXECUTING" -> Color(0xFFFFCC00)
            "ERROR" -> Color(0xFFFF3333)
            "WAITING", "IDLE" -> Color(0xFF00CCFF)
            else -> Color.Gray
        }
    }

    // ==============================
    // MESSAGE STRUCTURE
    // ==============================
    data class MessageEntry(val id: String, val app: String, val sender: String, val text: String, val time: Long)
    val messageBuffer = Collections.synchronizedList(mutableListOf<MessageEntry>())
    val latestSummary = MutableStateFlow("No new updates.")
    private const val MAX_BUFFER_SIZE = 200


    fun addMessage(context: Context, entry: MessageEntry) {
        synchronized(messageBuffer) {
            if (messageBuffer.any { it.id == entry.id }) return
            messageBuffer.add(entry)
            if (messageBuffer.size > MAX_BUFFER_SIZE) {
                messageBuffer.removeAt(0)
            }
        }

        Log.d("OPTIMUS", "📥 Message added. Buffer: ${messageBuffer.size}")

        if (shouldTriggerImmediateSummary() && !isProcessing) {
            triggerBurstSummary(context.applicationContext) // Use applicationContext to avoid leaks
        }
    }

    private fun shouldTriggerImmediateSummary(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(messageBuffer) {
            val recentCount = messageBuffer.takeLast(5).count { now - it.time <= 3 * 60 * 1000 }
            return recentCount >= 3
        }
    }

    fun getSummaryTargetMessages(): List<MessageEntry> {
        val now = System.currentTimeMillis()
        synchronized(messageBuffer) {
            // We use a local copy of lastSummaryTime to ensure stability during the filter
            val lastTime = lastSummaryTime
            return messageBuffer.filter {
                it.time > lastTime && now - it.time < 10 * 60 * 1000
            }.toList()
        }
    }

    fun removeProcessed(messages: List<MessageEntry>) {
        if (messages.isEmpty()) return

        synchronized(messageBuffer) {
            val ids = messages.map { it.id }.toSet()
            messageBuffer.removeIf { it.id in ids }
            val newestTimestamp = messages.maxOf { it.time }
            lastSummaryTime = newestTimestamp

            summaryConsumed = false
        }
    }

    fun cleanupOldMessages() {
        val now = System.currentTimeMillis()
        synchronized(messageBuffer) {
            messageBuffer.removeIf { now - it.time > 2 * 60 * 60 * 1000 }
        }
    }

    private fun triggerBurstSummary(context: Context) {
        Log.d("OPTIMUS", "🔥 BOX A: Burst detected. Processing immediately...")
        val targets = getSummaryTargetMessages()
        if (targets.isNotEmpty()) {
            // Call LLM immediately
            LLM.summarizeMessages(context, targets) { summary ->
                if (summary.isNotBlank()) {
                    latestSummary.value = summary
                    summaryConsumed = false
                    lastNotificationTime = System.currentTimeMillis()

                    // 🔥 CRITICAL: Clear Box A messages so Box B doesn't see them
                    removeProcessed(targets)
                    Log.d("OPTIMUS", "✅ BOX A: Summary stored & buffer cleared.")
                }
            }
        }
    }

}
