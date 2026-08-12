package com.audiokit

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object AudioWaveformExtractor {

  fun extract(context: Context, uri: String, sampleCount: Int): List<Float> {
    val count = max(10, sampleCount)
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null

    try {
      setDataSource(extractor, context, uri)

      val audioTrackIndex = findAudioTrackIndex(extractor)
      if (audioTrackIndex < 0) {
        return fallbackSamples(count)
      }

      extractor.selectTrack(audioTrackIndex)
      val inputFormat = extractor.getTrackFormat(audioTrackIndex)
      val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return fallbackSamples(count)

      val durationUs = when {
        inputFormat.containsKey(MediaFormat.KEY_DURATION) ->
          inputFormat.getLong(MediaFormat.KEY_DURATION)
        else -> 0L
      }

      codec = MediaCodec.createDecoderByType(mime)
      codec.configure(inputFormat, null, null, 0)
      codec.start()

      val peaks = FloatArray(count)
      val bufferInfo = MediaCodec.BufferInfo()
      var inputDone = false
      var outputDone = false
      var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

      while (!outputDone) {
        if (!inputDone) {
          val inputIndex = codec.dequeueInputBuffer(10_000)
          if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
              codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
              )
              inputDone = true
            } else {
              codec.queueInputBuffer(
                inputIndex,
                0,
                sampleSize,
                extractor.sampleTime,
                0,
              )
              extractor.advance()
            }
          }
        }

        val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
        when {
          outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val outputFormat = codec.outputFormat
            if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
              outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
              outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
          }

          outputIndex >= 0 -> {
            if (bufferInfo.size > 0) {
              val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
              accumulatePeaks(
                peaks = peaks,
                buffer = outputBuffer,
                bufferInfo = bufferInfo,
                sampleRate = outputSampleRate,
                channelCount = outputChannels,
                durationUs = durationUs,
              )
            }
            codec.releaseOutputBuffer(outputIndex, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              outputDone = true
            }
          }
        }
      }

      if (peaks.all { it == 0f }) {
        return fallbackSamples(count)
      }

      return peaks.map { min(1f, it) }
    } catch (_: Exception) {
      return fallbackSamples(count)
    } finally {
      try {
        codec?.stop()
      } catch (_: Exception) {
      }
      try {
        codec?.release()
      } catch (_: Exception) {
      }
      try {
        extractor.release()
      } catch (_: Exception) {
      }
    }
  }

  private fun setDataSource(extractor: MediaExtractor, context: Context, uri: String) {
    when {
      uri.startsWith("http") -> extractor.setDataSource(uri, HashMap())
      uri.startsWith("file://") || uri.startsWith("content://") ->
        extractor.setDataSource(context, Uri.parse(uri), null)
      uri.startsWith("/") -> extractor.setDataSource(uri)
      else -> extractor.setDataSource(context, Uri.parse(uri), null)
    }
  }

  private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
    for (index in 0 until extractor.trackCount) {
      val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith("audio/")) {
        return index
      }
    }
    return -1
  }

  private fun accumulatePeaks(
    peaks: FloatArray,
    buffer: java.nio.ByteBuffer,
    bufferInfo: MediaCodec.BufferInfo,
    sampleRate: Int,
    channelCount: Int,
    durationUs: Long,
  ) {
    if (sampleRate <= 0 || channelCount <= 0) {
      return
    }

    buffer.position(bufferInfo.offset)
    buffer.limit(bufferInfo.offset + bufferInfo.size)
    val shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val frameCount = shortBuffer.remaining() / channelCount
    if (frameCount <= 0) {
      return
    }

    val effectiveDurationUs = if (durationUs > 0) {
      durationUs
    } else {
      frameCount.toLong() * 1_000_000L / sampleRate
    }

    for (frame in 0 until frameCount) {
      var peak = 0f
      for (channel in 0 until channelCount) {
        val sample = shortBuffer.get(frame * channelCount + channel)
        peak = max(peak, abs(sample.toFloat()) / Short.MAX_VALUE)
      }

      val frameTimeUs = bufferInfo.presentationTimeUs +
        (frame.toLong() * 1_000_000L / sampleRate)
      val bucket = ((frameTimeUs * peaks.size) / effectiveDurationUs)
        .toInt()
        .coerceIn(0, peaks.size - 1)
      peaks[bucket] = max(peaks[bucket], peak)
    }
  }

  private fun fallbackSamples(count: Int): List<Float> {
    return List(count) { 0.3f }
  }
}
