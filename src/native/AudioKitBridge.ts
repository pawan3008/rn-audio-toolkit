import { TurboModuleRegistry, NativeModules } from 'react-native';
import type { Spec } from './NativeAudioKit';

export type { Spec };

// Try TurboModule first (New Architecture), fallback to NativeModules (Old Architecture)
const NativeAudioKit: Spec | null =
  TurboModuleRegistry.get<Spec>('RNAudioKitSpec') ??
  NativeModules.RNAudioKitSpec ??
  null;

export default NativeAudioKit;
