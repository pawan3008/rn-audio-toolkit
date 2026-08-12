import { AudioPresets } from '../src/config/presets';
import { mergeConfig } from '../src/config/mergeConfig';
import { DEFAULT_AUDIO_KIT_CONFIG } from '../src/config/defaultConfig';

describe('AudioKit config', () => {
  it('applies LEARNING preset', () => {
    const config = mergeConfig(DEFAULT_AUDIO_KIT_CONFIG, AudioPresets.LEARNING);
    expect(config.backgroundPlayback).toBe(false);
    expect(config.stopOnBackground).toBe(true);
    expect(config.showNotification).toBe(false);
  });

  it('merges custom config over preset', () => {
    const config = mergeConfig(DEFAULT_AUDIO_KIT_CONFIG, {
      ...AudioPresets.LEARNING,
      stopOnScreenLock: false,
    });
    expect(config.stopOnScreenLock).toBe(false);
  });

  it('MUSIC preset enables background playback', () => {
    const config = mergeConfig(DEFAULT_AUDIO_KIT_CONFIG, AudioPresets.MUSIC);
    expect(config.backgroundPlayback).toBe(true);
    expect(config.showNotification).toBe(true);
  });
});
