# DeckRec — DJ live recording for Android

Records the master output of a class-compliant USB DJ mixer straight onto an Android phone, with
the meters, markers, sound shaping and library a live recording app needs. It is an original
Android app built to cover the same job as Pioneer's iOS-only DJM-REC — none of Pioneer's code,
artwork or branding is used, and it is not affiliated with Pioneer DJ / AlphaTheta.

Built and tested against Samsung Galaxy hardware (USB-C host mode).

## What it does

| Capability | Notes |
|---|---|
| Record from a USB mixer | Any USB Audio Class device Android exposes as a capture endpoint |
| Multi-channel channel picking | Choose which stereo pair of a multi-channel mixer to record |
| 24-bit / 16-bit WAV | Streaming RIFF writer, auto-split before the 4 GB format limit |
| AAC | 64–320 kbps AAC-LC in an `.m4a` container |
| Record meters | Stereo peak + RMS with peak-hold, clip latch and limiter gain-reduction |
| Gain | ±24 dB input trim, smoothed so it can be ridden mid-set |
| Sub Bass | Low shelf plus an octave-divider that synthesises sub content from the input |
| Loudness | Stereo-linked soft-knee compressor with automatic make-up |
| Peak limiter | Look-ahead brickwall at −0.3 dBFS |
| Track markers | Manual `MARK` button plus automatic transition detection |
| Background recording | Foreground service, wake lock, transport controls in the shade |
| Library | Waveform overview, playback, marker editing, track-list export |
| Sharing | Android share sheet (Dropbox, Drive, Mixcloud…) and copy into the phone's Music folder |

## Building

Open `deckrec-android` in Android Studio (Ladybug or newer) and run, or from the command line:

```bash
cd deckrec-android
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

CI builds the same APK on every push — see `.github/workflows/android.yml` at the repository root
— and uploads it as a downloadable artifact, which is the easiest way to get an installable build
without a local SDK. The DSP regression tests run first and gate the build:

```bash
./gradlew :app:testDebugUnitTest
```

## Installing on a Samsung phone

1. Download the APK from the CI run's artifacts and unzip it.
2. Allow installation from your browser or file manager when prompted.
3. Grant the microphone permission on first launch. Android gates *all* audio capture behind it,
   including USB input, so recording cannot start without it.

## Connecting a mixer

1. Use a USB-C **OTG / host** cable — a charge-only cable will not enumerate the mixer.
2. Switch the mixer's USB audio output on (on DJM-series hardware this is the `USB` / `REC OUT`
   setting in the mixer's own utility menu).
3. Open DeckRec, tap the refresh icon in the INPUT card, and select the mixer.
4. If the mixer has more than two channels, pick the pair carrying the master bus under
   SOURCE CHANNELS. The meters run live while the record screen is open — before you hit REC —
   so the right pair is the one that moves with the music. Set your gain here too.

If the app reports that a mixer is on the USB bus but not available as an input, Android's audio
system has not accepted it: check the cable, check that the mixer's USB output is enabled, and try
a powered hub if the mixer draws more than the phone supplies.

## Architecture

```
DeckRecApp            singletons: settings, library, USB scanner, recording engine
├── usb/              UsbAudioScanner — capture endpoints + raw USB bus, kept in sync
├── audio/
│   ├── RecordingEngine   one urgent-priority thread: read → DSP → disk
│   ├── dsp/              Biquad, SubBass, Loudness, BrickwallLimiter, LevelMeter,
│   │                     TransitionDetector, DspChain
│   └── write/            WavSink (cue chunks), AacSink (MediaCodec), PeakFile
├── data/             RecordingStore (JSON sidecars), SettingsStore, models
├── service/          RecordingService — foreground lifetime + shade transport
└── ui/               Compose screens, view model, meters and waveform
```

The engine lives in the `Application`, not the service, so a set survives the activity being
destroyed or the service being rebound. Metering sits at the end of the DSP chain, so the number on
screen is the number in the file.

### Two things the iOS app does that no Android app can

* **Controlling the mixer's own peak limiter.** DJM-REC toggles the limiter *inside the mixer* over
  Pioneer's proprietary control protocol, which is not published and is not exposed to a USB host.
  DeckRec applies its own look-ahead brickwall limiter to the recorded signal instead.
* **Timestamps from fader movement.** DJM-REC's automatic track markers come from the mixer
  reporting its own fader positions. Nothing equivalent reaches a USB host, so DeckRec derives
  markers from the audio: it watches the low and high bands for the spectral signature of a track
  swap. Sensitivity and minimum gap are tunable in Settings.
