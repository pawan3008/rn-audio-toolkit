package com.audiokit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.facebook.react.bridge.ReadableMap

object AudioKitNotificationHelper {
  private const val CHANNEL_ID = "react_native_audio_kit"
  private const val PROGRESS_MAX = 1_000
  const val NOTIFICATION_ID = 88001

  private data class NotificationState(
    val title: String,
    val artist: String,
    val album: String,
    val isPlaying: Boolean,
    val artwork: Bitmap?,
    val positionMs: Long,
    val durationMs: Long,
    val generation: Long
  )

  @Volatile
  private var state: NotificationState? = null

  fun update(context: Context, config: ReadableMap?) {
    if (config == null) return
    if (!config.hasKey("showNotification") || !config.getBoolean("showNotification")) return
    ensureChannel(context)
  }

  fun prepare(source: ReadableMap, isPlaying: Boolean, generation: Long) {
    state = NotificationState(
      title = source.safeString("title").ifBlank { "Audio" },
      artist = source.safeString("artist"),
      album = source.safeString("album"),
      isPlaying = isPlaying,
      artwork = null,
      positionMs = 0L,
      durationMs = 0L,
      generation = generation
    )
  }

  fun show(context: Context, source: ReadableMap, player: Any?) {
    val generation = state?.generation ?: 0L
    prepare(source, true, generation)
    notifyCurrent(context)
  }

  fun showWithMetadata(
    context: Context,
    title: String?,
    artist: String?,
    album: String?,
    uri: String?,
    player: Any?
  ) {
    state = NotificationState(
      title = title?.trim().orEmpty().ifBlank { "Audio" },
      artist = artist?.trim().orEmpty(),
      album = album?.trim().orEmpty(),
      isPlaying = true,
      artwork = null,
      positionMs = 0L,
      durationMs = 0L,
      generation = state?.generation ?: 0L
    )
    notifyCurrent(context)
  }

  fun updatePlaybackState(
    context: Context,
    isPlaying: Boolean,
    positionMs: Long? = null,
    durationMs: Long? = null
  ) {
    val current = state ?: return
    state = current.copy(
      isPlaying = isPlaying,
      positionMs = positionMs?.coerceAtLeast(0L) ?: current.positionMs,
      durationMs = durationMs?.takeIf { it > 0L } ?: current.durationMs
    )
    notifyCurrent(context)
  }

  fun updateProgress(context: Context, positionMs: Long, durationMs: Long) {
    val current = state ?: return
    state = current.copy(
      positionMs = positionMs.coerceAtLeast(0L),
      durationMs = durationMs.coerceAtLeast(0L)
    )
    notifyCurrent(context)
  }

  fun setArtwork(context: Context, generation: Long, artwork: Bitmap): Boolean {
    val current = state ?: return false
    if (current.generation != generation) return false
    state = current.copy(artwork = artwork)
    notifyCurrent(context)
    return true
  }

  fun buildCurrentNotification(context: Context): Notification? {
    val current = state ?: return null
    val builder = baseBuilder(context, current)
    val artwork = current.artwork
    if (artwork == null) {
      return buildFallbackNotification(context, builder, current)
    }

    return try {
      val content = buildCustomContent(context, current, artwork)
      builder
        .setCustomContentView(content)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .build()
    } catch (_: RuntimeException) {
      buildFallbackNotification(context, baseBuilder(context, current), current)
    }
  }

  private fun baseBuilder(
    context: Context,
    current: NotificationState
  ): NotificationCompat.Builder {
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle(current.title)
      .setContentText(current.artist)
      .setSubText(current.album.ifBlank { null })
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setOngoing(current.isPlaying)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
  }

  private fun buildFallbackNotification(
    context: Context,
    builder: NotificationCompat.Builder,
    current: NotificationState
  ): Notification {
    val action = if (current.isPlaying) "PAUSE" else "PLAY"
    val icon = if (current.isPlaying) {
      android.R.drawable.ic_media_pause
    } else {
      android.R.drawable.ic_media_play
    }
    return builder
      .setLargeIcon(current.artwork)
      .addAction(buildFallbackAction(context, action, icon))
      .build()
  }

  private fun buildCustomContent(
    context: Context,
    current: NotificationState,
    artwork: Bitmap
  ): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.audio_kit_notification_compact)
    views.setTextViewText(R.id.audio_kit_notification_title, current.title)
    views.setTextViewText(R.id.audio_kit_notification_artist, current.artist)
    views.setTextViewText(R.id.audio_kit_notification_album, current.album)
    views.setViewVisibility(
      R.id.audio_kit_notification_artist,
      if (current.artist.isBlank()) View.GONE else View.VISIBLE
    )
    views.setViewVisibility(
      R.id.audio_kit_notification_album,
      if (current.album.isBlank()) View.GONE else View.VISIBLE
    )
    views.setImageViewBitmap(R.id.audio_kit_notification_artwork, artwork)
    views.setImageViewResource(
      R.id.audio_kit_notification_play_pause,
      if (current.isPlaying) R.drawable.audio_kit_ic_pause else R.drawable.audio_kit_ic_play
    )
    views.setContentDescription(
      R.id.audio_kit_notification_play_pause,
      context.getString(
        if (current.isPlaying) R.string.audio_kit_pause else R.string.audio_kit_play
      )
    )
    views.setOnClickPendingIntent(
      R.id.audio_kit_notification_previous,
      actionPendingIntent(context, AudioKitActionReceiver.ACTION_PREVIOUS)
    )
    views.setOnClickPendingIntent(
      R.id.audio_kit_notification_play_pause,
      actionPendingIntent(
        context,
        if (current.isPlaying) AudioKitActionReceiver.ACTION_PAUSE else AudioKitActionReceiver.ACTION_PLAY
      )
    )
    views.setOnClickPendingIntent(
      R.id.audio_kit_notification_next,
      actionPendingIntent(context, AudioKitActionReceiver.ACTION_NEXT)
    )

    val duration = current.durationMs
    val position = current.positionMs.coerceAtLeast(0L)
    if (duration > 0L) {
      val progress = ((position.coerceAtMost(duration) * PROGRESS_MAX) / duration).toInt()
      views.setProgressBar(R.id.audio_kit_notification_progress, PROGRESS_MAX, progress, false)
    } else {
      views.setProgressBar(R.id.audio_kit_notification_progress, PROGRESS_MAX, 0, true)
    }
    return views
  }

  private fun notifyCurrent(context: Context) {
    ensureChannel(context)
    val notification = buildCurrentNotification(context) ?: return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, notification)
  }

  private fun buildFallbackAction(
    context: Context,
    action: String,
    icon: Int
  ): NotificationCompat.Action {
    val receiverAction = if (action == "PAUSE") {
      AudioKitActionReceiver.ACTION_PAUSE
    } else {
      AudioKitActionReceiver.ACTION_PLAY
    }
    return NotificationCompat.Action(icon, action, actionPendingIntent(context, receiverAction))
  }

  private fun actionPendingIntent(context: Context, action: String): PendingIntent {
    val intent = Intent(context, AudioKitActionReceiver::class.java).apply {
      this.action = action
      setPackage(context.packageName)
    }
    return PendingIntent.getBroadcast(
      context,
      action.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
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

  fun clear(context: Context) {
    state = null
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.cancel(NOTIFICATION_ID)
  }

  fun cancel(context: Context) {
    clear(context)
  }

  private fun ReadableMap.safeString(key: String): String {
    return if (hasKey(key) && !isNull(key)) getString(key)?.trim().orEmpty() else ""
  }
}
