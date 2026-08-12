import React, { useCallback, useMemo, useState } from 'react';
import {
  GestureResponderEvent,
  LayoutChangeEvent,
  Pressable,
  StyleSheet,
  View,
} from 'react-native';

export interface AudioWaveformProps {
  data?: number[];
  progress: number;
  duration: number;
  onSeek?: (positionMs: number) => void;
  barCount?: number;
  activeColor?: string;
  inactiveColor?: string;
  height?: number;
  zoom?: number;
}

const PLACEHOLDER_CACHE = new Map<number, number[]>();

function getPlaceholderSamples(count: number): number[] {
  const cached = PLACEHOLDER_CACHE.get(count);
  if (cached) {
    return cached;
  }

  const samples = Array.from(
    { length: count },
    (_, index) => 0.2 + (Math.sin(index * 0.5) + 1) * 0.3,
  );
  PLACEHOLDER_CACHE.set(count, samples);
  return samples;
}

function AudioWaveformComponent({
  data,
  progress,
  duration,
  onSeek,
  barCount = 40,
  activeColor = '#007aff',
  inactiveColor = '#d0d0d0',
  height = 48,
  zoom = 1,
}: AudioWaveformProps): React.ReactElement {
  const effectiveBarCount = Math.max(10, Math.floor(barCount * zoom));
  const samples = useMemo(() => {
    if (data && data.length > 0) {
      return data;
    }
    return getPlaceholderSamples(effectiveBarCount);
  }, [data, effectiveBarCount]);

  const progressRatio = duration > 0 ? progress / duration : 0;
  const barHeights = useMemo(
    () => samples.map(amplitude => Math.max(4, amplitude * height)),
    [samples, height],
  );

  const [trackWidth, setTrackWidth] = useState(0);

  const handleLayout = useCallback((event: LayoutChangeEvent) => {
    setTrackWidth(event.nativeEvent.layout.width);
  }, []);

  const handlePress = useCallback(
    (event: GestureResponderEvent) => {
      if (!onSeek || duration <= 0 || trackWidth <= 0) {
        return;
      }

      const ratio = Math.max(
        0,
        Math.min(1, event.nativeEvent.locationX / trackWidth),
      );
      onSeek(ratio * duration);
    },
    [duration, onSeek, trackWidth],
  );

  const renderBars = (color: string) =>
    barHeights.map((barHeight, index) => (
      <View key={index} style={styles.barWrapper}>
        <View
          style={[
            styles.bar,
            {
              height: barHeight,
              backgroundColor: color,
            },
          ]}
        />
      </View>
    ));

  return (
    <Pressable
      onLayout={handleLayout}
      onPress={handlePress}
      style={[styles.container, { height }]}
    >
      <View pointerEvents="none" style={styles.barsRow}>
        {renderBars(inactiveColor)}
      </View>
      <View
        pointerEvents="none"
        style={[styles.progressMask, {width: `${progressRatio * 100}%`}]}
      >
        <View style={[styles.barsRow, trackWidth > 0 && {width: trackWidth}]}>
          {renderBars(activeColor)}
        </View>
      </View>
    </Pressable>
  );
}

export const AudioWaveform = React.memo(AudioWaveformComponent);

const styles = StyleSheet.create({
  container: {
    position: 'relative',
    width: '100%',
    justifyContent: 'center',
  },
  barsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 2,
    width: '100%',
  },
  progressMask: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    overflow: 'hidden',
  },
  barWrapper: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bar: {
    width: 3,
    borderRadius: 2,
  },
});
