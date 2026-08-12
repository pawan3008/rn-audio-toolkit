import { TurboModuleRegistry, type TurboModule } from 'react-native';

export interface Spec extends TurboModule {
  configure(config: Object): Promise<void>;
  play(source: Object): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  stop(): Promise<void>;
  seekTo(positionMs: number): Promise<void>;
  setVolume(volume: number): Promise<void>;
  setPlaybackSpeed(speed: number): Promise<void>;
  preload(url: string): Promise<{ durationMs: number }>;
  getDuration(url: string): Promise<number>;
  cache(url: string): Promise<{ path: string; cached: boolean }>;
  clearCache(): Promise<void>;
  getCacheSize(): Promise<number>;
  getPlaybackState(): Promise<Object>;
  getWaveform(url: string, sampleCount: number): Promise<{ samples: number[] }>;
  getResumePosition(sourceId: string): Promise<number>;
  setResumePosition(sourceId: string, positionMs: number): Promise<void>;
  setABRepeat(startMs: number, endMs: number): Promise<void>;
  clearABRepeat(): Promise<void>;
  detectSilenceGaps(
    url: string,
    thresholdDb: number,
    minSilenceMs: number,
  ): Promise<{ gaps: Array<{ startMs: number; endMs: number }> }>;
  destroy(): Promise<void>;

  // Recording
  startRecording(config: Object): Promise<void>;
  stopRecording(): Promise<Object>;
  pauseRecording(): Promise<void>;
  resumeRecording(): Promise<void>;
  getRecordingState(): Promise<Object>;

  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.get<Spec>('RNAudioKitSpec');
