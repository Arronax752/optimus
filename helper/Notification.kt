package com.example.optimus.helper

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

class NotificationListener : NotificationListenerService() {

    companion object {
        var instance: NotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("OPTIMUS", "✅ Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return
        val pkg = sbn.packageName

        // 1. Basic Filters
        if (pkg.contains("android") || pkg.contains("systemui")) return
        if (!sbn.isClearable) return

        val appName = getAppName(pkg)
        val title = extras.getString("android.title") ?: "Unknown"
        val id = sbn.key
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null && textLines.isNotEmpty()) {
            textLines.takeLast(3).forEach { line ->
                val msg = line.toString()
                val entryId = "$id-${msg.hashCode()}"
                val entry = State.MessageEntry(
                    id = entryId,
                    app = appName,
                    sender = title,
                    text = msg,
                    time = System.currentTimeMillis()
                )
                State.addMessage(this, entry)
            }
        }
        else {
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            val entry = State.MessageEntry(
                id = id,
                app = appName,
                sender = title,
                text = text,
                time = System.currentTimeMillis()
            )
            State.addMessage(this, entry)
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName.split(".").lastOrNull()?.capitalize(Locale.ROOT) ?: packageName
        }
    }
}
