package com.remoteaudiosync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.remoteaudiosync.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SyncoForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.remoteaudiosync.service.START"
        const val ACTION_STOP = "com.remoteaudiosync.service.STOP"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "synco_background_service"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRefreshJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START || (action == null && intent == null)) {
            startServiceForeground()
            acquireWakeLock()
            // Ensure the process-lifetime connection is running and reconnected to the last known
            // desktop, so the link survives the app being cleared from recents / the OS restarting
            // this sticky service. A null intent means the OS re-created us (START_STICKY restart).
            try {
                val app = application as com.remoteaudiosync.app.RemoteAudioSyncApp
                app.syncoConnection.start()
                app.syncoConnection.reconnectLastServer()
            } catch (e: Exception) {
                // Swallow lifecycle races on startup
            }
        } else if (action == ACTION_STOP) {
            stopServiceForeground()
        }
        return START_STICKY
    }

    private fun startServiceForeground() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Synco Active Sync")
            .setContentText("Background synchronization is active and running")
            .setSmallIcon(com.remoteaudiosync.R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Synco::BackgroundSyncWakeLock"
            ).apply {
                acquire()
            }
        }
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            while (true) {
                kotlinx.coroutines.delay(240000L)
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    wakeLock?.acquire()
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun stopServiceForeground() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Synco Background Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Synco connected and synchronized with your PC in the background."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
