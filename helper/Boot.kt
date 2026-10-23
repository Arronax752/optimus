package com.example.optimus.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.util.Log
import com.example.optimus.vision.Gesture
import com.example.optimus.voice.Voice

class Boot : BroadcastReceiver() {

    // In helper/Boot.kt
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("optimus", MODE_PRIVATE)
        if (!prefs.getBoolean("user_initialized", false)) return

        Log.d("OPTIMUS", "System Awake. Re-occupying the Assistant slot.")

        // 1. Wake the Assistant Service (The Master)
        val assistantIntent = Intent(context, OptimusAssistantService::class.java)
        context.startForegroundService(assistantIntent)

        // 2. Wake the limbs
        context.startForegroundService(Intent(context, Voice::class.java))
        context.startForegroundService(Intent(context, Gesture::class.java))
    }
}
