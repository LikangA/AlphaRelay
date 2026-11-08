package com.alpharelay.camera

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CameraHub.init(this)
        Diag.init(this)
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "导出进度", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "alpharelay_import"
    }
}
