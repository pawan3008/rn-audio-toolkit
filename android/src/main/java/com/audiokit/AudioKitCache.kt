package com.audiokit

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.net.URL
import java.security.MessageDigest

class AudioKitCache(private val context: Context) {

  private val cacheDir: File = File(context.cacheDir, "RNAudioKitCache").apply { mkdirs() }

  fun getCachedPath(url: String): String? {
    val file = fileForUrl(url)
    return if (file.exists()) file.absolutePath else null
  }

  fun cache(url: String, sizeLimit: Long): Pair<String, Boolean> {
    if (!url.startsWith("http")) {
      return url to true
    }

    val file = fileForUrl(url)
    if (file.exists()) {
      return file.absolutePath to true
    }

    enforceLimit(sizeLimit)

    return try {
      URL(url).openStream().use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
      }
      file.absolutePath to true
    } catch (_: Exception) {
      url to false
    }
  }

  fun clear() {
    cacheDir.listFiles()?.forEach { it.delete() }
  }

  fun size(): Long {
    return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
  }

  private fun fileForUrl(url: String): File {
    val hash = MessageDigest.getInstance("SHA-256")
      .digest(url.toByteArray())
      .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP) }
    val ext = Uri.parse(url).lastPathSegment
      ?.substringAfterLast('.', "")
      ?.lowercase()
      ?.takeIf { it.isNotEmpty() }
      ?: "audio"
    return File(cacheDir, "$hash.$ext")
  }

  private fun enforceLimit(limit: Long) {
    if (limit <= 0) return
    val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
    var total = files.sumOf { it.length() }
    for (file in files) {
      if (total <= limit) break
      total -= file.length()
      file.delete()
    }
  }
}
