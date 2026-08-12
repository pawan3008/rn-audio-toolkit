package com.audiokit

import com.facebook.react.TurboReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class AudioKitPackage : TurboReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return if (name == AudioKitModule.NAME) {
      AudioKitModule(reactContext)
    } else {
      null
    }
  }

  // Old architecture (Bridge) fallback — exposes the module via NativeModules
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
    return listOf(AudioKitModule(reactContext))
  }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
    return ReactModuleInfoProvider {
      mapOf(
        AudioKitModule.NAME to ReactModuleInfo(
          AudioKitModule.NAME,
          AudioKitModule.NAME,
          false, // canOverrideExistingModule
          false, // needsEagerInit
          false, // isCxxModule
          false  // isTurboModule — set to false for backward compatibility
        )
      )
    }
  }
}
