# Retro Cam 📸

A camera app for your phone with named retro filters — starting with **50' cam**,
a grainy 1950s press-photo look: high-contrast black & white, heavy film grain,
glowing highlights (halation), soft focus and a vignette.

It's a PWA (progressive web app): no app store needed. Open the URL on your
phone, allow camera access, and add it to your home screen so it launches
like a real app.

## Filters

| Name | Look |
| --- | --- |
| **Normal** | No filter |
| **50' cam** | 1950s B&W press photo — grain, glow, vignette |
| **Super 70s** | Warm faded 70s color film |
| **Polaroid '89** | Washed-out instant-photo look |
| **VHS '95** | Camcorder tape — fringing, scanlines, noise |

## Camera controls

- **Shutter** — takes the photo and **saves it automatically** (a "Saved ✓"
  toast confirms).
- **Video mode** — tap **VIDEO** under the filter strip; the shutter turns
  red and records filtered video (with sound). Filters can even be switched
  mid-recording. Tap again to stop; the clip saves automatically.
- **Flash** — the ⚡ button (top-left) toggles flash. The rear camera uses
  the real torch when the phone has one; the front camera lights your face
  with a white screen. In video mode the torch stays on while recording.
- **Gallery** — tap the thumbnail (bottom-left) to browse **everything shot
  with the app**; swipe left/right between photos and videos, and use
  Save / Share / Delete on any of them.
- **Tap to focus** — tap anywhere on the viewfinder; a yellow ring marks the
  spot and the camera focuses/meters there (on phones whose camera supports
  it — otherwise focus stays automatic).
- **Exposure** — drag the ☀ slider on the right edge up/down to brighten or
  darken (±2 stops). Double-tap the slider to reset.
- **Flip** — switches between rear and front camera.

## Get it on your phone

A GitHub Actions workflow (`.github/workflows/pages.yml`) deploys the app to
GitHub Pages automatically on every push. Once it has run, the app is live at
**https://skigoogs-star.github.io/TESTES/**

1. Open that URL on your phone and allow camera access.
2. In the browser menu, tap **Add to Home Screen** — done. It now opens
   fullscreen like a native app.

If the URL shows a 404, check the **Actions** tab for the "Deploy to GitHub
Pages" run. If the run failed to enable Pages, do it once by hand: repo
**Settings → Pages → Source: GitHub Actions**, then re-run the workflow.

## Adding a new filter

Everything lives in [`filters.js`](filters.js) — you never need to touch any
other file. Each filter is a small object registered with `registerFilter`,
composed from ready-made building blocks (`grayscale`, `makeCurveLUT` +
`applyLUT`, `grain`, `bloom`, `vignette`, `colorCast`, `splitTone`,
`desaturate`, `channelShift`, `scanlines`, `softFocus`).

Example — add a moody blue night filter:

```js
registerFilter({
  id: 'midnight',
  name: 'Midnight',
  apply(imageData, w, h, { preview = false } = {}) {
    const data = imageData.data;
    desaturate(data, 0.5);                                   // mute the colors
    colorCast(data, 0.85, 0.95, 1.2);                        // push blue
    applyLUT(data, makeCurveLUT({ black: 20, contrast: 0.4 })); // darker + punchier
    vignette(data, w, h, 0.4);                               // dark corners
    grain(data, w, h, preview ? 8 : 12);                     // film grain
  },
});
```

Paste it above the "ADD YOUR FILTER HERE" template at the bottom of
`filters.js`, reload the app, and a **Midnight** chip appears in the filter
strip automatically — working in both the live preview and captured photos.

> Tip: after changing files, bump `VERSION` in `sw.js` (e.g. `retrocam-v2`)
> so phones that installed the app pick up the update.

## Running locally

Any static server works, e.g.:

```sh
python3 -m http.server 8000
```

Note: browsers only allow camera access over HTTPS or on `localhost`.
