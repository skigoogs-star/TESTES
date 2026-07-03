// app.js — camera, live filtered preview, capture, save/share.
// Filters themselves live in filters.js; this file never needs to change
// when a new filter is added.

import { getFilters, getFilter } from './filters.js';

const PREVIEW_MAX_SIDE = 640; // live filtering stays smooth on phones
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
const shutterBtn = document.getElementById('shutter');
const flipBtn = document.getElementById('flip');
const lastPhotoBtn = document.getElementById('last-photo');
const lastPhotoImg = document.getElementById('last-photo-img');
const reviewScreen = document.getElementById('review-screen');
const reviewImg = document.getElementById('review-img');
const focusRing = document.getElementById('focus-ring');
const exposureSlider = document.getElementById('exposure');

let stream = null;
let facing = 'environment';
let activeFilterId = localStorage.getItem('retrocam-filter') || 'fifties-cam';
let rafId = 0;
let toastTimer = 0;
let capturedBlob = null;
let capturedUrl = null;
let exposureEV = 0; // in stops; applied as brightness gain so it always works

function exposureGain() {
  return Math.pow(2, exposureEV);
}

function showToast(text) {
  toast.textContent = text;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 1200);
}

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
  const scale = Math.min(1, PREVIEW_MAX_SIDE / Math.max(vw, vh));
  const w = Math.round(vw * scale);
  const h = Math.round(vh * scale);
  if (preview.width !== w || preview.height !== h) {
    preview.width = w;
    preview.height = h;
  }

  previewCtx.filter = exposureEV ? `brightness(${exposureGain()})` : 'none';
  previewCtx.drawImage(video, 0, 0, w, h);
  previewCtx.filter = 'none';
  const filter = getFilter(activeFilterId);
  if (filter.id !== 'normal') {
    const frame = previewCtx.getImageData(0, 0, w, h);
    filter.apply(frame, w, h, { preview: true });
    previewCtx.putImageData(frame, 0, 0);
  }
}

/* ---------------- capture ---------------- */

function capture() {
  if (!video.videoWidth) return;

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
  ctx.filter = exposureEV ? `brightness(${exposureGain()})` : 'none';
  ctx.drawImage(video, 0, 0, w, h);
  ctx.filter = 'none';
  ctx.setTransform(1, 0, 0, 1, 0, 0);

  const filter = getFilter(activeFilterId);
  if (filter.id !== 'normal') {
    const frame = ctx.getImageData(0, 0, w, h);
    filter.apply(frame, w, h, { preview: false });
    ctx.putImageData(frame, 0, 0);
  }

  // Everything above and below runs synchronously inside the shutter tap.
  // That matters: browsers only allow repeated downloads when each one is
  // directly tied to a user gesture — an async gap here and only the first
  // photo would ever save.
  const dataUrl = canvas.toDataURL('image/jpeg', 0.92);
  const a = document.createElement('a');
  a.href = dataUrl;
  a.download = photoFilename();
  document.body.appendChild(a);
  a.click();
  a.remove();

  // keep a blob around for the review screen's Share/Save buttons
  capturedBlob = dataUrlToBlob(dataUrl);
  if (capturedUrl) URL.revokeObjectURL(capturedUrl);
  capturedUrl = URL.createObjectURL(capturedBlob);
  reviewImg.src = capturedUrl;
  rememberLastPhoto();
  showToast('Saved ✓');
}

function dataUrlToBlob(dataUrl) {
  const bin = atob(dataUrl.split(',')[1]);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type: 'image/jpeg' });
}

function photoFilename() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `retrocam-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}.jpg`;
}

function savePhoto() {
  if (!capturedBlob) return;
  const a = document.createElement('a');
  a.href = capturedUrl;
  a.download = photoFilename();
  document.body.appendChild(a);
  a.click();
  a.remove();
}

async function sharePhoto() {
  if (!capturedBlob) return;
  const file = new File([capturedBlob], photoFilename(), { type: 'image/jpeg' });
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ files: [file] });
      rememberLastPhoto();
      return;
    } catch {
      return; // user cancelled the share sheet
    }
  }
  savePhoto(); // no share support — fall back to download
}

function rememberLastPhoto() {
  lastPhotoImg.src = capturedUrl;
  lastPhotoImg.hidden = false;
}

function closeReview() {
  reviewScreen.hidden = true;
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
  // ignore taps on the exposure slider
  if (e.target.closest('#exposure-control')) return;
  tapToFocus(e);
});

/* ---------------- exposure ---------------- */

exposureSlider.addEventListener('input', () => {
  exposureEV = parseFloat(exposureSlider.value) || 0;
  const label = exposureEV > 0 ? `+${exposureEV.toFixed(1)}` : exposureEV.toFixed(1);
  showToast(`Exposure ${label}`);
});

exposureSlider.addEventListener('dblclick', () => {
  exposureSlider.value = '0';
  exposureEV = 0;
  showToast('Exposure 0.0');
});

/* ---------------- wiring ---------------- */

buildFilterStrip();

shutterBtn.addEventListener('click', capture);
flipBtn.addEventListener('click', () => {
  facing = facing === 'environment' ? 'user' : 'environment';
  startCamera();
});
document.getElementById('retry-camera').addEventListener('click', startCamera);
document.getElementById('discard').addEventListener('click', closeReview);
document.getElementById('save').addEventListener('click', savePhoto);
document.getElementById('share').addEventListener('click', sharePhoto);
lastPhotoBtn.addEventListener('click', () => {
  if (!lastPhotoImg.hidden) reviewScreen.hidden = false;
});

document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopCamera();
  else startCamera();
});

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}

startCamera();
