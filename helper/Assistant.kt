package com.example.optimus.helper

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.content.Intent
import android.util.Log
import android.view.View
import com.example.optimus.vision.Gesture
import com.example.optimus.voice.Voice

// Consolidating all Assistant logic into one file
class OptimusAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // When the system 'binds' to us as the assistant,
        // we ensure all our limbs (Voice/Vision) are active.
        Log.d("OPTIMUS", "Assistant Role Active. Strengthening Services.")

        startForegroundService(Intent(this, Voice::class.java))
        startForegroundService(Intent(this, Gesture::class.java))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
class OptimusSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = OptimusSession(this)
}

    class OptimusSession(context: Context) : VoiceInteractionSession(context) {
        override fun onCreateContentView(): View = View(context)

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            // This is where you trigger your AI Voice listening logic
        }
    }

class OptimusRecognition : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
