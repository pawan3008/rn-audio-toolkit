import { useSyncExternalStore } from 'react';
import {
  getPlaybackTiming,
  subscribePlaybackTiming,
} from '../../utils/playbackTiming';

export interface AudioProgress {
  progress: number;
  duration: number;
}

export function useAudioProgress(): AudioProgress {
  const timing = useSyncExternalStore(
    subscribePlaybackTiming,
    getPlaybackTiming,
    getPlaybackTiming,
  );

  return {
    progress: timing.position,
    duration: timing.duration,
  };
}
