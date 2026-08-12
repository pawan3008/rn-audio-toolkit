import type { PlaybackState } from './PlaybackState';

export type AudioEventName =
  | 'play'
  | 'pause'
  | 'resume'
  | 'stop'
  | 'complete'
  | 'buffer'
  | 'progress'
  | 'error'
  | 'seek'
  | 'speedChange'
  | 'volumeChange'
  | 'queueChange'
  | 'interruption'
  | 'routeChange';

export interface ProgressEvent {
  position: number;
  duration: number;
  buffered?: number;
}

export interface AudioErrorEvent {
  code: string;
  message: string;
}

export type AudioEventPayload = {
  play: PlaybackState;
  pause: PlaybackState;
  resume: PlaybackState;
  stop: PlaybackState;
  complete: PlaybackState;
  buffer: PlaybackState;
  progress: ProgressEvent;
  error: AudioErrorEvent;
  seek: { position: number };
  speedChange: { speed: number };
  volumeChange: { volume: number };
  queueChange: { index: number; total: number };
  interruption: { type: 'began' | 'ended'; reason?: string };
  routeChange: { outputs: string[] };
};

export type AudioEventListener<T extends AudioEventName> = (
  payload: AudioEventPayload[T],
) => void;

export type AudioEventUnsubscribe = () => void;
