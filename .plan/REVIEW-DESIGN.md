# REVIEW-DESIGN.md — Athletic Cut Trainer, design review of the build

Reviewer: Fable (author of DESIGN.md). Scope: `athletic-cut/index.html` as of 2026-09-02, judged against DESIGN.md and against "does it work with one sweaty thumb from a bench". Evidence: the 34 screenshots in `tests/shots/`, plus my own renders at 390×844, 360×780, 430×932, 200 % root font size, `prefers-reduced-motion: reduce`, `forced-colors: active`, and a contact sheet of all 52 figures in `AC.figures` (scratch material under `/tmp/review/`, not in the repo). Contrast ratios below are computed with the WCAG 2.x relative-luminance formula from the token values actually in the CSS, not copied from the spec table.

Overall: the runner is recognisably the Scoreboard. Dark mode main-lift and rest screens are strong; hierarchy is right, targets are big, gaps are clean, the numeric pad and undo flows work, ARIA roles and live regions exist from page load, forced-colors and focus rings work. Light mode is a real theme, not an afterthought — but it inherits four amber-graphics contrast failures. The defects that matter most are not visual polish; they are five behaviours that will bite mid-set: wrong cues on exercise B of a superset, a toast that covers sheet buttons, timer digits two-thirds of the spec size, a checklist that looks pre-checked, and an RPE selection you cannot see.

Severity key: **Critical** = wrong information or a control that cannot be used mid-session. **Major** = a spec rule broken in a way a user will hit, or a legibility/reach failure. **Minor** = spec deviation with limited impact. **Nit** = polish.

---

## Critical

### C1. Superset exercise B shows exercise A's coaching cues
- **Where:** Session runner, any `rounds` block (Day A superset, Day B supersets, Day C rotational circuit, Day E complex). Screenshot `/tmp/review/shots/superset-b.png`: title "Hanging leg raise", cues "Front shin vertical · Drop the back knee straight down · Weight in the front heel" (those are the Bulgarian split squat's).
- **Why it matters:** The cue strip is the one piece of coaching the runner shows without a tap. On round 1 of every superset the second exercise is coached with the wrong movement's cues. It is also the kind of error that makes a user distrust everything else on the screen.
- **Cause:** `setsBlock()` reads `var ex = S().exercises[slot.exerciseId]` *before* `if (isRounds) slot = blk.items[b.itemIndex]` (index.html ≈ lines 3543–3547).
- **Fix:** Move the `ex` lookup after the `isRounds` reassignment:
  ```js
  var slot = blk.items[b.slotIndex || 0];
  var isRounds = blk.mode === 'rounds';
  if (isRounds) slot = blk.items[b.itemIndex];
  var ex = S().exercises[slot.exerciseId];
  ```
  Add a test: on Day A superset after logging A, `.cuestrip li:first-child` text must be "Pull the ribs down first".

### C2. The undo toast floats above bottom sheets and covers their buttons
- **Where:** Any sheet opened within 6 s of a log. `/tmp/review/shots/leave-sheet.png`: the toast sits on top of **Pause and leave** (the primary button) so only its top 8 px are visible. `numpad.png`: toast covers the `0` and `⌫` keys. `more-sheet.png`: toast covers "Show today's program". Cause: `.toast{z-index:60}` vs `.sheet{z-index:51}`, `bottom: calc(safe-b + 88px)` lands exactly on a sheet's action row, and `sheet()` never hides the toast.
- **Why it matters:** The superset flow logs A *without* a takeover (correct), so the toast is present for 6 s exactly when a user reaches for the value button (numpad) or `⋯`. A 44 px undo button lying over a 64 px "Done"/"Pause" button is a guaranteed mis-tap.
- **Fix (both):** In `ui.sheet()` call `hideToast()` before appending the scrim; and set `.toast{z-index:45}` so a sheet always paints over it. Keep `z-index:60` only if the toast is moved to `bottom: calc(var(--safe-b) + 12px)` *and* sheets are closed.

### C3. Nutrition "7-day average vs target" bars share one scale across kcal / g / steps
- **Where:** `16-nutrition` (both modes). kcal 2,666/2,650 draws at ~31 %, protein 195/190–200 g draws at ~2 %, steps 8,494 draws full. `AC.charts.hbars` computes `max = max(rows.value)` once and applies it to every row.
- **Why it matters:** The bar says "protein is nowhere near target" when the number beside it says it is met. This is the screen the program's weekly review depends on; a bar that contradicts its own label is worse than no bar.
- **Fix:** In `hbars`, accept a per-row `max` and use `width: min(100, value / (r.max || max) * 100)`. In `nutrition()` pass `max: s2.calorieTarget`, `max: s2.proteinMaxG`, `max: s2.stepsMax`. (The Progress volume-by-pattern use is correct with a shared max — leave that.)

### C4. Navigating between tabs keeps the previous screen's scroll position
- **Where:** Progress (scrolled) → Body opens at the bottom of the Recent list (`15-body`, `16-nutrition`, `17-settings` are all captured scrolled to the bottom for this reason). Measured: Progress `scrollY 671` → tap Body → `scrollY 671`.
- **Why it matters:** Body's weight stepper and **Save weight** — the daily action — are off-screen on arrival. Settings opens at "Delete all data". Everything here is window-scrolled (`.scroll` has `overflow-y:auto` but no bounded height, so the document scrolls — measured `inner scrollers: []`), and `render()` never resets it.
- **Fix:** In `AC.screens.render()`, after `app().appendChild(node)`, add `if (route !== prevRoute) window.scrollTo(0, 0); prevRoute = route;` (keep position on same-route re-renders so stepper taps don't jump).

---

## Major

### M1. Rest timer digits are 76 px (m:ss) / 94 px (ss); spec floor is 112 px
- **Where:** `05-rest` and every rest. Measured: `.dial-num.long` = `clamp(3rem,19.5vw,4.75rem)` → 76 px at 390, **70 px at 360**; short form `clamp(3.75rem,24vw,6rem)` → 94 px. §7 table: 112 px, "readable at 2 m". EMOM/interval clock same.
- **Why it matters:** This is the most-seen number in the app and it is read from the floor. 76 px is the size of the load value, not the hero.
- **Root cause is in the design, not only the build:** four tabular characters at 112 px are ~270 px wide and do not fit inside a 260 px ring with a 12 px stroke. The wireframe over-promised. Resolve it by letting the ring be the *backdrop*, not the container:
  ```css
  .dial{width:min(340px,88vw)}                       /* was min(260px,72vw) */
  .dial-num{font-size:clamp(4rem,28vw,7rem)}         /* 109 px at 390, 101 at 360 */
  .dial-num.long{font-size:clamp(3.5rem,24vw,6rem)}  /* 2:30 → 94 px at 390, fits 316 px inner */
  ```
  and drop the ring's `stroke-width` to 10 in `dial()`. Under 60 s the digits go to 109 px, which is where the spec wants them for the last minute. Re-check `08-emom` and `10-sprints` after the change; the runner has the vertical room (there is ~250 px of empty spacer under the ring today).

### M2. RPE chip selection is invisible; at 360 px the "10" chip hides under the action band
- **Where:** `04`/`06-rest-collapsed`: after selecting 8 and logging, `aria-checked="true"` is set (verified) but selected `#3A3A44` vs unselected `#2A2A32` is **1.27:1**; light `#DCDCD7` vs `#E8E8E4` is **1.12:1**. The same pairing is used for the segmented control (Units/Theme) and the Progress range chips ("12w" selected). At 360×780 five 62 px chips + gaps = 342 px > 328 px available, so `10` wraps to a second row that sits behind the sticky `LOG SET` band (`/tmp/review/shots/mainlift-360.png`).
- **Why it matters:** §6.3 says the selection persists to the next set. It does — but the user cannot see that RPE 8 is already selected, so they either tap it again (which *deselects* it, since click toggles) or think it was lost. Colour is carrying state at 1.27:1, which fails §3.3.5 in spirit and WCAG 1.4.11 in letter. The wrapped chip at 360 is unreachable.
- **Fix:**
  ```css
  .chip[aria-checked="true"],.chip[aria-pressed="true"],.seg button[aria-pressed="true"]{
    background:var(--primary-dim);color:var(--primary-text);
    box-shadow:inset 0 0 0 2px var(--primary-ink);   /* see M4 for --primary-ink */
  }
  .chip.is-target{box-shadow:inset 0 0 0 2px var(--line-ctrl)}
  .chip.is-target[aria-checked="true"]{box-shadow:inset 0 0 0 2px var(--primary-ink)}
  ```
  (dark: amber ring on `#2A2A32` = 7.89:1, text 10.5:1; light: `#9C6300` ring on `#E8E8E4` = 4.07:1, text 7.02:1). For the RPE row specifically: `.rpe .chip{flex:1 1 0;min-width:0}` so five chips always share the row (58 px each at 360, still ≥ 44). Also stop a second tap on the selected chip from deselecting it — a radio group should not toggle off; deselect via a sixth "—" chip if you want `null` back, or leave `null` to "no tap".

### M3. Unchecked checklist rows already show a ✓
- **Where:** `03-prep` both modes: all four unchecked rows display a check glyph inside the circle. Cause: the `i-check` `<use>` is always rendered inside `.box` and only its colour changes on `aria-checked="true"` (`stroke:var(--n-0)`); unchecked it inherits `currentColor`.
- **Why it matters:** Four rows that look done is the opposite of a checklist. The row's affordance ("tap to complete") is gone.
- **Fix:** `.check .box .ico{opacity:0} .check[aria-checked="true"] .box .ico{opacity:1}` — or don't append the icon until checked. Spec §5.7: ◯ → ● with a 140 ms fill.

### M4. Light mode: amber non-text graphics are 1.6–1.8:1 on light surfaces
- **Where (light only):** progress strip filled segments (`#FFB224` on `#F3F3F0` = **1.62**, vs unfilled `#D6D6D1` = 1.24), EMOM/effort ring (`08-emom-light`, 1.62), pips, chart lines and dots on white (**1.80**), nutrition/volume meters on `#E8E8E4` (1.47), the "12w"/e1RM legend dot. All these are state or data carriers. §3.2 caught the amber *button* (border added — good) but never the graphics.
- **Why it matters:** In a bright gym in light mode the "where am I in the session" strip and the EMOM work ring are the pale-on-pale objects. The strip is the spec's stated glanceable progress indicator (§7.3).
- **Fix:** Add one token, `--primary-ink`, used only for non-text amber graphics (strip, pips, ring `.dial--work .arc`, `.dial.is-final .arc`, `.chart .c-avg/.c-avg-dot/.s0/.s0f/.bar`, `.meter i`, legend dots):
  ```css
  :root{--primary-ink:var(--amber-fill)}                /* dark: #FFB224, 10.97:1 */
  /* light blocks */ --primary-ink:#9C6300;              /* 5.00:1 on #FFF, 4.50 on #F3F3F0, 4.07 on #E8E8E4 */
  ```
  Keep `--primary` (`#FFB224`) for filled buttons — `#1A1200` on it is 10.3:1 and it is the brand. `--primary-text` (`#7A4500`) is too dark to read as amber for a 4 px strip; `#9C6300` still reads as amber.

### M5. Several stick figures do not read as the movement — including the main lift
- **Where:** `07-exercise-sheet`, `12-exercise-detail`, and the contact sheet `/tmp/review/shots/figures.png`. Pose data in `AC.figures.F`.
  - `trap-bar-deadlift` frame a (`HINGE_DP`): hip (70,64) → knee (70,86) → ankle (62,112) is a straight leg; the bar `['bar',86,88,124,88]` floats 24 units above the floor (plates would be at y≈107). It draws a stiff-leg RDL with a bar in mid-air — which is *exactly* the "common mistake" listed under it ("hips shooting up first so it turns into a stiff-leg pull"). This is the program's headline lift.
  - `box-jump` frame b (`JUMP_TOP`): ankles at y=100 next to a box whose top is y=78 — the figure hovers beside the box, never lands on it.
  - `band-pull-apart` frame a: forearms are 4 units long (elbow 70,44 → wrist 72,40), so the arms vanish; frame b is fine.
  - `side-plank` frame b (`SIDEPL_UP`): hip only rises from 90 → 84 and the body stays on the floor; it reads as "lying down, one arm up".
  - Limb-length jumps between frames flagged by script (segment ratio > 1.6): kettlebell-swing forearms 17→8, pull-up/weighted-pull-up forearms 20→12, push-up 15→9, a-skip thigh/shin 26→15, turkish-get-up upper arm 7→17. These read as the arm shortening rather than moving.
  - The 38 px thumbnails in `.thumbbtn` (44 px button, 6 px stroke at 160-unit viewBox → ~1 px lines) are not legible as anything but "a person"; in `03-prep` the deep-squat-hold thumb shows frame a = a standing figure.
- **Why it matters:** §5.9/§12 make the figure the *only* shipped demo. A figure that illustrates the mistake is a negative.
- **Fix (pose data, viewBox 160×120, floor y=112):**
  - `trap-bar-deadlift`: `a: P(104,40, 96,46, 68,66, 100,66, 104,102, 102,64, 106,100, 76,90, 68,112, 82,90, 74,112)` (knees bent, shins near vertical, hands at bar height), `ea: [['bar',88,104,126,104], GND]`; frame b keep `STAND_R` with `['bar',66,66,104,66]`. Add a second short vertical stroke at each end (`['ln',88,98,88,110]`, `['ln',126,98,126,110]`) to hint the trap-bar frame.
  - `box-jump` b: `P(112,10, 112,22, 110,48, 100,34, 92,24, 122,34, 130,24, 106,66, 104,84, 116,66, 118,84)` with `eb: [['box',96,84,44,28], GND]` (feet on the box top).
  - `band-pull-apart` a: elbows (66,46)/(94,46), wrists (76,42)/(84,42); keep b.
  - `side-plank` b: hip (96,70), neck (56,66), head (44,62), support elbow (52,84)/hand (48,100), free wrist (58,44).
  - Normalise forearm/upper-arm/thigh/shin lengths within ±20 % across a/b for the flagged figures (a small script that asserts this belongs in `tests/run.mjs`).
  - Thumbnails: render the *end* frame (b) in the thumb, raise the stroke to 4.8 for `.thumbbtn svg` (`.thumbbtn .body{stroke-width:4.8}`), and hide the equipment ground line at that size. At 38 px the figure needs to be a silhouette, not a drawing.

### M6. 200 % text: the load value overflows onto the "+" cell; the rest panel outgrows the viewport
- **Where:** `/tmp/review/shots/mainlift-200-bottom.png`: `255` at 144 px is ~260 px wide in a 212 px cell and paints over the `+` button (still clickable, but invisible). `rest-200.png`: `.restpanel` is `position:absolute; inset:0` on a runner that is now 1,308 px tall, so the ring and the buttons can never be on screen together; spec §10.5 says the runner must still work at 200 %.
- **Fix:**
  ```css
  .stepper{container-type:inline-size}
  .stepper .step-val{font-size:min(var(--fs-num-xl),26cqw);min-width:0;overflow:hidden}
  .stepper--sm .step-val{font-size:min(var(--fs-num-lg),22cqw)}
  .runner.is-resting{height:100dvh;overflow:hidden}
  .restpanel{position:fixed;inset:0;overflow-y:auto}   /* header stays at z-index 31 above it */
  ```
  Otherwise 200 % holds up well: the band stays pinned, chips wrap, nothing scrolls horizontally.

### M7. Library rows truncate 14 of 40 exercise names
- **Where:** `11-library`: "90/90 hip rot…", "Bulgarian split …", "Chest-support…"; measured 14 truncations at 390 px. Cause: `.listrow-title{white-space:nowrap}` plus a 13 px caps pattern label and chevron on the right.
- **Why it matters:** The name is the row. The pattern label is already a filter chip above.
- **Fix:** `.listrow-title{white-space:normal}` (two lines, `line-height:1.2`), and drop the right-side pattern `.cap` from library rows (keep it in `listrow-sub`: "dumbbell · squat · e1RM 70 lb").

### M8. No reachability escape hatch; header is the only route to ✕ / ⋯ (anti-pattern 6); left-handed layout missing
- **Where:** Runner. §4.3.7 required swipe-down-on-body to open the `⋯` sheet; nothing in the code listens to touch/pointer gestures. Settings has no "Left-handed layout" toggle (§4.2, §5.14), so the `−` cell is always at the left/stretch edge for a right thumb — fine — but a left-handed user gets `+` in the stretch zone.
- **Fix:** In `sessionScreen()` attach `pointerdown/pointermove/pointerup` to `.runner-body`; if `dy > 64` and `|dx| < 40` and the target is not inside `.stepper,.chips,button`, call `moreSheet()`. Add `settings.leftHanded` to Settings as a `ui.toggle`, and `:root[data-hand="left"] .stepper{grid-template-columns:var(--tap-step) 1fr var(--tap-step)} .stepper .step-btn:first-child{order:3}` etc. (only the stepper cells and header buttons flip).

### M9. Reduced motion is only half honoured
- **Where:** With `prefers-reduced-motion: reduce`: panel/sheet/toast animations are correctly off (opt-in pattern works), figure loop is off. But the rest ring still animates continuously (`stroke-dashoffset` measured changing under reduce; spec M8: static quarter marks), `.runner-body{transition:opacity .32s}` and `.chip{transition}` still run (spec says colour transitions are allowed, so the chip is fine; the body dim is a 320 ms fade of a full-screen element and should be instant). No app-level "Reduce motion" override in Settings (§8.3).
- **Fix:** In `tickUI()`, when `U.reduceMotion.matches` (or the Settings override), round the fraction to quarters: `fraction = Math.ceil(fraction*4)/4` and skip per-frame updates unless the quarter changes. Move `.runner-body{transition}` inside the `no-preference` block. Add `reduceMotion: system/on/off` to Settings (the seed already carries the field).

### M10. Rest panel has no focus management; timer milestones are not announced
- **Where:** On takeover, `document.activeElement` stays wherever it was (the removed `LOG SET` — effectively body); the panel heading has no `tabindex="-1"`; on GO/skip focus does not return to `LOG SET`. `sr-status` gets "Rest started, 2:30." and `sr-alert` gets "Go." (good), but there is no "One minute left" / "Ten seconds" (§10.3), and the next-set text is not included in the start announcement.
- **Fix:** In `restPanel()`: give the `Rest` cap `tabindex="-1" id="rest-h"` and `setTimeout(()=>el.focus(),30)`; in `endRest()`'s render, focus `.actions .btn--primary`. In `tick()`, when `remaining` crosses 60 and 10 s (`!a.timer.announced60` etc., stored on the timer object so it survives refresh), call `U.announce('One minute left.')` / `'Ten seconds.'`. Start announcement: `'Rest started, 2 minutes 30 seconds. Next: set 3, 255 pounds times 5.'` (spell the time — "2:30" is read as a time of day by some screen readers).

### M11. Evolt empty state overflows the chart and the copy is three lines long
- **Where:** `14-progress-lower` both modes: "…very 6–8 weeks. BIA reads leaner than DEXA — treat it as a trend, not a…" clipped on both edges. `charts.line()` renders `emptyText` as a single centred SVG `<text>`; it cannot wrap. Same mechanism would clip any long empty text.
- **Fix:** In `line()`, when `!pts.length` return an HTML `<p class="hint" style="text-align:center;padding:24px 0">` instead of an SVG. Copy per §9.2 (one line): "No scans yet — scan every 6–8 weeks." Move the BIA-vs-DEXA sentence to the Evolt form sheet.

### M12. Timer dial is left-aligned on EMOM and sprints
- **Where:** `08-emom`, `10-sprints-running` both modes: ring centre at x≈145 of 390. `.dial{width:min(260px,72vw)}` inside a `.stack` column with default `align-items:stretch` → left. The rest panel centres it only because `.restpanel{align-items:center}`.
- **Fix:** `.dial{margin:var(--s-2) auto}`.

### M13. Outlined controls on `--surface-2` sheets (2.88:1) — §3.3 rule 3
- **Where:** Exercise sheet "RECORD MY OWN CLIP" (`07-exercise-sheet`) is `btn--outline` on the sheet; the New exercise and Evolt sheets use `.field input` with `border:1px solid var(--line-ctrl)` on `--surface-2`. Dark: `#65656F` on `#1E1E24` = **2.88**. (Light passes at 3.42, so this is dark-only.)
- **Fix:** `.sheet .btn--outline{background:var(--surface-sel);border-color:transparent}` and `.sheet .field input,.sheet .field textarea{background:var(--surface-sel);border-color:transparent}`.

---

## Minor

- **"+30" has no unit** on the rest panel and the collapsed bar; spec and aria say "+30 s". Fix label text to `+30 s` (button is 171 px wide; there is room).
- **Superset labels use the first word of the exercise name:** button "LOG · NEXT: HANGING", set rows "Bulgarian 1", "Hanging 1". Add a `shortName` column to `EX_ROWS` ("Split squat", "Leg raise", "OH press", "CS row", "Dips", "Face pull", "Landmine rot.", "Pallof", "Side plank", "DB bench", "1-arm row", "KB clean", "KB press", "KB front squat", "KB swing") and use it in the button, the set list and `.pairbar`.
- **"5 reps · top of range 7"** (main lift target line). ARCHITECTURE §8.3 chose `max = min + 2` and said to show "5–7" as a hint; the current copy reads like an instruction to do 7 on a heavy deadlift in a deficit. Use `5 reps` alone on the target line and put the rule where the suggestion lives: hint under the load stepper `7 reps @ ≤ RPE 8 → +5 lb next time`.
- **Sprint "GET SET" phase shows a cyan "SKIP REST" button** (`10-sprints-running`). During the 10 s get-set the label should be `GO NOW` (`btn--primary`); keep `SKIP REST` for the rest phase only. The get-set countdown itself is a good addition to the spec.
- **Collapsed rest bar has Skip twice:** bar `Skip` (44×44) and band `SKIP REST` (358×56). Keep the band button (thumb zone, 64 px) and remove the bar's `Skip`; the bar is then `2:30 · Set 2 · 255 lb × 5 · [+30 s]`, which is the spec's bar.
- **Leave sheet puts the destructive button first and full-size.** Order is Abandon (danger, 64 px, top) → Pause and leave (primary) → Cancel (ghost). §4.3.3/§11.11 want Cancel large and bottom (it is) and the destructive action not in a primary slot. Make Abandon `btn--sm` (56 px) with 16 px of space above the primary, or move it into the `⋯` sheet only (it is already there) and keep the leave sheet to Pause / Cancel.
- **Chart axis labels are 11 px** (`.chart .axis{font-size:11px}`) — below the 13 px absolute floor (§7, anti-pattern 21). Set 13 px and widen `padding.left` to 46.
- **Chart x-axis end labels spill outside the SVG** ("Sep 2", "Aug 30" measured beyond the viewBox); with `overflow:visible` they escape the card padding. Anchor the last label `end` and the first `start`.
- **Dial track is invisible:** `#2A2A32` on `#071A22` = 1.25 (dark), `#E8E8E4` on `#DDF1F8` = 1.05 (light), and on EMOM's `--bg` 1.39/1.11. Users see an arc with nothing to deplete *from*. Use `stroke:var(--line-ctrl);opacity:.45` for the track.
- **EMOM "missed" pip** (`--n-4`) vs an unfilled pip (`--line`) = 1.11:1 — the correction you tapped is not visible. Use `background:var(--danger-dim);box-shadow:inset 0 0 0 1px var(--danger)` for `.pips i.miss`.
- **Raw bodyweight dots** (`--text-3` at 50 %) on `--surface` = 2.26:1 in dark. Use opacity .7 (3.5:1).
- **Figure box inside the sheet is invisible** (`--surface-2` on `--surface-2`, `07-exercise-sheet`). `.sheet .figbox{background:var(--surface-press)}`.
- **`REST` caption is 13 px**; spec §5.8 says 15 px caps. `.restpanel > .cap{font-size:var(--fs-label)}`. Light-mode `--rest-text` on `--rest-dim` is 6.12:1, which the spec accepted only as "large" — at 15 px bold it isn't large; either accept AA here or darken light `--cyan-text` to `#055470` (7.1:1).
- **Stepper aria-labels omit step and unit:** "Decrease load" → spec "Decrease load by 5 pounds". Build the label from `opts.step`/`unit` in `ui.stepper`.
- **Focus order starts at the header** (✕ → ⋯ → thumb → …); spec §10.1 puts the header last on purpose. Either move the head after the body in DOM with `order:-1` on `.runner-head` (flex column), or accept and document.
- **One-time onboarding hints are never shown.** `flags.seenTypeHint` is unused; `seenRestHint` is set but no toast fires (§9.1: "Tap the number to type it." under the first stepper; "Rest started · tap ⌄ to adjust the next set." on first log).
- **Switch knob on the green track is 1.5:1** (dark `#F4F4F1` on `#5BE28A`). Use `--n-0` for the knob when checked, or a darker green track (`#2F9B5E`, 4.6:1 with the off-white knob).
- **Weekly volume heading shows the ISO key** "2026-W35" (wraps onto two lines). Use "this week" / "Aug 31 – Sep 6".
- **"Storage used: 44 KB · 6 sessions · save takes 0.6 ms"** — the save timing is developer telemetry; drop it from the user-facing string (or hide behind a long-press).
- **`forced-colors`:** buttons, chips, rings and steppers all get borders (good); the progress strip disappears entirely (segments have no border). Add `.progstrip .sg{border:1px solid CanvasText}` and `.progstrip .sg.on{background:Highlight}` in the forced-colors block.
- **Home mid-screen void** (`02-home`): ~330 px of nothing between the tiles and the links when there is no review/stall card. Acceptable per §11.15 ("three tiles and one card max"), but pull the three text links up under the tiles (they're navigation, not actions) so the eye doesn't fall into the gap; the band keeps the button at y≈720.

---

## Nits

- Figure alternation is 1.6 s (spec 1.2 s). `.fig.anim .f0/.f1{animation-duration:1.2s}`.
- `--fs-hero` (7rem) is defined and never used; the dial uses its own clamps.
- `CONTINUE TO MAIN LIFT 0/4` runs to one line at 390 but wraps at 360 with the count on line 2; render the count as a separate `<span>` with `margin-left:auto`.
- Rest panel `LOGGED`/`NEXT` rows sit ~250 px below the ring with a flex spacer between; moving them directly under the ring (and the spacer below them) shortens the eye path from "how long" to "what's next".
- `Skip rest` on the rest panel is `btn--ghost` (`--surface-sel`) — matches the spec; note it is the *only* neutral filled button on a cyan ground and reads slightly like a disabled state. A 1 px `--line-ctrl` border would separate it from "disabled" without changing the hierarchy.
- The `+ New` library action is a bare `+` icon (44 px) — spec wireframe shows `[+ New]` with text. Fine as an icon button since `aria-label` is set; consider the text for discoverability.
- `Set removed.` toast overlaps the set list after an undo on the superset screen (`/tmp/review/shots/superset.png`); with C2's z-index fix this becomes harmless.

---

## What I checked and found correct

Tokens & type: every colour token matches §2 exactly in both modes (verified by diffing the `:root` blocks); light block is duplicated under the media query and `[data-theme]` as required; `color-scheme: dark light` set; `system-ui` stack only, no webfonts, no raster images; tabular numerals on `.num` and slashed-zero on the dial; sizes are rem and root is never scaled below 16 px. Measured runner fonts at 390: exercise name 28/800, LAST value 22/600, load 72/800, reps 56/700, RPE chips 22/700, primary label 18/700 caps, header caption 13/600 caps `--text-2`, NEXT 18/600 — all per §7.

Contrast (dark): every ★ mid-set pairing is ≥ 9:1 (lowest: restbar next text 9.14); no `--text-3` is used for a number the user acts on; `--text-3` captions are 13–15 px only; amber button label 10.3:1; `+30` on cyan 10.2:1; danger button 5.98 (large); banner 7.5. Light: ★ pairings ≥ 7 except the two noted (REST cap 6.12, final-5-s digits 6.72 which is large text and passes AAA-large); amber buttons carry the `--primary-text` 1 px border via `--primary-border` (verified in `02-home-light`).

Layout & thumb zones: primary band is `position:sticky; bottom:0`, 358×64 at y = 768–832 (390), 704–768 (360), 856–920 (430) — inside the natural zone on all three; steppers 72 px cells (`72×90` load, `72×74` reps — taller than spec, which is fine); RPE chips 62×56 with 8 px gaps; header icon buttons 44×44; thumbnail button 44×44; LAST line is a 44 px-tall button; set-row Undo 64×44; rest buttons 171×64 with 16 px gap; collapsed-bar buttons 44×44 with 12 px gap; numpad keys 114×64; nav items 78×56 with icon + 13 px label. **No pair of distinct tappables closer than 8 px anywhere on the runner** (pairwise scan). Nav bar is hidden during a session; safe-area insets and `100dvh` used; `overscroll-behavior-y:none`; `touch-action:manipulation` on buttons; no `<input>` exists in the Day A/B/D runners (numpad only; verified `inputs in DOM: 0`).

Interaction model: prefill from last top set; suggestion rule fires correctly (seeded 250×7@8 → 255, amber value, "↑ 255 sugg." on the LAST line and "↑ suggested — Hit 7 @ RPE 8" under the stepper), `−` clears the amber; decrease never auto-applied; increments per loadType incl. kettlebell snap list; press-and-hold at 400 ms then 120 ms with acceleration; clamp nudge; RPE optional, persists to next set; undo from row, from toast, and from the rest panel — all cancel the timer and restore the draft with no confirm; timer is timestamp-based (`endAt`), persisted, recomputed on visibility, audio pre-scheduled on the audio clock; last-5-s amber digits + pulse; GO state holds 1.5 s with amber ground then returns to the runner with the next set prefilled; header advances to SET 2 OF 4 during rest; runner body is `inert` and dimmed to .4 during takeover (tab order verified: nothing in the body receives focus); collapsed bar shows time · next · +30; `⋯` sheet has skip exercise / skip block / edit rest / previous block / today's program / abandon (with second confirm); ✕ sheet pauses without losing data; resume lands on the runner; checklist Continue is never gated; block transition rests before the next block; Session RPE chips then summary with "Held all lifts / In a deficit, holding your lifts is success." and no red state.

Motion: all animation is opted in under `prefers-reduced-motion: no-preference`; transitions are transform/opacity only; no horizontal slides; press scale .97 at 80 ms; log sweep 480 ms; panel enter 320 ms emphasised; sheet 220 ms; toast 180 ms; no springs.

Accessibility: two-layer focus ring renders correctly on amber (`focus-primary.png`), cyan and neutral; `:focus-visible` only; steppers are `role=group` with labelled `−`/value/`+` buttons and a debounced (300 ms) polite live region; RPE is a `radiogroup` of `role=radio` with `aria-checked` and "program target" in the name; checklist items are `role=checkbox`; progress strip is `role=progressbar`; `role=timer` on the digits with `aria-atomic`; `#sr-status` (polite) and `#sr-alert` exist from page load; sheets are `role=dialog aria-modal` with `inert` on `#app`, focus trap, Escape and scrim close, focus restored on close; nav is `<nav aria-label="Primary">` with `aria-current="page"`; every icon button has an `aria-label`; `prefers-contrast: more` remaps `--line` and `--text-3`; `prefers-reduced-transparency` makes the nav opaque; `forced-colors` gives buttons/chips/steppers borders and the ring `CanvasText` (`forced-rest.png` is fully legible).

Anti-patterns: none of #1–5, 7–20, 22–28 are present (no `#000`/`#FFF`, no glass over content, no springs, no slides, no OS keyboard mid-session, whole-number RPE chips, no sliders, no confirms on reversible actions, persistent save-failure banner, GO held, colour never alone — "REST"/"GO"/"↑"/"slower" words present, one card max on Home, no gamification, three nutrition steppers, one-screen onboarding, single font, no nested runner scroll, `100dvh`, timestamp timer, honest iOS sound note in Settings, checklist not a gate). #6 (header-only actions) and #21 (11 px axis text; wrapped chip at 360) are the two that fail — see M8, Minor, M2.

---

## Spec compliance by section (summary)

| § | Status | Notes |
|---|---|---|
| 2 Tokens | ✓ | exact; light duplicated correctly |
| 3 Contrast | ◐ | dark ★ all pass; light amber graphics fail (M4); sheet outlines fail rule 3 (M13); chip state 1.27:1 (M2) |
| 4 Layout / thumb | ◐ | zones, sizes, 8 px rule ✓; no swipe-down hatch, no left-handed (M8); 360 chip wrap (M2) |
| 5.1 Home | ✓ | rest-day/resume variants present; links moved under tiles → band (acceptable) |
| 5.2 Main lift | ✓ | plus cue strip on set 1 (improvement) |
| 5.3 Superset | ✗ | wrong cues (C1); short-name labels (Minor) |
| 5.4 EMOM | ◐ | no DONE ✓ / cyan remainder — auto-count + MISSED is an acceptable simplification; dial off-centre (M12) |
| 5.5 Sprints | ◐ | get-set phase added (good); slower toggle ✓; stop card ✓; GET SET button label (Minor) |
| 5.6 Carry | ✓ | |
| 5.7 Checklist | ✗ | unchecked rows show ✓ (M3); no inline ▶ timer chip for timed items (not built — Minor omission) |
| 5.8 Rest | ◐ | layout ✓; digits 76 px (M1); "+30" unit; overrun counter not shown in bar (not built) |
| 5.9 Overlay | ◐ | ✓ content; outline button on sheet (M13); figure box invisible; no swap chips shown when slot has none (fine) |
| 5.10 Library | ◐ | truncation (M7) |
| 5.11 Progress | ◐ | ✓ structure; axis 11 px; Evolt overflow (M11) |
| 5.12 Body | ✓ | (scroll bug C4 affects arrival) |
| 5.13 Nutrition | ✗ | bar scaling (C3) |
| 5.14 Settings | ◐ | no left-handed, no reduce-motion override, no default-rest steppers (edit rest exists per block) |
| 6 Interaction | ✓ | all verified above; RPE deselect-on-retap is the one behaviour to change |
| 7 Glanceability | ◐ | all floors met except timer 112 px (M1) and 11 px axis |
| 8 Motion | ◐ | inventory ✓; reduced-motion ring/quarter marks not done (M9) |
| 9 States | ◐ | onboarding ✓; empty states ✓ except Evolt copy; error banner ✓; one-time hints missing |
| 10 A11y | ◐ | roles/names/live/focus ring/forced-colors ✓; rest focus + milestones (M10); 200 % (M6) |
| 11 Anti-patterns | ◐ | #6, #21 fail |
| 12 Budget | ✓ | single file, no requests, SVG figures |
