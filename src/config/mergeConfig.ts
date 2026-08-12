import type { AudioKitConfig } from '../types/AudioConfig';
import { DEFAULT_AUDIO_KIT_CONFIG } from './defaultConfig';

export function mergeConfig(
  base: AudioKitConfig,
  overrides?: Partial<AudioKitConfig>,
): AudioKitConfig {
  if (!overrides) {
    return { ...base };
  }
  return { ...base, ...overrides };
}

export function resolveConfig(
  input?: Partial<AudioKitConfig> | AudioKitConfig,
): AudioKitConfig {
  if (!input) {
    return { ...DEFAULT_AUDIO_KIT_CONFIG };
  }
  return mergeConfig(DEFAULT_AUDIO_KIT_CONFIG, input);
}
