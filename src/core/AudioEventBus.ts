import type {
  AudioEventName,
  AudioEventListener,
  AudioEventUnsubscribe,
} from '../types/AudioEvents';

type ListenerMap = {
  [K in AudioEventName]: Set<AudioEventListener<K>>;
};

export class AudioEventBus {
  private readonly listeners: ListenerMap = {
    play: new Set(),
    pause: new Set(),
    resume: new Set(),
    stop: new Set(),
    complete: new Set(),
    buffer: new Set(),
    progress: new Set(),
    error: new Set(),
    seek: new Set(),
    speedChange: new Set(),
    volumeChange: new Set(),
    queueChange: new Set(),
    interruption: new Set(),
    routeChange: new Set(),
    remotePrevious: new Set(),
    remoteNext: new Set(),
  };

  on<T extends AudioEventName>(
    event: T,
    listener: AudioEventListener<T>,
  ): AudioEventUnsubscribe {
    this.listeners[event].add(listener as AudioEventListener<AudioEventName>);
    return () => {
      this.listeners[event].delete(
        listener as AudioEventListener<AudioEventName>,
      );
    };
  }

  emit<T extends AudioEventName>(
    event: T,
    payload: Parameters<AudioEventListener<T>>[0],
  ): void {
    this.listeners[event].forEach(listener => {
      (listener as AudioEventListener<T>)(payload);
    });
  }

  removeAll(): void {
    Object.values(this.listeners).forEach(set => set.clear());
  }
}
