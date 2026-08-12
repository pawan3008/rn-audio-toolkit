export type AudioFocusMode = 'exclusive' | 'duck' | 'mix';

export interface AudioKitConfig {
  backgroundPlayback: boolean;
  stopOnBackground: boolean;
  stopOnScreenLock: boolean;
  showNotification: boolean;
  continueOnScreenLock: boolean;
  continueOnAppBackground: boolean;
  resumeAfterInterruption: boolean;
  audioFocus: AudioFocusMode;
  enableLockScreenControls: boolean;
  enableHeadphoneControls: boolean;
  pauseOnIncomingCall: boolean;
  pauseOnBluetoothDisconnect: boolean;
  pauseOnHeadphoneDisconnect: boolean;
  resumeAfterCall: boolean;
  resumeAfterFocusGain: boolean;
  releaseOnStop: boolean;
  keepAlive: boolean;
  autoDestroy: boolean;
  releaseRecorderOnBackground: boolean;
  cache: boolean;
  preload: boolean;
  cacheSizeLimit: number;
  defaultPlaybackSpeed: PlaybackSpeed;
  /** Auto-play next track in queue when current track completes */
  autoAdvance: boolean;
  /** Enable high-quality audio output (Bluetooth A2DP, media session) */
  highQualityPlayback: boolean;
  /** Allow only one full playback per source (exam/mock listening) */
  playOnce: boolean;
}

export type PlaybackSpeed = 0.5 | 0.75 | 1 | 1.25 | 1.5 | 2;

export const PLAYBACK_SPEEDS: readonly PlaybackSpeed[] = [
  0.5, 0.75, 1, 1.25, 1.5, 2,
];

export type AudioPresetName = 'MUSIC' | 'LEARNING' | 'PODCAST' | 'EXAM';
