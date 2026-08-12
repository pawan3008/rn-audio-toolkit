const consumedSources = new Set<string>();

export function isPlayOnceConsumed(uri: string): boolean {
  return consumedSources.has(uri);
}

export function markPlayOnceConsumed(uri: string): void {
  consumedSources.add(uri);
}

export function resetPlayOnce(uri?: string): void {
  if (uri) {
    consumedSources.delete(uri);
  } else {
    consumedSources.clear();
  }
}

export function canReplay(uri: string, playOnceEnabled: boolean): boolean {
  return !playOnceEnabled || !consumedSources.has(uri);
}
