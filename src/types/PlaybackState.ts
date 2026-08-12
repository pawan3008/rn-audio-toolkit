export type PlaybackStatus =
  | 'idle'
  | 'loading'
  | 'buffering'
  | 'playing'
  | 'paused'
  | 'stopped'
  | 'completed'
  | 'error';

export interface PlaybackState {
  status: PlaybackStatus;
  playing: boolean;
  paused: boolean;
  buffering: boolean;
  loading: boolean;
  completed: boolean;
  duration: number;
  position: number;
  speed: number;
  volume: number;
  error?: string;
}

export const INITIAL_PLAYBACK_STATE: PlaybackState = {
  status: 'idle',
  playing: false,
  paused: false,
  buffering: false,
  loading: false,
  completed: false,
  duration: 0,
  position: 0,
  speed: 1,
  volume: 1,
};
