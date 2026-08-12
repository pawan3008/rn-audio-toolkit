package com.audiokit

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat

class AudioKitService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    AudioKitNotificationHelper.ensureChannel(this)
    val notification = NotificationCompat.Builder(this, "react_native_audio_kit")
      .setContentTitle("Audio")
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setOngoing(true)
      .build()
    startForeground(88001, notification)
    return START_STICKY
  }

  companion object {
    fun start(context: Context) {
      val intent = Intent(context, AudioKitService::class.java)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, AudioKitService::class.java))
      AudioKitNotificationHelper.cancel(context)
    }
  }
}
