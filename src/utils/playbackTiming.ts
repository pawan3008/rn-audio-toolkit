export interface PlaybackTiming {
  position: number;
  duration: number;
}

export interface SetPlaybackTimingOptions {
  /** Bypass throttle — use for seek, pause, resume, stop */
  immediate?: boolean;
  /** Allow position to reset to 0 (stop/force-stop only) */
  allowReset?: boolean;
}

let timing: PlaybackTiming = { position: 0, duration: 0 };
const listeners = new Set<() => void>();
let lastNotifyAt = 0;
let pendingNotify: ReturnType<typeof setTimeout> | null = null;

const THROTTLE_MS = 100;

function notifyListeners(): void {
  lastNotifyAt = Date.now();
  listeners.forEach(listener => listener());
}

function scheduleNotify(immediate: boolean): void {
  const now = Date.now();
  if (immediate || now - lastNotifyAt >= THROTTLE_MS) {
    if (pendingNotify) {
      clearTimeout(pendingNotify);
      pendingNotify = null;
    }
    notifyListeners();
    return;
  }

  if (pendingNotify) {
    return;
  }

  pendingNotify = setTimeout(() => {
    pendingNotify = null;
    notifyListeners();
  }, THROTTLE_MS - (now - lastNotifyAt));
}

export function getPlaybackTiming(): PlaybackTiming {
  return timing;
}

export function setPlaybackTiming(
  next: Partial<PlaybackTiming>,
  options?: SetPlaybackTimingOptions,
): void {
  const immediate = options?.immediate ?? false;
  const allowReset = options?.allowReset ?? false;

  if (
    !allowReset &&
    next.position === 0 &&
    timing.position > 500 &&
    next.duration === undefined
  ) {
    return;
  }

  const updated = { ...timing, ...next };
  if (
    updated.position === timing.position &&
    updated.duration === timing.duration
  ) {
    return;
  }

  timing = updated;
  scheduleNotify(immediate);
}

export function subscribePlaybackTiming(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function resetPlaybackTiming(): void {
  if (pendingNotify) {
    clearTimeout(pendingNotify);
    pendingNotify = null;
  }
  timing = { position: 0, duration: 0 };
  notifyListeners();
}
