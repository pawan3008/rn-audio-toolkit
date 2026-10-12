package com.audiokit

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes as MediaAudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.pow

class AudioKitEngine(private val reactContext: ReactApplicationContext) : DefaultLifecycleObserver {

  private var player: ExoPlayer? = null
  private var mediaSession: MediaSession? = null
  private var config: ReadableMap? = null
  private var playbackSpeed = 1f
  private var abRepeatStart = 0L
  private var abRepeatEnd = 0L
  private var abRepeatEnabled = false
  private var currentSourceId: String? = null
  private var currentMediaId: String? = null
  private var sourceGeneration = 0L
  private var transportIsPlaying = false
  private var lastNotificationProgressAtMs = 0L
  private var wasPlayingBeforeFocusLoss = false
  private var destroyed = false
  private val cache = AudioKitCache(reactContext)
  private val artworkLoader = AudioKitArtworkLoader(reactContext)
  private val handler = Handler(Looper.getMainLooper())
  private val progressRunnable = object : Runnable {
    override fun run() {
      emitProgress()
      handler.postDelayed(this, 250)
    }
  }

  private val resumePrefs: SharedPreferences =
    reactContext.getSharedPreferences("RNAudioKit_resume", Context.MODE_PRIVATE)

  private val audioManager =
    reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var focusRequest: AudioFocusRequest? = null

  init {
    Handler(Looper.getMainLooper()).post {
      ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
  }

  fun configure(config: ReadableMap) {
    this.config = config
    AudioKitNotificationHelper.update(reactContext, config)
  }

  fun play(source: ReadableMap) {
    val uri = source.getString("uri") ?: throw IllegalArgumentException("Missing uri")
    handler.post {
      if (destroyed) return@post
      sourceGeneration += 1
      val generation = sourceGeneration
      artworkLoader.invalidate()
      currentSourceId = uri
      currentMediaId = "$generation:$uri"
      transportIsPlaying = false
      lastNotificationProgressAtMs = 0L

      ensurePlayer()
      ensureMediaSession()
      val resolvedUri = resolvePlaybackUri(uri)
      val title = source.safeString("title")
      val artist = source.safeString("artist")
      val album = source.safeString("album")
      val mediaId = currentMediaId ?: return@post
      val mediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(Uri.parse(resolvedUri))
        .setMediaMetadata(buildMetadata(title, artist, album, null))
        .build()

      if (configBool("showNotification", false)) {
        AudioKitNotificationHelper.prepareNext(source, generation)
      }

      requestAudioFocus()
      player?.setMediaItem(mediaItem)
      player?.prepare()
      player?.playWhenReady = true
      player?.setPlaybackSpeed(playbackSpeed)
      handler.removeCallbacks(progressRunnable)
      handler.post(progressRunnable)

      if (configBool("showNotification", false)) {
        AudioKitService.start(reactContext)
      }
      publishTransportState(true, "play")

      val artwork = source.safeString("artwork")
      if (artwork.isNotBlank()) {
        artworkLoader.load(artwork) { loaded ->
          if (loaded == null || destroyed) return@load
          if (generation != sourceGeneration || mediaId != currentMediaId) return@load
          val currentPlayer = player ?: return@load
          val currentItem = currentPlayer.currentMediaItem ?: return@load
          if (currentItem.mediaId != mediaId) return@load

          if (configBool("showNotification", false)) {
            AudioKitNotificationHelper.setArtwork(reactContext, generation, loaded.bitmap)
          }
          val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(buildMetadata(title, artist, album, loaded.data))
            .build()
          currentPlayer.replaceMediaItem(currentPlayer.currentMediaItemIndex, updatedItem)
        }
      }
    }
  }

  fun pause() {
    runOnMain { pauseInternal() }
  }

  fun resume() {
    runOnMain { resumeInternal() }
  }

  fun remotePrevious() {
    handler.post {
      emit("remotePrevious", Arguments.createMap())
    }
  }

  fun remoteNext() {
    handler.post {
      emit("remoteNext", Arguments.createMap())
    }
  }

  fun stop() {
    handler.post {
      handler.removeCallbacks(progressRunnable)
      invalidateCurrentSource()
      player?.stop()
      player?.seekTo(0)
      if (configBool("releaseOnStop", true)) {
        player?.clearMediaItems()
      }
      releaseMediaSession()
      if (configBool("autoDestroy", false)) {
        releasePlayer()
      }
      AudioKitService.stop(reactContext)
      abandonAudioFocus()
      emit("stop", playbackStateMap())
    }
  }

  fun seekTo(positionMs: Double) {
    handler.post {
      val currentPlayer = player ?: return@post
      currentPlayer.seekTo(positionMs.toLong())
      if (configBool("showNotification", false)) {
        AudioKitNotificationHelper.updateProgress(
          reactContext,
          positionMs.toLong(),
          currentPlayer.duration.takeUnless { it == C.TIME_UNSET } ?: 0L
        )
      }
    }
  }

  fun setVolume(volume: Double) {
    handler.post {
      player?.volume = volume.toFloat()
    }
  }

  fun setPlaybackSpeed(speed: Double) {
    playbackSpeed = speed.toFloat()
    handler.post {
      player?.setPlaybackSpeed(playbackSpeed)
    }
  }

  fun preload(url: String): ReadableMap {
    val durationMs = getDurationMs(url)
    val result = Arguments.createMap()
    result.putDouble("durationMs", durationMs.toDouble())
    return result
  }

  fun getDuration(url: String): Double {
    return getDurationMs(url).toDouble()
  }

  fun cache(url: String): ReadableMap {
    val limit = configLong("cacheSizeLimit", 100L * 1024 * 1024)
    val (path, cached) = cache.cache(url, limit)
    val result = Arguments.createMap()
    result.putString("path", path)
    result.putBoolean("cached", cached)
    return result
  }

  fun clearCache() {
    cache.clear()
  }

  fun getCacheSize(): Double {
    return cache.size().toDouble()
  }

  fun getPlaybackState(): ReadableMap {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return playbackStateMap()
    }
    val latch = CountDownLatch(1)
    var result: ReadableMap = Arguments.createMap()
    handler.post {
      result = playbackStateMap()
      latch.countDown()
    }
    latch.await(2, TimeUnit.SECONDS)
    return result
  }

  fun getWaveform(url: String, sampleCount: Int): ReadableMap {
    val samples = extractWaveformSamples(resolvePlaybackUri(url), sampleCount)
    val array = Arguments.createArray()
    samples.forEach { array.pushDouble(it.toDouble()) }
    val result = Arguments.createMap()
    result.putArray("samples", array)
    return result
  }

  fun getResumePosition(sourceId: String): Double {
    return resumePrefs.getLong(sourceId, 0L).toDouble()
  }

  fun setResumePosition(sourceId: String, positionMs: Double) {
    resumePrefs.edit().putLong(sourceId, positionMs.toLong()).apply()
  }

  fun setABRepeat(startMs: Double, endMs: Double) {
    abRepeatStart = startMs.toLong()
    abRepeatEnd = endMs.toLong()
    abRepeatEnabled = true
  }

  fun clearABRepeat() {
    abRepeatEnabled = false
  }

  fun detectSilenceGaps(url: String, thresholdDb: Double, minSilenceMs: Double): ReadableMap {
    val samples = extractWaveformSamples(resolvePlaybackUri(url), 500)
    val durationMs = getDurationMs(url)
    val gaps = Arguments.createArray()
    val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
    val msPerSample = durationMs.toDouble() / max(1, samples.size)
    var gapStart: Double? = null

    samples.forEachIndexed { index, amp ->
      val ms = index * msPerSample
      if (amp < threshold) {
        if (gapStart == null) gapStart = ms
      } else if (gapStart != null) {
        if (ms - gapStart!! >= minSilenceMs) {
          val gap = Arguments.createMap()
          gap.putDouble("startMs", gapStart!!)
          gap.putDouble("endMs", ms)
          gaps.pushMap(gap)
        }
        gapStart = null
      }
    }

    val result = Arguments.createMap()
    result.putArray("gaps", gaps)
    return result
  }

  fun destroy() {
    handler.post {
      if (destroyed) return@post
      destroyed = true
      handler.removeCallbacksAndMessages(null)
      invalidateCurrentSource()
      AudioKitService.stop(reactContext)
      releaseMediaSession()
      releasePlayer()
      abandonAudioFocus()
      artworkLoader.release()
      ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
  }

  override fun onStop(owner: LifecycleOwner) {
    if (configBool("stopOnBackground", false)) {
      stop()
    } else if (!configBool("backgroundPlayback", false)) {
      pause()
    }
  }

  private fun ensurePlayer() {
    if (player != null) return

    val audioAttributes = MediaAudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
      .build()

    val highQuality = configBool("highQualityPlayback", true)

    player = ExoPlayer.Builder(reactContext)
      .setAudioAttributes(audioAttributes, false)
      .apply {
        if (highQuality) {
          setSeekBackIncrementMs(5_000)
          setSeekForwardIncrementMs(15_000)
        }
      }
      .build()

    player?.addListener(object : Player.Listener {
      override fun onPlaybackStateChanged(state: Int) {
        when (state) {
          Player.STATE_BUFFERING -> emit("buffer", playbackStateMap())
          Player.STATE_ENDED -> {
            transportIsPlaying = false
            val currentPlayer = player
            AudioKitNotificationHelper.updatePlaybackState(
              reactContext,
              false,
              currentPlayer?.currentPosition,
              currentPlayer?.duration?.takeUnless { it == C.TIME_UNSET }
            )
            emit("complete", playbackStateMap())
          }
        }
      }

      override fun onPlayerError(error: PlaybackException) {
        val payload = Arguments.createMap()
        payload.putString("code", "E_PLAYBACK")
        payload.putString("message", error.message ?: "Playback error")
        emit("error", payload)
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        val currentPlayer = player ?: return
        when {
          isPlaying -> publishTransportState(true, "play")
          currentPlayer.playbackState == Player.STATE_BUFFERING -> Unit
          currentPlayer.playbackState == Player.STATE_ENDED -> Unit
          currentPlayer.playbackState == Player.STATE_IDLE && currentPlayer.mediaItemCount == 0 -> Unit
          else -> publishTransportState(false, "pause")
        }
      }
    })
  }

  private fun ensureMediaSession() {
    if (mediaSession != null) return
    val sessionPlayer = player ?: return
    val remoteControlPlayer = object : ForwardingPlayer(sessionPlayer) {
      override fun play() {
        resumeInternal()
      }

      override fun pause() {
        pauseInternal()
      }

      override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) resumeInternal() else pauseInternal()
      }

      // Fix 3 (next/previous in notification): the player uses a single
      // MediaItem (no real playlist), so instead of letting media3 try to move
      // a non-existent playlist position, route the seek-to-next/previous
      // transport commands to the SAME remoteNext()/remotePrevious() handlers
      // the notification addAction + AudioKitActionReceiver already use. This
      // makes the lock-screen / MediaStyle transport buttons and the
      // notification buttons follow one path (emit remoteNext/remotePrevious).
      override fun seekToNext() {
        this@AudioKitEngine.remoteNext()
      }

      override fun seekToPrevious() {
        this@AudioKitEngine.remotePrevious()
      }

      override fun seekToNextMediaItem() {
        this@AudioKitEngine.remoteNext()
      }

      override fun seekToPreviousMediaItem() {
        this@AudioKitEngine.remotePrevious()
      }

      // Fix 3b (next icon invisible / previous dead): in media3 the commands a
      // MediaSession can advertise and honor are intersected with the UNDERLYING
      // player's available commands. The ExoPlayer uses a single MediaItem (no
      // playlist), so it does NOT report COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM) /
      // COMMAND_SEEK_TO_PREVIOUS(_MEDIA_ITEM) as available. That made MediaStyle
      // hide the NEXT button and caused the session to drop the PREVIOUS command
      // before it ever reached the seekToPrevious() override above. Advertising
      // the commands only in onConnect() is not enough because the session still
      // gates on the wrapped player's own available commands. Override the
      // command surface here so media3/MediaStyle consistently treat next/
      // previous as available: render the NEXT icon and dispatch NEXT/PREVIOUS
      // to the seek overrides above (which route to remoteNext()/remotePrevious()).
      override fun getAvailableCommands(): Player.Commands {
        return Player.Commands.Builder()
          .addAll(super.getAvailableCommands())
          .add(Player.COMMAND_SEEK_TO_NEXT)
          .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
          .add(Player.COMMAND_SEEK_TO_PREVIOUS)
          .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
          .build()
      }

      override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
          Player.COMMAND_SEEK_TO_NEXT,
          Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_PREVIOUS,
          Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
          else -> super.isCommandAvailable(command)
        }
      }
    }
    val callback = object : MediaSession.Callback {
      override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
      ): MediaSession.ConnectionResult {
        // Fix 3 (next/previous in notification): MediaStyle derives which
        // transport buttons to render from the session's advertised player
        // commands. Previously only COMMAND_PLAY_PAUSE (+ metadata) was
        // advertised, so MediaStyle hid the next/previous actions even though
        // the notification added all three. Advertise the seek-to-next/previous
        // commands (both the plain and media-item variants, to cover how
        // MediaStyle/media3 may invoke them). The ForwardingPlayer overrides
        // above divert these to remoteNext()/remotePrevious(), so no real
        // (non-existent) playlist seek occurs. This is additive — COMMAND_PLAY_PAUSE
        // and the metadata commands are unchanged.
        val commands = Player.Commands.Builder()
          .add(Player.COMMAND_PLAY_PAUSE)
          .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
          .add(Player.COMMAND_GET_METADATA)
          .add(Player.COMMAND_SEEK_TO_NEXT)
          .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
          .add(Player.COMMAND_SEEK_TO_PREVIOUS)
          .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
          .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
          .setAvailableSessionCommands(androidx.media3.session.SessionCommands.EMPTY)
          .setAvailablePlayerCommands(commands)
          .build()
      }
    }
    mediaSession = MediaSession.Builder(reactContext, remoteControlPlayer)
      .setCallback(callback)
      .build()
    // Share the session with the notification helper so the media notification
    // is built as a MediaStyle notification linked to this session (single
    // shared system media slot). Do NOT create a second session here.
    AudioKitNotificationHelper.setMediaSession(mediaSession)
  }

  private fun releaseMediaSession() {
    AudioKitNotificationHelper.clearMediaSession()
    mediaSession?.release()
    mediaSession = null
  }

  private fun releasePlayer() {
    releaseMediaSession()
    player?.release()
    player = null
  }

  private fun invalidateCurrentSource() {
    sourceGeneration += 1
    artworkLoader.invalidate()
    currentSourceId = null
    currentMediaId = null
    transportIsPlaying = false
    lastNotificationProgressAtMs = 0L
  }

  private fun buildMetadata(
    title: String,
    artist: String,
    album: String,
    artwork: ByteArray?
  ): MediaMetadata {
    return MediaMetadata.Builder()
      .setTitle(title.ifBlank { "Audio" })
      .setArtist(artist)
      .setAlbumTitle(album)
      .apply {
        if (artwork != null) {
          setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
      }
      .build()
  }

  private fun resolvePlaybackUri(uri: String): String {
    if (uri.startsWith("http")) {
      val cached = cache.getCachedPath(uri)
      if (cached != null) return "file://$cached"
    }
    if (uri.startsWith("/")) return "file://$uri"
    return uri
  }

  private fun getDurationMs(url: String): Long {
    val retriever = MediaMetadataRetriever()
    try {
      val resolved = resolvePlaybackUri(url)
      if (resolved.startsWith("http")) {
        retriever.setDataSource(resolved, HashMap<String, String>())
      } else {
        retriever.setDataSource(reactContext, Uri.parse(resolved))
      }
      return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    } catch (_: Exception) {
      return 0L
    } finally {
      retriever.release()
    }
  }

  private fun extractWaveformSamples(uri: String, sampleCount: Int): List<Float> {
    return AudioWaveformExtractor.extract(reactContext, uri, sampleCount)
  }

  private fun emitProgress() {
    val currentPlayer = player ?: return
    val position = currentPlayer.currentPosition
    val duration = currentPlayer.duration

    if (abRepeatEnabled && position >= abRepeatEnd) {
      seekTo(abRepeatStart.toDouble())
    }

    currentSourceId?.let { setResumePosition(it, position.toDouble()) }

    val payload = Arguments.createMap()
    payload.putDouble("position", position.toDouble())
    payload.putDouble("duration", if (duration == C.TIME_UNSET) 0.0 else duration.toDouble())
    emit("progress", payload)

    val now = SystemClock.elapsedRealtime()
    if (
      configBool("showNotification", false) &&
      currentPlayer.isPlaying &&
      now - lastNotificationProgressAtMs >= 1_000L
    ) {
      lastNotificationProgressAtMs = now
      AudioKitNotificationHelper.updateProgress(
        reactContext,
        position,
        if (duration == C.TIME_UNSET) 0L else duration
      )
    }
  }

  private fun playbackStateMap(): ReadableMap {
    val currentPlayer = player
    val state = Arguments.createMap()
    val isPlaying = currentPlayer?.isPlaying == true
    val playbackState = currentPlayer?.playbackState ?: Player.STATE_IDLE
    val duration = currentPlayer?.duration ?: 0L
    val position = currentPlayer?.currentPosition ?: 0L

    val status = when {
      playbackState == Player.STATE_ENDED -> "completed"
      playbackState == Player.STATE_BUFFERING -> "buffering"
      playbackState == Player.STATE_IDLE && currentPlayer?.mediaItemCount == 0 -> "idle"
      playbackState == Player.STATE_IDLE -> "loading"
      isPlaying -> "playing"
      else -> "paused"
    }

    state.putString("status", status)
    state.putBoolean("playing", isPlaying)
    state.putBoolean("paused", !isPlaying && (currentPlayer?.mediaItemCount ?: 0) > 0)
    state.putBoolean("buffering", playbackState == Player.STATE_BUFFERING)
    state.putBoolean("loading", playbackState == Player.STATE_IDLE && (currentPlayer?.mediaItemCount ?: 0) > 0)
    state.putBoolean("completed", playbackState == Player.STATE_ENDED)
    state.putDouble("duration", if (duration == C.TIME_UNSET) 0.0 else duration.toDouble())
    state.putDouble("position", position.toDouble())
    state.putDouble("speed", (currentPlayer?.playbackParameters?.speed ?: 1f).toDouble())
    state.putDouble("volume", (currentPlayer?.volume ?: 1f).toDouble())
    return state
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      handler.post(block)
    }
  }

  private fun publishTransportState(isPlaying: Boolean, event: String) {
    if (transportIsPlaying == isPlaying) return
    transportIsPlaying = isPlaying
    val currentPlayer = player
    AudioKitNotificationHelper.updatePlaybackState(
      reactContext,
      isPlaying,
      currentPlayer?.currentPosition,
      currentPlayer?.duration?.takeUnless { it == C.TIME_UNSET }
    )
    emit(event, playbackStateMap())
  }

  private fun pauseInternal(abandonFocus: Boolean = true) {
    val currentPlayer = player ?: return
    if (currentPlayer.mediaItemCount == 0 || !transportIsPlaying) return
    currentPlayer.pause()
    publishTransportState(false, "pause")
    if (abandonFocus) {
      abandonAudioFocus()
    }
  }

  private fun resumeInternal() {
    val currentPlayer = player ?: return
    if (currentPlayer.mediaItemCount == 0 || transportIsPlaying) return
    requestAudioFocus()
    if (currentPlayer.playbackState == Player.STATE_ENDED) {
      currentPlayer.seekTo(0)
    }
    currentPlayer.playWhenReady = true
    currentPlayer.setPlaybackSpeed(playbackSpeed)
    publishTransportState(true, "resume")
  }

  private fun requestAudioFocus() {
    abandonAudioFocus()

    val focusMode = configString("audioFocus", "exclusive")
    val focusGain = when (focusMode) {
      "mix" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
      else -> AudioManager.AUDIOFOCUS_GAIN
    }

    val attrs = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build()

    focusRequest = AudioFocusRequest.Builder(focusGain)
      .setAudioAttributes(attrs)
      .setOnAudioFocusChangeListener { change ->
        handler.post {
          when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
              wasPlayingBeforeFocusLoss = player?.isPlaying == true
              pauseInternal(abandonFocus = false)
              // Permanent focus loss means another app/web/call took exclusive
              // audio focus (e.g. the user opened YouTube and kept it). The
              // transient branch already notifies JS via interruption/began;
              // permanent loss must do the same so the JS layer gets a reliable
              // signal (it only self-pauses on a began event) and can
              // self-correct if native pause ever fails. We do NOT resume here --
              // resume stays gated behind the AUDIOFOCUS_GAIN branch and the
              // resumeAfterFocusGain/resumeAfterCall flags (both false in-app),
              // so background playback and no-auto-resume behavior are preserved.
              emit("interruption", interruptionMap("began"))
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
              wasPlayingBeforeFocusLoss = player?.isPlaying == true
              if (configBool("pauseOnIncomingCall", true)) {
                pauseInternal(abandonFocus = false)
              }
              emit("interruption", interruptionMap("began"))
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
              emit("interruption", interruptionMap("ended"))
              if (configBool("resumeAfterFocusGain", false) || configBool("resumeAfterCall", false)) {
                if (wasPlayingBeforeFocusLoss) resumeInternal()
              }
            }
          }
        }
      }
      .build()

    audioManager.requestAudioFocus(focusRequest!!)
  }

  private fun abandonAudioFocus() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    focusRequest = null
  }

  private fun interruptionMap(type: String): ReadableMap {
    val map = Arguments.createMap()
    map.putString("type", type)
    return map
  }

  private fun emit(event: String, payload: ReadableMap) {
    if (!reactContext.hasActiveReactInstance()) return
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(event, payload)
  }

  private fun configBool(key: String, default: Boolean): Boolean {
    return config?.let {
      if (it.hasKey(key)) it.getBoolean(key) else default
    } ?: default
  }

  private fun configLong(key: String, default: Long): Long {
    return config?.let {
      if (it.hasKey(key)) it.getDouble(key).toLong() else default
    } ?: default
  }

  private fun configString(key: String, default: String): String {
    return config?.getString(key) ?: default
  }

  private fun ReadableMap.safeString(key: String): String {
    return if (hasKey(key) && !isNull(key)) getString(key)?.trim().orEmpty() else ""
  }
}
