package com.audiokit

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = AudioKitModule.NAME)
class AudioKitModule(
  private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

  private val engine = AudioKitEngine(reactContext).also {
    AudioKitModuleHolder.engine = it
  }

  override fun getName(): String = NAME

  @ReactMethod
  fun configure(config: ReadableMap, promise: Promise) {
    try {
      engine.configure(config)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_CONFIGURE", e.message, e)
    }
  }

  @ReactMethod
  fun play(source: ReadableMap, promise: Promise) {
    try {
      engine.play(source)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_PLAY", e.message, e)
    }
  }

  @ReactMethod
  fun pause(promise: Promise) {
    try {
      engine.pause()
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_PAUSE", e.message, e)
    }
  }

  @ReactMethod
  fun resume(promise: Promise) {
    try {
      engine.resume()
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_RESUME", e.message, e)
    }
  }

  @ReactMethod
  fun stop(promise: Promise) {
    try {
      engine.stop()
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_STOP", e.message, e)
    }
  }

  @ReactMethod
  fun seekTo(positionMs: Double, promise: Promise) {
    try {
      engine.seekTo(positionMs)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_SEEK", e.message, e)
    }
  }

  @ReactMethod
  fun setVolume(volume: Double, promise: Promise) {
    try {
      engine.setVolume(volume)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_VOLUME", e.message, e)
    }
  }

  @ReactMethod
  fun setPlaybackSpeed(speed: Double, promise: Promise) {
    try {
      engine.setPlaybackSpeed(speed)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_SPEED", e.message, e)
    }
  }

  @ReactMethod
  fun preload(url: String, promise: Promise) {
    try {
      promise.resolve(engine.preload(url))
    } catch (e: Exception) {
      promise.reject("E_PRELOAD", e.message, e)
    }
  }

  @ReactMethod
  fun getDuration(url: String, promise: Promise) {
    try {
      promise.resolve(engine.getDuration(url))
    } catch (e: Exception) {
      promise.reject("E_DURATION", e.message, e)
    }
  }

  @ReactMethod
  fun cache(url: String, promise: Promise) {
    try {
      promise.resolve(engine.cache(url))
    } catch (e: Exception) {
      promise.reject("E_CACHE", e.message, e)
    }
  }

  @ReactMethod
  fun clearCache(promise: Promise) {
    try {
      engine.clearCache()
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_CLEAR_CACHE", e.message, e)
    }
  }

  @ReactMethod
  fun getCacheSize(promise: Promise) {
    try {
      promise.resolve(engine.getCacheSize())
    } catch (e: Exception) {
      promise.reject("E_CACHE_SIZE", e.message, e)
    }
  }

  @ReactMethod
  fun getPlaybackState(promise: Promise) {
    try {
      promise.resolve(engine.getPlaybackState())
    } catch (e: Exception) {
      promise.reject("E_STATE", e.message, e)
    }
  }

  @ReactMethod
  fun getWaveform(url: String, sampleCount: Int, promise: Promise) {
    Thread {
      try {
        val result = engine.getWaveform(url, sampleCount)
        reactContext.runOnUiQueueThread { promise.resolve(result) }
      } catch (e: Exception) {
        reactContext.runOnUiQueueThread {
          promise.reject("E_WAVEFORM", e.message, e)
        }
      }
    }.start()
  }

  @ReactMethod
  fun getResumePosition(sourceId: String, promise: Promise) {
    try {
      promise.resolve(engine.getResumePosition(sourceId))
    } catch (e: Exception) {
      promise.reject("E_RESUME_POS", e.message, e)
    }
  }

  @ReactMethod
  fun setResumePosition(sourceId: String, positionMs: Double, promise: Promise) {
    try {
      engine.setResumePosition(sourceId, positionMs)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_RESUME_POS", e.message, e)
    }
  }

  @ReactMethod
  fun setABRepeat(startMs: Double, endMs: Double, promise: Promise) {
    try {
      engine.setABRepeat(startMs, endMs)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_AB_REPEAT", e.message, e)
    }
  }

  @ReactMethod
  fun clearABRepeat(promise: Promise) {
    try {
      engine.clearABRepeat()
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_AB_REPEAT", e.message, e)
    }
  }

  @ReactMethod
  fun detectSilenceGaps(
    url: String,
    thresholdDb: Double,
    minSilenceMs: Double,
    promise: Promise,
  ) {
    Thread {
      try {
        val result = engine.detectSilenceGaps(url, thresholdDb, minSilenceMs)
        reactContext.runOnUiQueueThread { promise.resolve(result) }
      } catch (e: Exception) {
        reactContext.runOnUiQueueThread {
          promise.reject("E_SILENCE", e.message, e)
        }
      }
    }.start()
  }

  @ReactMethod
  fun destroy(promise: Promise) {
    try {
      engine.destroy()
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("E_DESTROY", e.message, e)
    }
  }

  @ReactMethod
  fun addListener(eventName: String) {}

  @ReactMethod
  fun removeListeners(count: Int) {}

  companion object {
    const val NAME = "RNAudioKitSpec"
  }
}
