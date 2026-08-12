import { useEffect, useState } from 'react';
import { AudioKit } from '../../core/AudioKit';
import type { AudioEventUnsubscribe } from '../../types';

export interface TranscriptSegment {
  startMs: number;
  endMs: number;
  text: string;
}

export interface UseTranscriptSyncOptions {
  segments: readonly TranscriptSegment[];
  followPlayback?: boolean;
}

export function useTranscriptSync(options: UseTranscriptSyncOptions) {
  const [activeIndex, setActiveIndex] = useState(-1);
  const { segments, followPlayback = true } = options;

  useEffect(() => {
    if (!followPlayback || segments.length === 0) {
      return;
    }

    const findIndex = (positionMs: number) => {
      const index = segments.findIndex(
        segment => positionMs >= segment.startMs && positionMs < segment.endMs,
      );
      setActiveIndex(index);
    };

    const unsubscribers: AudioEventUnsubscribe[] = [
      AudioKit.on('progress', event => findIndex(event.position)),
      AudioKit.on('seek', event => findIndex(event.position)),
      AudioKit.on('play', state => findIndex(state.position)),
    ];

    findIndex(AudioKit.getPlaybackState().position);

    return () => unsubscribers.forEach(unsub => unsub());
  }, [segments, followPlayback]);

  const activeSegment = activeIndex >= 0 ? segments[activeIndex] : undefined;

  return {
    activeIndex,
    activeSegment,
    setActiveIndex,
  };
}
