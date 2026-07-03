// app.js — camera, live filtered preview, photo + video capture, flash,
// tap-to-focus, exposure, and a gallery of everything shot with the app.
// Filters themselves live in filters.js; this file never needs to change
// when a new filter is added.

import { getFilters, getFilter } from './filters.js';

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
const lastPhotoBtn = document.getElementById('last-photo');
const lastPhotoImg = document.getElementById('last-photo-img');
const reviewScreen = document.getElementById('review-screen');
const galleryTrack = document.getElementById('gallery-track');
const galleryCounter = document.getElementById('gallery-counter');
const focusRing = document.getElementById('focus-ring');
const exposureSlider = document.getElementById('exposure');

let stream = null;
let facing = 'environment';
let activeFilterId = localStorage.getItem('retrocam-filter') || 'fifties-cam';
let mode = 'photo'; // 'photo' | 'video'
let flashOn = false;
let rafId = 0;
let toastTimer = 0;
let brightness = 0; // -3..+3; mapped to a gentle gain so it always works

let recorder = null;
let recordChunks = [];
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

async function startCamera() {
  stopCamera();
  errorBox.hidden = true;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: facing,
        width: { ideal: 1920 },
        height: { ideal: 1080 },
      },
      audio: false,
    });
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
  if (filter.id !== 'normal') {
    const frame = previewCtx.getImageData(0, 0, w, h);
    filter.apply(frame, w, h, { preview: true });
    previewCtx.putImageData(frame, 0, 0);
  }
}

/* ---------------- flash ---------------- */

async function setTorch(on) {
  const track = stream && stream.getVideoTracks()[0];
  if (!track || !track.getCapabilities) return false;
  try {
    if (track.getCapabilities().torch) {
      await track.applyConstraints({ advanced: [{ torch: on }] });
      return true;
    }
  } catch {
    /* torch not available */
  }
  return false;
}

function screenFlash(on) {
  flash.classList.toggle('screen', on);
}

flashToggle.addEventListener('click', () => {
  flashOn = !flashOn;
  flashToggle.classList.toggle('off', !flashOn);
  showToast(flashOn ? 'Flash on' : 'Flash off');
  // if toggled off mid-recording, kill the torch
  if (!flashOn && recorder) setTorch(false);
  if (flashOn && recorder) setTorch(true);
});

/* ---------------- photo capture ---------------- */

async function takePhoto() {
  if (!video.videoWidth) return;

  let torchUsed = false;
  if (flashOn) {
    // rear camera: real torch when the hardware has one; front camera (or no
    // torch): light the face up with a white screen instead
    torchUsed = facing === 'environment' && (await setTorch(true));
    if (!torchUsed) screenFlash(true);
    await sleep(350); // give auto-exposure a beat to adapt to the light
  }

  flash.classList.add('on');
  setTimeout(() => flash.classList.remove('on'), 60);

  const vw = video.videoWidth;
  const vh = video.videoHeight;
  const scale = Math.min(1, CAPTURE_MAX_SIDE / Math.max(vw, vh));
  const w = Math.round(vw * scale);
  const h = Math.round(vh * scale);

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
  ctx.drawImage(video, 0, 0, w, h);
  ctx.filter = 'none';
  ctx.setTransform(1, 0, 0, 1, 0, 0);

  if (torchUsed) setTorch(false);
  screenFlash(false);

  const filter = getFilter(activeFilterId);
  if (filter.id !== 'normal') {
    const frame = ctx.getImageData(0, 0, w, h);
    filter.apply(frame, w, h, { preview: false });
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
    recorder.stop();
    return;
  }
  if (!stream) return;

  // grab the mic for this recording — like a normal camera app, video
  // records with sound whenever the mic is available
  releaseMic();
  try {
    audioStream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true },
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

  const canvasStream = preview.captureStream(30);
  const tracks = [...canvasStream.getVideoTracks()];
  const hasAudio = !!(audioStream && audioStream.getAudioTracks().length);
  if (hasAudio) tracks.push(...audioStream.getAudioTracks());

  const mime = pickRecordingMime(hasAudio);
  try {
    recorder = new MediaRecorder(
      new MediaStream(tracks),
      mime ? { mimeType: mime, audioBitsPerSecond: 128000 } : {}
    );
  } catch {
    showToast('Video not supported here');
    setTorch(false);
    releaseMic();
    return;
  }
  recordChunks = [];
  recorder.ondataavailable = (e) => {
    if (e.data && e.data.size) recordChunks.push(e.data);
  };
  recorder.onstop = onRecordingStop;
  recorder.start(1000);

  recordStartedAt = Date.now();
  recordTimer.hidden = false;
  recordTickInt = setInterval(() => {
    const s = Math.floor((Date.now() - recordStartedAt) / 1000);
    recordTime.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }, 250);
  shutterBtn.classList.add('recording');
  flipBtn.disabled = true;
}

function onRecordingStop() {
  const mimeType = (recorder && recorder.mimeType) || 'video/webm';
  const ext = mimeType.includes('mp4') ? 'mp4' : 'webm';
  recorder = null;
  clearInterval(recordTickInt);
  recordTimer.hidden = true;
  recordTime.textContent = '0:00';
  shutterBtn.classList.remove('recording');
  flipBtn.disabled = false;
  setTorch(false);
  releaseMic(); // free the mic between recordings, like a normal camera app

  const blob = new Blob(recordChunks, { type: mimeType });
  recordChunks = [];
  if (!blob.size) return;

  downloadBlob(blob, mediaFilename(ext));
  const thumb = makeThumb(preview);
  setThumbnail(thumb);
  addShot(blob, 'video', thumb);
  showToast('Saved ✓');
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
  if (e.target.closest('#exposure-control') || e.target.closest('#flash-toggle')) return;
  tapToFocus(e);
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
document.getElementById('exposure-wrap').addEventListener('pointerdown', () => {
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
    if (recorder) recorder.stop();
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

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}

startCamera();
