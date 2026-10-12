package com.audiokit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class AudioKitArtworkLoader(context: Context) {

  data class Artwork(val bitmap: Bitmap, val data: ByteArray)

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val executor = Executors.newFixedThreadPool(2)
  private val cache = object : LruCache<String, Artwork>(CACHE_BYTES) {
    override fun sizeOf(key: String, value: Artwork): Int = value.bitmap.byteCount + value.data.size
  }

  @Volatile
  private var currentRequest: Future<*>? = null

  fun load(value: String, callback: (Artwork?) -> Unit) {
    currentRequest?.cancel(true)
    cache.get(value)?.let { cached ->
      mainHandler.post { callback(cached) }
      return
    }

    currentRequest = executor.submit {
      val artwork = try {
        loadArtwork(value)
      } catch (_: Exception) {
        null
      }
      if (Thread.currentThread().isInterrupted) return@submit
      if (artwork != null) cache.put(value, artwork)
      mainHandler.post { callback(artwork) }
    }
  }

  fun invalidate() {
    currentRequest?.cancel(true)
    currentRequest = null
  }

  fun release() {
    invalidate()
    executor.shutdownNow()
    cache.evictAll()
  }

  private fun loadArtwork(value: String): Artwork? {
    if (value.isBlank()) return null
    val bytes = when {
      value.startsWith("http://") || value.startsWith("https://") -> loadRemote(value)
      value.startsWith("data:image/") -> loadDataUri(value)
      value.startsWith("file://") -> {
        File(Uri.parse(value).path ?: return null).inputStream().use(::readBounded)
      }
      value.startsWith("/") -> File(value).inputStream().use(::readBounded)
      value.startsWith("content://") || value.startsWith("android.resource://") -> {
        appContext.contentResolver.openInputStream(Uri.parse(value))?.use(::readBounded)
      }
      value.startsWith("asset://") -> {
        appContext.assets.open(value.removePrefix("asset://")).use(::readBounded)
      }
      else -> null
    } ?: return null

    return decode(bytes)
  }

  private fun loadRemote(value: String): ByteArray? {
    val url = URL(value)
    if (url.protocol != "http" && url.protocol != "https") return null
    val connection = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = 5_000
      readTimeout = 10_000
      instanceFollowRedirects = true
    }
    return try {
      connection.connect()
      if (connection.responseCode !in 200..299) return null
      val length = connection.contentLengthLong
      if (length > MAX_INPUT_BYTES) return null
      connection.inputStream.use(::readBounded)
    } finally {
      connection.disconnect()
    }
  }

  private fun loadDataUri(value: String): ByteArray? {
    val comma = value.indexOf(',')
    if (comma <= 0 || !value.substring(0, comma).contains(";base64")) return null
    val encoded = value.substring(comma + 1)
    if (encoded.length > MAX_BASE64_LENGTH) return null
    return try {
      Base64.decode(encoded, Base64.DEFAULT).takeIf { it.size <= MAX_INPUT_BYTES }
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun readBounded(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
      if (Thread.currentThread().isInterrupted) return null
      val count = input.read(buffer)
      if (count < 0) break
      total += count
      if (total > MAX_INPUT_BYTES) return null
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }

  private fun decode(bytes: ByteArray): Artwork? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > MAX_EDGE || bounds.outHeight / sampleSize > MAX_EDGE) {
      sampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeStream(
      ByteArrayInputStream(bytes),
      null,
      BitmapFactory.Options().apply { inSampleSize = sampleSize }
    ) ?: return null

    val bounded = if (bitmap.width > MAX_EDGE || bitmap.height > MAX_EDGE) {
      val scale = minOf(MAX_EDGE.toFloat() / bitmap.width, MAX_EDGE.toFloat() / bitmap.height)
      Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
      ).also { if (it !== bitmap) bitmap.recycle() }
    } else {
      bitmap
    }

    val encoded = ByteArrayOutputStream().use { output ->
      if (!bounded.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
      output.toByteArray()
    }
    return Artwork(bounded, encoded)
  }

  private companion object {
    const val MAX_INPUT_BYTES = 10 * 1024 * 1024
    const val MAX_BASE64_LENGTH = (MAX_INPUT_BYTES * 4 / 3) + 16
    const val MAX_EDGE = 1024
    const val CACHE_BYTES = 8 * 1024 * 1024
  }
}
