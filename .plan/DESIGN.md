# DESIGN.md — Athletic Cut Trainer

Design specification for the single-file, offline, mobile-first training app described in `trainingappspec.md`, running the program in `athleticcutprogram.md`. This document is the source of truth for every visual and interaction decision. If the engineer has to make a design call that isn't covered here, the fallback rule is in §13 ("When in doubt").

Written 2026-09-01. Research notes and sources are in §14; sources marked "(snippet)" were confirmed only through search excerpts because the host was unreachable from the build network.

---

## 0. Context that drives everything

The person using this app is:

- Holding a ~390 px-wide phone **in one hand**, usually the right, thumb doing all the work (Hoober: ~75 % of interactions are thumb-driven, ~49 % one-handed grip).
- **Out of breath**, between sets of a heavy trap-bar deadlift, with 150 s on the clock.
- **Sweaty.** Capacitive screens mis-register wet touches; small targets fail; accidental taps happen.
- Reading the phone from a **bench or the floor at arm's length (~60–75 cm)**, often for less than a second.
- In a gym with **bad or no Wi-Fi**, sometimes with the phone face-up on a bench and the screen dimming.
- Doing this **four times a week for 12–14 weeks**, so novelty is worthless and friction compounds.

Every decision below is downstream of those six facts.

---

## 1. Design direction: "Scoreboard"

**The app is a gym scoreboard, not a fitness dashboard.**

A stadium scoreboard is the best-tested piece of glanceable UI in the world: huge tabular numerals, near-black ground, one or two saturated signal colours, no decoration, readable from 40 m by someone who is shouting. That is the point of view. The session runner shows *one number you are about to lift*, *one number you did last time*, *one button*, and — when resting — *one clock*. Everything else is demoted to chrome or hidden behind a tap.

Concretely this means:

1. **Numbers are the interface.** Load, reps, timer and "last time" are set in a 56–112 px tabular-numeral display style. Labels are small, low-emphasis, and sit *under* the number they describe, never beside it.
2. **Near-black, not decorated dark.** Base surface `#0A0A0C` (OLED-friendly, but not `#000` — Apple and Material both warn that pure black causes halation and makes elevation unreadable; near-black also avoids OLED black-smear on scroll). Elevation is communicated by lighter surfaces, not shadows, per Material's dark-theme guidance.
3. **One accent, one state colour.** Amber `#FFB224` is *the* action colour (Log Set, Start Session, suggested-increase flag). Cyan `#4DD2FF` is *rest*. When the screen is amber-dominant you are working; when it is cyan-dominant you are resting. A person can tell which state the phone is in from across the rack without reading anything. Green and red are reserved for "beat last time" and "danger/destructive", used sparingly. No other hues exist in the product.
4. **Thumb-first geometry.** The bottom 40 % of the viewport holds every action that matters; the top 25 % holds only orientation (which block, which set) and is never the sole route to anything.
5. **Fat targets, fat spacing.** 44 px is the *floor* (Apple HIG default, WCAG 2.5.5 AAA). Primary and stepper controls are 64–72 px. Adjacent tappable elements have ≥ 8 px dead space so a sweaty mis-tap lands on nothing rather than on the wrong thing.
6. **Motion is functional or absent.** No spring bounces on the runner. Motion exists to (a) confirm a log, (b) show the timer taking over and giving back the screen, and (c) keep the rest ring moving so the user knows the app is alive. Reduced-motion users get the same information via opacity.
7. **Borrow from 2025–26 platform languages only where it helps.** From Material 3 Expressive we take *containment* (a grouped, filled control region reads faster than floating flat controls) and *size hierarchy*, not its springy shape-morphing. From Apple's Liquid Glass we take the layering rule — translucency belongs on the floating navigation/controls layer only, never on content — and the accessibility contract (Reduce Transparency / Increase Contrast must degrade to opaque, high-contrast). We do *not* adopt glass aesthetics as decoration; a sweaty thumb does not benefit from refraction.

What "Scoreboard" is *not*: not a Strava-style social feed, not a card-soup dashboard, not gradients and glass, not friendly rounded illustrations, not a coach with a personality. It is quiet, loud only where it counts.

---

## 2. Design tokens

All tokens are CSS custom properties on `:root`. Dark is the default (`color-scheme: dark light`). Light mode is applied by `@media (prefers-color-scheme: light)` **and** `[data-theme="light"]`; dark by `[data-theme="dark"]`. Do not offer an in-app theme toggle on first release beyond `system / dark / light` in Settings (Apple HIG: respect the system setting; the override exists only because gym lighting is unpredictable).

```css
:root {
  color-scheme: dark light;

  /* ---------- 2.1 Neutral ramp (dark) ---------- */
  --n-0:   #0A0A0C;   /* base ground, OLED near-black */
  --n-1:   #141418;   /* card */
  --n-2:   #1E1E24;   /* sheet / elevated card / bottom bar */
  --n-3:   #2A2A32;   /* pressed surface, chip unselected */
  --n-4:   #3A3A44;   /* chip selected (neutral), stepper track */
  --n-5:   #65656F;   /* control border (3.19:1 on --n-1, 3.43:1 on --n-0) */
  --n-6:   #8A8A96;   /* low-emphasis text */
  --n-7:   #B9B9C2;   /* mid-emphasis text */
  --n-8:   #F4F4F1;   /* high-emphasis text (off-white; never #FFF) */

  /* ---------- 2.2 Hue ramps (dark) ---------- */
  --amber-fill:  #FFB224;  --amber-fill-press: #E69E14;
  --amber-text:  #FFC65A;  --amber-dim: #2A1E00;  --on-amber: #1A1200;
  --cyan-fill:   #4DD2FF;  --cyan-text: #7DDFFF;  --cyan-dim: #071A22;  --on-cyan: #001A24;
  --green-fill:  #5BE28A;  --green-text: #7CEBA3; --green-dim: #0C2A18;
  --red-fill:    #B42318;  --red-text:   #FF8585; --red-dim:   #2E0F0D; --on-red: #FFF1F0;

  /* ---------- 2.3 Semantic roles ---------- */
  --bg:            var(--n-0);
  --surface:       var(--n-1);
  --surface-2:     var(--n-2);
  --surface-press: var(--n-3);
  --surface-sel:   var(--n-4);
  --line:          #33333C;      /* hairline divider, decorative only */
  --line-ctrl:     var(--n-5);   /* borders that carry meaning */
  --text:          var(--n-8);
  --text-2:        var(--n-7);
  --text-3:        var(--n-6);

  --primary:       var(--amber-fill);
  --primary-press: var(--amber-fill-press);
  --on-primary:    var(--on-amber);
  --primary-text:  var(--amber-text);
  --primary-dim:   var(--amber-dim);

  --rest:          var(--cyan-fill);
  --on-rest:       var(--on-cyan);
  --rest-text:     var(--cyan-text);
  --rest-dim:      var(--cyan-dim);

  --success:       var(--green-fill);
  --success-text:  var(--green-text);
  --success-dim:   var(--green-dim);

  --danger:        var(--red-fill);
  --on-danger:     var(--on-red);
  --danger-text:   var(--red-text);
  --danger-dim:    var(--red-dim);

  --focus:         var(--cyan-fill);
  --focus-inner:   var(--n-8);
  --scrim:         rgba(0,0,0,.72);

  /* ---------- 2.4 Typography ---------- */
  --font-ui:  system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --font-num: var(--font-ui);            /* same face; numerals use tnum */
  --font-mono: ui-monospace, Menlo, Consolas, "DejaVu Sans Mono", monospace; /* JSON export preview only */

  /* Sizes are px-anchored rem (root 16 px). Never scale root below 16. */
  --fs-hero:   7rem;      /* 112 px – rest timer digits */
  --fs-num-xl: 4.5rem;    /* 72 px – load stepper value */
  --fs-num-lg: 3.5rem;    /* 56 px – reps stepper value, EMOM minute clock */
  --fs-num-md: 2rem;      /* 32 px – tile values, "Last:" load */
  --fs-h1:     1.75rem;   /* 28 px – exercise name in runner, screen titles */
  --fs-h2:     1.375rem;  /* 22 px – section headers, "Last:" line */
  --fs-title:  1.125rem;  /* 18 px – list rows, button labels */
  --fs-body:   1.0625rem; /* 17 px – body (Apple default) */
  --fs-label:  0.9375rem; /* 15 px – secondary labels */
  --fs-cap:    0.8125rem; /* 13 px – captions, units; absolute minimum anywhere */

  --lh-tight: 1.05;  --lh-num: 1;  --lh-body: 1.4;
  --fw-reg: 400;  --fw-med: 500;  --fw-semi: 600;  --fw-bold: 700;  --fw-black: 800;
  --tracking-num: -0.02em;   /* large numerals */
  --tracking-cap:  0.06em;   /* uppercase captions */

  /* ---------- 2.5 Spacing (4 px grid) ---------- */
  --s-1: 4px;  --s-2: 8px;  --s-3: 12px;  --s-4: 16px;  --s-5: 20px;
  --s-6: 24px; --s-8: 32px; --s-10: 40px; --s-12: 48px; --s-16: 64px;
  --gutter: 16px;                       /* screen side margin */
  --safe-t: env(safe-area-inset-top, 0px);
  --safe-b: env(safe-area-inset-bottom, 0px);

  /* ---------- 2.6 Radii ---------- */
  --r-1: 6px;    /* chips, inline tags */
  --r-2: 12px;   /* buttons, inputs, stepper cells */
  --r-3: 20px;   /* cards */
  --r-4: 28px;   /* sheets (top corners) */
  --r-full: 999px;

  /* ---------- 2.7 Elevation ---------- */
  /* Dark mode: elevation = lighter surface, no shadow (Material dark-theme rule). */
  --elev-0: none;
  --elev-1: none;
  --elev-2: 0 -1px 0 var(--line);                 /* bottom bar top edge */
  --elev-3: 0 12px 32px rgba(0,0,0,.6);           /* sheet over scrim only */

  /* ---------- 2.8 Motion ---------- */
  --d-instant: 80ms;    /* press feedback */
  --d-fast:    140ms;   /* chip select, toggle */
  --d-base:    220ms;   /* card/sheet enter, tab swap */
  --d-slow:    320ms;   /* timer takeover, screen push */
  --d-long:    480ms;   /* log-set confirmation sweep */
  --ease-std:     cubic-bezier(0.2, 0, 0, 1);      /* Material "standard" */
  --ease-decel:   cubic-bezier(0, 0, 0, 1);        /* enter */
  --ease-accel:   cubic-bezier(0.3, 0, 1, 1);      /* exit */
  --ease-emph-in: cubic-bezier(0.05, 0.7, 0.1, 1); /* timer takeover enter */
  --ease-emph-out:cubic-bezier(0.3, 0, 0.8, 0.15); /* timer give-back */

  /* ---------- 2.9 Sizing ---------- */
  --tap-min:  44px;
  --tap-ctrl: 56px;   /* chips row, secondary buttons */
  --tap-big:  64px;   /* primary button, timer buttons */
  --tap-step: 72px;   /* stepper ± cells */
  --bar-h:    56px;   /* bottom nav */
}

/* ---------- Light mode ---------- */
@media (prefers-color-scheme: light) {
  :root:not([data-theme="dark"]) { /* see block below */ }
}
:root[data-theme="light"],
:root:not([data-theme="dark"]) { /* duplicate the light block under the media query */ }

/* Light block (paste under both selectors above) */
/*
  --n-0: #F3F3F0;  --n-1: #FFFFFF;  --n-2: #FFFFFF;  --n-3: #E8E8E4;  --n-4: #DCDCD7;
  --n-5: #8A8A93;  --n-6: #5F5F69;  --n-7: #4B4B55;  --n-8: #121215;
  --line: #D6D6D1;
  --amber-fill: #FFB224; --amber-fill-press: #E69E14; --amber-text: #7A4500; --amber-dim: #FFF1D6; --on-amber: #1A1200;
  --cyan-fill:  #075F7D; --cyan-text:  #075F7D; --cyan-dim:  #DDF1F8; --on-cyan: #FFFFFF;
  --green-fill: #0F6B36; --green-text: #0F6B36; --green-dim: #E3F5EA;
  --red-fill:   #B0261B; --red-text:   #B0261B; --red-dim:   #FDE7E5; --on-red: #FFFFFF;
  --focus: #0A7EA4; --focus-inner: #FFFFFF;
  --scrim: rgba(18,18,21,.5);
  --elev-2: 0 -1px 0 var(--line), 0 -4px 16px rgba(0,0,0,.06);
  --elev-3: 0 12px 32px rgba(0,0,0,.18);
*/
```

Implementation notes on tokens:

- **Numerals**: every element that shows a number sets `font-variant-numeric: tabular-nums; font-feature-settings: "tnum"`. San Francisco defaults to proportional figures; without this the load value jumps sideways when the stepper goes 95 → 100. Also set `font-variant-numeric: slashed-zero` on the timer only.
- **`--n-8` is off-white**, not `#FFF`. Off-white on near-black still measures 17.95:1; pure white adds halation on OLED with no legibility gain.
- **Light-mode amber**: the *fill* stays `#FFB224` with dark text on it (10.30:1), but amber as *text* on light surfaces must use `--amber-text: #7A4500` (7.05:1). Never set light amber text on a light surface.
- **Do not add hues.** If a new state needs colour, it maps to one of amber / cyan / green / red or it is neutral.

---

## 3. Contrast audit (WCAG 2.2)

Ratios computed with the WCAG relative-luminance formula (script in the build scratchpad). Requirements: AA 4.5:1 normal text, 3:1 large text (≥ 24 px regular or ≥ 18.5 px bold); AAA 7:1 normal, 4.5:1 large; non-text UI boundaries 3:1 (1.4.11).

**Rule: anything read mid-set (load, reps, timer, "Last:", set counter, block name) must hit AAA (7:1), not just AA.** Every such pairing below is marked ★.

### 3.1 Dark mode

| Foreground | Background | Ratio | Used for | AA | AAA |
|---|---|---|---|---|---|
| `--text` #F4F4F1 | `--bg` #0A0A0C | **17.95** | ★ stepper values, timer, exercise name | ✓ | ✓ |
| `--text` | `--surface` #141418 | **16.67** | ★ card text, "Last:" line | ✓ | ✓ |
| `--text` | `--surface-2` #1E1E24 | **15.05** | ★ sheet text, bottom bar labels | ✓ | ✓ |
| `--text` | `--surface-press` #2A2A32 | 12.91 | pressed rows | ✓ | ✓ |
| `--text` | `--surface-sel` #3A3A44 | 10.20 | selected RPE chip | ✓ | ✓ |
| `--text-2` #B9B9C2 | `--bg` | 10.16 | ★ block name / "Set 2 of 4" | ✓ | ✓ |
| `--text-2` | `--surface` | 9.43 | secondary rows | ✓ | ✓ |
| `--text-2` | `--surface-2` | 8.51 | sheet secondary | ✓ | ✓ |
| `--text-2` | `--surface-press` | 7.31 | pressed secondary | ✓ | ✓ |
| `--text-3` #8A8A96 | `--bg` | 5.80 | captions ≥ 13 px, units | ✓ | large only |
| `--text-3` | `--surface` | 5.39 | captions on cards | ✓ | large only |
| `--text-3` | `--surface-2` | 4.86 | captions on sheets — **13 px minimum, never for numbers** | ✓ | ✗ |
| `--on-primary` #1A1200 | `--primary` #FFB224 | **10.30** | ★ "LOG SET" label | ✓ | ✓ |
| `--on-primary` | `--primary-press` #E69E14 | 8.20 | pressed primary | ✓ | ✓ |
| `--on-rest` #001A24 | `--rest` #4DD2FF | **10.21** | ★ "+30 s" / "SKIP" on rest buttons | ✓ | ✓ |
| `--on-danger` #FFF1F0 | `--danger` #B42318 | 5.98 | "Abandon session" label (≥ 18 px bold ⇒ large ⇒ AAA) | ✓ | ✓ (large) |
| `--primary-text` #FFC65A | `--bg` / `--surface` | 12.69 / 11.79 | ★ suggested-increase value | ✓ | ✓ |
| `--primary-text` | `--primary-dim` #2A1E00 | 10.50 | stall card, suggestion pill | ✓ | ✓ |
| `--rest-text` #7DDFFF | `--bg` / `--surface` | 13.07 / 12.14 | ★ "NEXT: Hanging leg raise" during rest | ✓ | ✓ |
| `--rest-text` | `--rest-dim` #071A22 | 11.76 | ★ rest takeover ground | ✓ | ✓ |
| `--text` | `--rest-dim` | 16.15 | ★ timer digits on rest ground | ✓ | ✓ |
| `--success-text` #7CEBA3 | `--bg` / `--surface` | 13.44 / 12.48 | "beat last session" | ✓ | ✓ |
| `--success-text` | `--success-dim` #0C2A18 | 10.49 | PR pill | ✓ | ✓ |
| `--danger-text` #FF8585 | `--bg` / `--surface` | 8.43 / 7.83 | "slower" sprint flag, delete | ✓ | ✓ |
| `--danger-text` | `--danger-dim` #2E0F0D | 7.52 | warning card | ✓ | ✓ |
| `--line-ctrl` #65656F | `--bg` / `--surface` | 3.43 / 3.19 | stepper & chip borders (non-text 3:1) | ✓ | — |
| `--line-ctrl` | `--surface-2` | 2.88 | **fails — controls on sheets must be filled (`--surface-sel`), not outlined** | ✗ | — |
| `--focus` #4DD2FF | `--bg` / `--surface` | 11.28 / 10.48 | focus ring outer | ✓ | — |
| `--focus` | `--primary` | 1.03 | **fails alone — hence the two-layer ring in §10** | see §10 | — |
| `--primary` #FFB224 | `--surface` | 10.19 | amber icon/border on card | ✓ | — |
| `--line` #33333C | `--bg` | 1.58 | decorative dividers only; never the sole affordance | exempt | — |

### 3.2 Light mode

| Foreground | Background | Ratio | AA | AAA |
|---|---|---|---|---|
| `--text` #121215 | `--bg` #F3F3F0 | **16.82** ★ | ✓ | ✓ |
| `--text` | `--surface` #FFFFFF | **18.70** ★ | ✓ | ✓ |
| `--text` | `--surface-press` #E8E8E4 | 15.22 | ✓ | ✓ |
| `--text-2` #4B4B55 | `--bg` / `--surface` | 7.75 / 8.62 ★ | ✓ | ✓ |
| `--text-3` #5F5F69 | `--bg` | 5.68 | ✓ | large only |
| `--on-primary` #1A1200 | `--primary` #FFB224 | **10.30** ★ | ✓ | ✓ |
| `--on-rest` #FFFFFF | `--rest` #075F7D | **7.14** ★ | ✓ | ✓ |
| `--on-danger` #FFFFFF | `--danger` #B0261B | 6.69 | ✓ | ✓ (large) |
| `--primary-text` #7A4500 | `--bg` / `--surface` | 7.05 / 7.84 ★ | ✓ | ✓ |
| `--primary-text` | `--primary-dim` #FFF1D6 | 7.02 | ✓ | ✓ |
| `--rest-text` #075F7D | `--bg` / `--surface` | 6.42 / 7.14 ★ (≥ 22 px ⇒ large ⇒ AAA) | ✓ | ✓ |
| `--rest-text` | `--rest-dim` #DDF1F8 | 6.12 | ✓ | ✓ (large) |
| `--success-text` #0F6B36 | `--bg` / `--surface` | 5.95 / 6.61 | ✓ | ✓ (large) |
| `--success-text` | `--success-dim` #E3F5EA | 5.83 | ✓ | ✓ (large) |
| `--danger-text` #B0261B | `--bg` / `--surface` | 6.02 / 6.69 | ✓ | ✓ (large) |
| `--danger-text` | `--danger-dim` #FDE7E5 | 5.65 | ✓ | ✓ (large) |
| `--line-ctrl` #8A8A93 | `--bg` / `--surface` | 3.08 / 3.42 | ✓ (non-text) | — |
| `--focus` #0A7EA4 | `--bg` / `--surface` | 4.17 / 4.63 | ✓ (non-text) | — |
| `--primary` #FFB224 | `--bg` | 1.62 | **amber fill needs a 1 px `--primary-text` border in light mode** (7.84:1) | fixed | — |

### 3.3 Audit rules the engineer must keep

1. Any element in the "★ mid-set" set uses only `--text`, `--text-2`, `--primary-text`, `--rest-text`, `--on-primary`, `--on-rest`. Never `--text-3`.
2. `--text-3` is only for 13–15 px captions and never for a number the user acts on.
3. Outlined (border-only) controls exist only on `--bg` and `--surface`. On `--surface-2` (sheets, bottom bar) controls are filled with `--surface-sel`.
4. In light mode, every amber-filled button has `border: 1px solid var(--primary-text)`.
5. Colour never carries meaning alone (Apple HIG "Button Shapes"; WCAG 1.4.1): the suggested-increase flag is amber **and** has an ↑ glyph; "slower" sprint is red **and** has a label; the rest state is cyan **and** the heading says "REST".

---

## 4. Layout system

### 4.1 Viewport and safe areas

- Design canvas: **390 × 844 CSS px** (iPhone 14/15/16 class). Must also work at 360 × 780 (Pixel class) and 430 × 932; nothing is fixed-height except the runner's bottom action zone.
- `<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">`. `viewport-fit=cover` is required for `env(safe-area-inset-*)` to be non-zero.
- Top safe area ≈ 47–59 px (notch / Dynamic Island). Bottom safe area ≈ 34 px (home indicator). The bottom action zone always pads by `max(var(--safe-b), 12px)`.
- Body: `overscroll-behavior-y: none` on the runner to prevent pull-to-refresh killing a session; `-webkit-user-select: none` and `touch-action: manipulation` on all controls to kill the 300 ms tap delay and double-tap zoom.
- `100dvh`, not `100vh`, for full-height layouts (Safari toolbar collapse).

### 4.2 Thumb-zone map (right-handed, one-handed grip, 390 × 844)

Derived from Hoober's grip studies and the "natural / stretch / hard" model. Mirror the x-axis for left-handed mode (Settings → Left-handed layout; it only flips the stepper minus/plus order and the header buttons — see §4.4).

```
y=0    ┌──────────────────────────────────────────┐
       │ status bar (safe-area-top ~47px)          │
y=47   ├──────────────────────────────────────────┤  HARD (red)
       │  ✕/⋯ only. Orientation text.              │  Top 25%: tap accuracy ~61%,
       │  Nothing here is the only way to do it.  │  +0.7–1.2 s per tap.
y=250  ├──────────────────────────────────────────┤
       │  STRETCH (yellow)                        │  Content & read-only info.
       │  Big display numbers live here — they    │  Tap-to-expand allowed,
       │  are READ, not tapped.                   │  never tap-required.
y=480  ├──────────────────────────────────────────┤
       │  NATURAL (green)                         │  Tap accuracy ~96%.
       │  Steppers, RPE chips, undo               │  All required actions,
y=720  │  ── primary action band ──               │  primary button at
       │  LOG SET / SKIP / +30s (64px)            │  y≈720–784.
y=784  ├──────────────────────────────────────────┤
y=810  │  bottom nav / home indicator safe area   │  Nav bar 56px above
y=844  └──────────────────────────────────────────┘  the 34px inset.
        x: 0 ─── left 80 px is stretch for a right thumb; the − cell of a
        stepper goes there (less-used than +). Far-right column x>330 is
        natural: + cells and "Log Set" right edge.
```

### 4.3 Placement rules

1. **Primary action = bottom band, full-width minus gutters, 64 px tall, `--primary` fill.** One per screen. On the runner it is `LOG SET`; during rest it is `SKIP REST` (secondary style) and `+30 s` (rest style) side by side; on Home it is `START SESSION`.
2. **Secondary actions** sit directly above the primary band or inside the natural zone as 56 px chips.
3. **Destructive actions** (abandon session, delete entry) live behind the top-right `⋯` menu — deliberately in the hard zone so they cannot be hit by accident — and always confirm via a bottom sheet whose *confirm* button is `--danger` and *cancel* is the larger, primary-positioned control.
4. **Bottom nav** (Home · Session · Library · Progress · More) is a 56 px bar above the safe-area inset. It hides during an active session (the runner is modal; §6.8 covers exit).
5. **Nothing tappable within 8 px of another tappable thing** unless they are cells of the same stepper (which have a 1 px `--line` divider and 72 px cells — a mis-tap still hits the intended stepper).
6. **Full-bleed content, inset controls.** Backgrounds run edge-to-edge; controls respect the 16 px gutter (Apple HIG: buttons feel native when inset).
7. **Reachability escape hatch**: a swipe-down anywhere on the runner body (not on a stepper) pulls the header's `⋯` menu down as a bottom sheet. This means the hard zone is never *required*.

### 4.4 Tap target audit (minimums)

| Control | Size | Notes |
|---|---|---|
| Primary button | 358 × 64 | full width minus 2 × 16 gutter |
| Rest buttons (+30 s / Skip) | 171 × 64 each, 16 px gap | |
| Stepper ± cells | 72 × 72 | visible glyph 28 px; cell is the target |
| Stepper value (tap to type) | ≥ 150 × 72 | opens numeric sheet, §6.2 |
| RPE chips | 62 × 56, 8 px gap (5 chips = 342 px) | |
| Checklist rows | full × 56 | whole row toggles |
| Bottom nav items | 78 × 56 | |
| Header icon buttons | 44 × 44, 48 × 48 hit area via padding | |
| Undo (toast) | 88 × 44 | |
| Undo (set row) | 64 × 44 | |
| List rows (library, history) | full × 64 | |
| Chart legend toggles | 44 × 44 | |
| Text links (e.g. "History") | inline 17 px, 44 px line-box | WCAG inline exception, but pad anyway |

Nothing interactive is smaller than 44 × 44. WCAG 2.5.8 (24 px) is therefore satisfied with headroom; we target 2.5.5 (44 px, AAA) throughout.

---

## 5. Screen wireframes

Notation: `[ ]` button, `( )` chip, `{ }` stepper cell, `▮` filled progress, `░` empty progress, `·` low-emphasis text, size annotations in px. Widths are at 390 px.

### 5.1 Home

```
┌──────────────────────────────────────────┐ safe-top
│ ATHLETIC CUT · WEEK 3 OF 13         [⚙]  │ 13px caps --text-3 / 44px icon
├──────────────────────────────────────────┤
│                                          │
│  Today                                   │ 15px --text-3
│  Day A                                   │ 28px 800 --text
│  Lower Power + Hinge                     │ 18px 500 --text-2
│  · Trap bar deadlift 4×5 @8              │ 15px --text-3 (main lift preview)
│  · ~38 min                               │
│                                          │
├──────────────────────────────────────────┤
│  ┌──────────┐ ┌──────────┐ ┌──────────┐  │ 3 tiles, 110×88, --surface
│  │ 196.4    │ │ 31.8     │ │ 2/4      │  │ 32px tnum --text
│  │ lb ·7d   │ │ in waist │ │ this wk  │  │ 13px --text-3
│  └──────────┘ └──────────┘ └──────────┘  │ tap tile → Body metrics / Progress
│                                          │
│  ┌──────────────────────────────────────┐│ optional card, only if it exists:
│  │ ↑ Front squat has stalled 2 weeks    ││ stall / weekly-review card
│  │   In a deficit this is usually       ││ --primary-dim bg, --primary-text
│  │   calories, not effort.   [Got it]   ││ 15px body, 44px dismiss
│  └──────────────────────────────────────┘│
│                                          │
│  Log weight ▸   History ▸   Progress ▸   │ 17px links, 44px line-box
│                                          │
│  ┌──────────────────────────────────────┐│ y≈720
│  │          START SESSION               ││ 358×64 --primary, 18px 700
│  └──────────────────────────────────────┘│
├──────────────────────────────────────────┤
│  ⌂ Home   ▶ Session   ≡ Library  ↗ Prog  ⋯ │ 56px bottom nav
└──────────────────────────────────────────┘ safe-bottom
```

Rest-day variant: heading becomes `Rest day`, sub-line `Next: Day B — Upper Strength`, primary button becomes `START DAY B ANYWAY` in secondary style (filled `--surface-sel`, `--text`) — the schedule is order-dependent, not date-locked, so the user can always start. If a session is in progress, the card is replaced by `Resume Day A · Main lift · Set 3 of 4` and the button reads `RESUME SESSION`.

Hierarchy: the day name is the biggest text; the button is the biggest object; tiles are glanceable numbers; everything else is small.

### 5.2 Session Runner — Main lift

```
┌──────────────────────────────────────────┐ safe-top
│ [✕]   MAIN LIFT · SET 2 OF 4        [⋯]  │ 13px caps --text-2, 44px icons
│       ▮▮░░ ▮▮▮▮▮░░░░░ ░░ ░░ ░░           │ block progress strip, 4px tall:
│                                          │ segments = blocks, fill = sets
├──────────────────────────────────────────┤
│  Trap bar deadlift                 [▣]   │ 28px 800 --text; [▣] 44px thumb
│                                          │  → opens Exercise overlay (§5.9)
│  LAST  245 lb × 5 @ 8        ↑ 250 sugg. │ "LAST" 13px caps --text-3
│                                          │ "245 lb × 5 @ 8" 22px 600 --text
│                                          │ "↑ 250 sugg." 15px --primary-text
├──────────────────────────────────────────┤ y≈250 (stretch zone: read only)
│         LOAD · lb                        │ 13px caps --text-3
│  ┌──────┬──────────────────┬──────┐      │ stepper 358×72, --surface,
│  │  −   │       250        │  +   │      │ 1px --line-ctrl, r-2
│  └──────┴──────────────────┴──────┘      │ value 72px tnum 800 (amber if
│                                          │ it equals the suggestion)
│         REPS                             │
│  ┌──────┬──────────────────┬──────┐      │ y≈470
│  │  −   │        5         │  +   │      │ value 56px tnum 700
│  └──────┴──────────────────┴──────┘      │
│                                          │
│  RPE ·optional                           │ 13px caps
│  ( 6 ) ( 7 ) ( 8 ) ( 9 ) (10 )           │ 5 chips 62×56, target chip (8)
│                                          │ has a 2px --line-ctrl ring
│  ✓ Set 1  245 × 5 @ 8          [Undo]    │ 15px row, 44px; only last set
│                                          │ shows Undo
│  ┌──────────────────────────────────────┐│ y≈720
│  │             LOG SET                  ││ 358×64 --primary
│  └──────────────────────────────────────┘│
└──────────────────────────────────────────┘ safe-bottom (no nav bar)
```

Warm-up sets: a small `(+ warm-up)` chip sits left of the set list; logging from it marks `isWarmup` and does not start the rest timer.

### 5.3 Session Runner — Superset

Same skeleton. Differences:

```
│ [✕]   SUPERSET · ROUND 2 OF 3       [⋯]  │
│       ● Bulgarian split squat   ○ Hanging leg raise │ pair indicator: 15px,
│                                          │ current = --text 600, next = --text-3
│  Bulgarian split squat            [▣]    │ 28px
│  8 each leg · dumbbells                  │ 15px --text-2 target line
│  LAST  40 lb × 8 @ 8                     │
│  … LOAD / REPS steppers …                │ reps stepper prefilled 8
│  NEXT ▸ Hanging leg raise · 10           │ 15px --rest-text, sits above button
│  ┌──────────────────────────────────────┐│
│  │       LOG · NEXT: LEG RAISE          ││ button label states the handoff
│  └──────────────────────────────────────┘│
```

After B is logged the label reads `LOG · THEN REST 60 s`; on log, rest timer takes over (§5.7) and the round counter advances. Rest only after the pair. Bodyweight exercises hide the LOAD stepper (or show `+ add load` chip for weighted variants); "near failure" targets show the reps stepper with a `near failure` caption instead of a number.

### 5.4 Session Runner — EMOM finisher

```
┌──────────────────────────────────────────┐
│ [✕]   FINISHER · EMOM 8 MIN         [⋯]  │
├──────────────────────────────────────────┤
│  Kettlebell swings                 [▣]   │ 28px
│  12 swings at the top of each minute     │ 15px --text-2
│  Heavy bell · hips not arms              │ 15px --text-3
│                                          │
│              MINUTE 3 / 8                │ 15px caps --text-2
│                  42                      │ 112px tnum, counts down to 0
│      ◔ ring, 240px dia, 10px stroke      │ --primary fill; turns --rest
│                                          │ for the "rest remainder" phase
│  BELL   ( 20 ) ( 24 ) ( 28 ) ( 32 ) kg   │ real bell sizes; chip 56px
│                                          │ (selection pre-set before start)
│  ROUNDS DONE  ▮▮░░░░░░  2                │ 8 pips, 24px each
│                                          │
│  ┌────────────────┐ ┌──────────────────┐ │
│  │  MISSED ROUND  │ │     DONE ✓       │ │ 171×64 each. DONE = --primary,
│  └────────────────┘ └──────────────────┘ │ MISSED = --surface-sel
└──────────────────────────────────────────┘
```

Pre-start state shows `Duration {8} min · Reps {12}` steppers and one `START EMOM` primary button. During the EMOM the screen stays on this layout; the beep + haptic fires at the top of each minute and the ring resets. Tapping `DONE ✓` within a minute logs that round as complete and the remainder of the ring goes cyan ("rest"). If no tap by minute end the round is still auto-counted as complete (the user is swinging, not tapping); `MISSED ROUND` exists to correct that. Closing the block early via `⋯ → End finisher` logs rounds completed.

### 5.5 Session Runner — Interval / sprints (Day C)

```
┌──────────────────────────────────────────┐
│ [✕]   SPRINTS · EFFORT 4 OF 8       [⋯]  │
├──────────────────────────────────────────┤
│  Hill sprint                       [▣]   │
│  15–20 s · walk back · full 90 s rest    │
│                                          │
│           ▶ EFFORT                       │ 22px caps --primary-text  (or
│              17                          │ 112px tnum;  "REST" in --rest-text)
│      ◔ ring 240px, --primary / --rest    │
│                                          │
│  EFFORTS  ▮▮▮▮░░░░  4 / 8                │ pips; a "slower" effort pip is
│                                          │ --danger with a small ▾ glyph
│  ┌──────────────────────────────────────┐│ during rest phase only:
│  │  That one felt slower        (mark)  ││ 56px row, --surface, toggle chip
│  └──────────────────────────────────────┘│
│                                          │
│  ┌────────────────┐ ┌──────────────────┐ │
│  │      STOP      │ │   START EFFORT   │ │ primary alternates:
│  └────────────────┘ └──────────────────┘ │ START EFFORT → (auto) → START REST
└──────────────────────────────────────────┘
```

Effort timer is user-started (they are at the bottom of a hill; the app cannot know). Rest auto-starts when the effort timer ends or the user taps `END EFFORT`. When two of the last three efforts are marked slower, a `--primary-dim` card appears above the buttons: *"Last two were slower. The program says stop here — that's quality work done, not a failure."* with `[Stop sprints]` primary and `[One more]` secondary. Pre-start state exposes `Effort {18} s · Rest {90} s · Efforts {8}` steppers.

### 5.6 Session Runner — Carry

```
│ [✕]   FINISHER · TRIP 2 OF 4        [⋯]  │
│  Farmer's carry                    [▣]   │
│  4 trips × 40 m · heaviest grip holds    │
│  LAST  70 lb/hand × 40 m                 │
│         LOAD · lb per hand               │
│  { − }        70        { + }            │ 72px
│         DISTANCE · m                     │
│  { − }        40        { + }            │ ±5 m; 56px value
│  ✓ Trip 1  70 lb × 40 m         [Undo]   │
│  ┌──────────────────────────────────────┐│
│  │             LOG TRIP                 ││
│  └──────────────────────────────────────┘│
```

No reps stepper. Rest timer after each trip uses the block's `restSeconds` (default 60 s; the user can set 0 in `⋯` to disable for carries).

### 5.7 Session Runner — Checklist (prep, mobility)

```
│ [✕]   PREP · 5 MIN                  [⋯]  │
│  Get hips and t-spine open               │ 18px --text-2
│  ┌──────────────────────────────────────┐│ rows 56px, whole row toggles,
│  │ ◯  Deep squat hold · 60 s     [▣]   ││ 18px label, 32px checkbox glyph
│  │ ◯  90/90 rotations · 8/side   [▣]   ││ [▣] = 44px "show me" (overlay)
│  │ ◯  Glute bridge · 15          [▣]   ││
│  │ ◯  Box jumps · 2 × 3          [▣]   ││
│  └──────────────────────────────────────┘│
│  Timed items show a ( ▶ 60 s ) chip that │
│  runs an inline countdown in the row.    │
│  ┌──────────────────────────────────────┐│
│  │      CONTINUE TO MAIN LIFT   3/4     ││ primary; enabled at any count
│  └──────────────────────────────────────┘│ (checklist is guidance, not a gate)
```

Checked rows animate the glyph ◯ → ● (140 ms) and dim the label to `--text-2`. No logging beyond a `completedItems` count on the block.

### 5.8 Rest timer takeover state

This is the most-seen screen in the app. It replaces the runner body (the header stays) with a cyan-ground panel:

```
┌──────────────────────────────────────────┐
│ [✕]   MAIN LIFT · SET 2 OF 4 ✓      [⋯]  │ header unchanged, ✓ appended
├──────────────────────────────────────────┤ panel bg --rest-dim (#071A22)
│                                          │
│                 REST                     │ 15px caps --rest-text, tracking
│                                          │
│                 2:07                     │ 112px tnum 800 --text
│                                          │ (m:ss; under 60 s shows "47")
│        ◔ ring 260px, 12px stroke,        │ --rest on --n-3 track, sweeps CW
│          depleting clockwise             │ to empty
│                                          │
│  LOGGED   245 lb × 5 @ 8        [Undo]   │ 15px --text-2; undo 64×44
│                                          │
│  NEXT     Set 3 · 250 lb × 5             │ 18px --text; (superset: shows
│           ↑ suggested +5                 │ exercise B name instead)
│                                          │
│  ┌────────────────┐ ┌──────────────────┐ │ y≈720
│  │   SKIP REST    │ │      +30 s       │ │ 171×64: SKIP = --surface-sel
│  └────────────────┘ └──────────────────┘ │ on --text; +30 s = --rest fill
│            ⌄ adjust next set             │ 44px text button: collapses the
└──────────────────────────────────────────┘ panel to a 56px bar (§6.5)
```

Last 5 seconds: digits switch to `--primary-text` (amber) and the ring pulses (opacity 1 → .6 → 1, 500 ms, ×5; reduced-motion: no pulse, colour change only). At zero: digits show `GO`, the panel background flashes to `--primary-dim` for 480 ms, beep + haptic fire, and the panel auto-dismisses after 1.5 s back to the runner with the next set pre-filled. If the user is already on the collapsed bar, the bar turns amber and reads `GO · Set 3`.

Overrun: if the user doesn't return, the timer keeps counting *up* in `--text-3` (`+0:12 over`) in the collapsed bar — rest overrun is information, not an error.

### 5.9 Exercise overlay (from `[▣]` in the runner)

Bottom sheet, 88 % height, `--surface-2`, top radius `--r-4`, grab handle, scrollable:

```
│ ──                                       │ handle 36×4
│  Trap bar deadlift            [Close ✕]  │ 22px; close 44px, also swipe-down
│  ┌──────────────────────────────────────┐│
│  │       [ user clip | SVG figure ]     ││ 16:9 media box, --n-2, r-3;
│  │      ◁ start ─── ▷ end (toggle)      ││ SVG: 2-frame toggle, tap or
│  └──────────────────────────────────────┘│ auto 1.2 s alternate
│  CUES                                    │ 13px caps
│  • Push the floor away                   │ 18px --text, imperative
│  • Ribs down, chin tucked                │
│  • Hips and shoulders rise together      │
│  COMMON MISTAKES                         │
│  • Yanking off the floor                 │ 17px --text-2
│  SWAP FOR  ( Romanian DL ) ( Hip thrust) │ chips; tap → confirm sheet
│  [ Record my own clip ]                  │ 56px secondary; <input capture>
│  Open in library ▸                       │
```

### 5.10 Exercise library

```
│ Library                          [+ New] │ 28px title; 44px
│ [🔍 Search exercises…            ]       │ 56px field, --surface, r-2
│ (All) (Hinge) (Squat) (Push) (Pull)…     │ horiz-scroll chips 44px,
│ (Barbell) (Dumbbell) (KB) (Bodyweight)   │ 2 rows: pattern / equipment
│ ┌──────────────────────────────────────┐ │
│ │ ▣  Trap bar deadlift          hinge  │ │ rows 64px: 40px thumb,
│ │    Barbell · e1RM 285 lb   ▸         │ │ 18px name, 15px --text-3 meta
│ │ ▣  Bulgarian split squat      squat  │ │
│ │ ▣  Weighted pull-up          pull-v  │ │
│ └──────────────────────────────────────┘ │
```

Detail view = the overlay content (§5.9) as a full screen, plus:

```
│  ESTIMATED 1RM                            │
│  ┌──────────────────────────────────────┐ │ 200px chart: line --primary,
│  │ 300 ─────────────────╱──            │ │ dots 8px, last value labelled
│  │ 250 ──────╱─────────                │ │ 32px tnum in header; x = weeks
│  └──────────────────────────────────────┘ │
│  HISTORY                                  │ list of sessions, 56px rows
│  Wk 3  250 × 5 @ 8 · 250 × 5 @ 8 …        │
```

Custom exercise form: name, pattern (chips), equipment (chips), cues (3 text rows), mistakes (2 rows), optional clip. Fields 56 px tall. This is the one place a keyboard is expected; it never appears mid-session.

### 5.11 Progress

Vertically stacked cards, each 16 px gutter, each with a 32 px headline number and a chart. Order is the program's own priority order:

```
│ Progress                     (4 wk)(All) │ range chips
│ ┌──────────────────────────────────────┐ │
│ │ BODYWEIGHT · 7-DAY AVG               │ │ 13px caps
│ │ 196.4 lb   ▾ 0.8 lb/wk               │ │ 32px tnum; trend 15px --success-text
│ │  ●  ·  ● ·    ●                      │ │ raw dots: 6px --text-3 @ 50 %
│ │ ─────●──●──●──●──────                │ │ avg line: 3px --primary (dominant)
│ │ target band 0.7–1.0 lb/wk as --line  │ │
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │ WAIST · WEEKLY                       │ │ 31.8 in · target 30.5–31 band
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │ MAIN LIFTS · EST. 1RM                │ │ 4 lines, one per main lift,
│ │ (Trap bar)(Pull-up)(Front squat)     │ │ toggle chips 44px; legend =
│ └──────────────────────────────────────┘ │ chips, not colour alone
│ ┌──────────────────────────────────────┐ │
│ │ WEEKLY VOLUME BY PATTERN             │ │ horizontal bars, 8 patterns,
│ │ hinge   ▮▮▮▮▮▮▮▮  4,900 lb            │ │ label + number on every bar
│ │ pull-v  ▮▮▮░░░░░  1,800               │ │
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │ EVOLT SCANS                          │ │ discrete markers ◆ 12px;
│ │ lean mass 172.1 lb   body fat 11.4 % │ │ two small charts side by side
│ └──────────────────────────────────────┘ │
```

Chart rules: one accent line per chart (amber), everything else neutral. No gridlines heavier than `--line`. Axis labels 13 px `--text-3`. Every chart has a text summary line above it so the number is readable without parsing the chart.

### 5.12 Body metrics

```
│ Body                                     │
│ ┌──────────────────────────────────────┐ │ "quick weight" card, --surface
│ │ TODAY · FASTED WEIGHT                │ │
│ │ { − }      196.2      { + }   lb     │ │ 72px stepper ±0.2; value 56px;
│ │ tap value → numeric pad sheet        │ │ prefilled with last entry
│ │ [           SAVE WEIGHT            ] │ │ 64px primary
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │ appears only when due (weekly)
│ │ WAIST AT NAVEL · due today           │ │
│ │ { − }      31.8       { + }   in     │ │ ±0.1
│ │ [            SAVE WAIST            ] │ │
│ └──────────────────────────────────────┘ │
│ Evolt scan entry ▸                       │ 56px row → full form
│ RECENT                                   │ list, 56px rows, swipe-left delete
```

Evolt form: 8 fields (weight, lean mass, skeletal muscle mass, body-fat mass, body-fat %, visceral fat, BMR, TEE), each a 56 px `inputmode="decimal"` field with unit suffix; date defaults today; `SAVE SCAN` primary. Keyboard is acceptable here (not mid-session).

### 5.13 Nutrition

```
│ Nutrition                                │
│ ┌──────────────────────────────────────┐ │ today card
│ │ TODAY                                │ │
│ │ kcal     { − }  2,640  { + }  ±50    │ │ three steppers, 56px rows,
│ │ protein  { − }   195   { + }  ±5 g   │ │ values 32px tnum
│ │ steps    { − }  8,400  { + }  ±500   │ │
│ │ [               SAVE               ] │ │
│ └──────────────────────────────────────┘ │
│ 7-DAY AVERAGE vs TARGET                  │
│ kcal     2,655 / 2,650    ▮▮▮▮▮▮▮▮▮▮ ✓   │ bar, 15px; ✓ in --success-text
│ protein    191 / 190–200  ▮▮▮▮▮▮▮▮▮░ ✓   │
│ steps    7,900 / 8–10k    ▮▮▮▮▮▮▮▮░░ ·   │
│ Days on target this week   5 / 7         │ 32px tnum
```

No food database, no barcode, no macros beyond protein. Rows accept a tap on the value to open the numeric pad sheet.

### 5.14 Settings / export ("More")

```
│ More                                     │
│ Units          ( lb )( kg )              │ segmented, 56px
│ Theme          ( System )( Dark )( Light)│
│ Left-handed layout            [toggle]   │ 56px row, switch 51×31 (iOS size)
│ Rest timer sound              [toggle]   │
│ Haptics                       [toggle]   │
│ Keep screen awake in session  [toggle]   │
│ Default rest: main {150}s superset {60}s │ inline steppers
│ ─────────────────────────────            │
│ Export data (JSON) ▸                     │ 56px; triggers download
│ Import data ▸                            │ <input type=file>; confirm sheet
│ Storage used   38 KB · 6 clips (41 MB)   │ 15px --text-3
│ ─────────────────────────────            │
│ Reset program week / Delete all data ▸   │ --danger-text; double confirm
│ About · v1.0 · offline · no tracking     │ 13px --text-3
```

---

## 6. Session-runner interaction model

### 6.1 Principle

Zero keyboard in a Day A session (acceptance criterion). Every mid-session input is a stepper, a chip, or a button. Typing exists only as an escape hatch behind a tap on the stepper value.

### 6.2 Steppers

- Structure: `[−] [value] [+]` in one 72 px-tall container. Cells are `<button>`s; the value is a `<button aria-haspopup="dialog">`.
- **Increments** by `loadType`: barbell ±5 lb (±2.5 kg); dumbbell ±5 lb (±2 kg); kettlebell snaps through `[8,12,16,20,24,28,32,36,40,48] kg` (display in lb if units=lb but snap on the kg list); weighted-bodyweight ±2.5 lb (±1.25 kg), min 0 = "bodyweight"; distance ±5 m; time ±5 s; reps ±1; bodyweight scale ±0.2 lb / ±0.1 kg; waist ±0.1.
- **Press-and-hold** repeats after 400 ms at 6 steps/s (touchstart/pointerdown, not click). Haptic tick on each step where available.
- **Value tap** opens a numeric-pad bottom sheet (our own 3 × 4 grid of 64 px keys + `⌫` + `Done`, never the OS keyboard) — so even the escape hatch is thumb-sized.
- **Prefill**: last session's top-set load. If the suggestion rule (spec §5.1) fires, the value is prefilled *one increment higher*, rendered in `--primary-text`, and a 15 px `↑ suggested` caption appears under the stepper. Tapping `−` once returns to last time's value and the amber styling clears. A decrease is never auto-applied.
- Reps prefill: the target (5, 6, 8…). For ranges ("8–10") prefill the lower bound. For "near failure" the value shows `—` until the user steps; the caption reads `near failure`.
- Below-zero and above-999 are clamped; the cell flashes `--surface-press` (80 ms) instead of moving.

### 6.3 RPE selector

- Five chips `6 7 8 9 10`, 62 × 56, 8 px gaps. Halves are out: the program prescribes whole RPE and halves double the tap count for no decision value.
- The prescribed RPE chip has a 2 px `--line-ctrl` ring so it is one tap. Selected chip = `--surface-sel` fill, `--text` 700 label; the ring stays on the target.
- Optional: `LOG SET` works with no RPE selected (stored `null`). The caption reads `RPE · optional`. After 2 consecutive sets logged without RPE the caption stops saying optional and just says `RPE` — the nag is not worth the pixels.
- Selection persists to the next set of the same exercise (most sets of a 4 × 5 are the same RPE).

### 6.4 "Last time" line

Format is fixed: `LAST  245 lb × 5 @ 8` — caps label 13 px `--text-3`, then value 22 px 600 `--text`, `×` is the multiplication sign U+00D7, `@` precedes RPE. If last time had no RPE: `245 lb × 5`. If the last session had multiple sets, show the *top set* (highest load, then highest reps) and let the user tap the line to expand all sets of that session as a 15 px list (`245 × 5 @ 8 · 245 × 5 @ 8 · 245 × 4 @ 9 · 245 × 5 @ 8`). First-ever session: `LAST  —  first time` and the load stepper starts at the program's suggested starter or 0.

When the suggestion fires, the right side of the line shows `↑ 250 sugg.` in `--primary-text`. When the *current* input beats last (load higher at ≥ same reps, or reps higher at same load) the reps or load value gets a small `▲` glyph in `--success-text` after it — a pre-emptive "this will be a PR".

### 6.5 Logging a set

1. Tap `LOG SET` (64 px, amber). Button depresses (scale .97, 80 ms), haptic `medium`.
2. A 480 ms amber sweep runs left→right across the button while the label becomes `LOGGED ✓`; the set row is prepended to the set list with a 220 ms slide-in. Reduced motion: label swap and row appear, no sweep, no slide.
3. If the block has `restSeconds > 0` and this set isn't a warm-up: the rest panel enters from the bottom (320 ms `--ease-emph-in`, translateY 100 % → 0) and the runner body dims to 40 % opacity underneath. Reduced motion: 220 ms crossfade.
4. Timer starts *before* the animation (timestamp-based, see §6.7). Timer digits are visible from frame 1.
5. The runner underneath has already advanced to the next set: header says `SET 3 OF 4`, load prefilled per suggestion rule.

`⌄ adjust next set` (or swipe-down on the panel) collapses the panel to a 56 px bar pinned above the `LOG SET` button: `⏱ 1:47   NEXT set 3 · 250 × 5   [+30s]`. The user can edit steppers with the bar present. Tapping the bar re-expands. The `LOG SET` button is *disabled* while the timer runs, unless the set list shows the current set's fields differ from the pre-filled defaults **and** the user taps the bar's `Skip` — this prevents the one really bad mis-tap (logging set 3 while resting from set 2). Actually simpler and what we ship: while the timer runs, the primary band shows `SKIP REST` / `+30 s`; `LOG SET` only returns when the timer ends or is skipped.

### 6.6 Undo last set

Two affordances, both required:

- **Toast**: on every log, a 44 px-tall toast slides up above the primary band: `Logged 245 × 5 @ 8   [UNDO]` (`--surface-2`, undo label `--primary-text`). Visible 6 s, dismissed by swipe or by the next log. Undo here removes the SetLog, cancels the rest timer, restores the steppers to the logged values, and puts the header back to `SET 2 OF 4`. The panel exits with `--ease-emph-out` (220 ms).
- **Persistent row undo**: the most recent set in the set list always has a 64 × 44 `Undo` button on the right. Older sets show `Edit` instead (opens a sheet with the same steppers/RPE and `Save` / `Delete set`). This survives page refresh, so a mistake noticed after the toast is gone is still one tap.

Undoing during rest returns to the runner immediately (no confirmation — the action is itself reversible by re-logging). Undoing the last set of a *completed* block reopens that block. Undo never asks "are you sure".

### 6.7 Timer behaviour (engineering contract with UX consequences)

- Timer is `endsAt = Date.now() + restMs`, persisted to localStorage with the in-progress session. Display is `requestAnimationFrame`-driven while visible; on `visibilitychange` → visible, recompute from `endsAt`. This is what makes "keep running when backgrounded" and "survive refresh" true.
- **Screen on**: Wake Lock is requested at `Start Session` and re-requested on every `visibilitychange`. Settings toggle "Keep screen awake in session" defaults on. When Wake Lock is unavailable (older iOS Safari), a 13 px `--text-3` note appears once under the timer: `Keep the screen on — this browser can't hold it awake.`
- **Audio**: a short two-tone beep (Web Audio, oscillator, ~350 ms) plus a 1.2 s "GO" chime at zero and a single tick at 10 s left. The AudioContext is created and resumed inside the `Start Session` tap handler (iOS requires a user gesture). Design must reflect the hard constraint that **iOS suspends Web Audio when the screen locks**: the app's answer is Wake Lock (screen stays on, audio works), with the in-app visual `GO` state as the fallback. Do not promise lock-screen alarms; the spec's "phone screen off" criterion is met on Android via `navigator.vibrate` + audio and on iOS only while the screen is awake — say so in Settings under the Sound toggle in 13 px: `On iPhone, sound plays while the screen is on.`
- **Haptics**: `navigator.vibrate([60,40,60])` on Android; on iOS use the `<input type="checkbox" switch>` toggle trick (works Safari 17.4–26.4; assume it may stop working — it's a nicety, not a dependency). Every stepper tick, chip select, log, and timer-zero has a haptic where available.
- **Sound respects the system silent switch on iOS**; we don't fight it. The visual `GO` flash and the amber collapsed bar are designed to be sufficient alone.

### 6.8 Exit, skip, abandon, resume

- `[✕]` (top-left) never loses data. It shows a bottom sheet: `[Pause & leave]` (primary — session stays in-progress, Home shows Resume) / `[Abandon session]` (`--danger`, second confirm: "Abandon Day A? Logged sets are kept in history as an abandoned session.") / `[Cancel]`.
- `[⋯]` (top-right) sheet: `Skip this exercise`, `Skip rest of block`, `Swap exercise…`, `Edit rest time`, `Add warm-up set`, `Show program for today`, `Abandon session`. Skips advance the pointer and mark slots `skipped`, visible in the summary.
- Block transition: when the last set of a block is logged, the rest timer still runs (you rest before the superset too). At zero, a 220 ms crossfade brings in the next block with its header. A 15 px `--text-3` line under the block name says `Up next: Superset · 3 rounds` during the final rest.
- Session end: after the mobility checklist, a full-screen `Session RPE` prompt (chips 1–10 in two rows of five, 56 px), optional note (this one *is* a textarea — session is over), then the **summary card** (§9.4). `[Done]` returns Home and advances the schedule.
- Resume: reopening the page with `status: in-progress` lands *directly on the runner* at the exact block/set, with the timer recomputed. Home is not shown first. A 13 px header caption reads `resumed` for 3 s.

### 6.9 Units

`lb` default per the program. `kg` mode converts stored values for display; storage keeps the unit the set was logged in (`loadUnit`). Kettlebell snap list is kg-native either way.

---

## 7. Glanceability rules

"Glanceable" here means: **readable from a bench at 60–75 cm, phone flat or propped, sweat on the screen, 0.5 s of attention.** Legibility at 75 cm requires roughly 5 mm cap height for effortless reading (≈ 0.7 % of distance); on a 390 px-wide phone at ~2.75 px/mm that is ~14 px cap height, i.e. ~20 px font size for *words* and comfortably more for *numbers that must be read from further or faster*.

| Element | Min font (px) | Weight | Colour | Notes |
|---|---|---|---|---|
| Rest timer digits | 112 | 800 | `--text` on `--rest-dim` | readable at 2 m |
| EMOM / interval clock | 112 | 800 | `--text` | same |
| Load stepper value | 72 | 800 | `--text` / `--primary-text` | |
| Reps stepper value | 56 | 700 | `--text` | |
| Exercise name (runner) | 28 | 800 | `--text` | wraps to 2 lines max; truncates with … after |
| "Last:" value | 22 | 600 | `--text` | |
| Block · set counter | 13 caps, tracking .06em | 600 | `--text-2` | small but redundant with the progress strip |
| NEXT line during rest | 18 | 600 | `--text` | |
| Primary button label | 18 caps | 700 | `--on-primary` | |
| Rest buttons | 18 caps | 700 | | |
| RPE chip digits | 22 | 700 | | |
| Tile numbers (Home) | 32 | 700 | | |
| Any caption | 13 | 500 | `--text-3` | absolute floor; nothing smaller anywhere |

Additional rules:

1. **One number per zone.** The stepper value is alone on its line; its unit is a 13 px caption *above* the stepper, not beside the number.
2. **State by colour field, not by icon.** Working = neutral/amber; resting = cyan panel. A person can identify the state with the phone upside-down.
3. **Progress is a strip, not a label.** The 4 px block/set strip under the header is readable at a glance; the `SET 2 OF 4` text is confirmation.
4. **No text in the hero zone competes with the number.** During rest, the only things on the panel are REST, the digits, LOGGED/NEXT lines, and two buttons.
5. **Thumb never covers the number.** The stepper value is centred; the ± cells are on the outer edges; the timer digits are at y≈300–420, above the buttons.
6. **Screen brightness assumption**: gyms are bright; the OLED palette is chosen for max contrast (17.95:1) precisely so auto-brightness dips don't kill legibility.

---

## 8. Motion spec

### 8.1 Inventory (this is the complete list — nothing else animates)

| # | Transition | Duration | Easing | Property | Reduced-motion |
|---|---|---|---|---|---|
| M1 | Button press | 80 ms | `--ease-std` | `transform: scale(.97)`, bg → press colour | colour only |
| M2 | Chip select | 140 ms | `--ease-std` | bg, colour | same (colour is fine) |
| M3 | Stepper value change | 140 ms | `--ease-decel` | number crossfade (old fades out, new fades in) | instant swap |
| M4 | Log-set sweep | 480 ms | `--ease-std` | pseudo-element `translateX(-100%→100%)` over button | none; label swap only |
| M5 | Set row insert | 220 ms | `--ease-decel` | `translateY(-8px→0)` + opacity | opacity only |
| M6 | Rest panel enter | 320 ms | `--ease-emph-in` | `translateY(100%→0)`; body opacity 1→.4 | 220 ms opacity crossfade |
| M7 | Rest panel exit / collapse | 220 ms | `--ease-emph-out` | reverse of M6 | crossfade |
| M8 | Rest ring depletion | continuous | linear | `stroke-dashoffset` via rAF | replaced by 4 static quarter marks that fill at 75/50/25/0 % |
| M9 | Last-5-seconds pulse | 500 ms × 5 | ease-in-out | ring opacity 1→.6→1 | none; colour change to amber only |
| M10 | GO flash | 480 ms | `--ease-std` | panel bg `--rest-dim` → `--primary-dim` → back | 1 frame colour change held 1.5 s |
| M11 | Screen push (nav) | 220 ms | `--ease-std` | opacity crossfade — **no horizontal slide** | same |
| M12 | Bottom sheet enter/exit | 220 ms / 180 ms | `--ease-decel` / `--ease-accel` | translateY + scrim opacity | opacity only |
| M13 | Toast | 180 ms | `--ease-decel` | translateY(16px→0) + opacity | opacity |
| M14 | Checklist check | 140 ms | `--ease-std` | glyph fill, label colour | same |
| M15 | Chart line draw (Progress) | 480 ms | `--ease-decel` | `stroke-dashoffset` on first paint only | none |
| M16 | SVG exercise figure | 1.2 s alternate loop | ease-in-out | opacity toggle between two `<g>` frames | manual toggle only (tap) |

### 8.2 Rules

- Every transition uses `transform` or `opacity` only. Never animate `height`, `top`, `box-shadow` or `filter` (performance on mid-range phones; the 1 s interactive budget).
- Spring/bounce is banned on the runner. M3 Expressive's springs are for delight; nobody at RPE 8 wants delight, they want the number.
- No animation runs longer than 500 ms except the ring (continuous) and the SVG demo loop (opt-in, inside an overlay).
- No auto-playing motion on the runner besides M8/M9 — the rest ring is the app's "I'm alive" signal and is therefore allowed to move.
- Screen transitions crossfade, not slide: sliding implies spatial navigation the app doesn't have, and horizontal translation is a known vestibular trigger.

### 8.3 `prefers-reduced-motion`

Base styles are written **without** motion; animations are opted in under `@media (prefers-reduced-motion: no-preference)`. The reduce path is therefore the default, not a patch. In JS, `matchMedia('(prefers-reduced-motion: reduce)')` gates the rAF ring update (switch to quarter-marks) and the GO flash. Apple HIG Reduce Motion list honoured: no scaling, no z-depth changes, no x/y transitions, no blur animation, no repetitive animation. Settings also exposes `Reduce motion` as an app-level override (on/off/system) because the gym is exactly where someone might want the ring off.

---

## 9. Empty states, errors, onboarding

### 9.1 First run (one screen, no carousel)

```
│  Athletic Cut                            │ 28px
│  12–14 weeks · 4 days · offline          │ 17px --text-2
│  This app runs the program and keeps the │ 17px body, max 3 lines
│  numbers. No account, no cloud — your    │
│  data lives on this phone.               │
│  Units         ( lb ) ( kg )             │ segmented 56px
│  Start weight  { − }  199.7  { + }  lb   │ prefilled from program
│  Waist         { − }   32.3  { + }  in   │
│  [  Import a backup ]                    │ secondary 56px
│  ┌──────────────────────────────────────┐│
│  │            BEGIN WEEK 1              ││ primary 64px
│  └──────────────────────────────────────┘│
│  Add to Home Screen for full-screen use  │ 13px --text-3, iOS/Android hint
```

That's it. No tutorial: the runner is self-explaining because it has one button. The first `Start Session` shows a one-time 13 px caption under the stepper: `Tap the number to type it.` and a one-time toast on first log: `Rest started · tap ⌄ to adjust the next set.` Both dismissed forever after showing once (`onboardingSeen` flags).

### 9.2 Empty states (each is one line + one action, never an illustration)

| Where | Text | Action |
|---|---|---|
| Home tiles, no weight yet | `— lb` with caption `log your first weight` | tile tap → Body |
| Progress, < 2 data points | `Two sessions in and this chart will start moving.` | none |
| Progress lifts, none | `Your first main lift lands here.` | `Start session` |
| Library search, no hits | `Nothing called "flying pigeon".` | `+ Add "flying pigeon"` |
| History, none | `No sessions yet.` | `Start Day A` |
| Nutrition today, unlogged | steppers at 0 with `--text-3` values; save enabled | |
| Exercise with no media | inline SVG figure (Option B) is the default, so this cannot be empty; a custom exercise shows a neutral 16:9 `--n-2` box with a `[Record clip]` button | |

### 9.3 Errors (all in-app; there is no network to fail)

| Condition | Presentation |
|---|---|
| localStorage quota / write failure | persistent `--danger-dim` banner under header: `Couldn't save. Export a backup now.` with `[Export]`. App keeps working in memory. |
| IndexedDB clip too large (> 25 MB) | sheet: `That clip is 41 MB. Trim it to under ~20 s and try again.` |
| Import JSON invalid / wrong schema | sheet with the reason in 15 px mono: `schemaVersion 3 expected, got 1` and `[Cancel]` only — never partial-import. |
| Import valid | confirm sheet: `Replace all data with backup from 2026-08-30 (14 sessions)?` `[Replace]` `--danger` / `[Cancel]` primary-positioned. |
| Wake Lock unavailable | one-time 13 px note under timer (§6.7). |
| Audio blocked | if `AudioContext.state !== 'running'` after the Start tap, show a 44 px `🔇 Tap to enable sound` chip in the runner header area until resolved. |
| Refresh mid-animation | irrelevant: state is timestamp-based; UI re-derives. |

### 9.4 Session summary card (end of session)

```
│  Day A · done                           │ 28px
│  38 min · RPE 8                         │ 17px --text-2
│  ┌──────────────────────────────────────┐
│  │ TOTAL VOLUME   9,140 lb               │ 32px tnum
│  │ BEAT LAST TIME                        │ 13px caps --success-text
│  │  ▲ Trap bar deadlift 250 × 5 (was 245)│ 17px, only if any
│  │  ▲ Split squat 45 × 8                 │
│  │ SKIPPED  Hanging leg raise            │ 15px --text-3, only if any
│  └──────────────────────────────────────┘
│  [ Add a note ]                          │ secondary
│  [               DONE                ]   │ primary
```

If nothing beat last time: `HELD ALL LIFTS` in `--success-text` with the program's own line: *"In a deficit, holding your lifts is success."* The app never shows a red "you got weaker" state.

### 9.5 Weekly review (every 7 days, shown on Home as a card, opens a sheet)

Weight trend for the week (7-day avg delta), verdict line in plain words (`Losing 0.9 lb/wk — hold calories.` / `Nothing moved in 2 weeks — drop 300.` / `Losing 1.7 lb/wk two weeks running — eat more; that costs muscle.`), the suggested calorie number as a 32 px numeral, and `[Set 2,350 kcal target]` / `[Keep 2,650]`. The warning variant uses `--danger-dim` with `--danger-text` heading; the hold variant is `--success-dim`.

---

## 10. Accessibility

### 10.1 Focus

- Focus ring is **two-layer** so it works on amber, cyan and neutral fills: `outline: 2px solid var(--focus); outline-offset: 2px; box-shadow: 0 0 0 2px var(--focus-inner)` — inner 2 px off-white, outer 2 px cyan (light mode: outer `#0A7EA4`, inner white). The inner layer guarantees ≥ 3:1 change against every surface including the amber primary (off-white on amber 1.64:1 fails alone; cyan on amber 1.03:1 fails alone; the pair against any surface always includes one layer ≥ 3:1). Meets WCAG 2.4.13 (AAA) 2 px-perimeter rule and 2.4.11 (focus not obscured — the bottom bar never overlaps a focusable control because the runner body has bottom padding equal to the band height).
- `:focus-visible` only; touch users never see the ring.
- Focus order on the runner: exercise name → thumbnail → last line → load − / value / + → reps − / value / + → RPE chips → set list → primary button → header ✕ → header ⋯. Header last, on purpose.
- When the rest panel opens, focus moves to the panel heading (`tabindex="-1"`); on close it returns to `LOG SET`.

### 10.2 Roles and names

- Steppers: container `role="group" aria-labelledby="load-label"`; `−`/`+` are `<button aria-label="Decrease load by 5 pounds">` / `"Increase load by 5 pounds"` (unit and step spoken); the value button has `aria-label="Load, 250 pounds. Tap to type a value."` and `aria-live="polite" aria-atomic="true"` on the visually-hidden text `250 pounds` so a tick is announced once per change (debounced 300 ms during press-and-hold so VoiceOver isn't flooded).
- RPE chips: `<div role="radiogroup" aria-label="RPE, optional">` with `<button role="radio" aria-checked>`; the target chip's ring is described: `aria-describedby` → `"Program target"`.
- Checklist: `<ul>` of `<button role="checkbox" aria-checked>`.
- Set list rows: `<li>` with text `Set 1, 245 pounds times 5 at RPE 8`; the Undo button `aria-label="Undo set 1"`.
- Icon-only buttons: `✕` → `aria-label="Leave session"`, `⋯` → `"More session actions"`, `[▣]` → `"Show form cues and demo for Trap bar deadlift"`, `⚙` → `"Settings"`, `+30 s` → `"Add 30 seconds of rest"`, `▶` (checklist inline timer) → `"Start 60 second timer"`. Every icon button has visible text or a tooltip-free `aria-label`; no `title`-only labels.
- Progress strip: `<div role="progressbar" aria-valuenow="6" aria-valuemin="0" aria-valuemax="17" aria-label="Session progress, set 6 of 17">`.
- Charts: `<svg role="img" aria-label="Bodyweight, 7-day average 196.4 pounds, down 0.8 per week">` plus a visually-hidden `<table>` of the plotted points. The summary line above each chart is real text.
- Bottom nav: `<nav aria-label="Primary">` with `aria-current="page"`.
- Sheets: `<div role="dialog" aria-modal="true" aria-labelledby>`; scrim click and Escape close; focus trapped; `inert` on the page behind.

### 10.3 The timer

- Digits: `<div role="timer" aria-atomic="true" aria-label="Rest remaining">2:07</div>`. `role="timer"` is implicitly `aria-live="off"`, which is *correct*: announcing every second is unusable.
- Announcements come from a separate visually-hidden `<div role="status" aria-live="polite" aria-atomic="true">` that receives text only at milestones: on start `"Rest started, 2 minutes 30 seconds. Next: set 3, 250 pounds times 5."`, at 60 s `"One minute left."`, at 10 s `"Ten seconds."`, and at zero via a second node `<div role="alert">` set to `"Go. Set 3."` (assertive is justified exactly once). `+30 s` announces `"Added 30 seconds, 1 minute 47 left."` Skip announces `"Rest skipped."`
- EMOM: the status region announces `"Minute 4 of 8"` at each top; the beep is the sound, the announcement is the text.
- The live regions exist in the DOM from page load (empty) so assistive tech registers them; never inject them on demand.

### 10.4 Reduced motion / transparency / contrast

- `prefers-reduced-motion` handled per §8.3.
- `prefers-reduced-transparency`: the bottom nav's `backdrop-filter: blur(20px)` on `--surface-2` at 88 % alpha is the only translucent surface; under reduced-transparency (or when `backdrop-filter` is unsupported) it becomes opaque `--surface-2`. Content never sits under glass.
- `prefers-contrast: more`: `--line` becomes `--line-ctrl`, `--text-3` becomes `--text-2`, and outlined controls gain 2 px borders. That is the full list; the base palette is already ≥ 7:1 where it matters.
- `forced-colors: active`: rely on system colours; ensure the ring uses `stroke: CanvasText` and buttons keep borders (`border: 1px solid transparent` in base so forced-colors paints them).

### 10.5 Text scaling

- All sizes in rem; root stays 16 px but honours user font scaling (do **not** set `-webkit-text-size-adjust: none`). At 200 % the runner must still work: the stepper container grows in height, the set list becomes scrollable, the primary band stays pinned. Test at iOS Larger Text "AX3".
- Hero numerals use `clamp(4rem, 28vw, 7rem)` so they never overflow at 360 px.

### 10.6 Hit-target audit — see §4.4. Nothing below 44 × 44; primary controls 56–72.

### 10.7 Colour-blind and low-vision checks

- Amber vs cyan vs green vs red are distinguishable under deuteranopia/protanopia by luminance alone (amber 10.97:1 vs cyan 11.28:1 differ in hue not luminance — hence every state also carries a word: `REST`, `GO`, `↑ suggested`, `slower`).
- Progress chart series are distinguished by chips (toggle) and direct labels, never by a legend swatch alone.

---

## 11. Anti-patterns (do not build these)

1. **Pure `#000` backgrounds.** Halation, OLED smear, no elevation. Use `#0A0A0C`.
2. **Pure `#FFF` text.** Use `#F4F4F1`.
3. **Glass/blur over content.** Liquid-Glass-style materials belong on the floating controls layer only; a blurred timer is a slow, unreadable timer.
4. **Springy, bouncy, morphing motion on the runner.** Expressive is for launchers; this is a scoreboard.
5. **Horizontal slide transitions.** Vestibular trigger, implies navigation depth we don't have.
6. **Anything that requires a tap in the top 25 % of the screen.** Header actions must be reachable via the swipe-down sheet.
7. **The OS keyboard mid-session.** Steppers and our own numeric pad only.
8. **Half-RPE chips, 1–10 RPE grids, sliders.** Five whole-number chips.
9. **A slider for load or rest time.** Sliders are hopeless with sweat and at 72 px they'd still be imprecise.
10. **Confirm dialogs on reversible actions.** Undo, don't confirm. Confirm only destructive/irreversible (abandon, delete, import-replace).
11. **Destructive action in the primary position** (Apple HIG). Cancel is always the big, bottom, primary-styled button.
12. **Toasts that auto-dismiss critical info.** Save failures are persistent banners.
13. **Auto-dismissing the GO state before the user sees it.** 1.5 s hold, then the collapsed bar stays amber until the next log.
14. **Colour as the only signal** for suggestion / slower / rest / PR.
15. **Card soup on Home.** Three tiles and one card max; the button is the hero.
16. **Streaks, badges, confetti, motivational copy.** The program says "holding your lifts is success"; the UI says that, once, in the summary. No gamification.
17. **A food database, macro rings, or step goals with mascots.** Three steppers.
18. **Illustrated empty states.** One line, one action.
19. **Onboarding carousels.** One screen.
20. **Multiple font families or any webfont.** `system-ui` only; offline and instant.
21. **Text under 13 px, tap targets under 44 px, adjacent tappables under 8 px apart.**
22. **Icons without text on the bottom nav.** Icon + 13 px label, always.
23. **Nested scroll regions on the runner.** The runner scrolls as one column; only the exercise overlay and set-edit sheet have their own scroll.
24. **`100vh`.** Use `100dvh`.
25. **Rest timer computed by decrementing a counter.** Timestamp arithmetic or it will drift and die in the background.
26. **Promising lock-screen alarms on iOS.** Say what's true in Settings.
27. **Auto-decreasing the load suggestion.** Never (program rule).
28. **Making the prep/mobility checklist a gate.** It's guidance; `Continue` is always enabled.

---

## 12. Asset and weight budget

- Zero external requests. `system-ui` fonts. No raster images.
- Exercise visuals: **Option B (inline SVG, two-frame)** as the *default* and the only thing shipped in v1 — it is the only option that meets "offline, single file, no licensing question" without a build-time download step. ~25 figures × ~1.5 KB each ≈ 40 KB. Each figure is a `<symbol>` in one hidden `<svg>` sprite; frames are two `<g class="f0">` / `<g class="f1">` groups toggled by CSS class. Figures are 12-stroke stick figures on a 160 × 120 viewBox: 6 px round-capped strokes in `currentColor`, equipment as filled `--n-5` shapes (bar = 4 px rect with 14 px end circles; kettlebell = 18 px circle + handle arc; box = 40 × 24 rect). Start and end frames differ only in limb angles so the toggle reads as motion. Option A images can be added later behind the same `mediaRef`; user clips (IndexedDB blob) always win.
- Icon set: 14 inline SVG symbols, 24 × 24 viewBox, 2 px stroke: close, more, settings, play, pause, plus, minus, check, undo, chevron-down, chevron-right, home, list, chart, dots-menu, mute. ≈ 3 KB.
- Chart rendering: hand-rolled SVG (`<polyline>`, `<circle>`, `<rect>`), no chart library.
- Budget: ≤ 400 KB source total; target ~180 KB (JS ~90, CSS ~25, program data ~15, SVG ~45, HTML ~5). Loads interactive < 1 s on a 2022 mid-range Android over file:// or a local server.

---

## 13. When in doubt

1. Would a sweaty thumb hit it? Make it bigger.
2. Can it be read from the floor? Make the number bigger, the label smaller.
3. Is it in the top quarter? Move it or duplicate it lower.
4. Does it animate? Ask why; default to opacity.
5. Is it a second colour? It isn't. Map to amber / cyan / green / red / neutral.
6. Does it ask "are you sure"? Replace with Undo unless data is destroyed.
7. Is it copy? Cut it in half. The program's own voice ("hips not arms") is the model.

---

## 14. Sources

Primary sources actually fetched and read:

- Apple HIG — Dark Mode: https://developer.apple.com/design/human-interface-guidelines/dark-mode (fetched via `developer.apple.com/tutorials/data/design/human-interface-guidelines/dark-mode.json`) — base vs elevated backgrounds, 4.5:1 minimum / 7:1 preferred, respect system appearance, test with Increase Contrast + Reduce Transparency.
- Apple HIG — Materials / Liquid Glass: https://developer.apple.com/design/human-interface-guidelines/materials (JSON endpoint) — glass only on the controls/navigation layer, never in the content layer; use sparingly; thicker material = better legibility.
- Apple HIG — Accessibility: https://developer.apple.com/design/human-interface-guidelines/accessibility (JSON endpoint) — 44 × 44 pt default hit target, 12/24 pt spacing, 17 pt default text, Reduce Motion list (no scaling, z-depth, x/y transitions, blur, repetition), don't rely on colour alone.
- Apple HIG — Buttons: https://developer.apple.com/design/human-interface-guidelines/buttons (JSON endpoint) — 44 pt minimum, 1–2 prominent buttons per view, style not size for hierarchy, verb labels, never make destructive the primary role, always include a press state.
- Apple HIG — Layout: https://developer.apple.com/design/human-interface-guidelines/layout (JSON endpoint) — safe areas, inset buttons on iOS, extend backgrounds edge-to-edge.
- WCAG 2.2 Understanding SC 2.5.8 Target Size (Minimum): https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html (read via `raw.githubusercontent.com/w3c/wcag/main/understanding/22/target-size-minimum.html`) — 24 × 24 CSS px, five exceptions, spacing-circle rule.
- WCAG 2.2 Understanding SC 1.4.6 Contrast (Enhanced): https://www.w3.org/WAI/WCAG22/Understanding/contrast-enhanced.html (raw GitHub mirror) — 7:1 / 4.5:1, large text = 18 pt (≈24 px) or 14 pt bold (≈18.5 px), 20/80 vision rationale.
- WCAG 2.2 Understanding SC 2.4.13 Focus Appearance: https://www.w3.org/WAI/WCAG22/Understanding/focus-appearance.html (raw GitHub mirror) — 2 px perimeter, 3:1 change between states.
- MDN — ARIA `timer` role: https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/timer_role (read via mdn/content raw) — implicit `aria-live="off"`, `aria-atomic`, switch to `alert` for milestone announcements.
- MDN — `prefers-reduced-motion`: https://developer.mozilla.org/en-US/docs/Web/CSS/@media/prefers-reduced-motion (mdn/content raw) — replace motion with opacity, avoid scaling/panning large objects.
- Material Components Android — Motion theming (M3 tokens): https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md — standard `0.2,0,0,1`, decelerate `0,0,0,1`, accelerate `0.3,0,1,1`, emphasized-decelerate `0.05,0.7,0.1,1`, emphasized-accelerate `0.3,0,0.8,0.15`; durations short 50–200 / medium 250–400 / long 450–600 ms.
- Material Components Android — Dark theme: https://github.com/material-components/material-components-android/blob/master/docs/theming/Dark.md — dark grey not black, elevation via lighter surfaces.
- Android Developers — Principles of Wear OS development (glanceability): https://developer.android.com/training/wearables/principles — "complete tasks within seconds", one or two tasks, haptic confirmation for fitness actions.
- Android Developers — Material 3 Expressive for Wear: https://developer.android.com/design/ui/wear/guides/get-started/apply — containment, size hierarchy, dedicated numeral type role, variable-weight type.
- Modern Font Stacks: https://github.com/system-fonts/modern-font-stacks — `system-ui` resolution per OS.
- Liquid Glass reference (community, mirrors Apple guidance): https://github.com/conorluddy/LiquidGlassReference — three-layer model, no glass-on-glass, accessibility behaviours.
- ios-haptics: https://github.com/tijnjh/ios-haptics — `<input type="checkbox" switch>` haptic trick, Safari 17.4+.

Secondary sources and search-result excerpts used (host unreachable from the build network; treated as corroboration only):

- Steven Hoober, "How Do Users Really Hold Mobile Devices?" (UXmatters, 2013) and "Design for Fingers, Touch and People, Part 3" (2017) — 1,333 observations, ~75 % thumb, ~49 % one-handed; people look at and touch the centre. https://www.uxmatters.com/mt/archives/2013/02/how-do-users-really-hold-mobile-devices.php (snippet)
- Thumb-zone accuracy figures (~96 % natural vs ~61 % top-third, +0.7–1.2 s per stretched tap): https://parachutedesign.ca/blog/thumb-zone-ux/ and https://inkbotdesign.com/mobile-ux/ (snippets)
- Material Design dark theme baseline `#121212` and 0–16 % overlay range: https://medium.com/snapp-mobile/design-for-the-dark-theme-9a2185bbb1d5 (snippet); Apple's `#1C1C1E` elevated surface: https://irisapp.cc/ios-dark-mode-design-8-practical-guidelines-for-better-contrast-and-readability/ (snippet)
- Material 3 Expressive research framing ("most researched update", emphasis on colour/shape/size/motion/containment): https://m3.material.io/blog/building-with-m3-expressive and https://design.google/library/expressive-material-design-google-research (snippets)
- Motion duration norms (200–300 ms typical; > 500 ms sluggish; opt-in animation pattern): https://blog.pope.tech/2025/12/08/design-accessible-animation-and-movement/ and https://web.dev/articles/prefers-reduced-motion (snippets)
- Tabular numerals and San Francisco's proportional default: https://dev.to/alanwest/tabular-numbers-in-css-font-variant-numeric-vs-monospace-hacks-25cn and https://nanx.me/blog/post/font-variant-numeric/ (snippets)
- iOS Safari suspends Web Audio when locked; PWA background limits: https://developer.apple.com/forums/thread/762582 and https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide (snippets)
- iOS haptic workaround lifespan (17.4–26.4): https://vibrator.dev/ (snippet)
- Viewing-distance legibility rule of thumb (text height ≈ 0.7 % of distance minimum, 1–1.4 % comfortable): https://digitalsignage.com/digital_signage/docs/guides/typography-viewing-distance/ (snippet)
- Fitness-app UX corroboration (large targets, haptic confirmation, "current exercise → timer → next" hierarchy, ≤ 3 steps to log): https://www.zfort.com/blog/How-to-Design-a-Fitness-App-UX-UI-Best-Practices-for-Engagement-and-Retention and https://dataconomy.com/2025/11/11/best-ux-ui-practices-for-fitness-apps-retaining-and-re-engaging-users/ (snippets)
- 2026 trend listicles reviewed and mostly rejected as fashion (glassmorphism, neumorphism, AI personalisation): https://www.mindinventory.com/blog/mobile-app-ui-ux-design-trends/, https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/ (snippets). The durable items extracted — dark-first, bottom navigation, accessibility-first, performance-as-design — are already in this spec.

Not reachable and therefore **not** cited despite being sought: Nielsen Norman Group ("Glanceable UI", mobile UX), `m3.material.io` token pages, `web.dev` full article, Google Codelabs dark-theme lab. Values attributed to them above were cross-checked against the fetched mirrors or independently computed.
