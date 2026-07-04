// mp4-recorder.js — records the filtered canvas straight to MP4 with
// H.264 video + AAC audio via WebCodecs, using the phone's hardware
// encoders. This is the same format a native camera app produces, so
// gallery apps play the sound. app.js falls back to MediaRecorder when
// WebCodecs (or an AAC encoder) isn't available.
//
// Muxing by mp4-muxer (vendored, MIT — https://github.com/Vanilagy/mp4-muxer).

import { Muxer, ArrayBufferTarget } from './mp4-muxer.mjs';

// H.264 + AAC — what native camera apps produce, so gallery apps play it.
// The __mp4TestCodecs hook swaps in VP9 + Opus so automated tests can
// exercise this exact pipeline on machines whose Chromium ships without
// H.264/AAC encoders; phones never set it.
function videoCodec() {
  return window.__mp4TestCodecs
    ? { webcodecs: 'vp09.00.10.08', muxer: 'vp9', extra: {} }
    : { webcodecs: 'avc1.42E01F', muxer: 'avc', extra: { avc: { format: 'avc' } } };
}

function audioCodec() {
  return window.__mp4TestCodecs
    ? { webcodecs: 'opus', muxer: 'opus' }
    : { webcodecs: 'mp4a.40.2', muxer: 'aac' };
}

export async function mp4RecorderSupported(withAudio) {
  try {
    if (!window.VideoEncoder || !window.MediaStreamTrackProcessor) return false;
    const v = await VideoEncoder.isConfigSupported({
      codec: videoCodec().webcodecs,
      width: 1280,
      height: 720,
      bitrate: 5_000_000,
      framerate: 30,
    });
    if (!v.supported) return false;
    if (withAudio) {
      if (!window.AudioEncoder) return false;
      const a = await AudioEncoder.isConfigSupported({
        codec: audioCodec().webcodecs,
        sampleRate: 48000,
        numberOfChannels: 1,
        bitrate: 128000,
      });
      if (!a.supported) return false;
    }
    return true;
  } catch {
    return false;
  }
}

export class Mp4Recorder {
  constructor({ width, height, audioTrack }) {
    this.width = width;
    this.height = height;
    this.audioTrack = audioTrack || null;
    this.stopped = false;
    this.error = null;
    this.lastKey = -Infinity;
    this.startTime = 0;
  }

  start() {
    const settings = this.audioTrack ? this.audioTrack.getSettings() : {};
    const sampleRate = settings.sampleRate || 48000;
    const channels = settings.channelCount || 1;
    const vcodec = videoCodec();
    const acodec = audioCodec();

    this.muxer = new Muxer({
      target: new ArrayBufferTarget(),
      video: { codec: vcodec.muxer, width: this.width, height: this.height },
      audio: this.audioTrack
        ? { codec: acodec.muxer, sampleRate, numberOfChannels: channels }
        : undefined,
      fastStart: 'in-memory',
      firstTimestampBehavior: 'offset', // mic and canvas clocks differ
    });

    this.videoEncoder = new VideoEncoder({
      output: (chunk, meta) => this.muxer.addVideoChunk(chunk, meta),
      error: (e) => {
        this.error = e;
      },
    });
    this.videoEncoder.configure({
      codec: vcodec.webcodecs,
      width: this.width,
      height: this.height,
      bitrate: Math.min(8_000_000, Math.max(2_500_000, this.width * this.height * 6)),
      framerate: 30,
      ...vcodec.extra,
    });

    if (this.audioTrack) {
      this.audioEncoder = new AudioEncoder({
        output: (chunk, meta) => this.muxer.addAudioChunk(chunk, meta),
        error: () => {
          this.audioEncoder = null; // keep the video even if audio dies
        },
      });
      this.audioEncoder.configure({
        codec: acodec.webcodecs,
        sampleRate,
        numberOfChannels: channels,
        bitrate: 128000,
      });
      this.audioReader = new MediaStreamTrackProcessor({ track: this.audioTrack }).readable.getReader();
      this.pumpAudio();
    }

    this.startTime = performance.now();
  }

  async pumpAudio() {
    try {
      for (;;) {
        const { value, done } = await this.audioReader.read();
        if (done || this.stopped) {
          if (value) value.close();
          break;
        }
        if (this.audioEncoder && this.audioEncoder.state === 'configured') {
          this.audioEncoder.encode(value);
        }
        value.close();
      }
    } catch {
      /* mic track ended */
    }
  }

  // called from the render loop with the freshly painted filtered canvas
  addFrame(canvas) {
    if (this.stopped || !this.videoEncoder || this.videoEncoder.state !== 'configured') return;
    if (this.videoEncoder.encodeQueueSize > 4) return; // drop frames when behind
    const timestamp = Math.round((performance.now() - this.startTime) * 1000);
    const keyFrame = timestamp - this.lastKey >= 2_000_000;
    if (keyFrame) this.lastKey = timestamp;
    const frame = new VideoFrame(canvas, { timestamp, duration: 33333 });
    try {
      this.videoEncoder.encode(frame, { keyFrame });
    } catch {
      /* encoder closed */
    }
    frame.close();
  }

  async stop() {
    this.stopped = true;
    if (this.audioReader) {
      try {
        await this.audioReader.cancel();
      } catch {
        /* already ended */
      }
    }
    try {
      await this.videoEncoder.flush();
    } catch {
      /* flushing a dead encoder */
    }
    if (this.audioEncoder && this.audioEncoder.state === 'configured') {
      try {
        await this.audioEncoder.flush();
      } catch {
        /* ignore */
      }
    }
    if (this.error) throw this.error;
    this.muxer.finalize();
    return new Blob([this.muxer.target.buffer], { type: 'video/mp4' });
  }
}
