# REVIEW-DESIGN-2.md — Athletic Cut Trainer, round 2 (verification of the fixes)

Reviewer: Fable. Scope: `athletic-cut/index.html` at commit `5c70f16` ("Fix findings from the design and correctness reviews"), judged against REVIEW-DESIGN.md round 1 and DESIGN.md. Evidence: the 34 re-rendered shots in `tests/shots/`, plus my own Playwright renders at 390×844, 360×780, 430×932, 200 % root font, `prefers-reduced-motion: reduce`, `forced-colors: active`, four theme situations (dark OS, light OS, Light override on a dark OS, Dark override on a light OS), CDP touch-event swipes, and a contact sheet of all 38 figures at 40 px and 160 px (scratch under `/tmp/…/scratchpad/r2/`, not in the repo). Contrast ratios are computed from the colours actually painted (`getComputedStyle`), not from the token table.

**Verdict in one paragraph.** 4 of 4 Criticals and 9 of 13 Majors are genuinely fixed, and the runner is now a good gym screen: 94/109 px timer digits inside a 340 px ring, visible RPE state, honest checklist, correct superset cues, the toast no longer covers sheet buttons, tabs open at the top. Three fixes did not land the way they look like they did: **the swipe-down reachability gesture never fires on a touch screen** (only with a mouse), **the light-mode amber-graphics token is missing from the Settings "Light" override block** so anyone who picks Light on a dark-OS phone gets the old 1.6:1 strip/ring/charts, and **the toggle knob fix is overridden by a light-mode rule that also applies in dark mode** (white on green, 1.65:1, and it is pure `#FFF`). Everything else remaining is small.

Status key: **FIXED** — verified visually or by measurement. **PARTIAL** — the visible symptom is gone but part of the finding was not addressed. **NOT FIXED**. **WON'T-FIX-OK** — not done and I no longer think it matters.

---

## A. Verification of round-1 findings

### Critical

| # | Finding | Status | Evidence |
|---|---|---|---|
| C1 | Superset B showed A's cues | **FIXED** | Day A superset after logging A: `.ex-name` "Hanging leg raise", cues `["Pull the ribs down first","Raise the legs with control, no swing","Lift until the hips curl up"]`; `ex` lookup now happens after the `isRounds` reassignment (line ≈3874). |
| C2 | Undo toast floated over sheet buttons | **FIXED** | `.toast{z-index:45}` < `.sheet{z-index:51}`, and `ui.sheet()` calls `hideToast()` first (line 1935). Measured: superset-A log leaves 1 toast; opening the numpad → 0 toasts; opening ⋯ → 0 toasts. `a-numpad.png` shows `0`/`⌫`/Done clean. |
| C3 | Nutrition bars shared one scale | **FIXED** | `16-nutrition`: kcal 2,666/2,650 full, protein 195/190–200 ≈ 98 %, steps 8,494/8k–10k ≈ 85 %. Bars now read the same story as the numbers. |
| C4 | Tab change kept scroll position | **FIXED** | Progress scrolled to `scrollY 626` → Body opens at `scrollY 0`. `15-body` now shows the weight stepper + SAVE WEIGHT on arrival. `render()` resets on route/param change only (line 5173), so stepper taps on the same screen do not jump. |

### Major

| # | Finding | Status | Evidence |
|---|---|---|---|
| M1 | Timer digits 76/94 px vs 112 floor | **FIXED** | `.dial` = 340 px at 390/430, 317 px at 360. `2:30` = 93.6 px (390), 86.4 (360), 96 (430); under 60 s = 109.2 / 100.8 / 112. Digits always fit the inner diameter (225 px in 309, 208 in 288). Stroke stayed 12 (I suggested 10) — fine, it still fits. |
| M2 | RPE selection invisible; "10" wrapped at 360 | **FIXED** | Selected chip: `--primary-dim` fill + 2 px inset `--primary-ink` ring. Ring vs page = **10.97:1** dark, **4.50:1** light; label `--primary-text` on fill = 10.5 / 7.02. Five chips in one row at 360 (59×56), 390 (62×56), 430 (73×56), 200 % (65×64 / 59×64). Re-tapping the selected chip keeps it checked (`aria-checked="true"`). Selection persists to set 2 (verified). |
| M3 | Unchecked rows showed ✓ | **FIXED** | `03-prep`: four empty rings; `.check .box .ico{opacity:0}` until checked. |
| M4 | Light-mode amber graphics 1.6–1.8:1 | **PARTIAL — see N1, N5** | `--primary-ink` exists and is used by the strip, pips, work/final ring, chart line/dots/band/bars, meters. Light (OS) = `#9C6300`: 4.50 on `--bg`, 5.00 on `--surface`, 4.07 on `--surface-press`, 4.29 on `--rest-dim`. **But** the token is declared twice in the `@media (prefers-color-scheme: light)` block (line 80) and **not at all** in the `:root[data-theme="light"]` block (lines 89–101). Measured with a dark OS + Settings → Light: `--primary-ink` = `#FFB224`, strip on bg **1.62**, chart line on white **1.80**, meter on press **1.47**. Also the e1RM legend swatch is inline `var(--primary)` (line 4775), so in light mode the legend dot is `#FFB224` on a `#E8E8E4` chip (1.47) and does not match the `#9C6300` line it labels. |
| M5 | Figures don't read; thumbs are frame a | **PARTIAL — see N6** | Trap-bar frame a now has bent knees, vertical shins, hands at a bar on the floor with trap-bar uprights — reads as a deadlift setup, not an RDL. Box jump b lands on the box. Band pull-apart arms visible. Limb-length jumps mostly normalised (worst remaining: glute-bridge thigh 27→19, dip shins 22→16, TGU torso 42→30, dead-hang shins 26→21, thoracic-rotation upper arm 15→11). Side-plank b hip only rises 90→80 and still reads as "lying down, one arm up". **Thumbnails still render frame a** (`.thumbbtn` never sets `.frame1`; `.fig .f1{opacity:0}`), so "Deep squat hold" in Prep is still a standing figure and goblet/front squat thumbs are standing figures. Stroke was raised to 5.5 (good). No limb-ratio assertion was added to `tests/run.mjs`. |
| M6 | 200 %: load value overflowed; rest panel outgrew viewport | **FIXED** | `.stepper{container-type:inline-size}`; value = `min(4.5rem, 26cqw)`: 72 px at 360/390/430 (spec), 92.6 px at 390-200 %, 84.8 at 360-200 % — `scrollWidth === clientWidth` everywhere. Rest panel is `position:fixed; overflow-y:auto`; at 390-200 % the ring (y 138–478) and both buttons (y 660–762) are on screen without scrolling; the panel scrolls 67 px for the "adjust" link. Two 200 % nits remain (N9). |
| M7 | Library truncated 14 names | **FIXED** | 0 truncated titles at 390; pattern moved into the sub-line ("dumbbell · squat · e1RM 70 lb"). |
| M8 | No swipe-down hatch; no left-handed layout | **NOT FIXED (swipe) / PARTIAL (left-handed) — see N2, N7** | `attachReachSwipe` listens to `pointerdown`/`pointerup` only. Instrumented CDP touch swipe on the runner body: `pointerdown → pointermove ×2 → pointercancel → touchend`; **`pointerup` never fires** because the browser claims the vertical pan, so `moreSheet()` is never called. Sheet opened 0/4 times by touch, 1/1 by mouse drag. On a phone this feature does not exist. Left-handed: Settings toggle exists, `data-hand="left"` persists across `render()`, stepper flips (`+` at x 17, `−` at x 301, both 72 px wide, borders correct) — but the header ✕/⋯ do not flip (✕ x 16, ⋯ x 330), which §4.2 asks for. |
| M9 | Reduced motion half-honoured | **FIXED** | Under `reduce`, the arc offset is quantised: 182.2 (= C/4) where the raw value would be 242.9; `.runner-body` transition is `0s`; `.runner-body{transition}` moved inside the `no-preference` block; Settings has Reduce motion System/On/Off and `reduceMotion()` reads it. |
| M10 | No rest-panel focus management; no milestones | **PARTIAL** | On takeover `document.activeElement` = the `Rest` cap (`tabindex=-1`); on Skip/GO focus lands on `LOG SET`. `sr-status` log: "Rest started, 2:30." → "One minute left." → "Ten seconds." → alert "Go."; `said60/said10` live on the timer object. Not done: the start announcement still says "2:30" (read as a time of day by VoiceOver) and does not include the next set. |
| M11 | Evolt empty state overflowed | **FIXED** | "No scans yet — scan every 6–8 weeks." centred as HTML; BIA sentence is a wrapping `<p>` below (kept on the card rather than moved to the form — fine). |
| M12 | Dial left-aligned on EMOM/sprints | **FIXED** | Dial centre x = 195 on 390 in both blocks (`margin: … auto`). |
| M13 | Outlined controls on sheets (2.88:1) | **FIXED** | `.sheet .btn--outline` and `.sheet .field input` are filled `--surface-sel` with transparent border ("RECORD MY OWN CLIP" bg `#3A3A44` on `#1E1E24`). |

### Minor

| Finding | Status | Evidence |
|---|---|---|
| "+30" has no unit | **FIXED** (inconsistently) | Panel button says **+30 SEC**, collapsed bar says **+30 s**. Pick one. |
| Superset labels use first word | **FIXED** | `shortName` column: "LOG · NEXT: LEG RAISE", pair bar "Split squat / Leg raise", rows "Split squat 1". |
| "5 reps · top of range 7" | **FIXED** | Target line "5 reps"; hint under the stepper "7 reps @ RPE 8 or less → +5 lb next time". |
| Sprint GET SET showed SKIP REST | **FIXED** | GET SET phase → amber **GO NOW**; rest phase → cyan SKIP REST. |
| Collapsed bar had Skip twice | **FIXED** | Bar = `2:30 · Set 3 · 250 lb × 5 · +30 s`; band = SKIP REST. |
| Leave sheet: destructive first and full-size | **PARTIAL** | Order is now Pause (primary) → Cancel (ghost, 64) → **Abandon (danger, 56, at the very bottom, y 765–821)**. The destructive button moved from the top to the single most reachable slot on the screen. Cancel is no longer bottom. See N8. |
| Axis labels 11 px | **FIXED** | 13 px. |
| Axis end labels spill | **FIXED** | First/last x labels anchored start/end; only a 1 px left bleed on y labels. |
| Dial track invisible | **FIXED** | `--line-ctrl` at .45 — visible in `10-sprints-running` and the last-5-s shot. |
| EMOM missed pip invisible | **FIXED in CSS, but see N10** | `.pips i.miss` has the danger ring, but the pip for the *current* minute is never drawn, so tapping MISSED changes nothing on screen for up to 60 s (only a toast). |
| Raw bodyweight dots 2.26:1 | **FIXED** | `.c-raw{opacity:.7}`. |
| Figure box invisible in sheet | **FIXED** | `#2A2A32` box on `#1E1E24` sheet. |
| REST caption 13 px; light cyan-text | **FIXED** | 15 px; light `--cyan-text` `#055470` → 7.18:1 on `--rest-dim`. |
| Stepper aria-labels omit step/unit | **FIXED** | "Decrease load by 5 lb". |
| Focus order starts at header | **NOT FIXED** | Tab order: Leave → More → first row … Documented nowhere. Low priority; header-first is the platform norm. Accept and document, or `order:-1`. |
| One-time onboarding hints never shown | **NOT FIXED** | `flags.seenTypeHint` is never read; `seenRestHint` is set on first log but no hint is shown (0 toasts after the first log). |
| Switch knob 1.5:1 on green | **NOT FIXED — see N3** | Measured **1.65:1** in dark mode. The new `.switch[aria-checked="true"] .knob{background:var(--n-0)}` is beaten by `:root:not([data-theme="dark"]) .switch .knob{background:#fff}` (specificity 0,4,0 vs 0,3,0), and that selector matches whenever the theme is "system" — i.e. in dark mode too. Result: a pure-`#FFF` knob in dark mode, which §2 forbids. |
| Weekly volume heading shows ISO key | **FIXED** | "week of Aug 31". |
| Storage string exposes save timing | **FIXED** | "Storage used: 50 KB · 4 sessions". |
| forced-colors strip disappears | **FIXED** | Segments bordered, done = Highlight (`p-forced-rest.png`). |
| Home mid-screen void | **FIXED** | Links moved to y 323 under the tiles; band stays at 644. |

### Nits

| Finding | Status |
|---|---|
| Figure alternation 1.6 s → 1.2 s | **FIXED** |
| `--fs-hero` unused | **WON'T-FIX-OK** (dial uses its own clamps; harmless) |
| CONTINUE count wraps at 360 | **NOT FIXED** — `c-prep-360x780.png`: "CONTINUE TO MAIN LIFT" / "0/4" on two lines |
| LOGGED/NEXT far below the ring | **NOT FIXED** — 146 px gap at 390 (ring bottom 457, LOGGED top 603). Acceptable; the spacer keeps NEXT near the thumb. |
| Skip rest ghost reads disabled | **FIXED** — 1 px `--line-ctrl` border |
| `+ New` is icon-only | **WON'T-FIX-OK** |
| "Set removed." toast over set list | **FIXED** via z-index |

---

## B. Did the fixes break anything?

- **Bigger dial / digits.** Fit at all three widths and at 200 % (see M1/M6 numbers). At 200 % the dial keeps `min(340px,88vw)` and the digits cap at 112/128 px, still inside the ring. No regression. The `long` class flips at `text.length > 3`, so "10:00" would also get the small size — fine.
- **Rest panel `position:fixed; overflow-y:auto`.** Works: buttons visible without scrolling at every size I tried. Two small regressions at 200 % only (N9): the fixed `padding-top: safe-t + 76px` assumes a one-line header, but "MAIN LIFT · SET 2 OF 4" wraps to two lines at 200 %, so the **REST** caption is hidden under the header (`c-rest-390x844-200.png`); and the sticky `.actions` band has `background:none`, so the LOGGED/NEXT rows show through the gap between the two buttons while scrolling.
- **`--primary-ink`.** Correct everywhere it is used; nothing that should be `--primary` (filled buttons, `--primary-text` labels) got the ink value — buttons stay `#FFB224` with `--on-primary` (10.3:1) in every theme. The two defects are the missing override-block declaration (N1) and the legend swatch that still uses `--primary` (N5).
- **Chip selection (`--primary-dim` + inset `--primary-ink` ring).** Ring vs surrounding surface: RPE row 10.97 (dark) / 4.50 (light); library filter chips 10.97 / 4.50; Progress range chips 10.97 / 4.50; segmented controls on the `--surface-press` track 7.89 / 4.07. Text on fill 10.5 / 7.02. All ≥ 3:1 non-text, ≥ 7:1 text. The unselected RPE label (`--text-2` on `--surface-press`) is 7.31 / 7.02. The target ring (`--line-ctrl`) vs selected ring (amber) are visually distinct in both modes. Good.
- **`.rpe-row` `flex:1 1 0`.** One row at 360/390/430 and at 200 % (min chip 59×56, ≥ 44). Good.
- **Stepper `cqw`.** 72 px at 390 (spec), 72 at 360 and 430, 92.6 at 200 % — degrades sanely, never overflows the cell.
- **Swipe-down gesture.** Does not fire accidentally — because it does not fire at all on touch (N2). Even if it worked, the design is right: it ignores starts inside `.stepper,.chip,button`, and at 200 % (scrollable body) the browser's scroll would cancel it. When it is rebuilt on `touchend`, add the "page did not scroll" guard so a scroll never opens the sheet. Note the rest panel has no gesture at all (body is `inert`, panel has no listener), so during rest ⋯ is header-only.
- **Left-handed.** Stepper flips and stays 72 px; header does not flip (N7).
- **Focus / announcements.** In: `Rest` cap. Out: `LOG SET`. Milestones announced once each and survive refresh (stored on the timer). Start announcement still terse (M10 partial).
- **Stick figures.** Contact sheet at 40 px and 160 px, both frames, all 38. At full size every figure except **side plank** (b) and **thoracic rotation** (a ≈ b) reads as its movement; **dip** is weak (the two short strokes at hip height look like a belt, not bars) and both **row** variants are busy at 40 px (the diagonal bench line crosses the torso). At 40 px, the figures whose *start* frame is a plain standing figure (deep squat hold, goblet squat, front squat, safety-bar squat, KB front squat, KB press, overhead press, dead hang, box jump) are indistinguishable from each other — the end frame is the informative one and the thumb still shows the start (N6).

---

## C. New findings

### N1 — Major: `--primary-ink` is not declared in the `[data-theme="light"]` block
- **Where:** lines 80 (declared twice inside the `prefers-color-scheme: light` block) and 89–101 (absent). Measured with dark OS + Settings → Light: ink `#FFB224`; strip 1.62, chart line 1.80, meter 1.47, EMOM ring 1.62 — exactly round-1 M4, for the exact user the override exists for (bright gym, dark phone).
- **Fix:** delete the duplicate on line 80 and add `--primary-ink:#9C6300;` to the `:root[data-theme="light"]` block. Add a test: `data-theme=light` + `colorScheme:'dark'` → `getPropertyValue('--primary-ink') === '#9C6300'`.

### N2 — Major: the reachability swipe never fires on a touch screen
- **Where:** `attachReachSwipe` (line ≈3768). Touch sequence measured on the runner body: `pointerdown, pointermove, pointermove, pointercancel, touchend` — `pointerup` is never delivered once Chrome/Safari take the vertical pan, so the ≥ 64 px check never runs. Mouse drag works, which is why it looked fixed.
- **Fix:** use touch events (passive) alongside the pointer path, and guard against scroll:
  ```js
  function attachReachSwipe(el) {
    var x0 = 0, y0 = 0, s0 = 0, live = false;
    function start(x, y, target) {
      if (target.closest('.stepper,.chip,button,input,textarea')) { live = false; return; }
      x0 = x; y0 = y; s0 = window.scrollY; live = true;
    }
    function end(x, y) {
      if (!live) return; live = false;
      if (Math.abs(window.scrollY - s0) > 8) return;            /* it was a scroll */
      if (y - y0 > 64 && Math.abs(x - x0) < 40) moreSheet();
    }
    el.addEventListener('touchstart', function (e) { var t = e.touches[0]; start(t.clientX, t.clientY, e.target); }, { passive: true });
    el.addEventListener('touchend', function (e) { var t = e.changedTouches[0]; end(t.clientX, t.clientY); }, { passive: true });
    el.addEventListener('pointerdown', function (e) { if (e.pointerType !== 'touch') start(e.clientX, e.clientY, e.target); });
    el.addEventListener('pointerup', function (e) { if (e.pointerType !== 'touch') end(e.clientX, e.clientY); });
  }
  ```
  Attach it to `.restpanel` as well (call it in `restPanel()`), since the body is `inert` during rest. Test with CDP `Input.dispatchTouchEvent` (start → 8 moves → end), not `page.mouse`.

### N3 — Major: toggle knob rule regressed to pure white in dark mode
- **Where:** lines 336–337: `:root[data-theme="light"] .switch .knob, :root:not([data-theme="dark"]) .switch .knob{background:#fff}`. The second selector matches the default "system" theme in dark mode; its specificity (0,4,0) beats the checked-knob rule (0,3,0). Result: `#FFF` on `#5BE28A` = **1.65:1** on every "on" toggle in Settings (`17-settings.png`), and pure white in dark mode.
- **Fix:** delete lines 336–337. Keep `.switch .knob{background:var(--n-8)}` (dark: off-white on `#3A3A44`; light: `#121215` on `#DCDCD7` = 12:1) and `.switch[aria-checked="true"] .knob{background:var(--n-0)}` (dark: `#0A0A0C` on green 11.6:1; light: `#F3F3F0` on `#0F6B36` 6.6:1). Add a 1 px `var(--line-ctrl)` border to the knob so it still has an edge on the light track.

### N4 — Minor: toasts survive abandon / route change
- **Where:** `d-sprint-rest.png` shows "Round marked missed." from the EMOM of a *previous, abandoned* session on top of the Day C sprint rest screen. `render()` never hides the toast; only `sheet()` and the toast's own timer do.
- **Fix:** in `render()`, when `route !== prevRoute || cur.param !== prevParam`, call `ui.hideToast()`. Also hide in `engine.abandon()`.

### N5 — Minor: e1RM legend swatch uses `--primary`, not `--primary-ink`
- **Where:** line 4775, inline `background:var(--primary)`. In light mode the dot is `#FFB224` (1.47:1 on the `#E8E8E4` chip) and a different colour from the `#9C6300` line it identifies.
- **Fix:** `['var(--primary-ink)','var(--rest)','var(--success)','var(--text-2)']`.

### N6 — Minor: thumbnails show the start frame; four figures still weak
- **Fix (thumbs):** `.thumbbtn .f0,.listrow .fig .f0{opacity:0} .thumbbtn .f1,.listrow .fig .f1{opacity:1}` — the end frame is the pose that names the exercise (bottom of the squat, hips high on the bridge, legs up on the raise, feet on the box).
- **Fix (poses, viewBox 160×120, floor y 112):**
  - `side-plank` b: `SIDEPL_UP` hip → (96,66), neck (56,62), head (44,56), support elbow (52,80)/hand (48,100), free wrist (58,36); ankles stay (132,96)/(134,96) so shoulder–hip–ankle is one straight line.
  - `thoracic-rotation` b: free arm straight up — elbow (56,44), wrist (60,26); keep the support arm; rotate head to (44,50).
  - `dip`: replace the two 22-unit strokes with real bars: `['ln',36,72,72,72],['ln',88,72,124,72],['ln',40,72,40,112],['ln',120,72,120,112], GND`.
  - `glute-bridge`, `dip`, `turkish-get-up`: bring the flagged segments within ±20 % (thigh 27→19, shins 22→16, torso 42→30).
  - Add the limb-ratio assertion to `tests/run.mjs` (max/min of each segment across a/b ≤ 1.25) so the next pose edit cannot regress silently.

### N7 — Minor: left-handed layout does not flip the header buttons
- **Fix:** `:root[data-hand="left"] .runner-head-row{flex-direction:row-reverse}` (the caption is `flex:1; text-align:center`, so it stays centred).

### N8 — Minor: Abandon now sits in the most reachable slot of the leave sheet
- **Where:** `a-leave-sheet.png`: Pause (amber) → Cancel → **Abandon** (red, y 765–821, natural zone). §4.3.3: destructive actions live behind ⋯ and are never in a primary-positioned slot; Cancel is the large bottom control.
- **Fix:** remove `Abandon session` from `leaveSheet()` (it already lives in the ⋯ sheet with its own confirm). The ✕ sheet becomes Pause / Cancel.

### N9 — Minor (200 % only): REST caption hidden under the wrapped header; values wrap mid-number
- **Fix:** in `restPanel()`, set `panel.style.paddingTop = head.offsetHeight + 'px'` (measure `.runner-head` after render) instead of the fixed 76 px; `.rest-meta .r > span:not(.lbl){white-space:nowrap}` so "250 lb × 5" never splits; give `.restpanel .actions` `background:linear-gradient(to top,var(--rest-dim) 62%,transparent)` so scrolled rows do not show between the buttons.

### N10 — Minor: MISSED gives no on-screen feedback for up to 60 s
- **Where:** `emomBlock` draws pips only for `i < b.minute`; `markMissed` marks the *current* minute, so the danger ring appears only after the minute rolls over. A toast is the only confirmation.
- **Fix:** `class: i < b.minute ? (b.missed[i] ? 'miss' : 'on') : (i === b.minute && b.missed[i] ? 'miss' : '')`, and flip the MISSED button to `aria-pressed="true"` / label "Missed ✓" for the rest of that minute.

### N11 — Minor: bodyweight LOGGED line reads "× 10"
- **Where:** `a-superset-rest.png`: "LOGGED × 10" for the hanging leg raise (no load). Same code path for the set rows and the collapsed bar.
- **Fix:** in the three `bits` builders, when `load` is null push `reps + ' reps'` instead of `'× ' + reps`.

### N12 — Nits
- "+30 SEC" (panel) vs "+30 s" (bar): use one; I would keep **+30 s** on both — it is the spec label and it fits the bar.
- Start announcement: `'Rest started, ' + spoken(sec) + '. Next: set 3, 250 pounds times 5.'` where `spoken(150)` = "2 minutes 30 seconds".
- CONTINUE count still wraps at 360: render `0/4` as `<span style="margin-left:auto">`.
- Checklist timed items (§5.7 inline ▶ 60 s chip) are still not built; the 60 s squat hold has no countdown in Prep.
- One-time hints (§9.1) still not built; either build the two toasts or delete the two flags.

---

## D. As a gym UI, honestly

I walked Day A at 390×844 in dark mode: Prep → 4 sets of trap bar with rests → superset ×3 → EMOM → wrap-up, and the same in light.

**What is now good, and I would ship as-is:** the main-lift screen. Name at 28/800, three cues on set 1 only, LAST line at 22/600, the 72 px load and 56 px reps in 72 px cells, RPE with a visible target ring and a visible amber selection, one amber button at y 768. Nothing on it needs a second look. The rest screen is the best screen in the app: 94 px `2:30` dropping to 109 px under a minute, cyan ring you can see deplete from across the rack, LOGGED/Undo and NEXT at 15/18 px, two 64 px buttons, the amber last-five-seconds state. The collapsed bar (`2:30 · Set 3 · 250 lb × 5 · +30 s` over SKIP REST) is exactly what "adjust next set" should be. Superset labelling ("LOG · NEXT: LEG RAISE", "LOG · THEN REST 60S") tells you the handoff without reading anything else. Tabs open at the top. Light mode is a real theme with nothing pale-on-pale — *when the OS is light*.

**What is still awkward mid-set:**
1. The header is the only route to ⋯ and ✕ on a phone (N2). On a bench with a right thumb, that is a two-hand reach every time you want to edit rest or skip an exercise.
2. Light theme picked in Settings on a dark phone silently loses every graphics-contrast fix (N1). A user in a bright gym who flips to Light gets the pale strip and ring that round 1 flagged.
3. The thumbnail next to the exercise name is decorative, not informative (N6). Nine of them are the same standing figure. Showing the end frame fixes most of that for free.
4. "LOGGED × 10" and "+30 SEC / +30 s" are the kind of small inconsistencies that read as sloppiness on the screen you see 16 times a session.
5. The ✕ sheet puts ABANDON SESSION under your thumb (N8). It confirms, so it is not dangerous, but it is the wrong thing to see when you meant to pause.

**Where the spec was wrong in practice, and the build's choice should stand:**
- 112 px digits inside a 260 px ring never fit; the 340 px ring with 94/109 px digits is the right resolution and the spec should be updated to it.
- The spec's "+30 s" is fine, but the build's larger `+30 SEC` on the 171 px button is actually the more legible label at arm's length; whichever wins, make the bar match.
- The spec put LOGGED/NEXT directly under the ring; the build's spacer pushes NEXT into the natural zone next to the buttons. At 390 that leaves a 146 px hole, but NEXT is where the thumb already is when the timer ends. Keep the build's layout.
- The spec's swipe-down "anywhere on the runner body" is right in intent but must not fire when the body scrolled (200 %); the rebuilt gesture in N2 encodes that.

**Bottom line:** fix N1, N2 and N3 (three small edits, all with tests), then N4–N8, and this is a gym app I would use four times a week without noticing it. Nothing left is a design problem; it is finishing.
