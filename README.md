# rn-audio-toolkit

[![npm version](https://img.shields.io/npm/v/rn-audio-toolkit.svg)](https://www.npmjs.com/package/rn-audio-toolkit)
[![npm downloads](https://img.shields.io/npm/dm/rn-audio-toolkit.svg)](https://www.npmjs.com/package/rn-audio-toolkit)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

React Native audio player for Android and iOS. Play, pause, seek, speed control, background playback, cache — one library, both platforms.

You can use the ready-made `<AudioPlayer />` component, or build your own UI with `useAudioPlayer()`. Your choice.

## Install

```bash
npm install rn-audio-toolkit
```

Then rebuild the app (`pod install` on iOS, then run Android/iOS).

**Android** — add internet permission if you play remote URLs:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

**iOS** — for background audio, add to `Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
  <string>audio</string>
</array>
```

## Quick start

Three lines to get going:

```tsx
import { AudioKit, AudioPresets, useAudioPlayer } from 'rn-audio-toolkit';

AudioKit.configure(AudioPresets.MUSIC);

function Screen() {
  const player = useAudioPlayer('https://example.com/song.mp3');

  const togglePlay = () => {
    if (player.state.playing) {
      player.pause();
    } else if (player.state.paused && !player.state.completed) {
      player.resume();
    } else {
      player.play(); // also replays from start after track ends
    }
  };

  return (
    <>
      <Button title={player.state.playing ? 'Pause' : 'Play'} onPress={togglePlay} />
    </>
  );
}
```

Call `AudioKit.configure()` once when your app starts (or when a screen mounts). Pick a preset that fits your app — `MUSIC`, `PODCAST`, `LEARNING`, or `EXAM`.

## Music app recipe (recommended)

Use this pattern for Spotify/Gaana-style players — smooth UI, auto-play on skip, replay after track ends:

```tsx
import {
  AudioKit,
  AudioPresets,
  useAudioPlayer,
  useAudioProgress,
  formatPlaybackTime,
} from 'rn-audio-toolkit';

AudioKit.configure(AudioPresets.MUSIC);

const PLAYER_OPTIONS = {
  preload: true,
  cache: true,
  waveformSamples: 64,
  autoPlayOnSourceChange: true, // next/prev track auto-plays
} as const;

function NowPlaying({ trackUri }: { trackUri: string }) {
  const player = useAudioPlayer(trackUri, PLAYER_OPTIONS);

  return (
    <>
      {/* Progress in a separate component — avoids full-screen re-renders */}
      <ProgressBar onSeek={player.seekTo} />
      <PlayButton player={player} />
    </>
  );
}

// Only re-renders on progress updates (every ~100ms), not the whole screen
function ProgressBar({ onSeek }: { onSeek: (ms: number) => void }) {
  const { progress, duration } = useAudioProgress();
  return (
    <Text>
      {formatPlaybackTime(progress)} / {formatPlaybackTime(duration)}
    </Text>
  );
}

function PlayButton({ player }: { player: ReturnType<typeof useAudioPlayer> }) {
  const toggle = () => {
    if (player.state.playing) player.pause();
    else if (player.state.paused && !player.state.completed) player.resume();
    else player.play();
  };

  return <Button title={player.state.playing ? 'Pause' : 'Play'} onPress={toggle} />;
}
```

**Skip to next track** — just change the URI. No need to call `stop()` first:

```tsx
const [index, setIndex] = useState(0);
const track = TRACKS[index];
const player = useAudioPlayer(track.uri, { autoPlayOnSourceChange: true });

// User taps next — auto-plays if music was already playing
const goNext = () => setIndex(i => i + 1);
```

**Track ends → play again** — handled automatically. `player.play()` restarts from 00:00.

**Force stop** (reset everything):

```tsx
await player.forceStop(); // or AudioKit.forceStop()
```

## Exam / mock listening (`playOnce`)

For PTE/IELTS-style listening — audio plays **once only**, no replay:

```tsx
AudioKit.configure(AudioPresets.EXAM); // playOnce: true by default

const player = useAudioPlayer(question.audioUrl, {
  autoPlay: true,
  playOnce: true,
  preload: true,
});

// Disable controls after audio finishes
const locked = player.playOnceConsumed; // true after complete

<Button
  disabled={player.playOnceConsumed}
  onPress={() => player.play()}
/>
```

Or per-hook without EXAM preset:

```tsx
const player = useAudioPlayer(url, { playOnce: true });

// New attempt — unlock the same source
player.resetPlayOnce();
```

| Property | Description |
|----------|-------------|
| `playOnceConsumed` | `true` after the source has finished once |
| `canReplay` | `false` when replay is blocked |
| `resetPlayOnce()` | Clear lock for current source (retake) |

## Two ways to build UI

### Option 1 — Built-in player (fastest)

Good when you want something working quickly and don't care about custom design yet.

```tsx
<AudioPlayer
  source={{ uri: url, title: 'My Track' }}
  preset="MUSIC"
  showWaveform
  showSpeed
  cache
  preload
/>
```

That gives you play/pause/stop, optional waveform, and speed buttons. No extra code.

### Option 2 — Your own UI (full control)

Good when you already have a design, or you want buttons/icons that match your app.

**Way A — use the hook directly**

```tsx
import {
  AudioKit,
  AudioPresets,
  useAudioPlayer,
  formatPlaybackTime,
} from 'rn-audio-toolkit';

AudioKit.configure(AudioPresets.MUSIC);

function MyPlayer() {
  const player = useAudioPlayer('https://example.com/song.mp3', {
    preload: true,
    cache: true,
  });

  const isPlaying = player.state.playing;

  return (
    <View>
      <Text>{formatPlaybackTime(player.progress)} / {formatPlaybackTime(player.duration)}</Text>

      <Pressable onPress={() => (isPlaying ? player.pause() : player.play())}>
        <Text>{isPlaying ? 'Pause' : 'Play'}</Text>
      </Pressable>

      <Pressable onPress={() => player.seekTo(player.progress + 10_000)}>
        <Text>+10s</Text>
      </Pressable>
    </View>
  );
}
```

**Way B — render prop on `<AudioPlayer />`**

Same hook, but `AudioPlayer` handles config/preset for you. You only write the UI:

```tsx
<AudioPlayer
  source={{ uri: url, title: 'My Track' }}
  preset="MUSIC"
  cache
  preload
>
  {player => (
    <View>
      <Text>{formatPlaybackTime(player.progress)}</Text>
      <Pressable onPress={() => player.play()}>
        <Text>{player.state.playing ? '⏸' : '▶'}</Text>
      </Pressable>
    </View>
  )}
</AudioPlayer>
```

**Mix and match** — use `<AudioWaveform />` with `useAudioProgress` for smooth updates:

```tsx
function Waveform({ player }: { player: ReturnType<typeof useAudioPlayer> }) {
  const { progress, duration } = useAudioProgress();
  return (
    <AudioWaveform
      data={player.waveform}
      progress={progress}
      duration={duration}
      onSeek={player.seekTo}
      activeColor="#4a90d9"
    />
  );
}
```

## Presets

Don't want to think about background mode, notifications, and cache settings? Use a preset.

| Preset | Background | Notification | Stops on app close | playOnce |
|--------|------------|--------------|--------------------|----------|
| `MUSIC` | yes | yes | no | no |
| `PODCAST` | yes | yes | no | no |
| `LEARNING` | no | no | yes | no |
| `EXAM` | no | no | yes | yes |

```tsx
AudioKit.configure(AudioPresets.MUSIC);   // music / podcast apps
AudioKit.configure(AudioPresets.LEARNING); // lessons, no background
```

Need to tweak one thing? Spread the preset:

```tsx
AudioKit.configure({
  ...AudioPresets.LEARNING,
  stopOnScreenLock: false,
});
```

## What you get from `useAudioPlayer`

| Property / method | What it does |
|-------------------|--------------|
| `player.play()` | Start playback (also replays from start after track ends) |
| `player.pause()` | Pause |
| `player.resume()` | Resume after pause |
| `player.stop()` | Stop and reset progress |
| `player.forceStop()` | Stop + clear resume position + full reset |
| `player.seekTo(ms)` | Jump to position |
| `player.setSpeed(1.5)` | Change speed (0.5 – 2) |
| `player.setVolume(0.8)` | Volume 0 – 1 |
| `player.state.playing` | `true` when audio is playing |
| `player.state.paused` | `true` when paused mid-track |
| `player.state.completed` | `true` when track has finished |
| `player.progress` | Current position in ms (use `useAudioProgress` in UI) |
| `player.duration` | Total length in ms |
| `player.waveform` | Amplitude samples for waveform UI |

### Hook options

| Option | Default | What it does |
|--------|---------|--------------|
| `preload` | `false` | Load duration before play |
| `cache` | `false` | Cache remote files locally |
| `autoPlay` | `false` | Start playing when source loads |
| `autoPlayOnSourceChange` | `false` | Keep playing when URI changes (next/prev) |
| `playOnce` | `false` | One playback per source — blocks replay (exam mode) |
| `waveformSamples` | — | Number of waveform bars to generate |
| `initialSpeed` | — | Set once on first mount only |

### `useAudioProgress()` — for smooth UI

Put progress bars and waveforms in a child component using this hook. The parent screen won't re-render on every progress tick.

```tsx
const { progress, duration } = useAudioProgress();
```

## More features (when you need them)

**Queue**

```tsx
AudioKit.addQueue({ uri: url1, title: 'Track 1' });
AudioKit.addQueue({ uri: url2, title: 'Track 2' });
await AudioKit.next();
```

**Bookmarks**

```tsx
AudioKit.addBookmark(45_000, 'Important part');
```

**A-B repeat** (loop a section)

```tsx
AudioKit.setABRepeat({ startMs: 10_000, endMs: 30_000 });
AudioKit.setABRepeat(null); // turn off
```

**Events**

```tsx
const off = AudioKit.on('progress', ({ position, duration }) => {
  console.log(position, duration);
});
off(); // cleanup
```

## Common questions

**Background audio stops when I leave the app**  
You're probably on `LEARNING` or `EXAM` preset. Switch to `MUSIC` or `PODCAST`, or set `backgroundPlayback: true`.

**Track ended — play button doesn't work**  
Use the toggle pattern above (`play()` handles replay automatically). Don't call `resume()` when `player.state.completed` is `true`.

**Next track doesn't auto-play**  
Pass `autoPlayOnSourceChange: true` to `useAudioPlayer`. Change the URI without calling `stop()` first.

**UI blinks or lags during playback**  
Use `useAudioProgress()` in a separate child component for the progress bar / waveform. Don't read `player.progress` in the parent screen.

**Can I get duration before pressing play?**  
Yes: `await AudioKit.getDuration(url)` or `await player.preload(url)`.

**Do I need the New Architecture?**  
No. Works with both old and new React Native architecture.

**Custom UI vs built-in — which should I pick?**  
Built-in if you want speed. Hook or render prop if you care about design. The playback logic is the same either way.

**Full working example**  
See the demo app: `LA_audio_demo/App.tsx` in this repo.

## Links

- npm: https://www.npmjs.com/package/rn-audio-toolkit
- Issues: https://github.com/pawan3008/react-native-audiokit/issues

## License

MIT
