import NativeAudioKit from '../native/AudioKitBridge';
import { createNativeModuleFallback } from '../native/NativeModuleFallback';
import { AudioEventBus } from './AudioEventBus';
import { AudioQueue } from './AudioQueue';
import { initializeNativeEventBridge } from './NativeEventBridge';
import {
  AudioPresets,
  DEFAULT_AUDIO_KIT_CONFIG,
  mergeConfig,
  resolveConfig,
} from '../config';
import type {
  ABRepeatRange,
  AudioEventName,
  AudioEventListener,
  AudioEventUnsubscribe,
  AudioKitConfig,
  AudioPresetName,
  Bookmark,
  ListeningAnalytics,
  PlaybackSpeed,
  PlaybackState,
} from '../types';
import { INITIAL_PLAYBACK_STATE } from '../types/PlaybackState';
import type { AudioSourceInput } from '../types/AudioSource';
import { resolveAudioSource } from '../utils/resolveAudioSource';
import { resetPlaybackTiming, setPlaybackTiming } from '../utils/playbackTiming';
import {
  canReplay as canReplaySource,
  isPlayOnceConsumed,
  markPlayOnceConsumed,
  resetPlayOnce as resetPlayOnceSource,
} from '../utils/playOnce';

function getNativeModule() {
  return NativeAudioKit ?? createNativeModuleFallback();
}

function ensureBridge(): void {
  initializeNativeEventBridge(AudioKit.events);
}

export class AudioKit {
  static readonly events = new AudioEventBus();
  private static config: AudioKitConfig = { ...DEFAULT_AUDIO_KIT_CONFIG };
  private static readonly queue = new AudioQueue();
  private static state: PlaybackState = { ...INITIAL_PLAYBACK_STATE };
  private static bookmarks: Bookmark[] = [];
  private static abRepeat: ABRepeatRange | null = null;
  private static analytics: ListeningAnalytics | null = null;
  private static autoResumeEnabled = true;
  private static silenceTrimEnabled = false;
  private static silenceGaps: Array<{ startMs: number; endMs: number }> = [];
  private static currentSourceId: string | null = null;
  private static bridgeBound = false;
  private static playOnceOverride: boolean | null = null;

  private static bindNativeStateSync(): void {
    if (AudioKit.bridgeBound) {
      return;
    }
    AudioKit.bridgeBound = true;
    ensureBridge();

    const syncState = (payload: PlaybackState) => {
      AudioKit.state = { ...AudioKit.state, ...payload };
    };

    AudioKit.events.on('play', syncState);
    AudioKit.events.on('pause', state => {
      syncState(state);
      AudioKit.saveResumePosition(state.position);
    });
    AudioKit.events.on('resume', syncState);
    AudioKit.events.on('stop', syncState);
    AudioKit.events.on('complete', async state => {
      AudioKit.state = { ...AudioKit.state, ...state };
      AudioKit.endAnalytics(false);
      if (AudioKit.currentSourceId) {
        if (AudioKit.isPlayOnceEnabled()) {
          markPlayOnceConsumed(AudioKit.currentSourceId);
        }
        getNativeModule()
          .setResumePosition(AudioKit.currentSourceId, 0)
          .catch(() => undefined);
      }
      if (AudioKit.config.autoAdvance) {
        const nextUri = AudioKit.queue.peekNext();
        if (nextUri) {
          await AudioKit.next();
        }
      }
    });
    AudioKit.events.on('buffer', syncState);
    AudioKit.events.on('progress', event => {
      AudioKit.state = {
        ...AudioKit.state,
        position: event.position,
        duration: event.duration,
      };
      AudioKit.handleSilenceTrim(event.position);
    });
    AudioKit.events.on('error', error => {
      AudioKit.state = {
        ...AudioKit.state,
        status: 'error',
        error: error.message,
      };
    });
  }

  static configure(
    configOrPreset: Partial<AudioKitConfig> | AudioKitConfig | AudioPresetName,
  ): void {
    AudioKit.bindNativeStateSync();

    if (typeof configOrPreset === 'string') {
      const preset = AudioPresets[configOrPreset];
      AudioKit.config = mergeConfig(DEFAULT_AUDIO_KIT_CONFIG, preset);
    } else {
      AudioKit.config = resolveConfig(configOrPreset);
    }

    getNativeModule()
      .configure(AudioKit.config as unknown as Object)
      .catch(() => undefined);
  }

  static getConfig(): AudioKitConfig {
    return { ...AudioKit.config };
  }

  static setAutoResume(enabled: boolean): void {
    AudioKit.autoResumeEnabled = enabled;
  }

  static setSilenceTrimming(enabled: boolean): void {
    AudioKit.silenceTrimEnabled = enabled;
  }

  /** Override config playOnce for the current session (pass null to use config) */
  static setPlayOnceOverride(enabled: boolean | null): void {
    AudioKit.playOnceOverride = enabled;
  }

  static isPlayOnceEnabled(): boolean {
    return AudioKit.playOnceOverride ?? AudioKit.config.playOnce;
  }

  static isPlayOnceConsumed(uri?: string): boolean {
    const sourceId = uri ?? AudioKit.currentSourceId;
    return sourceId ? isPlayOnceConsumed(sourceId) : false;
  }

  static canReplay(uri?: string): boolean {
    const sourceId = uri ?? AudioKit.currentSourceId;
    if (!sourceId) {
      return true;
    }
    return canReplaySource(sourceId, AudioKit.isPlayOnceEnabled());
  }

  static resetPlayOnce(uri?: string): void {
    resetPlayOnceSource(uri);
  }

  static async play(source?: AudioSourceInput): Promise<void> {
    AudioKit.bindNativeStateSync();

    const uri = source
      ? resolveAudioSource(source).uri
      : AudioKit.queue.current();

    if (!uri) {
      throw new Error('No audio source provided and queue is empty.');
    }

    const resolved = source ? resolveAudioSource(source) : { uri, type: 'url' };

    if (AudioKit.isPlayOnceConsumed(resolved.uri)) {
      return;
    }

    if (
      resolved.uri === AudioKit.currentSourceId &&
      AudioKit.state.paused &&
      !AudioKit.state.playing &&
      AudioKit.state.status !== 'stopped' &&
      AudioKit.state.status !== 'idle' &&
      AudioKit.state.status !== 'completed' &&
      !AudioKit.state.completed &&
      !AudioKit.isAtEnd()
    ) {
      await AudioKit.resume();
      return;
    }

    if (
      resolved.uri === AudioKit.currentSourceId &&
      AudioKit.isAtEnd()
    ) {
      if (AudioKit.isPlayOnceEnabled()) {
        return;
      }
      await AudioKit.replay();
      return;
    }

    if (
      resolved.uri !== AudioKit.currentSourceId &&
      AudioKit.state.playing
    ) {
      setPlaybackTiming(
        { position: 0, duration: 0 },
        { immediate: true, allowReset: true },
      );
    }

    AudioKit.currentSourceId = resolved.uri;
    const originalUri = resolved.uri;

    if (AudioKit.config.cache && resolved.uri.startsWith('http')) {
      try {
        const cached = await AudioKit.cache(resolved.uri);
        if (cached.cached && cached.path && !cached.path.startsWith('http')) {
          resolved.uri = cached.path;
        }
      } catch {
        resolved.uri = originalUri;
      }
    }

    if (AudioKit.config.preload) {
      try {
        await AudioKit.preload(resolved.uri);
      } catch {
        if (resolved.uri !== originalUri) {
          resolved.uri = originalUri;
          try {
            await AudioKit.preload(resolved.uri);
          } catch {
            // Continue playback even if metadata preload fails.
          }
        }
      }
    }

    if (AudioKit.silenceTrimEnabled) {
      const gaps = await AudioKit.detectSilenceGaps(resolved.uri);
      AudioKit.silenceGaps = gaps.gaps;
    }

    let resumePosition = 0;
    if (AudioKit.autoResumeEnabled && !AudioKit.isPlayOnceEnabled()) {
      resumePosition = await getNativeModule().getResumePosition(resolved.uri);
    }

    await getNativeModule().play(resolved);

    if (resumePosition > 0) {
      await AudioKit.seekTo(resumePosition);
    }

    AudioKit.startAnalytics(resolved.uri);
  }

  static async pause(): Promise<void> {
    await getNativeModule().pause();
  }

  static async resume(): Promise<void> {
    if (AudioKit.isPlayOnceConsumed(AudioKit.currentSourceId ?? undefined)) {
      return;
    }
    if (AudioKit.isAtEnd()) {
      if (AudioKit.isPlayOnceEnabled()) {
        return;
      }
      await AudioKit.replay();
      return;
    }
    await getNativeModule().resume();
  }

  /** Restart current track from the beginning */
  static async replay(): Promise<void> {
    if (!AudioKit.canReplay(AudioKit.currentSourceId ?? undefined)) {
      return;
    }
    AudioKit.bindNativeStateSync();
    await getNativeModule().seekTo(0);
    AudioKit.state = {
      ...AudioKit.state,
      position: 0,
      completed: false,
      playing: true,
      paused: false,
      status: 'playing',
    };
    AudioKit.events.emit('seek', { position: 0 });
    await getNativeModule().resume();
  }

  static isAtEnd(): boolean {
    const { completed, status, duration, position } = AudioKit.state;
    return (
      completed ||
      status === 'completed' ||
      (duration > 0 && position >= duration - 200)
    );
  }

  static async stop(): Promise<void> {
    await AudioKit.saveResumePosition(AudioKit.state.position);
    await getNativeModule().stop();
    AudioKit.endAnalytics(true);
    AudioKit.state = { ...INITIAL_PLAYBACK_STATE, status: 'stopped' };
    AudioKit.currentSourceId = null;
    resetPlaybackTiming();
    AudioKit.events.emit('stop', AudioKit.state);
  }

  /** Fully stops playback, clears resume position, and resets progress */
  static async forceStop(clearResume = true): Promise<void> {
    const sourceId = AudioKit.currentSourceId;
    await getNativeModule().stop();
    AudioKit.endAnalytics(true);
    if (clearResume && sourceId) {
      await getNativeModule().setResumePosition(sourceId, 0).catch(() => undefined);
    }
    AudioKit.state = { ...INITIAL_PLAYBACK_STATE, status: 'stopped' };
    AudioKit.currentSourceId = null;
    resetPlaybackTiming();
    AudioKit.events.emit('stop', AudioKit.state);
  }

  static async seekTo(positionMs: number): Promise<void> {
    if (
      AudioKit.isPlayOnceEnabled() &&
      AudioKit.isPlayOnceConsumed(AudioKit.currentSourceId ?? undefined)
    ) {
      return;
    }
    await getNativeModule().seekTo(positionMs);
    AudioKit.state = { ...AudioKit.state, position: positionMs };
    AudioKit.events.emit('seek', { position: positionMs });
  }

  static async setVolume(volume: number): Promise<void> {
    const clamped = Math.max(0, Math.min(1, volume));
    await getNativeModule().setVolume(clamped);
    AudioKit.state = { ...AudioKit.state, volume: clamped };
    AudioKit.events.emit('volumeChange', { volume: clamped });
  }

  static async setPlaybackSpeed(speed: PlaybackSpeed): Promise<void> {
    await getNativeModule().setPlaybackSpeed(speed);
    AudioKit.state = { ...AudioKit.state, speed };
    AudioKit.events.emit('speedChange', { speed });
  }

  static async preload(url: string): Promise<{ durationMs: number }> {
    return getNativeModule().preload(url);
  }

  static async getDuration(url: string): Promise<number> {
    return getNativeModule().getDuration(url);
  }

  static async getWaveform(url: string, sampleCount = 100): Promise<number[]> {
    const result = await getNativeModule().getWaveform(url, sampleCount);
    return result.samples;
  }

  static async cache(url: string): Promise<{ path: string; cached: boolean }> {
    return getNativeModule().cache(url);
  }

  static async clearCache(): Promise<void> {
    await getNativeModule().clearCache();
  }

  static async getCacheSize(): Promise<number> {
    return getNativeModule().getCacheSize();
  }

  static async detectSilenceGaps(
    url: string,
    thresholdDb = -40,
    minSilenceMs = 500,
  ): Promise<{ gaps: Array<{ startMs: number; endMs: number }> }> {
    return getNativeModule().detectSilenceGaps(url, thresholdDb, minSilenceMs);
  }

  static addQueue(source: AudioSourceInput): void {
    AudioKit.queue.add(source);
    AudioKit.events.emit('queueChange', {
      index: AudioKit.queue.getIndex(),
      total: AudioKit.queue.getLength(),
    });
  }

  static async next(): Promise<void> {
    const uri = AudioKit.queue.next();
    if (uri) {
      await AudioKit.play(uri);
      AudioKit.events.emit('queueChange', {
        index: AudioKit.queue.getIndex(),
        total: AudioKit.queue.getLength(),
      });
    }
  }

  static async previous(): Promise<void> {
    const uri = AudioKit.queue.previous();
    if (uri) {
      await AudioKit.play(uri);
      AudioKit.events.emit('queueChange', {
        index: AudioKit.queue.getIndex(),
        total: AudioKit.queue.getLength(),
      });
    }
  }

  static clearQueue(): void {
    AudioKit.queue.clear();
    AudioKit.events.emit('queueChange', { index: -1, total: 0 });
  }

  static getPlaybackState(): PlaybackState {
    return { ...AudioKit.state };
  }

  static async syncPlaybackState(): Promise<PlaybackState> {
    try {
      const nativeState = await getNativeModule().getPlaybackState();
      AudioKit.state = { ...AudioKit.state, ...(nativeState as PlaybackState) };
    } catch {
      // ignore
    }
    return AudioKit.getPlaybackState();
  }

  static on<T extends AudioEventName>(
    event: T,
    listener: AudioEventListener<T>,
  ): AudioEventUnsubscribe {
    AudioKit.bindNativeStateSync();
    return AudioKit.events.on(event, listener);
  }

  static async setABRepeat(range: ABRepeatRange | null): Promise<void> {
    AudioKit.abRepeat = range;
    if (range) {
      await getNativeModule().setABRepeat(range.startMs, range.endMs);
    } else {
      await getNativeModule().clearABRepeat();
    }
  }

  static getABRepeat(): ABRepeatRange | null {
    return AudioKit.abRepeat ? { ...AudioKit.abRepeat } : null;
  }

  static addBookmark(positionMs: number, label?: string): Bookmark {
    const bookmark: Bookmark = {
      id: `${Date.now()}`,
      positionMs,
      label,
      createdAt: Date.now(),
    };
    AudioKit.bookmarks.push(bookmark);
    return bookmark;
  }

  static getBookmarks(): readonly Bookmark[] {
    return [...AudioKit.bookmarks];
  }

  static removeBookmark(id: string): void {
    AudioKit.bookmarks = AudioKit.bookmarks.filter(b => b.id !== id);
  }

  static getAnalytics(): ListeningAnalytics | null {
    return AudioKit.analytics ? { ...AudioKit.analytics } : null;
  }

  static async destroy(): Promise<void> {
    await getNativeModule().destroy();
    AudioKit.state = { ...INITIAL_PLAYBACK_STATE };
    AudioKit.currentSourceId = null;
  }

  private static handleSilenceTrim(positionMs: number): void {
    if (!AudioKit.silenceTrimEnabled || AudioKit.silenceGaps.length === 0) {
      return;
    }
    for (const gap of AudioKit.silenceGaps) {
      if (positionMs >= gap.startMs && positionMs < gap.endMs) {
        AudioKit.seekTo(gap.endMs).catch(() => undefined);
        break;
      }
    }
  }

  private static async saveResumePosition(positionMs: number): Promise<void> {
    if (!AudioKit.currentSourceId || !AudioKit.autoResumeEnabled) {
      return;
    }
    await getNativeModule()
      .setResumePosition(AudioKit.currentSourceId, positionMs)
      .catch(() => undefined);
  }

  private static startAnalytics(sourceId: string): void {
    AudioKit.analytics = {
      listeningTimeMs: 0,
      completionPercent: 0,
      skipped: false,
      speedUsed: AudioKit.state.speed,
      sourceId,
      startedAt: Date.now(),
    };
  }

  private static endAnalytics(skipped: boolean): void {
    if (!AudioKit.analytics) {
      return;
    }
    const endedAt = Date.now();
    const duration = AudioKit.state.duration;
    const position = AudioKit.state.position;
    AudioKit.analytics = {
      ...AudioKit.analytics,
      endedAt,
      skipped,
      listeningTimeMs: endedAt - AudioKit.analytics.startedAt,
      completionPercent: duration > 0 ? (position / duration) * 100 : 0,
      speedUsed: AudioKit.state.speed,
    };
  }
}
