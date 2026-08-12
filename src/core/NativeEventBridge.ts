import { NativeEventEmitter, Platform } from 'react-native';
import NativeAudioKit from '../native/AudioKitBridge';
import type { AudioEventName, AudioEventPayload } from '../types/AudioEvents';
import type { PlaybackState } from '../types/PlaybackState';
import { AudioEventBus } from './AudioEventBus';

const NATIVE_EVENTS = [
  'play',
  'pause',
  'resume',
  'stop',
  'complete',
  'buffer',
  'progress',
  'error',
  'interruption',
  'routeChange',
] as const;

type NativeEventName = (typeof NATIVE_EVENTS)[number];

let initialized = false;
let emitter: NativeEventEmitter | null = null;

function mapNativePayload(
  event: NativeEventName,
  payload: Record<string, unknown>,
): AudioEventPayload[AudioEventName] | null {
  switch (event) {
    case 'progress':
      return {
        position: Number(payload.position ?? 0),
        duration: Number(payload.duration ?? 0),
        buffered: payload.buffered != null ? Number(payload.buffered) : undefined,
      };
    case 'error':
      return {
        code: String(payload.code ?? 'E_UNKNOWN'),
        message: String(payload.message ?? 'Unknown error'),
      };
    case 'interruption':
      return {
        type: payload.type === 'ended' ? 'ended' : 'began',
        reason: payload.reason != null ? String(payload.reason) : undefined,
      };
    case 'routeChange':
      return {
        outputs: Array.isArray(payload.outputs)
          ? payload.outputs.map(String)
          : [],
      };
    case 'play':
    case 'pause':
    case 'resume':
    case 'stop':
    case 'complete':
    case 'buffer':
      return payload as unknown as PlaybackState;
    default:
      return null;
  }
}

export function initializeNativeEventBridge(events: AudioEventBus): void {
  if (initialized || !NativeAudioKit) {
    return;
  }

  initialized = true;
  emitter = new NativeEventEmitter(NativeAudioKit);

  NATIVE_EVENTS.forEach(eventName => {
    emitter?.addListener(eventName, (payload: Record<string, unknown>) => {
      const mapped = mapNativePayload(eventName, payload ?? {});
      if (mapped != null) {
        events.emit(eventName as AudioEventName, mapped as AudioEventPayload[AudioEventName]);
      }
    });
  });

  if (Platform.OS === 'android') {
    NativeAudioKit.addListener('RNAudioKit');
  }
}

export function teardownNativeEventBridge(): void {
  if (!initialized || !emitter) {
    return;
  }
  NATIVE_EVENTS.forEach(eventName => emitter?.removeAllListeners(eventName));
  if (NativeAudioKit) {
    NativeAudioKit.removeListeners(NATIVE_EVENTS.length);
  }
  initialized = false;
  emitter = null;
}
