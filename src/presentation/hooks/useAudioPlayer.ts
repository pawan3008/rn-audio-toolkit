import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AudioKit } from '../../core/AudioKit';
import type {
  AudioEventUnsubscribe,
  PlaybackSpeed,
  PlaybackState,
} from '../../types';
import type { AudioSourceInput } from '../../types/AudioSource';
import {
  getPlaybackTiming,
  resetPlaybackTiming,
  setPlaybackTiming,
} from '../../utils/playbackTiming';
import {
  getCachedWaveform,
  setCachedWaveform,
} from '../../utils/waveformCache';

export interface UseAudioPlayerOptions {
  autoPlay?: boolean;
  /** Auto-play when source URI changes (e.g. skip to next track) */
  autoPlayOnSourceChange?: boolean;
  /** Allow only one full playback per source (exam/mock listening) */
  playOnce?: boolean;
  preload?: boolean;
  cache?: boolean;
  initialSpeed?: PlaybackSpeed;
  waveformSamples?: number;
}

export interface UseAudioPlayerReturn {
  state: PlaybackState;
  play: (source?: AudioSourceInput) => Promise<void>;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  stop: () => Promise<void>;
  /** Fully stops playback, clears resume position, resets progress */
  forceStop: (clearResume?: boolean) => Promise<void>;
  seekTo: (positionMs: number) => Promise<void>;
  setVolume: (volume: number) => Promise<void>;
  setSpeed: (speed: PlaybackSpeed) => Promise<void>;
  preload: (url: string) => Promise<{ durationMs: number }>;
  getDuration: (url: string) => Promise<number>;
  loadWaveform: (url?: string, sampleCount?: number) => Promise<number[]>;
  duration: number;
  progress: number;
  waveform: number[];
  /** True when playOnce is enabled and this source has finished playing */
  playOnceConsumed: boolean;
  /** False when playOnce blocks replay for the current source */
  canReplay: boolean;
  /** Clear playOnce lock for the current source (e.g. new exam attempt) */
  resetPlayOnce: () => void;
}

function toTransportState(state: PlaybackState): PlaybackState {
  const timing = getPlaybackTiming();
  return {
    ...state,
    position: timing.position,
    duration: timing.duration || state.duration,
  };
}

function syncTimingFromState(
  payload: PlaybackState,
  immediate = true,
): void {
  const next: { position?: number; duration?: number } = {};
  if (payload.duration > 0) {
    next.duration = payload.duration;
  }
  if (payload.position > 0 || payload.paused || payload.status === 'paused') {
    next.position = payload.position;
  }
  if (Object.keys(next).length > 0) {
    setPlaybackTiming(next, { immediate });
  }
}

export function useAudioPlayer(
  source?: AudioSourceInput,
  options?: UseAudioPlayerOptions,
): UseAudioPlayerReturn {
  const [transport, setTransport] = useState<PlaybackState>(() =>
    toTransportState(AudioKit.getPlaybackState()),
  );
  const [waveform, setWaveform] = useState<number[]>([]);
  const [playOnceConsumed, setPlayOnceConsumed] = useState(false);
  const initialSpeedApplied = useRef(false);
  const resolvedUriRef = useRef<string | undefined>(undefined);
  const wasPlayingRef = useRef(false);
  const hasAutoPlayedRef = useRef(false);

  const resolvedUri = useMemo(() => {
    if (!source) {
      return undefined;
    }
    return typeof source === 'string' ? source : source.uri;
  }, [source]);

  useEffect(() => {
    wasPlayingRef.current = transport.playing;
  }, [transport.playing]);

  useEffect(() => {
    AudioKit.setPlayOnceOverride(
      options?.playOnce === undefined ? null : options.playOnce,
    );
    return () => AudioKit.setPlayOnceOverride(null);
  }, [options?.playOnce]);

  useEffect(() => {
    setPlayOnceConsumed(false);
    if (resolvedUri) {
      AudioKit.resetPlayOnce(resolvedUri);
    }
  }, [resolvedUri]);

  const loadWaveform = useCallback(
    async (url?: string, sampleCount = options?.waveformSamples ?? 80) => {
      const target = url ?? resolvedUri;
      if (!target) {
        return [];
      }

      const cached = getCachedWaveform(target);
      if (cached) {
        setWaveform(cached);
        return cached;
      }

      const samples = await AudioKit.getWaveform(target, sampleCount);
      setCachedWaveform(target, samples);
      if (resolvedUriRef.current === target) {
        setWaveform(samples);
      }
      return samples;
    },
    [resolvedUri, options?.waveformSamples],
  );

  useEffect(() => {
    const current = AudioKit.getPlaybackState();
    setPlaybackTiming(
      { position: current.position, duration: current.duration },
      { immediate: true },
    );

    const sync = (payload: PlaybackState) => {
      setTransport(prev => ({ ...prev, ...payload }));
    };

    const unsubscribers: AudioEventUnsubscribe[] = [
      AudioKit.on('play', event => {
        syncTimingFromState(event);
        sync(event);
      }),
      AudioKit.on('pause', event => {
        syncTimingFromState(event);
        sync(event);
      }),
      AudioKit.on('resume', event => {
        syncTimingFromState(event);
        sync(event);
      }),
      AudioKit.on('stop', event => {
        resetPlaybackTiming();
        sync(event);
      }),
      AudioKit.on('complete', event => {
        const timing = getPlaybackTiming();
        setPlaybackTiming(
          {
            position: timing.duration > 0 ? timing.duration : timing.position,
          },
          { immediate: true },
        );
        if (AudioKit.isPlayOnceEnabled()) {
          setPlayOnceConsumed(true);
        }
        sync(event);
      }),
      AudioKit.on('buffer', sync),
      AudioKit.on('progress', event => {
        setPlaybackTiming({
          position: event.position,
          duration: event.duration,
        });
      }),
      AudioKit.on('seek', event => {
        setPlaybackTiming(
          { position: event.position },
          { immediate: true, allowReset: event.position === 0 },
        );
      }),
      AudioKit.on('speedChange', event => {
        setTransport(prev => ({ ...prev, speed: event.speed }));
      }),
      AudioKit.on('volumeChange', event => {
        setTransport(prev => ({ ...prev, volume: event.volume }));
      }),
    ];

    return () => unsubscribers.forEach(unsub => unsub());
  }, []);

  useEffect(() => {
    if (!resolvedUri) {
      resolvedUriRef.current = undefined;
      setWaveform([]);
      return;
    }

    const shouldAutoPlay =
      options?.autoPlayOnSourceChange && wasPlayingRef.current;
    const shouldStartPlayback =
      shouldAutoPlay || (options?.autoPlay === true && !hasAutoPlayedRef.current);

    resolvedUriRef.current = resolvedUri;
    const cached = getCachedWaveform(resolvedUri);
    setWaveform(cached ?? []);
    setPlaybackTiming({ position: 0, duration: 0 }, { immediate: true, allowReset: true });

    let cancelled = false;

    const run = async () => {
      const waveformPromise =
        options?.waveformSamples && !cached
          ? loadWaveform(resolvedUri, options.waveformSamples).catch(() => undefined)
          : Promise.resolve();

      try {
        if (options?.preload) {
          await AudioKit.preload(resolvedUri);
        }
        if (cancelled) {
          return;
        }
        if (options?.cache) {
          await AudioKit.cache(resolvedUri);
        }
        if (cancelled) {
          return;
        }
        if (shouldStartPlayback) {
          await AudioKit.play(source);
          hasAutoPlayedRef.current = true;
        }
      } catch {
        // Preload/cache/play failures should not block the player UI.
      }

      await waveformPromise;
    };

    run().catch(() => undefined);

    return () => {
      cancelled = true;
    };
  }, [
    resolvedUri,
    source,
    options?.autoPlay,
    options?.autoPlayOnSourceChange,
    options?.cache,
    options?.preload,
    options?.waveformSamples,
    loadWaveform,
  ]);

  useEffect(() => {
    if (initialSpeedApplied.current || options?.initialSpeed == null) {
      return;
    }
    initialSpeedApplied.current = true;
    AudioKit.setPlaybackSpeed(options.initialSpeed).catch(() => undefined);
  }, [options?.initialSpeed]);

  const play = useCallback(
    async (playSource?: AudioSourceInput) => {
      const targetUri = playSource
        ? typeof playSource === 'string'
          ? playSource
          : playSource.uri
        : resolvedUri;

      if (targetUri && !AudioKit.canReplay(targetUri)) {
        return;
      }

      const kitState = AudioKit.getPlaybackState();
      const isAtEnd = AudioKit.isAtEnd();
      const isPausedSameSource =
        kitState.paused &&
        !kitState.playing &&
        !isAtEnd &&
        targetUri === resolvedUri &&
        kitState.status !== 'stopped';

      if (isAtEnd && targetUri === resolvedUri) {
        if (!AudioKit.canReplay(targetUri)) {
          return;
        }
        await AudioKit.replay();
      } else if (isPausedSameSource) {
        await AudioKit.resume();
      } else {
        await AudioKit.play(playSource ?? source);
      }
      setTransport(toTransportState(AudioKit.getPlaybackState()));
    },
    [source, resolvedUri],
  );

  const pause = useCallback(async () => {
    await AudioKit.pause();
    setTransport(toTransportState(AudioKit.getPlaybackState()));
  }, []);

  const resume = useCallback(async () => {
    if (resolvedUri && !AudioKit.canReplay(resolvedUri)) {
      return;
    }
    if (AudioKit.isAtEnd()) {
      await AudioKit.replay();
    } else {
      await AudioKit.resume();
    }
    setTransport(toTransportState(AudioKit.getPlaybackState()));
  }, [resolvedUri]);

  const resetPlayOnce = useCallback(() => {
    if (resolvedUri) {
      AudioKit.resetPlayOnce(resolvedUri);
    }
    setPlayOnceConsumed(false);
  }, [resolvedUri]);

  const stop = useCallback(async () => {
    await AudioKit.stop();
    setTransport(toTransportState(AudioKit.getPlaybackState()));
  }, []);

  const forceStop = useCallback(async (clearResume = true) => {
    await AudioKit.forceStop(clearResume);
    setTransport(toTransportState(AudioKit.getPlaybackState()));
  }, []);

  const seekTo = useCallback(async (positionMs: number) => {
    if (resolvedUri && !AudioKit.canReplay(resolvedUri)) {
      return;
    }
    setPlaybackTiming({ position: positionMs }, { immediate: true });
    await AudioKit.seekTo(positionMs);
    setTransport(toTransportState(AudioKit.getPlaybackState()));
  }, [resolvedUri]);

  const setVolume = useCallback(async (volume: number) => {
    await AudioKit.setVolume(volume);
    setTransport(toTransportState(AudioKit.getPlaybackState()));
  }, []);

  const setSpeed = useCallback(async (speed: PlaybackSpeed) => {
    await AudioKit.setPlaybackSpeed(speed);
    setTransport(toTransportState(AudioKit.getPlaybackState()));
  }, []);

  const preload = useCallback((url: string) => AudioKit.preload(url), []);
  const getDuration = useCallback(
    (url: string) => AudioKit.getDuration(url),
    [],
  );

  const timing = getPlaybackTiming();

  return {
    state: {
      ...transport,
      position: timing.position,
      duration: timing.duration || transport.duration,
    },
    play,
    pause,
    resume,
    stop,
    forceStop,
    seekTo,
    setVolume,
    setSpeed,
    preload,
    getDuration,
    loadWaveform,
    duration: timing.duration || transport.duration,
    progress: timing.position,
    waveform,
    playOnceConsumed,
    canReplay: resolvedUri ? AudioKit.canReplay(resolvedUri) : true,
    resetPlayOnce,
  };
}
