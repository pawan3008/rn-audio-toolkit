package com.audiokit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AudioKitActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    AudioKitModuleHolder.engine?.let { engine ->
      when (intent?.action) {
        ACTION_PLAY -> engine.resume()
        ACTION_PAUSE -> engine.pause()
        ACTION_PREVIOUS -> engine.remotePrevious()
        ACTION_NEXT -> engine.remoteNext()
      }
    }
  }

  companion object {
    const val ACTION_PLAY = "com.audiokit.action.PLAY"
    const val ACTION_PAUSE = "com.audiokit.action.PAUSE"
    const val ACTION_PREVIOUS = "com.audiokit.action.PREVIOUS"
    const val ACTION_NEXT = "com.audiokit.action.NEXT"
  }
}

object AudioKitModuleHolder {
  var engine: AudioKitEngine? = null
}
