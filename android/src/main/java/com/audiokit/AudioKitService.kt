package com.audiokit

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

class AudioKitService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    AudioKitNotificationHelper.ensureChannel(this)
    val notification = AudioKitNotificationHelper.buildCurrentNotification(this)
    if (notification == null) {
      stopSelf(startId)
      return START_NOT_STICKY
    }
    startForeground(AudioKitNotificationHelper.NOTIFICATION_ID, notification)
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    AudioKitNotificationHelper.clear(this)
    super.onDestroy()
  }

  companion object {
    fun start(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, AudioKitService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, AudioKitService::class.java))
      AudioKitNotificationHelper.clear(context)
    }
  }
}
