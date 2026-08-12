import type { AudioKitConfig, AudioPresetName } from '../types/AudioConfig';
import { DEFAULT_AUDIO_KIT_CONFIG } from './defaultConfig';

export const MUSIC_PRESET: AudioKitConfig = {
  ...DEFAULT_AUDIO_KIT_CONFIG,
  backgroundPlayback: true,
  stopOnBackground: false,
  showNotification: true,
  continueOnScreenLock: true,
  continueOnAppBackground: true,
  resumeAfterInterruption: true,
  audioFocus: 'duck',
  enableLockScreenControls: true,
  enableHeadphoneControls: true,
  resumeAfterCall: true,
  resumeAfterFocusGain: true,
  keepAlive: true,
  autoDestroy: false,
  cache: true,
  preload: true,
  autoAdvance: true,
  highQualityPlayback: true,
};

export const LEARNING_PRESET: AudioKitConfig = {
  ...DEFAULT_AUDIO_KIT_CONFIG,
  backgroundPlayback: false,
  stopOnBackground: true,
  stopOnScreenLock: true,
  showNotification: false,
  releaseOnStop: true,
  autoDestroy: true,
  cache: true,
  preload: true,
};

export const PODCAST_PRESET: AudioKitConfig = {
  ...DEFAULT_AUDIO_KIT_CONFIG,
  backgroundPlayback: true,
  stopOnBackground: false,
  showNotification: true,
  continueOnAppBackground: true,
  resumeAfterInterruption: true,
  audioFocus: 'duck',
  enableLockScreenControls: true,
  enableHeadphoneControls: true,
  resumeAfterCall: true,
  keepAlive: true,
  cache: true,
  preload: true,
};

export const EXAM_PRESET: AudioKitConfig = {
  ...DEFAULT_AUDIO_KIT_CONFIG,
  backgroundPlayback: false,
  stopOnBackground: true,
  stopOnScreenLock: true,
  showNotification: false,
  audioFocus: 'exclusive',
  releaseOnStop: true,
  autoDestroy: true,
  releaseRecorderOnBackground: true,
  cache: false,
  preload: true,
  playOnce: true,
};

export const AudioPresets: Record<AudioPresetName, AudioKitConfig> = {
  MUSIC: MUSIC_PRESET,
  LEARNING: LEARNING_PRESET,
  PODCAST: PODCAST_PRESET,
  EXAM: EXAM_PRESET,
};
