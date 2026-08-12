import type { Spec } from './NativeAudioKit';

const NOT_LINKED =
  'rn-audio-toolkit native module is not linked. Run pod install and rebuild the app.';

export function createNativeModuleFallback(): Spec {
  const reject = () => Promise.reject(new Error(NOT_LINKED));

  return {
    configure: reject,
    play: reject,
    pause: reject,
    resume: reject,
    stop: reject,
    seekTo: reject,
    setVolume: reject,
    setPlaybackSpeed: reject,
    preload: reject,
    getDuration: reject,
    cache: reject,
    clearCache: reject,
    getCacheSize: reject,
    getPlaybackState: reject,
    getWaveform: reject,
    getResumePosition: reject,
    setResumePosition: reject,
    setABRepeat: reject,
    clearABRepeat: reject,
    detectSilenceGaps: reject,
    destroy: reject,
    startRecording: reject,
    stopRecording: reject,
    pauseRecording: reject,
    resumeRecording: reject,
    getRecordingState: reject,
    addListener: () => undefined,
    removeListeners: () => undefined,
  };
}
