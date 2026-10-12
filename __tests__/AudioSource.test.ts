import { resolveAudioSource } from '../src/utils/resolveAudioSource';

describe('resolveAudioSource', () => {
  it('preserves object source metadata and artwork', () => {
    const source = {
      uri: 'https://example.com/audio.mp3',
      type: 'url' as const,
      title: 'Lesson 1',
      artist: 'Teacher',
      album: 'Listening',
      artwork: 'https://example.com/artwork.png',
    };

    expect(resolveAudioSource(source)).toEqual(source);
  });

  it('keeps omitted artwork backward compatible', () => {
    expect(
      resolveAudioSource({
        uri: '/tmp/audio.mp3',
        title: 'Local audio',
      }),
    ).toEqual({
      uri: '/tmp/audio.mp3',
      type: 'file',
      title: 'Local audio',
      artist: undefined,
      album: undefined,
      artwork: undefined,
    });
  });

  it.each([
    ['https://example.com/audio.mp3', 'url'],
    ['file:///tmp/audio.mp3', 'file'],
    ['data:audio/mpeg;base64,AAAA', 'base64'],
    ['lesson_audio', 'asset'],
  ])('infers %s as %s', (uri, type) => {
    expect(resolveAudioSource(uri)).toEqual({ uri, type });
  });
});
