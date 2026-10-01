// mp4-recorder.js — records the filtered canvas straight to MP4 with
// H.264 video + AAC audio via WebCodecs, using the phone's hardware
// encoders. This is the same format a native camera app produces, so
// gallery apps play the sound. app.js falls back to MediaRecorder when
// WebCodecs (or an AAC encoder) isn't available.
//
// All encoding runs in a Web Worker (mp4-worker.js): the camera filters
// block the main thread for tens of ms per frame, which would starve the
// mic reader and produce chopped-up audio if anything ran here.
//
// Muxing by mp4-muxer (vendored, MIT — https://github.com/Vanilagy/mp4-muxer).

// H.264 + AAC by default. The __mp4TestCodecs hook swaps in VP9 + Opus so
// automated tests can exercise this exact pipeline on machines whose
// Chromium ships without H.264/AAC encoders; phones never set it.
function videoCodec() {
  return window.__mp4TestCodecs
    ? { webcodecs: 'vp09.00.10.08', muxer: 'vp9', avcFormat: false }
    : { webcodecs: 'avc1.42E01F', muxer: 'avc', avcFormat: true };
}

function audioCodec() {
  return window.__mp4TestCodecs
    ? { webcodecs: 'opus', muxer: 'opus' }
    : { webcodecs: 'mp4a.40.2', muxer: 'aac' };
}

export async function mp4RecorderSupported(withAudio) {
  try {
    if (!window.VideoEncoder || !window.MediaStreamTrackProcessor || !window.Worker) {
      return false;
    }
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
    this.startTime = 0;
    this.ac = null;
    this.worker = null;
  }

  async start() {
    let audioReadable = null;
    if (this.audioTrack) {
      // normalize the mic to 48 kHz through an AudioContext: encoders
      // (Chrome's opus especially) garble audio at oddball mic rates like
      // 44.1 kHz, which plays back as noise
      let track = this.audioTrack;
      try {
        this.ac = new AudioContext({ sampleRate: 48000 });
        if (this.ac.state === 'suspended') await this.ac.resume();
        const src = this.ac.createMediaStreamSource(new MediaStream([this.audioTrack]));
        const dst = this.ac.createMediaStreamDestination();
        src.connect(dst);
        track = dst.stream.getAudioTracks()[0];
      } catch {
        track = this.audioTrack; // record from the raw track if the graph fails
      }
      audioReadable = new MediaStreamTrackProcessor({ track }).readable;
    }

    this.worker = new Worker('./mp4-worker.js', { type: 'module' });
    const vcodec = videoCodec();
    const acodec = audioCodec();
    this.worker.postMessage(
      {
        type: 'start',
        width: this.width,
        height: this.height,
        videoCodec: vcodec.webcodecs,
        muxerVideoCodec: vcodec.muxer,
        avcFormat: vcodec.avcFormat,
        audioCodec: acodec.webcodecs,
        muxerAudioCodec: acodec.muxer,
        bitrate: Math.min(8_000_000, Math.max(2_500_000, this.width * this.height * 6)),
        audioReadable,
      },
      audioReadable ? [audioReadable] : []
    );
    this.startTime = performance.now();
  }

  // called from the render loop with the freshly painted filtered canvas;
  // the frame is transferred to the worker, so this never blocks
  addFrame(canvas) {
    if (this.stopped || !this.worker) return;
    const timestamp = Math.round((performance.now() - this.startTime) * 1000);
    const frame = new VideoFrame(canvas, { timestamp, duration: 33333 });
    this.worker.postMessage({ type: 'frame', frame }, [frame]);
  }

  stop() {
    this.stopped = true;
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        if (this.worker) {
          this.worker.terminate();
          this.worker = null;
        }
        if (this.ac) {
          this.ac.close().catch(() => {});
          this.ac = null;
        }
      };
      const timer = setTimeout(() => {
        cleanup();
        reject(new Error('encoder timed out'));
      }, 15000);
      this.worker.onmessage = (e) => {
        clearTimeout(timer);
        if (e.data.type === 'done') {
          this.audioChunkCount = e.data.audioChunkCount; // diagnostics
          this.audioDurationUs = e.data.audioDurationUs;
          cleanup();
          resolve(new Blob([e.data.buffer], { type: 'video/mp4' }));
        } else {
          cleanup();
          reject(new Error(e.data.message));
        }
      };
      this.worker.postMessage({ type: 'stop' });
    });
  }
}
