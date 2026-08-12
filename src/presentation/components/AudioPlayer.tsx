import React, { useEffect } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { AudioKit } from '../../core/AudioKit';
import type { AudioKitConfig, AudioPresetName } from '../../types/AudioConfig';
import type { AudioSourceInput } from '../../types/AudioSource';
import { useAudioPlayer } from '../hooks/useAudioPlayer';
import type { UseAudioPlayerReturn } from '../hooks/useAudioPlayer';
import { AudioWaveform } from './AudioWaveform';

export interface AudioPlayerProps {
  source: AudioSourceInput;
  autoPlay?: boolean;
  /** Allow only one full playback per source (exam/mock listening) */
  playOnce?: boolean;
  showWaveform?: boolean;
  showSpeed?: boolean;
  cache?: boolean;
  preload?: boolean;
  backgroundPlayback?: boolean;
  config?: Partial<AudioKitConfig>;
  preset?: AudioPresetName;
  /** Pass your own UI. You get play, pause, progress, waveform — everything from the hook. */
  children?: (player: UseAudioPlayerReturn) => React.ReactNode;
}

const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2] as const;

export function AudioPlayer({
  source,
  autoPlay = false,
  playOnce = false,
  showWaveform = false,
  showSpeed = false,
  cache = false,
  preload = false,
  backgroundPlayback,
  config,
  preset,
  children,
}: AudioPlayerProps): React.ReactElement {
  useEffect(() => {
    if (preset) {
      AudioKit.configure(preset);
    }
    if (config || backgroundPlayback !== undefined) {
      AudioKit.configure({
        ...config,
        ...(backgroundPlayback !== undefined && { backgroundPlayback }),
      });
    }
  }, [preset, config, backgroundPlayback]);

  const player = useAudioPlayer(source, { autoPlay, cache, preload, playOnce });

  const playDisabled = playOnce && player.playOnceConsumed;

  if (children) {
    return <>{children(player)}</>;
  }

  return (
    <View style={styles.container}>
      {showWaveform && (
        <AudioWaveform
          progress={player.progress}
          duration={player.duration}
          onSeek={player.seekTo}
          data={player.waveform}
        />
      )}

      <View style={styles.controls}>
        <TouchableOpacity
          onPress={() => player.play()}
          style={[styles.button, playDisabled && styles.buttonDisabled]}
          disabled={playDisabled}>
          <Text>{player.state.playing ? 'Pause' : 'Play'}</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => player.pause()} style={styles.button}>
          <Text>Pause</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => player.stop()} style={styles.button}>
          <Text>Stop</Text>
        </TouchableOpacity>
      </View>

      {showSpeed && (
        <View style={styles.speedRow}>
          {SPEEDS.map(speed => (
            <TouchableOpacity
              key={speed}
              onPress={() => player.setSpeed(speed)}
              style={[
                styles.speedButton,
                player.state.speed === speed && styles.speedActive,
              ]}
            >
              <Text>{speed}x</Text>
            </TouchableOpacity>
          ))}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 12,
  },
  controls: {
    flexDirection: 'row',
    gap: 8,
  },
  button: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#e8e8e8',
    borderRadius: 8,
  },
  buttonDisabled: {
    opacity: 0.4,
  },
  speedRow: {
    flexDirection: 'row',
    gap: 6,
  },
  speedButton: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
    backgroundColor: '#f0f0f0',
  },
  speedActive: {
    backgroundColor: '#007aff',
  },
});
