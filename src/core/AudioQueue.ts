import type { AudioSourceInput } from '../types/AudioSource';
import { resolveAudioSource } from '../utils/resolveAudioSource';

export class AudioQueue {
  private items: string[] = [];
  private currentIndex = -1;

  add(source: AudioSourceInput): void {
    const resolved = resolveAudioSource(source);
    this.items.push(resolved.uri);
    if (this.currentIndex === -1) {
      this.currentIndex = 0;
    }
  }

  addMany(sources: readonly AudioSourceInput[]): void {
    sources.forEach(source => this.add(source));
  }

  clear(): void {
    this.items = [];
    this.currentIndex = -1;
  }

  next(): string | null {
    if (this.items.length === 0) {
      return null;
    }
    if (this.currentIndex < this.items.length - 1) {
      this.currentIndex += 1;
    }
    return this.current();
  }

  previous(): string | null {
    if (this.items.length === 0) {
      return null;
    }
    if (this.currentIndex > 0) {
      this.currentIndex -= 1;
    }
    return this.current();
  }

  current(): string | null {
    if (this.currentIndex < 0 || this.currentIndex >= this.items.length) {
      return null;
    }
    return this.items[this.currentIndex] ?? null;
  }

  peekNext(): string | null {
    if (this.items.length === 0) {
      return null;
    }
    const nextIndex = this.currentIndex + 1;
    if (nextIndex >= this.items.length) {
      return null;
    }
    return this.items[nextIndex] ?? null;
  }

  getIndex(): number {
    return this.currentIndex;
  }

  getLength(): number {
    return this.items.length;
  }

  getAll(): readonly string[] {
    return [...this.items];
  }
}
