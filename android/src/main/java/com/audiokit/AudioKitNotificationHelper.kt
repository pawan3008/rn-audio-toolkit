package com.audiokit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.ReadableMap

object AudioKitNotificationHelper {

  private const val CHANNEL_ID = "react_native_audio_kit"
  private const val NOTIFICATION_ID = 88001

  fun update(context: Context, config: ReadableMap?) {
    if (config == null) return
    if (!config.hasKey("showNotification") || !config.getBoolean("showNotification")) return
    createChannel(context)
  }

  fun show(context: Context, source: ReadableMap, player: Any?) {
    showWithMetadata(context, source.getString("title"), source.getString("artist"), null, null, player)
  }

  fun showWithMetadata(context: Context, title: String?, artist: String?, album: String?, uri: String?, player: Any?) {
    ensureChannel(context)
    val displayTitle = title ?: "Audio"
    val displayArtist = artist ?: ""

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle(displayTitle)
      .setContentText(displayArtist)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .addAction(buildAction(context, "PAUSE", android.R.drawable.ic_media_pause))
      .addAction(buildAction(context, "PLAY", android.R.drawable.ic_media_play))
      .build()

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, notification)
  }

  fun updatePlaybackState(context: Context, isPlaying: Boolean) {}

  private fun buildAction(context: Context, action: String, icon: Int): NotificationCompat.Action {
    val intent = Intent(context, AudioKitActionReceiver::class.java).apply {
      this.action = action
    }
    val pending = PendingIntent.getBroadcast(
      context,
      action.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Action(icon, action, pending)
  }

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "Audio Playback",
      NotificationManager.IMPORTANCE_LOW
    )
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
  }

  private fun createChannel(context: Context) {
    ensureChannel(context)
  }

  fun cancel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.cancel(NOTIFICATION_ID)
  }
}
