const cache = new Map<string, number[]>();

export function getCachedWaveform(uri: string): number[] | undefined {
  return cache.get(uri);
}

export function setCachedWaveform(uri: string, samples: number[]): void {
  cache.set(uri, samples);
}

export function clearWaveformCache(): void {
  cache.clear();
}
