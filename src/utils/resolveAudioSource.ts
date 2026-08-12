import type { AudioSourceInput } from '../types/AudioSource';

export function resolveAudioSource(source: AudioSourceInput): {
  uri: string;
  type: string;
  title?: string;
  artist?: string;
  album?: string;
  artwork?: string;
} {
  if (typeof source === 'string') {
    return { uri: source, type: inferSourceType(source) };
  }
  return {
    uri: source.uri,
    type: source.type ?? inferSourceType(source.uri),
    title: source.title,
    artist: source.artist,
    album: source.album,
    artwork: source.artwork,
  };
}

function inferSourceType(uri: string): string {
  if (uri.startsWith('http://') || uri.startsWith('https://')) {
    return 'url';
  }
  if (uri.startsWith('data:')) {
    return 'base64';
  }
  if (uri.startsWith('file://') || uri.startsWith('/')) {
    return 'file';
  }
  return 'asset';
}
