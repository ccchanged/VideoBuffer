package com.example.instantreplay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.instantreplay.recording.DebugLogger

class App : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DebugLogger.init(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
