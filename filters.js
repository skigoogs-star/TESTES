// filters.js — filter engine + registry.
//
// Every filter in the app lives in this file. The UI (chips), live preview
// and photo capture all read from the registry, so adding a new filter here
// is ALL you need to do — see the "ADD YOUR FILTER HERE" template at the
// bottom of the file.

const FILTERS = [];

export function registerFilter(filter) {
  FILTERS.push(filter);
}

export function getFilters() {
  return FILTERS;
}

export function getFilter(id) {
  return FILTERS.find((f) => f.id === id) || FILTERS[0];
}

/* ------------------------------------------------------------------ */
/* Reusable building blocks                                            */
/* Each helper works on ImageData.data (Uint8ClampedArray) in place.   */
/* ------------------------------------------------------------------ */

/** Rec.709 luminance of one pixel. */
function lum(data, i) {
  return 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
}

/** Extract a Float32Array of per-pixel luminance. */
export function luminanceMap(data, w, h) {
  const out = new Float32Array(w * h);
  for (let p = 0, i = 0; p < out.length; p++, i += 4) out[p] = lum(data, i);
  return out;
}

/**
 * Build a 256-entry tone LUT.
 * black/white: input levels mapped to 0/255 (crush + clip).
 * contrast: 0 = linear, 1 = strong S-curve. lift: raises output floor.
 */
export function makeCurveLUT({ black = 0, white = 255, contrast = 0, lift = 0 } = {}) {
  const lut = new Uint8ClampedArray(256);
  for (let v = 0; v < 256; v++) {
    let x = (v - black) / (white - black);
    x = Math.min(1, Math.max(0, x));
    // smoothstep-based S-curve blended with linear
    const s = x * x * (3 - 2 * x);
    let y = x + (s - x) * contrast;
    y = lift / 255 + y * (1 - lift / 255);
    lut[v] = Math.round(y * 255);
  }
  return lut;
}

/** Apply a tone LUT to all RGB channels. */
export function applyLUT(data, lut) {
  for (let i = 0; i < data.length; i += 4) {
    data[i] = lut[data[i]];
    data[i + 1] = lut[data[i + 1]];
    data[i + 2] = lut[data[i + 2]];
  }
}

/** Convert to grayscale (luminance). */
export function grayscale(data) {
  for (let i = 0; i < data.length; i += 4) {
    const l = lum(data, i);
    data[i] = data[i + 1] = data[i + 2] = l;
  }
}

/** Desaturate towards gray by amount (0..1). */
export function desaturate(data, amount) {
  for (let i = 0; i < data.length; i += 4) {
    const l = lum(data, i);
    data[i] += (l - data[i]) * amount;
    data[i + 1] += (l - data[i + 1]) * amount;
    data[i + 2] += (l - data[i + 2]) * amount;
  }
}

/** Multiply channels by (r,g,b) factors — warm/cool color casts. */
export function colorCast(data, r, g, b) {
  for (let i = 0; i < data.length; i += 4) {
    data[i] *= r;
    data[i + 1] *= g;
    data[i + 2] *= b;
  }
}

/**
 * Split-tone: tint shadows and highlights separately.
 * shadow/highlight are [r,g,b] offsets applied weighted by darkness/brightness.
 */
export function splitTone(data, shadow, highlight) {
  for (let i = 0; i < data.length; i += 4) {
    const t = lum(data, i) / 255;
    const sw = 1 - t;
    data[i] += shadow[0] * sw + highlight[0] * t;
    data[i + 1] += shadow[1] * sw + highlight[1] * t;
    data[i + 2] += shadow[2] * sw + highlight[2] * t;
  }
}

/**
 * Film grain. amount is noise amplitude in 0..255 units; strongest in
 * midtones, like real film. Monochrome noise (same on all channels).
 */
export function grain(data, w, h, amount) {
  for (let i = 0; i < data.length; i += 4) {
    const l = lum(data, i);
    const mid = 1 - Math.abs(l - 128) / 160; // fades in deep shadows/highlights
    const n = (Math.random() * 2 - 1) * amount * Math.max(0.15, mid);
    data[i] += n;
    data[i + 1] += n;
    data[i + 2] += n;
  }
}

/** Elliptical vignette. strength 0..1 darkens, negative lightens edges. */
export function vignette(data, w, h, strength, softness = 0.75) {
  const cx = w / 2;
  const cy = h / 2;
  const maxD = Math.sqrt(cx * cx + cy * cy);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const dx = x - cx;
      const dy = y - cy;
      const d = Math.sqrt(dx * dx + dy * dy) / maxD;
      const t = Math.min(1, Math.max(0, (d - (1 - softness)) / softness));
      const f = 1 - strength * t * t;
      const i = (y * w + x) * 4;
      data[i] *= f;
      data[i + 1] *= f;
      data[i + 2] *= f;
    }
  }
}

/** Separable box blur on a Float32Array field (in place, returns field). */
function boxBlurField(field, w, h, radius, passes = 2) {
  const tmp = new Float32Array(field.length);
  for (let p = 0; p < passes; p++) {
    // horizontal
    for (let y = 0; y < h; y++) {
      const row = y * w;
      let acc = 0;
      const win = radius * 2 + 1;
      for (let x = -radius; x <= radius; x++) acc += field[row + Math.min(w - 1, Math.max(0, x))];
      for (let x = 0; x < w; x++) {
        tmp[row + x] = acc / win;
        const out = Math.max(0, x - radius);
        const inn = Math.min(w - 1, x + radius + 1);
        acc += field[row + inn] - field[row + out];
      }
    }
    // vertical
    for (let x = 0; x < w; x++) {
      let acc = 0;
      const win = radius * 2 + 1;
      for (let y = -radius; y <= radius; y++) acc += tmp[Math.min(h - 1, Math.max(0, y)) * w + x];
      for (let y = 0; y < h; y++) {
        field[y * w + x] = acc / win;
        const out = Math.max(0, y - radius);
        const inn = Math.min(h - 1, y + radius + 1);
        acc += tmp[inn * w + x] - tmp[out * w + x];
      }
    }
  }
  return field;
}

/**
 * Halation/bloom: bright areas glow outward, like lights on 1950s film.
 * threshold: luminance where glow starts. strength: how much light is added.
 */
export function bloom(data, w, h, { threshold = 190, strength = 0.9, scale = 4, radius = 6 } = {}) {
  const dw = Math.max(1, Math.floor(w / scale));
  const dh = Math.max(1, Math.floor(h / scale));
  const down = new Float32Array(dw * dh);
  // bright-pass downsample (nearest is fine, the blur hides it)
  for (let y = 0; y < dh; y++) {
    for (let x = 0; x < dw; x++) {
      const i = ((y * scale) * w + x * scale) * 4;
      down[y * dw + x] = Math.max(0, lum(data, i) - threshold);
    }
  }
  boxBlurField(down, dw, dh, radius, 2);
  // additive upsample with bilinear sampling
  for (let y = 0; y < h; y++) {
    const fy = Math.min(dh - 1.001, y / scale);
    const y0 = Math.floor(fy);
    const ty = fy - y0;
    for (let x = 0; x < w; x++) {
      const fx = Math.min(dw - 1.001, x / scale);
      const x0 = Math.floor(fx);
      const tx = fx - x0;
      const a = down[y0 * dw + x0];
      const b = down[y0 * dw + x0 + 1];
      const c = down[(y0 + 1) * dw + x0];
      const d = down[(y0 + 1) * dw + x0 + 1];
      const g = (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
      if (g > 0.5) {
        const add = g * strength;
        const i = (y * w + x) * 4;
        data[i] += add;
        data[i + 1] += add;
        data[i + 2] += add;
      }
    }
  }
}

/** Soft focus: blend a blurred copy back in. amount 0..1. */
export function softFocus(data, w, h, amount, radius = 2) {
  const l = new Float32Array(w * h * 3);
  const dw = Math.max(1, Math.floor(w / 2));
  const dh = Math.max(1, Math.floor(h / 2));
  const ch = [new Float32Array(dw * dh), new Float32Array(dw * dh), new Float32Array(dw * dh)];
  for (let y = 0; y < dh; y++) {
    for (let x = 0; x < dw; x++) {
      const i = ((y * 2) * w + x * 2) * 4;
      const p = y * dw + x;
      ch[0][p] = data[i];
      ch[1][p] = data[i + 1];
      ch[2][p] = data[i + 2];
    }
  }
  for (const c of ch) boxBlurField(c, dw, dh, radius, 2);
  for (let y = 0; y < h; y++) {
    const sy = Math.min(dh - 1, y >> 1);
    for (let x = 0; x < w; x++) {
      const sp = sy * dw + Math.min(dw - 1, x >> 1);
      const i = (y * w + x) * 4;
      data[i] += (ch[0][sp] - data[i]) * amount;
      data[i + 1] += (ch[1][sp] - data[i + 1]) * amount;
      data[i + 2] += (ch[2][sp] - data[i + 2]) * amount;
    }
  }
  void l;
}

/** Shift R channel left and B channel right by px — VHS color fringing. */
export function channelShift(data, w, h, px) {
  const src = new Uint8ClampedArray(data);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = (row + x) * 4;
      const xr = Math.min(w - 1, Math.max(0, x + px));
      const xb = Math.min(w - 1, Math.max(0, x - px));
      data[i] = src[(row + xr) * 4];
      data[i + 2] = src[(row + xb) * 4 + 2];
    }
  }
}

/** Horizontal scanlines: darken every step-th row by amount (0..1). */
export function scanlines(data, w, h, step, amount) {
  for (let y = 0; y < h; y += step) {
    const row = y * w * 4;
    for (let x = 0; x < w; x++) {
      const i = row + x * 4;
      data[i] *= 1 - amount;
      data[i + 1] *= 1 - amount;
      data[i + 2] *= 1 - amount;
    }
  }
}

/**
 * Fisheye lens warp (barrel distortion) — not a filter, a lens: app.js
 * applies it before the active filter so grain/vignette stay natural.
 * strength 0..3. Uses a cached remap table so live preview stays fast.
 */
const fisheyeCache = { key: '', lut: null };
export function fisheye(imageData, w, h, strength) {
  if (!(strength > 0)) return;
  const key = `${w}x${h}:${strength.toFixed(2)}`;
  if (fisheyeCache.key !== key) {
    const lut = new Int32Array(w * h);
    const k = strength * 0.35;
    const cx = (w - 1) / 2;
    const cy = (h - 1) / 2;
    let p = 0;
    for (let y = 0; y < h; y++) {
      const ny = (y - cy) / cy;
      for (let x = 0; x < w; x++, p++) {
        const nx = (x - cx) / cx;
        const f = 1 + k * (nx * nx + ny * ny);
        const sx = Math.round(cx + (nx / f) * cx);
        const sy = Math.round(cy + (ny / f) * cy);
        lut[p] = sy * w + sx;
      }
    }
    fisheyeCache.key = key;
    fisheyeCache.lut = lut;
  }
  const src = new Uint8ClampedArray(imageData.data);
  const dst = imageData.data;
  const lut = fisheyeCache.lut;
  for (let p = 0, i = 0; p < lut.length; p++, i += 4) {
    const s = lut[p] * 4;
    dst[i] = src[s];
    dst[i + 1] = src[s + 1];
    dst[i + 2] = src[s + 2];
  }
}

/* ------------------------------------------------------------------ */
/* Filters                                                             */
/* ------------------------------------------------------------------ */

registerFilter({
  id: 'normal',
  name: 'Normal',
  apply() {
    /* passthrough */
  },
});

// 50' cam — 1950s press-photo film: grainy high-contrast B&W with glowing
// highlights (halation), soft focus and a vignette.
registerFilter({
  id: 'fifties-cam',
  name: "50' cam",
  apply(imageData, w, h, { preview = false } = {}) {
    const data = imageData.data;
    grayscale(data);
    softFocus(data, w, h, 0.4, preview ? 1 : 2);
    applyLUT(data, makeCurveLUT({ black: 26, white: 224, contrast: 0.55, lift: 10 }));
    bloom(data, w, h, {
      threshold: 178,
      strength: 1.1,
      scale: preview ? 4 : 6,
      radius: preview ? 5 : 8,
    });
    vignette(data, w, h, 0.45, 0.8);
    grain(data, w, h, preview ? 18 : 24);
  },
});

// Super 70s — warm faded color film, golden cast, lifted blacks.
registerFilter({
  id: 'super-70s',
  name: 'Super 70s',
  apply(imageData, w, h, { preview = false } = {}) {
    const data = imageData.data;
    colorCast(data, 1.12, 1.03, 0.82);
    desaturate(data, 0.18);
    applyLUT(data, makeCurveLUT({ black: 0, white: 248, contrast: 0.2, lift: 26 }));
    vignette(data, w, h, 0.25, 0.85);
    grain(data, w, h, preview ? 8 : 11);
  },
});

// Polaroid '89 — washed out, cool shadows, warm highlights, dreamy softness.
registerFilter({
  id: 'polaroid-89',
  name: "Polaroid '89",
  apply(imageData, w, h, { preview = false } = {}) {
    const data = imageData.data;
    desaturate(data, 0.3);
    splitTone(data, [-4, 2, 14], [12, 6, -6]);
    applyLUT(data, makeCurveLUT({ black: 0, white: 255, contrast: -0.25, lift: 22 }));
    softFocus(data, w, h, 0.25, preview ? 1 : 2);
    vignette(data, w, h, 0.18, 0.9);
    grain(data, w, h, preview ? 5 : 7);
  },
});

// VHS '95 — camcorder tape: color fringing, scanlines, video noise.
registerFilter({
  id: 'vhs-95',
  name: "VHS '95",
  apply(imageData, w, h, { preview = false } = {}) {
    const data = imageData.data;
    desaturate(data, 0.3);
    colorCast(data, 1.04, 1.0, 1.06);
    channelShift(data, w, h, Math.max(1, Math.round(w / 320)));
    applyLUT(data, makeCurveLUT({ black: 10, white: 245, contrast: 0.15, lift: 14 }));
    scanlines(data, w, h, 3, 0.14);
    grain(data, w, h, preview ? 10 : 13);
  },
});

/* ------------------------------------------------------------------ */
/* ADD YOUR FILTER HERE                                                */
/*                                                                     */
/* Copy this template, tweak the helpers, and the app picks it up      */
/* automatically — a new named chip appears in the carousel and the    */
/* filter works in both live preview and captured photos.              */
/*                                                                     */
/* registerFilter({                                                    */
/*   id: 'my-filter',            // unique, no spaces                  */
/*   name: 'My Filter',          // shown in the app                   */
/*   apply(imageData, w, h, { preview = false } = {}) {                */
/*     const data = imageData.data;                                    */
/*     // compose any of the helpers above, e.g.:                      */
/*     // grayscale(data);                                             */
/*     // colorCast(data, 1.1, 1.0, 0.9);                              */
/*     // applyLUT(data, makeCurveLUT({ contrast: 0.3 }));             */
/*     // bloom(data, w, h, { threshold: 200, strength: 0.8 });        */
/*     // vignette(data, w, h, 0.3);                                   */
/*     // grain(data, w, h, 12);                                       */
/*   },                                                                */
/* });                                                                 */
/* ------------------------------------------------------------------ */
