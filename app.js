// app.js — camera, live filtered preview, photo + video capture, flash,
// tap-to-focus, exposure, and a gallery of everything shot with the app.
// Filters themselves live in filters.js; this file never needs to change
// when a new filter is added.

import { getFilters, getFilter, fisheye } from './filters.js';
import { Mp4Recorder, mp4RecorderSupported } from './mp4-recorder.js';

const APP_VERSION = 'v18'; // keep in sync with VERSION in sw.js

const PREVIEW_MAX_SIDE = 640; // live filtering stays smooth on phones
const RECORD_MAX_SIDE = 960; // canvas size while recording video
const CAPTURE_MAX_SIDE = 2048;

const video = document.getElementById('video');
const viewfinder = document.getElementById('viewfinder');
const preview = document.getElementById('preview');
const previewCtx = preview.getContext('2d', { willReadFrequently: true });
const flash = document.getElementById('flash');
const toast = document.getElementById('filter-name-toast');
const errorBox = document.getElementById('camera-error');
const errorMsg = document.getElementById('camera-error-msg');
const filterStrip = document.getElementById('filter-strip');
const modeSwitch = document.getElementById('mode-switch');
const shutterBtn = document.getElementById('shutter');
const flipBtn = document.getElementById('flip');
const flashToggle = document.getElementById('flash-toggle');
const recordTimer = document.getElementById('record-timer');
const recordTime = document.getElementById('record-time');
const recordMic = document.getElementById('record-mic');
const lastPhotoBtn = document.getElementById('last-photo');
const lastPhotoImg = document.getElementById('last-photo-img');
const reviewScreen = document.getElementById('review-screen');
const galleryTrack = document.getElementById('gallery-track');
const galleryCounter = document.getElementById('gallery-counter');
const focusRing = document.getElementById('focus-ring');
const exposureSlider = document.getElementById('exposure');
const fisheyeToggle = document.getElementById('fisheye-toggle');
const fisheyeControl = document.getElementById('fisheye-control');
const fisheyeSlider = document.getElementById('fisheye');

let stream = null;
let facing = 'environment';
let activeFilterId = localStorage.getItem('retrocam-filter') || 'fifties-cam';
let mode = 'photo'; // 'photo' | 'video'
let flashOn = false;
let rafId = 0;
let toastTimer = 0;
let brightness = 0; // -3..+3; mapped to a gentle gain so it always works
let fisheyeOn = false;
let fisheyeAmount = Math.min(3, Math.max(0, parseFloat(localStorage.getItem('retrocam-fisheye')) || 1.5));

let recorder = null; // active recording controller: { requestStop, addFrame? }
let recordStartedAt = 0;
let recordTickInt = 0;
let audioStream = null;

let galleryItems = [];
let galleryUrls = [];

function brightnessGain() {
  // 1.5 slider units per stop: the ±3 range spans ±2 stops, so each nudge
  // changes the image a third less than the old ±2-stop slider did
  return Math.pow(2, brightness / 1.5);
}

function showToast(text) {
  toast.textContent = text;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 1200);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ---------------- filter chips ---------------- */

function buildFilterStrip() {
  filterStrip.innerHTML = '';
  for (const f of getFilters()) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'filter-chip';
    chip.textContent = f.name;
    chip.dataset.id = f.id;
    chip.setAttribute('role', 'option');
    chip.addEventListener('click', () => selectFilter(f.id));
    filterStrip.appendChild(chip);
  }
  updateChips();
}

function updateChips() {
  for (const chip of filterStrip.children) {
    chip.classList.toggle('active', chip.dataset.id === activeFilterId);
  }
}

function selectFilter(id) {
  activeFilterId = id;
  localStorage.setItem('retrocam-filter', id);
  updateChips();
  showToast(getFilter(id).name);
}

/* ---------------- camera ---------------- */

// Phones expose several back lenses (main/ultrawide/macro/depth) and only
// the main one has the flash unit. Guessing by list order is unreliable, so
// once the user asks for flash we PROBE the lenses for a real torch (see
// ensureTorchCamera) and remember the winner here.
let torchCamId = localStorage.getItem('retrocam-torchcam') || null;

async function listBackCameras() {
  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    const backs = devices.filter(
      (d) => d.kind === 'videoinput' && /back|rear|environment/i.test(d.label)
    );
    // best guess ordering until a torch probe settles it: main lens first
    // ("camera2 0" on Android), specialty lenses (ultra/tele/macro/depth) last
    const score = (label) => {
      if (/camera2 0\b/i.test(label)) return -1;
      if (/ultra|tele|macro|depth|zoom/i.test(label)) return 1;
      return 0;
    };
    return backs.sort((a, b) => score(a.label) - score(b.label));
  } catch {
    return [];
  }
}

async function pickBackCameraId() {
  if (torchCamId) return torchCamId;
  const backs = await listBackCameras();
  return backs.length ? backs[0].deviceId : null;
}

// The torch capability can populate a beat AFTER the camera opens (Chrome
// quirk), so poll briefly instead of reading once.
async function trackGrowsTorch(track, tries = 4) {
  for (let i = 0; i < tries; i++) {
    try {
      if (track.getCapabilities && track.getCapabilities().torch) return true;
    } catch {
      /* keep polling */
    }
    await sleep(200);
  }
  return false;
}

let torchProbeDone = false;

// Find and switch to the back lens that really has the flash. Phones only
// allow one camera open at a time, so this briefly cycles through lenses
// the first time flash is enabled, then remembers the winner.
async function ensureTorchCamera() {
  const track = stream && stream.getVideoTracks()[0];
  if (track && (await trackGrowsTorch(track))) {
    torchProbeDone = true;
    return true;
  }
  if (torchProbeDone) return false; // already searched this session
  torchProbeDone = true;

  const currentId = track && track.getSettings ? track.getSettings().deviceId : null;
  const backs = (await listBackCameras()).filter((d) => d.deviceId !== currentId);
  if (!backs.length) return false;

  stopCamera(); // release the camera so other lenses can open
  let winner = null;
  for (const dev of backs) {
    let probe = null;
    try {
      probe = await navigator.mediaDevices.getUserMedia({
        video: { deviceId: { exact: dev.deviceId } },
        audio: false,
      });
      const t = probe.getVideoTracks()[0];
      const hasTorch = await trackGrowsTorch(t);
      for (const tr of probe.getTracks()) tr.stop();
      if (hasTorch) {
        winner = dev.deviceId;
        break;
      }
    } catch {
      if (probe) for (const tr of probe.getTracks()) tr.stop();
    }
  }
  if (winner) {
    torchCamId = winner;
    localStorage.setItem('retrocam-torchcam', winner);
  }
  await startCamera(); // reopens with torchCamId when one was found
  return !!winner;
}

async function startCamera() {
  stopCamera();
  errorBox.hidden = true;
  const size = { width: { ideal: 1920 }, height: { ideal: 1080 } };
  try {
    const backId = facing === 'environment' ? await pickBackCameraId() : null;
    if (backId) {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { deviceId: { exact: backId }, ...size },
          audio: false,
        });
      } catch {
        stream = null; // that lens failed — fall back to facingMode below
        if (backId === torchCamId) {
          torchCamId = null; // stored lens no longer exists
          localStorage.removeItem('retrocam-torchcam');
        }
      }
    }
    if (!stream) {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: facing, ...size },
        audio: false,
      });
    }
  } catch (err) {
    showCameraError(err);
    return;
  }
  video.srcObject = stream;
  await video.play().catch(() => {});
  preview.classList.toggle('mirrored', facing === 'user');
  renderLoop();
}

function stopCamera() {
  cancelAnimationFrame(rafId);
  if (stream) {
    for (const t of stream.getTracks()) t.stop();
    stream = null;
  }
}

function showCameraError(err) {
  const denied = err && (err.name === 'NotAllowedError' || err.name === 'SecurityError');
  errorMsg.textContent = denied
    ? 'Camera access was denied. Allow camera access for this site in your browser settings, then try again.'
    : 'Could not start the camera on this device.';
  errorBox.hidden = false;
}

function renderLoop() {
  rafId = requestAnimationFrame(renderLoop);
  if (video.readyState < 2 || !video.videoWidth) return;

  const vw = video.videoWidth;
  const vh = video.videoHeight;
  const maxSide = recorder ? RECORD_MAX_SIDE : PREVIEW_MAX_SIDE;
  const scale = Math.min(1, maxSide / Math.max(vw, vh));
  const w = Math.round(vw * scale);
  const h = Math.round(vh * scale);
  if (preview.width !== w || preview.height !== h) {
    preview.width = w;
    preview.height = h;
  }

  previewCtx.filter = brightness ? `brightness(${brightnessGain()})` : 'none';
  previewCtx.drawImage(video, 0, 0, w, h);
  previewCtx.filter = 'none';
  const filter = getFilter(activeFilterId);
  const wantFisheye = fisheyeOn && fisheyeAmount > 0;
  if (wantFisheye || filter.id !== 'normal') {
    const frame = previewCtx.getImageData(0, 0, w, h);
    // lens before film: warp first so the filter's grain/vignette stay natural
    if (wantFisheye) fisheye(frame, w, h, fisheyeAmount);
    if (filter.id !== 'normal') filter.apply(frame, w, h, { preview: true });
    previewCtx.putImageData(frame, 0, 0);
  }
  // feed the freshly painted frame to the MP4 recorder when one is running
  if (recorder && recorder.addFrame) recorder.addFrame(preview);
}

/* ---------------- flash ---------------- */

function torchEngaged(track, on) {
  // ground truth: the track's live settings, not the constraint call —
  // browsers accept best-effort constraints without actually acting
  try {
    const s = track.getSettings ? track.getSettings() : {};
    return 'torch' in s ? s.torch === on : true;
  } catch {
    return true;
  }
}

async function setTorch(on) {
  const track = stream && stream.getVideoTracks()[0];
  if (!track) return false;
  // path 1: capability advertised → apply and verify
  try {
    if (track.getCapabilities && track.getCapabilities().torch) {
      await track.applyConstraints({ advanced: [{ torch: on }] });
      if (torchEngaged(track, on)) return true;
    }
  } catch {
    /* fall through */
  }
  // path 2: some phones support torch without advertising it. `exact` makes
  // the constraint required (throws when unsupported) — a bare `torch: on`
  // is best-effort and "succeeds" everywhere, which broke the fallbacks.
  try {
    await track.applyConstraints({ torch: { exact: on } });
    return torchEngaged(track, on);
  } catch {
    return false;
  }
}

// path 3: the real camera flash, hardware-synchronized with a full-res
// still — what native camera apps fire. Returns a JPEG blob or null.
async function captureWithHardwareFlash() {
  try {
    if (!('ImageCapture' in window)) return null;
    const track = stream && stream.getVideoTracks()[0];
    if (!track) return null;
    const ic = new ImageCapture(track);
    const caps = await ic.getPhotoCapabilities();
    if (!caps || !Array.isArray(caps.fillLightMode) || !caps.fillLightMode.includes('flash')) {
      return null;
    }
    return await ic.takePhoto({ fillLightMode: 'flash' });
  } catch {
    return null;
  }
}

function screenFlash(on) {
  flash.classList.toggle('screen', on);
}

let flashProbing = false;

flashToggle.addEventListener('click', async () => {
  if (flashProbing) return;
  flashOn = !flashOn;
  flashToggle.classList.toggle('off', !flashOn);
  // if toggled off mid-recording, kill the torch
  if (!flashOn && recorder) setTorch(false);
  if (flashOn && recorder) setTorch(true);
  if (!flashOn) {
    showToast('Flash off');
    return;
  }
  // tell the user which flash this camera will actually fire
  if (facing !== 'environment') {
    showToast('Flash on (screen)');
    return;
  }
  if (recorder) return; // can't switch lenses mid-recording

  flashProbing = true;
  showToast('Flash: checking…');
  let kind = 'screen';
  try {
    // switches to the torch-capable back lens when one exists
    if (await ensureTorchCamera()) {
      // confirm it truly lights, then turn it off until capture
      if (await setTorch(true)) {
        kind = 'torch';
        await sleep(250); // visible blink = proof for the user
        setTorch(false);
      }
    }
    if (kind === 'screen' && 'ImageCapture' in window && stream) {
      try {
        const caps = await new ImageCapture(stream.getVideoTracks()[0]).getPhotoCapabilities();
        if (caps && Array.isArray(caps.fillLightMode) && caps.fillLightMode.includes('flash')) {
          kind = 'camera flash';
        }
      } catch {
        /* stay on screen */
      }
    }
  } finally {
    flashProbing = false;
  }
  showToast(`Flash on (${kind})`);
});

/* ---------------- photo capture ---------------- */

async function takePhoto() {
  if (!video.videoWidth) return;

  // flash, in order of quality: torch → hardware still-flash → white screen
  let torchUsed = false;
  let flashBitmap = null;
  if (flashOn) {
    if (facing === 'environment') {
      torchUsed = await setTorch(true);
      if (!torchUsed) {
        const hwBlob = await captureWithHardwareFlash();
        if (hwBlob) {
          flashBitmap = await createImageBitmap(hwBlob, { imageOrientation: 'from-image' }).catch(
            () => null
          );
        }
      }
    }
    if (!torchUsed && !flashBitmap) screenFlash(true);
    if (!flashBitmap) await sleep(350); // let auto-exposure adapt to the light
  }

  flash.classList.add('on');
  setTimeout(() => flash.classList.remove('on'), 60);

  const source = flashBitmap || video;
  const sw = flashBitmap ? flashBitmap.width : video.videoWidth;
  const sh = flashBitmap ? flashBitmap.height : video.videoHeight;
  const scale = Math.min(1, CAPTURE_MAX_SIDE / Math.max(sw, sh));
  const w = Math.round(sw * scale);
  const h = Math.round(sh * scale);

  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (facing === 'user') {
    // keep selfies matching what the user saw in the mirrored preview
    ctx.translate(w, 0);
    ctx.scale(-1, 1);
  }
  ctx.filter = brightness ? `brightness(${brightnessGain()})` : 'none';
  ctx.drawImage(source, 0, 0, w, h);
  ctx.filter = 'none';
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  if (flashBitmap) flashBitmap.close();

  if (torchUsed) setTorch(false);
  screenFlash(false);

  const filter = getFilter(activeFilterId);
  const wantFisheye = fisheyeOn && fisheyeAmount > 0;
  if (wantFisheye || filter.id !== 'normal') {
    const frame = ctx.getImageData(0, 0, w, h);
    if (wantFisheye) fisheye(frame, w, h, fisheyeAmount);
    if (filter.id !== 'normal') filter.apply(frame, w, h, { preview: false });
    ctx.putImageData(frame, 0, 0);
  }

  // Download synchronously with the shutter gesture (no flash) or within its
  // transient-activation window (flash). A fresh one-time blob: URL plus a
  // millisecond-unique filename keeps browsers' duplicate-download and
  // multiple-download heuristics from blocking or prompting.
  const blob = dataUrlToBlob(canvas.toDataURL('image/jpeg', 0.92));
  downloadBlob(blob, mediaFilename('jpg'));

  const thumb = makeThumb(canvas);
  setThumbnail(thumb);
  addShot(blob, 'photo', thumb);
  showToast('Saved ✓');
}

function dataUrlToBlob(dataUrl) {
  const bin = atob(dataUrl.split(',')[1]);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type: 'image/jpeg' });
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}

function mediaFilename(ext) {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `retrocam-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}-${String(d.getMilliseconds()).padStart(3, '0')}.${ext}`;
}

function makeThumb(source) {
  const t = document.createElement('canvas');
  const s = 128 / Math.max(source.width, source.height);
  t.width = Math.max(1, Math.round(source.width * s));
  t.height = Math.max(1, Math.round(source.height * s));
  t.getContext('2d').drawImage(source, 0, 0, t.width, t.height);
  return t.toDataURL('image/jpeg', 0.7);
}

function setThumbnail(thumbDataUrl) {
  lastPhotoImg.src = thumbDataUrl;
  lastPhotoImg.hidden = false;
}

/* ---------------- video recording ---------------- */

function releaseMic() {
  if (audioStream) {
    for (const t of audioStream.getTracks()) t.stop();
    audioStream = null;
  }
}

function pickRecordingMime(withAudio) {
  if (!window.MediaRecorder) return '';
  // only formats whose AUDIO codec is explicitly supported — a phone can
  // accept bare "video/mp4" and then silently drop the audio track
  const candidates = withAudio
    ? [
        'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
        'video/mp4;codecs=avc1,mp4a.40.2',
        'video/webm;codecs=vp9,opus',
        'video/webm;codecs=vp8,opus',
        'video/mp4',
        'video/webm',
      ]
    : ['video/mp4', 'video/webm;codecs=vp9', 'video/webm'];
  return candidates.find((t) => MediaRecorder.isTypeSupported(t)) || '';
}

async function toggleRecording() {
  if (recorder) {
    recorder.requestStop();
    return;
  }
  if (!stream) return;

  // grab the mic for this recording — like a normal camera app, video
  // records with sound whenever the mic is available
  releaseMic();
  try {
    // raw, camera-style audio: voice-call processing (echo cancellation,
    // noise suppression, auto gain) mangles music and ambient sound
    audioStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
        channelCount: { ideal: 2 },
        sampleRate: { ideal: 48000 },
      },
    });
  } catch (err) {
    audioStream = null;
    showToast(
      err && err.name === 'NotAllowedError'
        ? 'No sound — allow microphone in site settings'
        : 'Recording without sound'
    );
  }

  if (flashOn) await setTorch(true);

  const hasAudio = !!(audioStream && audioStream.getAudioTracks().length);

  // arm the render loop so the canvas paints one frame at recording
  // resolution before the encoder locks in its dimensions
  let stopEarly = false;
  recorder = { requestStop: () => (stopEarly = true) };
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  if (stopEarly || !stream) {
    recorder = null;
    setTorch(false);
    releaseMic();
    return;
  }

  // prefer WebCodecs MP4 (H.264 + AAC): the format native camera apps
  // produce, so phone gallery apps play the sound
  let controller = null;
  if (await mp4RecorderSupported(hasAudio)) {
    try {
      const rec = new Mp4Recorder({
        width: preview.width,
        height: preview.height,
        audioTrack: hasAudio ? audioStream.getAudioTracks()[0] : null,
      });
      await rec.start();
      let stopping = false;
      controller = {
        addFrame: (canvas) => rec.addFrame(canvas),
        requestStop: async () => {
          if (stopping) return;
          stopping = true;
          const blob = await rec.stop().catch(() => null);
          finalizeRecording(blob, 'mp4');
        },
      };
    } catch {
      controller = null;
    }
  }
  if (!controller) controller = startMediaRecorder(hasAudio);
  if (!controller) {
    recorder = null;
    showToast('Video not supported here');
    setTorch(false);
    releaseMic();
    return;
  }
  recorder = controller;

  // live proof of whether sound is being captured on this recording
  recordMic.textContent = hasAudio ? '🎙' : '🔇';

  recordStartedAt = Date.now();
  recordTimer.hidden = false;
  recordTickInt = setInterval(() => {
    const s = Math.floor((Date.now() - recordStartedAt) / 1000);
    recordTime.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }, 250);
  shutterBtn.classList.add('recording');
  flipBtn.disabled = true;
}

// fallback recorder for devices without WebCodecs MP4 support
function startMediaRecorder(hasAudio) {
  if (!window.MediaRecorder) return null;
  const canvasStream = preview.captureStream(30);
  const tracks = [...canvasStream.getVideoTracks()];
  if (hasAudio) tracks.push(...audioStream.getAudioTracks());
  const mime = pickRecordingMime(hasAudio);
  let mr;
  try {
    mr = new MediaRecorder(
      new MediaStream(tracks),
      mime ? { mimeType: mime, audioBitsPerSecond: 128000 } : {}
    );
  } catch {
    return null;
  }
  const chunks = [];
  mr.ondataavailable = (e) => {
    if (e.data && e.data.size) chunks.push(e.data);
  };
  mr.onstop = () => {
    const type = mr.mimeType || 'video/webm';
    finalizeRecording(new Blob(chunks, { type }), type.includes('mp4') ? 'mp4' : 'webm');
  };
  mr.start(1000);
  return {
    requestStop: () => {
      try {
        mr.stop();
      } catch {
        /* already stopped */
      }
    },
  };
}

function finalizeRecording(blob, ext) {
  recorder = null;
  clearInterval(recordTickInt);
  recordTimer.hidden = true;
  recordTime.textContent = '0:00';
  shutterBtn.classList.remove('recording');
  flipBtn.disabled = false;
  setTorch(false);
  releaseMic(); // free the mic between recordings, like a normal camera app

  if (!blob || !blob.size) {
    showToast('Recording failed');
    return;
  }

  downloadBlob(blob, mediaFilename(ext));
  const thumb = makeThumb(preview);
  setThumbnail(thumb);
  addShot(blob, 'video', thumb);
  showToast(`Saved ✓ ${ext.toUpperCase()}`);
  if (ext === 'webm') {
    // this phone couldn't record MP4/AAC; warn that native gallery apps may
    // play WEBM clips without sound — in-app playback and Share still work
    setTimeout(() => showToast('Tip: play WEBM clips here or use Share'), 1500);
  }
}

/* ---------------- mode switch ---------------- */

modeSwitch.addEventListener('click', (e) => {
  const btn = e.target.closest('button[data-mode]');
  if (!btn || recorder) return;
  mode = btn.dataset.mode;
  for (const b of modeSwitch.children) b.classList.toggle('active', b === btn);
  shutterBtn.classList.toggle('video', mode === 'video');
  shutterBtn.setAttribute('aria-label', mode === 'video' ? 'Record video' : 'Take photo');
});

/* ---------------- gallery (IndexedDB) ---------------- */

function openDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('retrocam-db', 1);
    req.onupgradeneeded = () =>
      req.result.createObjectStore('shots', { keyPath: 'id', autoIncrement: true });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function addShot(blob, type, thumb) {
  try {
    const db = await openDb();
    db.transaction('shots', 'readwrite').objectStore('shots').add({ blob, type, thumb, ts: Date.now() });
  } catch {
    /* gallery is best-effort; the download already saved the file */
  }
}

async function getShots() {
  try {
    const db = await openDb();
    return await new Promise((resolve) => {
      const req = db.transaction('shots').objectStore('shots').getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => resolve([]);
    });
  } catch {
    return [];
  }
}

async function deleteShotById(id) {
  try {
    const db = await openDb();
    db.transaction('shots', 'readwrite').objectStore('shots').delete(id);
  } catch {
    /* ignore */
  }
}

async function openGallery() {
  galleryItems = await getShots();
  if (!galleryItems.length) {
    showToast('No photos yet');
    return;
  }
  closeGalleryUrls();
  galleryTrack.innerHTML = '';
  for (const item of galleryItems) {
    const url = URL.createObjectURL(item.blob);
    galleryUrls.push(url);
    const slide = document.createElement('div');
    slide.className = 'slide';
    if (item.type === 'video') {
      const v = document.createElement('video');
      v.src = url;
      v.controls = true;
      v.playsInline = true;
      v.preload = 'metadata';
      slide.appendChild(v);
    } else {
      const img = document.createElement('img');
      img.src = url;
      img.alt = 'Photo';
      slide.appendChild(img);
    }
    galleryTrack.appendChild(slide);
  }
  reviewScreen.hidden = false;
  // open on the most recent shot
  requestAnimationFrame(() => {
    galleryTrack.scrollLeft = galleryTrack.scrollWidth;
    updateGalleryCounter();
  });
}

function galleryIndex() {
  if (!galleryTrack.clientWidth) return 0;
  return Math.min(
    galleryItems.length - 1,
    Math.max(0, Math.round(galleryTrack.scrollLeft / galleryTrack.clientWidth))
  );
}

function updateGalleryCounter() {
  galleryCounter.textContent = galleryItems.length
    ? `${galleryIndex() + 1} / ${galleryItems.length}`
    : '';
  // pause videos that were swiped away
  const idx = galleryIndex();
  galleryTrack.querySelectorAll('video').forEach((v) => {
    const slideIdx = [...galleryTrack.children].indexOf(v.parentElement);
    if (slideIdx !== idx) v.pause();
  });
}

let galleryScrollRaf = 0;
galleryTrack.addEventListener('scroll', () => {
  cancelAnimationFrame(galleryScrollRaf);
  galleryScrollRaf = requestAnimationFrame(updateGalleryCounter);
});

function closeGalleryUrls() {
  for (const url of galleryUrls) URL.revokeObjectURL(url);
  galleryUrls = [];
}

function closeGallery() {
  galleryTrack.querySelectorAll('video').forEach((v) => v.pause());
  reviewScreen.hidden = true;
  closeGalleryUrls();
  galleryTrack.innerHTML = '';
  galleryItems = [];
}

function currentGalleryItem() {
  return galleryItems[galleryIndex()] || null;
}

function saveCurrent() {
  const item = currentGalleryItem();
  if (!item) return;
  const ext = item.type === 'video' ? (item.blob.type.includes('mp4') ? 'mp4' : 'webm') : 'jpg';
  downloadBlob(item.blob, mediaFilename(ext));
  showToast('Saved ✓');
}

async function shareCurrent() {
  const item = currentGalleryItem();
  if (!item) return;
  const ext = item.type === 'video' ? (item.blob.type.includes('mp4') ? 'mp4' : 'webm') : 'jpg';
  const file = new File([item.blob], mediaFilename(ext), { type: item.blob.type || 'image/jpeg' });
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ files: [file] });
    } catch {
      /* user closed the share sheet */
    }
    return;
  }
  saveCurrent(); // no share support — fall back to download
}

async function deleteCurrent() {
  const idx = galleryIndex();
  const item = galleryItems[idx];
  if (!item) return;
  await deleteShotById(item.id);
  galleryItems.splice(idx, 1);
  const slide = galleryTrack.children[idx];
  if (slide) slide.remove();
  if (!galleryItems.length) {
    closeGallery();
    lastPhotoImg.hidden = true;
    lastPhotoImg.removeAttribute('src');
    return;
  }
  updateGalleryCounter();
  const last = galleryItems[galleryItems.length - 1];
  if (last && last.thumb) setThumbnail(last.thumb);
}

/* ---------------- tap to focus ---------------- */

function tapToFocus(e) {
  const rect = preview.getBoundingClientRect();
  if (
    e.clientX < rect.left ||
    e.clientX > rect.right ||
    e.clientY < rect.top ||
    e.clientY > rect.bottom
  ) {
    return;
  }

  // focus ring animation at the tap point
  const vfRect = viewfinder.getBoundingClientRect();
  focusRing.style.left = `${e.clientX - vfRect.left}px`;
  focusRing.style.top = `${e.clientY - vfRect.top}px`;
  focusRing.classList.remove('show');
  void focusRing.offsetWidth; // restart the animation
  focusRing.classList.add('show');

  // ask the camera to focus/meter on that point (where hardware supports it)
  const track = stream && stream.getVideoTracks()[0];
  if (!track || !track.getCapabilities) return;
  let x = (e.clientX - rect.left) / rect.width;
  const y = (e.clientY - rect.top) / rect.height;
  if (facing === 'user') x = 1 - x; // preview is mirrored
  try {
    const caps = track.getCapabilities();
    const adv = {};
    if (Array.isArray(caps.focusMode)) {
      if (caps.focusMode.includes('single-shot')) adv.focusMode = 'single-shot';
      else if (caps.focusMode.includes('continuous')) adv.focusMode = 'continuous';
    }
    if ('pointsOfInterest' in caps) adv.pointsOfInterest = [{ x, y }];
    if (Object.keys(adv).length) track.applyConstraints({ advanced: [adv] }).catch(() => {});
  } catch {
    /* focus not supported on this device — the ring still gives feedback */
  }
}

viewfinder.addEventListener('pointerdown', (e) => {
  // ignore taps on the overlay controls
  if (e.target.closest('.side-control') || e.target.closest('#flash-toggle') || e.target.closest('#fisheye-toggle')) {
    return;
  }
  tapToFocus(e);
});

/* ---------------- fisheye lens ---------------- */

fisheyeSlider.value = String(fisheyeAmount);

fisheyeToggle.addEventListener('click', () => {
  fisheyeOn = !fisheyeOn;
  fisheyeToggle.classList.toggle('off', !fisheyeOn);
  fisheyeControl.hidden = !fisheyeOn;
  showToast(fisheyeOn ? `Fisheye on (${fisheyeAmount.toFixed(1)})` : 'Fisheye off');
});

fisheyeSlider.addEventListener('input', () => {
  fisheyeAmount = Math.min(3, Math.max(0, parseFloat(fisheyeSlider.value) || 0));
  localStorage.setItem('retrocam-fisheye', String(fisheyeAmount));
  showToast(`Fisheye ${fisheyeAmount.toFixed(1)}`);
});

/* ---------------- brightness ---------------- */

exposureSlider.addEventListener('input', () => {
  brightness = parseFloat(exposureSlider.value) || 0;
  const label = brightness > 0 ? `+${brightness.toFixed(1)}` : brightness.toFixed(1);
  showToast(`Brightness ${label}`);
});

function resetBrightness() {
  exposureSlider.value = '0';
  brightness = 0;
  showToast('Brightness 0.0');
}

// double-tap the slider to snap back to center — pointer-based so it works
// on touchscreens, plus dblclick for mouse
let lastBrightnessTap = 0;
document.querySelector('#exposure-control .vrange-wrap').addEventListener('pointerdown', () => {
  const now = Date.now();
  if (now - lastBrightnessTap < 350) resetBrightness();
  lastBrightnessTap = now;
});
exposureSlider.addEventListener('dblclick', resetBrightness);

/* ---------------- wiring ---------------- */

buildFilterStrip();

shutterBtn.addEventListener('click', () => {
  if (mode === 'video') toggleRecording();
  else takePhoto();
});
flipBtn.addEventListener('click', () => {
  if (recorder) return;
  facing = facing === 'environment' ? 'user' : 'environment';
  startCamera();
});
document.getElementById('retry-camera').addEventListener('click', startCamera);
document.getElementById('close-review').addEventListener('click', closeGallery);
document.getElementById('save').addEventListener('click', saveCurrent);
document.getElementById('share').addEventListener('click', shareCurrent);
document.getElementById('delete-shot').addEventListener('click', deleteCurrent);
lastPhotoBtn.addEventListener('click', openGallery);

document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    if (recorder) recorder.requestStop();
    stopCamera();
  } else {
    startCamera();
  }
});

// restore the gallery thumbnail from the last session
getShots().then((shots) => {
  const last = shots[shots.length - 1];
  if (last && last.thumb && lastPhotoImg.hidden) setThumbnail(last.thumb);
});

document.getElementById('app-version').textContent = APP_VERSION;

/* ---------------- flash diagnostics (tap the version label) ---------------- */

async function showDiagnostics() {
  const diagText = document.getElementById('diag-text');
  const lines = [`Retro Cam ${APP_VERSION}`, `facing: ${facing}`, ''];
  lines.push(`browser: ${navigator.userAgent}`, '');
  const track = stream && stream.getVideoTracks()[0];
  if (!track) {
    lines.push('NO CAMERA TRACK');
  } else {
    lines.push(`camera: ${track.label || '(no label)'}`);
    let caps = {};
    try {
      caps = track.getCapabilities ? track.getCapabilities() : {};
    } catch {
      /* ignore */
    }
    lines.push(`torch capability: ${'torch' in caps ? JSON.stringify(caps.torch) : 'NOT LISTED'}`);
    let s = {};
    try {
      s = track.getSettings ? track.getSettings() : {};
    } catch {
      /* ignore */
    }
    lines.push(`settings.torch: ${'torch' in s ? s.torch : 'n/a'}`);

    // live attempt — if the phone supports it AT ALL, the torch lights now
    let attempt;
    try {
      await track.applyConstraints({ torch: { exact: true } });
      const on = !!(track.getSettings && track.getSettings().torch);
      attempt = on
        ? 'EXACT constraint OK — torch should be LIT right now'
        : 'constraint accepted but settings.torch=false (browser ignored it)';
      setTimeout(() => {
        track.applyConstraints({ torch: { exact: false } }).catch(() => {});
        track.applyConstraints({ advanced: [{ torch: false }] }).catch(() => {});
      }, 1500);
    } catch (e) {
      attempt = `exact constraint REJECTED: ${(e && e.name) || e}`;
    }
    lines.push(`torch attempt: ${attempt}`);

    if ('ImageCapture' in window) {
      try {
        const pc = await new ImageCapture(track).getPhotoCapabilities();
        lines.push(`fillLightMode: ${JSON.stringify(pc && pc.fillLightMode)}`);
      } catch (e) {
        lines.push(`ImageCapture caps failed: ${(e && e.name) || e}`);
      }
    } else {
      lines.push('ImageCapture: not available');
    }

    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const cams = devices.filter((d) => d.kind === 'videoinput');
      lines.push('', `cameras (${cams.length}):`);
      for (const c of cams) {
        const mark = torchCamId && c.deviceId === torchCamId ? '  ← saved torch lens' : '';
        lines.push(`  ${c.label || '(hidden label)'}${mark}`);
      }
      lines.push(`torch lens saved: ${torchCamId ? 'yes' : 'no'}`);
    } catch {
      /* ignore */
    }
  }
  diagText.textContent = lines.join('\n');
  document.getElementById('diag-panel').hidden = false;
}

document.getElementById('app-version').addEventListener('click', showDiagnostics);
document.getElementById('diag-close').addEventListener('click', () => {
  document.getElementById('diag-panel').hidden = true;
});

if ('serviceWorker' in navigator) {
  // auto-apply updates: when a new version of the app activates, reload once
  // so users get fixes without the close-and-reopen dance
  navigator.serviceWorker
    .register('./sw.js')
    .then((reg) => {
      reg.update().catch(() => {});
      // Android keeps PWAs alive in the background for hours, so a launch-time
      // check alone misses updates — re-check every time the app is foregrounded
      document.addEventListener('visibilitychange', () => {
        if (!document.hidden) reg.update().catch(() => {});
      });
      reg.addEventListener('updatefound', () => {
        const next = reg.installing;
        if (!next) return;
        const hadController = !!navigator.serviceWorker.controller;
        next.addEventListener('statechange', () => {
          if (next.state === 'activated' && hadController && !recorder) {
            location.reload();
          }
        });
      });
    })
    .catch(() => {});
}

startCamera();
