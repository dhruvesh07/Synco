package com.remoteaudiosync.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaNotificationListenerService : NotificationListenerService() {
    companion object {
        var listener: NotificationListener? = null

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, MediaNotificationListenerService::class.java)
        }
    }

    interface NotificationListener {
        fun onNotificationPosted(
            id: String,
            title: String,
            text: String,
            packageName: String,
            appName: String,
            timestamp: Long,
            isOngoing: Boolean,
            category: String = ""
        )
        fun onNotificationRemoved(id: String, packageName: String)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        
        val id = sbn.key ?: sbn.id.toString()
        val packageName = sbn.packageName ?: ""
        val notification = sbn.notification
        val extras = notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""
        val text = extractBodyText(extras)
        val isOngoing = sbn.isOngoing
        val timestamp = sbn.postTime
        val category = notification?.category ?: ""
        val pm = packageManager
        val appName = try {
            if (pm != null) {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } else {
                packageName
            }
        } catch (e: Exception) {
            packageName
        }

        listener?.onNotificationPosted(id, title, text, packageName, appName, timestamp, isOngoing, category)
    }

    private fun extractBodyText(extras: android.os.Bundle?): String {
        if (extras == null) return ""
        // Prefer the big-text / conversation body over the short text for full fidelity.
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        if (!bigText.isNullOrEmpty()) return bigText

        // Messaging-style notifications: collect all message lines.
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null && textLines.isNotEmpty()) {
            val lines = textLines.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            if (lines.isNotEmpty()) return lines.joinToString("\n")
        }

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        return buildString {
            if (!text.isNullOrEmpty()) append(text)
            if (!subText.isNullOrEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(subText)
            }
        }.toString()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        
        val id = sbn.key ?: sbn.id.toString()
        val packageName = sbn.packageName ?: ""
        listener?.onNotificationRemoved(id, packageName)
    }
}
