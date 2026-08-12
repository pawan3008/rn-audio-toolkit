package com.audiokit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AudioKitActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    AudioKitModuleHolder.engine?.let { engine ->
      when (intent?.action) {
        "PLAY" -> engine.resume()
        "PAUSE" -> engine.pause()
      }
    }
  }
}

object AudioKitModuleHolder {
  var engine: AudioKitEngine? = null
}
