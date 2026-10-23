package com.example.optimus.helper

import android.content.Context
import androidx.core.content.edit

object Memory {

    private const val PREFS_NAME = "optimus_memory"
    private const val KEY_MEM = "core_memory"

    fun getSchedule(context: Context): String {

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        return prefs.getString(
            KEY_MEM,
            schedule
        ) ?: schedule
    }

    fun replaceSchedule(context: Context, newMemory: String) {

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit {
            putString(KEY_MEM, newMemory)
        }
    }

    private val schedule = """
                ATUL WEEKLY ROUTINE:
                - Monday & Friday: Push day
                - Tuesday & Saturday: Pull day
                - Wednesday: Leg Day
                - Thursday & Sunday: Rest
                
                EVENTS:
                - Some Birthday
                
                LIKES:
                -
                DISLIKES:
                -
                MISC:
                -
                """
        .trimIndent()
}
