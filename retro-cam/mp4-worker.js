// mp4-worker.js — off-main-thread MP4 encoding. The camera filters block the
// main thread for tens of milliseconds per frame while recording; if audio
// were pulled there, mic packets would be dropped and recordings would sound
// like chopped-up noise. Everything time-sensitive lives here instead.

import { Muxer, ArrayBufferTarget } from './mp4-muxer.mjs';

let cfg = null;
let muxer = null;
let videoEncoder = null;
let audioEncoder = null;
let pendingVideo = [];
let error = null;
let lastKey = -Infinity;
let stopping = false;
let audioChunkCount = 0;
let audioDurationUs = 0;

function createMuxer(audioOpts) {
  if (muxer) return;
  muxer = new Muxer({
    target: new ArrayBufferTarget(),
    video: { codec: cfg.muxerVideoCodec, width: cfg.width, height: cfg.height },
    audio: audioOpts || undefined,
    fastStart: 'in-memory',
    firstTimestampBehavior: 'offset', // mic and canvas clocks differ
  });
  for (const [chunk, meta] of pendingVideo) muxer.addVideoChunk(chunk, meta);
  pendingVideo = [];
}

// created from the FIRST AudioData so sample rate/channels always match the
// real signal — header mismatches play back as garbled noise
function initAudio(sampleRate, numberOfChannels) {
  try {
    audioEncoder = new AudioEncoder({
      output: (chunk, meta) => {
        audioChunkCount++;
        audioDurationUs += chunk.duration || 0;
        muxer.addAudioChunk(chunk, meta);
      },
      error: () => {
        audioEncoder = null; // keep the video even if audio dies
      },
    });
    audioEncoder.configure({
      codec: cfg.audioCodec,
      sampleRate,
      numberOfChannels,
      bitrate: 128000,
    });
  } catch {
    audioEncoder = null;
  }
  createMuxer(
    audioEncoder ? { codec: cfg.muxerAudioCodec, sampleRate, numberOfChannels } : null
  );
}

async function pumpAudio(readable) {
  const reader = readable.getReader();
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        if (value) value.close();
        break;
      }
      if (stopping) {
        value.close();
        break;
      }
      if (!muxer) initAudio(value.sampleRate, value.numberOfChannels);
      if (audioEncoder && audioEncoder.state === 'configured') audioEncoder.encode(value);
      value.close();
    }
  } catch {
    /* mic track ended */
  }
}

self.onmessage = async (e) => {
  const msg = e.data;

  if (msg.type === 'start') {
    cfg = msg;
    videoEncoder = new VideoEncoder({
      output: (chunk, meta) =>
        muxer ? muxer.addVideoChunk(chunk, meta) : pendingVideo.push([chunk, meta]),
      error: (err) => {
        error = err;
      },
    });
    videoEncoder.configure({
      codec: cfg.videoCodec,
      width: cfg.width,
      height: cfg.height,
      bitrate: cfg.bitrate,
      framerate: 30,
      ...(cfg.avcFormat ? { avc: { format: 'avc' } } : {}),
    });
    if (msg.audioReadable) pumpAudio(msg.audioReadable);
    else createMuxer(null);
    return;
  }

  if (msg.type === 'frame') {
    const frame = msg.frame;
    if (
      !stopping &&
      videoEncoder &&
      videoEncoder.state === 'configured' &&
      videoEncoder.encodeQueueSize <= 6
    ) {
      const keyFrame = frame.timestamp - lastKey >= 2_000_000;
      if (keyFrame) lastKey = frame.timestamp;
      try {
        videoEncoder.encode(frame, { keyFrame });
      } catch {
        /* encoder closed */
      }
    }
    frame.close();
    return;
  }

  if (msg.type === 'stop') {
    stopping = true;
    try {
      try {
        await videoEncoder.flush();
      } catch {
        /* flushing a dead encoder */
      }
      if (audioEncoder && audioEncoder.state === 'configured') {
        try {
          await audioEncoder.flush();
        } catch {
          /* ignore */
        }
      }
      if (error) throw error;
      createMuxer(null); // mic never delivered a sample: mux video-only
      muxer.finalize();
      const buffer = muxer.target.buffer;
      self.postMessage({ type: 'done', buffer, audioChunkCount, audioDurationUs }, [buffer]);
    } catch (err) {
      self.postMessage({ type: 'error', message: String(err) });
    }
  }
};
