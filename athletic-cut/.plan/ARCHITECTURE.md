# Athletic Cut — Engineering Plan (ARCHITECTURE.md)

Target artifact: `/home/user/TESTES/athletic-cut/index.html` — one self-contained HTML file. All CSS and JS inline, vanilla JS (ES2020, no transpile), zero external resources, zero runtime network requests. Persistence in `localStorage` (one JSON blob, one versioned key) plus `IndexedDB` for user video clips only.

Sources of truth:
- Domain content: `bb98cea2-athleticcutprogram.md` (the "program")
- Contract: `11c5532d-trainingappspec.md` (the "spec")

Where the two disagree, the spec wins for app behaviour and the program wins for numbers (sets, reps, RPE, rest, minutes). Every deviation or gap is flagged inline with **[AMBIGUOUS]** and a recommended interpretation, and collected again in §13.

Visual approach decision: **Option B (hand-drawn two-frame inline SVG) applied consistently to every exercise**, plus written cues and the user-clip slot. Option A is rejected because the build must be a single file with no build step and no downloaded assets; Option C is rejected because it needs connectivity. This satisfies spec §2 ("Pick one of these three approaches and apply it consistently").

---

## 1. Hard requirements and acceptance criteria — implementer checklist

Tick each box only when the listed verification passes. R = requirement (spec §1–§6), AC = acceptance criterion (spec §7).

### 1.1 Technical constraints (spec §1)

- [ ] **R1** Single HTML file; all CSS and JS inline; no build step, no bundler, no npm, no CDN, no `<script src>`, no `<link rel=stylesheet>`, no `@import`, no `url(...)` pointing off-file, no web fonts.
  Verify: `grep -cE 'src="http|href="http|@import|fonts\.' index.html` → 0; Playwright request listener sees only the initial `file://` navigation.
- [ ] **R2** Mobile-first, designed for a 390px viewport; every interactive element ≥ 44×44 CSS px; primary actions in the bottom 40% of the screen (thumb zone).
  Verify: Playwright at 390×844 iterates `button, [role=button], input, select, a` and asserts `getBoundingClientRect()` width/height ≥ 44 for all visible ones; Log Set / Start Session / timer controls have `y > 0.6 * viewportHeight`.
- [ ] **R3** Works offline: no network calls at runtime after first load (see AC7).
- [ ] **R4** localStorage for all persistence; no server, no database, no auth. IndexedDB is used **only** for video blobs (spec §2 mandates it for clips). **[AMBIGUOUS]** §1 says "localStorage for all persistence" while §2 says clips go in IndexedDB; interpretation: all structured data in localStorage, opaque binary only in IndexedDB.
- [ ] **R5** Survives page refresh mid-workout: closing during set 3 of 5 and reopening restores the in-progress session exactly (same block, same slot, same set index, same stepper values, same running rest timer with correct remaining time).
- [ ] **R6** Screen does not sleep during an active session: `navigator.wakeLock.request('screen')` when a session is active, re-acquired on `visibilitychange` → visible; silently no-op where unsupported.

### 1.2 Exercise demonstration (spec §2)

- [ ] **R7** No hotlinked or downloaded copyrighted images. Every exercise visual is an inline SVG drawn for this app.
- [ ] **R8** One approach applied consistently: two-frame SVG (start/end) per exercise, toggled with a CSS animation (`@keyframes` swapping opacity of the two frames, ~1.2 s period; also a tap toggles).
- [ ] **R9** Every exercise has 3–5 imperative form cues.
- [ ] **R10** Every exercise has 1–2 common mistakes.
- [ ] **R11** Every exercise has a "Record my own clip" slot: `<input type="file" accept="video/*" capture="environment">`; the chosen file is stored as a Blob in IndexedDB; when present it is shown (`<video playsinline muted loop>` via `URL.createObjectURL`) instead of the SVG; the user can delete it.

### 1.3 Data model (spec §3)

- [ ] **R12** Entities Program, Day, Block, ExerciseSlot, Exercise, SessionLog, SetLog, BodyMetric, NutritionDay exist with at least the fields listed in the spec (§2 of this document refines them).
- [ ] **R13** Whole state stored as a single JSON blob under one versioned localStorage key, containing `schemaVersion`; a migration ladder upgrades old blobs rather than wiping.

### 1.4 Screens (spec §4)

- [ ] **R14 Home**: shows today's session or "Rest day" + next session and its date; one large **Start Session** button; three tiles: bodyweight (7-day average), waist (latest), days trained this week; small links to History and Progress.
- [ ] **R15 Session runner — layout**: top = block name + progress ("Main lift · Set 2 of 4"); middle = current exercise name + thumbnail (tap → overlay with cues, mistakes, media); "Last: 245 lb × 5 @ RPE 8" line; load and reps as large steppers (no keyboard); load defaults to last session's value; barbell ±5 lb, dumbbell ±5 lb, kettlebell snaps to real bell sizes (16/20/24/28/32 kg); RPE 6–10 one-tap selector, skippable; big **Log Set** button.
- [ ] **R16 Session runner — on Log Set**: rest timer auto-starts for the block's `restSeconds`; full-width countdown; audible + haptic cue at zero; Skip button; +30 s button; keeps running when backgrounded (wall-clock based).
- [ ] **R17 Block behaviours**: main lift = one exercise, full rest between sets; superset = A → B → rest → A → B, shows "next: B", rests only after the pair; EMOM finisher = dedicated mode (duration minutes, reps per minute, per-minute countdown, beep at top of each minute, rounds completed count); sprints = interval mode (effort duration, rest duration, effort count, "slower" marker that prompts to stop); carries = log distance + load, not reps; mobility and prep = checklist, no logging.
- [ ] **R18 End of session**: session RPE prompt (1–10), optional note (this is the one place a keyboard may appear, and it is optional), summary card with total volume and "beat last session" items.
- [ ] **R19 Exercise library**: searchable list (text input — allowed here, not part of a session); filter by pattern and equipment; detail view = media, cues, mistakes, substitutions, e1RM-over-time chart; user can add a custom exercise.
- [ ] **R20 Progress**: bodyweight chart with raw daily dots + 7-day rolling average line (average visually dominant); waist chart; e1RM per main lift over time (Epley); weekly volume by movement pattern; Evolt scan entries as discrete markers with lean mass and body-fat % plotted separately.
- [ ] **R21 Body metrics entry**: quick daily weight (one field, one tap, defaults to today); weekly waist prompt; full Evolt form: weight, lean mass, skeletal muscle mass, body fat mass, body fat %, visceral fat, BMR, TEE.
- [ ] **R22 Nutrition**: three numbers per day (calories, protein g, steps); 7-day average of each vs targets 2650 kcal / 190–200 g / 8–10k steps; "days on target this week" count. No food database, no barcode scanner.

### 1.5 Program logic (spec §5)

- [ ] **R23 Load suggestion (5.1)**: prefill last session's top-set weight; if last session's final set hit the top of the rep range at RPE ≤ 8, prefill one increment higher and flag visually; never auto-apply a decrease. **[AMBIGUOUS]** the program prescribes fixed reps ("4 x 5"), so "top of the rep range" needs a defined range → §8.3.
- [ ] **R24 Stall detection (5.2)**: no increase in e1RM across two consecutive weeks → card "<Lift> has stalled two weeks. In a deficit this usually means calories, not effort. Consider adding 200/day." Shown once per stall event, dismissible.
- [ ] **R25 Weekly review (5.3)**: every 7 days prompt with weight trend for the week, whether it's within 0.7–1.0 lb of loss, suggested calorie adjustment; loss > 1.5 lb/week for two consecutive weeks → "eat more" warning.
- [ ] **R26 Schedule (5.4)**: 4 required days order-dependent, not date-locked; marking a session complete advances the pointer; missing a day shifts everything; Day E optional and never blocks progression.

### 1.6 Non-goals (spec §6) — must NOT be present

- [ ] **R27** No accounts, social sharing, food database, video streaming, AI chat, push/system notifications, cloud sync. (`Notification`, `fetch`, `XMLHttpRequest`, `WebSocket`, `navigator.serviceWorker.register` must not appear in the JS — verify by grep.)

### 1.7 Acceptance criteria (spec §7)

- [ ] **AC1** Full Day A session start→finish with zero keyboard input (all inputs via taps: steppers, RPE chips, checklists, EMOM/timer buttons, session RPE chips; the end-of-session note is optional and skipped).
- [ ] **AC2** Close browser mid-session, reopen → exact position restored.
- [ ] **AC3** Rest timer fires audibly with the phone screen off (Web Audio pre-scheduled beep + keepalive; see §7; honest platform caveats in §12).
- [ ] **AC4** Every exercise shows a visual and written cues before the first set (runner shows thumbnail + first 3 cues inline for set 1 of any exercise, and the full overlay is one tap away).
- [ ] **AC5** Bodyweight chart shows the rolling average, not just raw points.
- [ ] **AC6** All data exportable as a JSON file and re-importable (round-trip equality of the state blob).
- [ ] **AC7** Zero network requests after initial page load.
- [ ] **AC8** Loads and is interactive in < 1 s on a mid-range phone (budget: total file ≤ 400 KB uncompressed, no synchronous IndexedDB work on the boot path, first render before IndexedDB is opened; measure `performance.now()` at first interactive paint < 300 ms in headless Chromium with 4× CPU throttle as a proxy).

### 1.8 Build-order constraint (spec §8)

- [ ] **R28** Build order followed: persistence → seed → main-lift runner → other block kinds → library/media → charts → metrics/nutrition → program logic. The file must be shippable (usable for Day A main lift) after chunk 3 of §10.

---

## 2. Data model (concrete)

### 2.1 Storage keys and versioning

```js
const LS_KEY        = 'athletic-cut:v1';          // the single JSON blob (spec §3)
const LS_BACKUP_KEY = 'athletic-cut:v1:pre-migrate'; // written once before a migration runs, overwritten on next migration
const IDB_NAME      = 'athletic-cut-media';
const IDB_VERSION   = 1;
const IDB_STORE     = 'clips';                    // keyPath: 'exerciseId'
const SCHEMA_VERSION = 1;
```

The localStorage *key* stays `athletic-cut:v1` forever; the *blob* carries `schemaVersion` and migrations run in-place. (A key rename would force old-key discovery; a version inside the blob is enough.)

### 2.2 Root state

```js
/**
 * @typedef {Object} AppState
 * @property {number}  schemaVersion              // 1
 * @property {string}  createdAt                  // ISO datetime, first boot
 * @property {string}  updatedAt                  // ISO datetime, bumped on every save
 * @property {Settings} settings
 * @property {Program} program                    // seeded; user edits allowed later
 * @property {Object<string, Exercise>} exercises // keyed by Exercise.id, seed + custom
 * @property {ScheduleState} schedule
 * @property {ActiveSession|null} activeSession   // in-progress runner state (R5)
 * @property {SessionLog[]} sessions              // completed/abandoned, append-only, newest last
 * @property {BodyMetric[]} metrics               // append-only, newest last
 * @property {Object<string, NutritionDay>} nutrition // keyed by 'YYYY-MM-DD'
 * @property {EvoltScan[]} scans
 * @property {Flags} flags                        // dismissed cards, review bookkeeping
 */

/**
 * @typedef {Object} Settings
 * @property {'lb'|'kg'} loadUnit          // default 'lb' (program is in lb)
 * @property {'lb'|'kg'} bodyweightUnit    // default 'lb'
 * @property {'in'|'cm'} waistUnit         // default 'in'
 * @property {number}   calorieTarget      // 2650
 * @property {number}   proteinMinG        // 190
 * @property {number}   proteinMaxG        // 200
 * @property {number}   stepsMin           // 8000
 * @property {number}   stepsMax           // 10000
 * @property {number}   fatFloorG          // 70 (display only)
 * @property {boolean}  sound              // true
 * @property {boolean}  vibrate            // true
 * @property {number}   restExtendSeconds  // 30
 * @property {number[]} kettlebellSizesKg  // [8,12,16,20,24,28,32,36,40,44,48]
 * @property {number}   weekStartsOn       // 1 = Monday (ISO)
 */

/**
 * @typedef {Object} ScheduleState
 * @property {string}  programStartDate   // 'YYYY-MM-DD' local, set at first boot
 * @property {number}  nextRequiredIndex  // 0..3 → index into program.requiredDayIds
 * @property {number}  cycleCount         // completed A→D cycles
 * @property {string|null} lastCompletedDate // 'YYYY-MM-DD'
 * @property {string|null} lastCompletedDayId
 */

/**
 * @typedef {Object} Flags
 * @property {Object<string,true>} dismissedStalls   // key `${exerciseId}:${isoWeekKey}`
 * @property {string|null} lastWeeklyReviewWeek      // isoWeekKey of last shown/dismissed review
 * @property {string|null} lastWaistPromptWeek       // isoWeekKey
 * @property {boolean} audioUnlocked                 // informational only (cannot persist an AudioContext)
 */
```

### 2.3 Program / Day / Block / Slot

```js
/**
 * @typedef {Object} Program
 * @property {string} id                 // 'athletic-cut'
 * @property {string} name               // 'Athletic Cut'
 * @property {number} weeks              // 12 (program says 12–14; store 12, display "12–14")
 * @property {number} weeksMax           // 14
 * @property {string} goal               // '199.7 lb → ~192 lb at ~10% scanner body fat, keeping 172 lb lean mass'
 * @property {string[]} requiredDayIds   // ['day-a','day-b','day-c','day-d']
 * @property {string}  optionalDayId     // 'day-e'
 * @property {Day[]}   days
 */

/**
 * @typedef {Object} Day
 * @property {string} id                       // 'day-a'..'day-e'
 * @property {string} letter                   // 'A'..'E'
 * @property {string} label                    // 'Day A — Lower Power + Hinge'
 * @property {'strength'|'conditioning'|'optional'} type
 * @property {string} [note]                   // e.g. Day C: "This is your conditioning day. Don't add lifting to it."
 * @property {Block[]} blocks
 * @property {DayOption[]} [options]           // Day E only: alternatives, user picks one at start
 */

/**
 * @typedef {Object} DayOption
 * @property {string} id                 // 'sport' | 'kb-complex' | 'nothing'
 * @property {string} label
 * @property {string} description
 * @property {Block[]} blocks            // blocks used when this option is chosen ([] for 'nothing')
 */

/**
 * @typedef {Object} Block
 * @property {string} id                                  // 'a-prep', 'a-main', ...
 * @property {'prep'|'main'|'superset'|'finisher'|'mobility'|'sprints'|'freeform'} kind
 *   // 'sprints' and 'freeform' extend the spec enum. [AMBIGUOUS] spec enum lists 5 kinds; Day C sprints
 *   // is neither main nor finisher in behaviour (interval mode), and Day E "Sport" has no exercises.
 *   // Recommended: add the two kinds. A stricter reading maps sprints→'main' with mode 'interval'.
 * @property {'checklist'|'straight-sets'|'rounds'|'emom'|'interval'|'carry'|'freeform'} mode
 *   // runner behaviour; decoupled from kind so a 'finisher' can be emom OR carry OR rounds
 * @property {string} label                               // 'Prep', 'Main lift', 'Superset', 'Superset A', 'Finisher', 'Mobility', 'Sprints', 'Rotational circuit'
 * @property {number} targetMinutes
 * @property {number} [rounds]                            // rounds/rounds-mode and emom (= minutes)
 * @property {number} restSeconds                         // between sets (straight-sets) or between rounds (rounds); 0 for checklist
 * @property {string} [instructions]                      // free text from the program shown under the block title
 * @property {ExerciseSlot[]} items
 * @property {ExerciseSlot[]} [alternateItems]            // Day D finisher: suitcase carry alternative
 * @property {string} [alternateLabel]                    // 'Short on time? Suitcase carry 4 × 40 m'
 * @property {Object} [emom]                              // mode 'emom': { minutes: 8, repsPerMinute: 12 }
 * @property {Object} [interval]                          // mode 'interval': { effortsMin: 8, effortsMax: 10, effortSecondsMin: 15, effortSecondsMax: 20, restSeconds: 90, stopRule: string }
 */

/**
 * @typedef {Object} RepsTarget
 * @property {'reps'|'reps-each-side'|'amrap'|'seconds'|'seconds-each-side'|'seconds-accumulated'|'meters'|'trips'|'minutes'|'sets-of'} kind
 * @property {number} [min]           // for 'reps': lower bound (the prescribed number)
 * @property {number} [max]           // upper bound used by load suggestion (§8.3)
 * @property {number} [value]         // for seconds/meters/minutes fixed values
 * @property {string} [display]       // override text, e.g. 'near failure', 'max reps'
 */

/**
 * @typedef {Object} ExerciseSlot
 * @property {string} id                       // unique in program: 'a-main-1'
 * @property {string} exerciseId
 * @property {number} sets                     // straight sets; for rounds-mode = block.rounds (duplicated for convenience)
 * @property {RepsTarget} repsTarget
 * @property {number|null} rpeTarget           // 8 for main lifts; null where unspecified
 * @property {'barbell'|'dumbbell'|'kettlebell'|'bodyweight'|'weighted-bodyweight'|'cable'|'band'|'distance'|'time'|'none'} loadType
 *   // 'cable','band','none' extend the spec enum. [AMBIGUOUS] Face pull and Pallof press are cable/band;
 *   // recommended: 'cable' steps ±5 lb; 'band' logs no load; 'none' for prep drills.
 * @property {'reps'|'distance'|'time'|'none'} logMetric   // what the Log Set button records
 * @property {number} [distanceM]              // carries: 40
 * @property {number} [seconds]                // time-based holds
 * @property {boolean} logged                  // false for prep/mobility (checklist only)
 * @property {string} [notes]
 * @property {string[]} substitutions          // exerciseIds
 */
```

### 2.4 Exercise

```js
/**
 * @typedef {Object} Exercise
 * @property {string} id
 * @property {string} name
 * @property {'hinge'|'squat'|'push-v'|'push-h'|'pull-v'|'pull-h'|'carry'|'rotation'|'anti-rotation'|'jump'|'sprint'|'mobility'|'core'} pattern
 *   // 'core' extends the spec enum. [AMBIGUOUS] hanging leg raise / TGU have no home in the spec enum;
 *   // recommended: add 'core'. Alternative: file them under 'anti-rotation'.
 * @property {string[]} equipment          // from: 'trap bar','barbell','dumbbell','kettlebell','pull-up bar','dip station','cable','band','landmine','bench','box','none','track/hill'
 * @property {string} mediaRef             // SVG symbol id, e.g. 'svg-trap-bar-deadlift' (two <g> frames inside)
 * @property {string[]} cues               // 3–5 imperatives
 * @property {string[]} mistakes           // 1–2
 * @property {string|null} userClipRef     // === id when a clip exists in IDB, else null
 * @property {boolean} isMainLift          // true → appears in Progress e1RM charts and stall detection
 * @property {boolean} isCustom            // user-added
 * @property {'lb'|'kg'} defaultLoadUnit   // 'kg' for kettlebell, else settings.loadUnit
 * @property {number} [increment]          // stepper step in defaultLoadUnit; kettlebell → snap list instead
 * @property {boolean} [unilateral]        // reps are per side
 */
```

### 2.5 Session logs

```js
/**
 * @typedef {Object} SessionLog
 * @property {string} id                        // 'ses_' + Date.now().toString(36) + random4
 * @property {string} dayId
 * @property {string} [dayOptionId]             // Day E choice
 * @property {string} startedAt                 // ISO
 * @property {string|null} completedAt
 * @property {'in-progress'|'complete'|'abandoned'} status
 * @property {SetLog[]} entries
 * @property {number|null} sessionRPE           // 1–10
 * @property {number|null} bodyweightAtTime     // latest 7-day avg at start, or null
 * @property {string} notes
 * @property {BlockResult[]} blockResults       // per-block summary for EMOM/interval/checklist blocks
 * @property {Object} totals                    // { volumeLb, setsLogged, durationSec }  computed at completion
 * @property {string[]} beatLast                // human strings, e.g. 'Trap bar deadlift e1RM 312 → 318'
 */

/**
 * @typedef {Object} SetLog
 * @property {string} blockId
 * @property {string} slotId
 * @property {string} exerciseId
 * @property {number} setIndex                  // 0-based; for rounds-mode = round index
 * @property {number|null} load                 // added load for weighted-bodyweight; 0 for bodyweight
 * @property {'lb'|'kg'} loadUnit
 * @property {number|null} reps                 // null for distance/time entries
 * @property {number|null} rpe                  // 6–10 or null (skipped)
 * @property {number|null} distanceM
 * @property {number|null} seconds
 * @property {boolean} isWarmup
 * @property {string} completedAt               // ISO
 */

/**
 * @typedef {Object} BlockResult
 * @property {string} blockId
 * @property {'checklist'|'emom'|'interval'|'freeform'} mode
 * @property {string[]} [checked]               // slot ids ticked
 * @property {number} [roundsCompleted]         // emom
 * @property {number} [roundsTarget]
 * @property {number} [load]                    // emom bell size (kg)
 * @property {number} [efforts]                 // interval: efforts completed
 * @property {number[]} [effortSeconds]         // interval: actual durations (planned value)
 * @property {number[]} [slowerFlags]           // indices of efforts marked slower
 * @property {boolean} [stoppedEarly]
 * @property {number} [minutes]                 // freeform (sport)
 * @property {string} [activity]                // freeform text (optional keyboard)
 */
```

### 2.6 Body metrics, nutrition, scans

```js
/**
 * @typedef {Object} BodyMetric
 * @property {string} id
 * @property {string} date                      // 'YYYY-MM-DD' local
 * @property {'weight'|'waist'|'bodyfatPct'|'leanMass'|'steps'} type
 * @property {number} value
 * @property {'lb'|'kg'|'in'|'cm'|'%'|'steps'} unit
 * @property {'manual'|'evolt-scan'} source
 * @property {string} [scanId]                  // when source === 'evolt-scan'
 */

/**
 * @typedef {Object} EvoltScan
 * @property {string} id
 * @property {string} date
 * @property {number} weight            // lb
 * @property {number} leanMass          // lb
 * @property {number} skeletalMuscleMass// lb
 * @property {number} bodyFatMass       // lb
 * @property {number} bodyFatPct        // %
 * @property {number} visceralFat       // level (unitless)
 * @property {number} bmr               // kcal
 * @property {number} tee               // kcal
 */
// Saving a scan ALSO appends BodyMetric rows: weight, bodyfatPct, leanMass with source 'evolt-scan' (so charts have one source).

/**
 * @typedef {Object} NutritionDay
 * @property {string} date
 * @property {number|null} calories
 * @property {number|null} proteinG
 * @property {number|null} steps
 */
```

### 2.7 ActiveSession (persisted in-progress runner state) — see §6.4 for the full shape.

### 2.8 Migration shape

```js
const MIGRATIONS = {
  // 0 → 1 : (none yet; slot reserved). Each entry mutates and returns the state.
  // 1 → 2 : function(state){ ...; state.schemaVersion = 2; return state; }
};
function migrate(raw /* parsed object or null */) {
  if (!raw || typeof raw !== 'object') return Seed.freshState();
  let s = raw;
  if (typeof s.schemaVersion !== 'number') s.schemaVersion = 0;
  if (s.schemaVersion < SCHEMA_VERSION) {
    try { localStorage.setItem(LS_BACKUP_KEY, JSON.stringify(s)); } catch (_) {}
    for (let v = s.schemaVersion; v < SCHEMA_VERSION; v++) {
      const fn = MIGRATIONS[v + 1];
      s = fn ? fn(s) : Object.assign(s, { schemaVersion: v + 1 });
    }
  }
  return Seed.reconcile(s); // adds any seed exercises/days missing by id, never overwrites user edits
}
```

`Seed.reconcile` is what lets a future release add an exercise without a schema bump: it inserts seed entries whose id is absent and leaves existing ones alone.

### 2.9 IndexedDB for clips

- DB `athletic-cut-media`, version 1, one object store `clips`, `keyPath: 'exerciseId'`, no indexes.
- Record: `{ exerciseId, blob /* File|Blob */, mimeType, size, createdAt }`.
- API (§5 Media module): `putClip(exerciseId, file)`, `getClip(exerciseId) → Blob|null`, `deleteClip(exerciseId)`, `listClipIds() → string[]`.
- On boot, `listClipIds()` runs **after** first render and sets `exercises[id].userClipRef = id` for each present id (reconciling localStorage with IDB so a cleared IDB doesn't leave dangling refs).
- Object URLs are created when a detail overlay opens and revoked when it closes.
- Export: JSON export excludes clips by default. **[AMBIGUOUS]** AC6 says "all data"; recommended: an "Include video clips (large)" toggle that base64-encodes each blob into `export.clips[exerciseId] = { mimeType, base64 }`; import restores them to IDB. Default off so a normal export stays under 1 MB.

### 2.10 Export file shape

```js
{
  "app": "athletic-cut",
  "exportedAt": "2026-09-01T12:00:00.000Z",
  "schemaVersion": 1,
  "state": { /* AppState verbatim, activeSession included */ },
  "clips": { /* optional: exerciseId → { mimeType, base64 } */ }
}
```
Import validates `app === 'athletic-cut'`, runs `migrate(state)`, then replaces the whole blob (after writing the pre-import blob to `athletic-cut:v1:pre-import`). Round-trip invariant: `JSON.stringify(migrate(exported.state)) === JSON.stringify(currentState)` when nothing changed in between.

---

## 3. Seed program data (complete, exact)

Conventions used below (apply everywhere):
- `rest 2.5 min` in the program → `restSeconds: 150`.
- `RepsTarget` shorthand: `R(5)` = `{kind:'reps', min:5, max:7}`; `RS(8)` = `{kind:'reps-each-side', min:8, max:10}`; `SEC(60)` = `{kind:'seconds', value:60}`; `SECS(30)` = `{kind:'seconds-each-side', value:30}`; `ACC(60)` = `{kind:'seconds-accumulated', value:60}`; `AMRAP('near failure')` = `{kind:'amrap', display:'near failure'}`; `TRIPS(4,40)` = `{kind:'trips', min:4, max:4, value:40}` (value = metres); `MIN(3)` = `{kind:'minutes', value:3}`; `SETSOF(2,3)` = `{kind:'sets-of', min:2, value:3}` ("2 sets of 3").
- **[AMBIGUOUS — rep ranges]** The program prescribes single numbers ("4 x 5") yet its progression rule says "when you hit the top of the rep range on all sets, add weight and drop back down", and spec 5.1 needs a "top of the rep range". Recommended rule, applied uniformly: `max = min + 2` for every rep-based slot. Display shows the prescribed number ("5") with the range as a hint ("5–7"). The stepper for reps still allows any value.
- Where the program gives no rest for a superset/circuit, `restSeconds: 60` is used, matching Day A's explicit "60s between rounds" and the header table's "minimal rest". Flagged per block.
- `targetMinutes` come from the "How every session is built" table: Prep 5, Main 12, Superset 10, Finisher 6–8 (use 7 when unspecified, 8 for the 8-min EMOM), Mobility 3. Day C prep is explicitly 8.

```js
const SEED_PROGRAM = {
  id: 'athletic-cut',
  name: 'Athletic Cut',
  weeks: 12, weeksMax: 14,
  goal: '199.7 lb → ~192 lb at ~10% scanner body fat, keeping all 172 lb of lean mass.',
  format: '4 required days, 1 optional. 35–40 min each.',
  rpeNote: 'The number in brackets is reps-in-reserve. RPE 8 means you could have done two more. Never grind to failure on the main lift during a cut.',
  requiredDayIds: ['day-a', 'day-b', 'day-c', 'day-d'],
  optionalDayId: 'day-e',
  days: [

    // ───────────────────────────── DAY A ─────────────────────────────
    {
      id: 'day-a', letter: 'A', label: 'Day A — Lower Power + Hinge', type: 'strength',
      blocks: [
        {
          id: 'a-prep', kind: 'prep', mode: 'checklist', label: 'Prep', targetMinutes: 5, restSeconds: 0,
          instructions: 'deep squat hold 60s → 90/90 rotations 8/side → glute bridge x15 → 2 sets of 3 box jumps, building height',
          items: [
            { id: 'a-prep-1', exerciseId: 'deep-squat-hold', sets: 1, repsTarget: SEC(60),    rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'a-prep-2', exerciseId: 'hip-90-90',       sets: 1, repsTarget: RS(8),      rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'a-prep-3', exerciseId: 'glute-bridge',    sets: 1, repsTarget: R(15),      rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'a-prep-4', exerciseId: 'box-jump',        sets: 2, repsTarget: SETSOF(2,3),rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [], notes: 'building height' },
          ],
        },
        {
          id: 'a-main', kind: 'main', mode: 'straight-sets', label: 'Main lift', targetMinutes: 12, restSeconds: 150,
          instructions: 'Trap bar deadlift — 4 x 5 @ RPE 8, rest 2.5 min',
          items: [
            { id: 'a-main-1', exerciseId: 'trap-bar-deadlift', sets: 4, repsTarget: R(5), rpeTarget: 8, loadType: 'barbell', logMetric: 'reps', logged: true, substitutions: [] },
          ],
        },
        {
          id: 'a-ss', kind: 'superset', mode: 'rounds', label: 'Superset', targetMinutes: 10, rounds: 3, restSeconds: 60,
          instructions: 'Superset, 3 rounds, 60s between rounds',
          items: [
            { id: 'a-ss-1', exerciseId: 'bulgarian-split-squat', sets: 3, repsTarget: RS(8),  rpeTarget: null, loadType: 'dumbbell',   logMetric: 'reps', logged: true, substitutions: [], notes: '8 each leg, dumbbells' },
            { id: 'a-ss-2', exerciseId: 'hanging-leg-raise',     sets: 3, repsTarget: R(10),  rpeTarget: null, loadType: 'bodyweight', logMetric: 'reps', logged: true, substitutions: [] },
          ],
        },
        {
          id: 'a-fin', kind: 'finisher', mode: 'emom', label: 'Finisher', targetMinutes: 8, rounds: 8, restSeconds: 0,
          instructions: 'Kettlebell swings, EMOM 8 min — 12 swings at the top of each minute, rest the remainder. Heavy bell, hips not arms.',
          emom: { minutes: 8, repsPerMinute: 12 },
          items: [
            { id: 'a-fin-1', exerciseId: 'kettlebell-swing', sets: 8, repsTarget: {kind:'reps', min:12, max:12}, rpeTarget: null, loadType: 'kettlebell', logMetric: 'reps', logged: true, substitutions: [], notes: 'Heavy bell, hips not arms.' },
          ],
        },
        {
          id: 'a-mob', kind: 'mobility', mode: 'checklist', label: 'Mobility', targetMinutes: 3, restSeconds: 0,
          instructions: '90/90 x10/side',
          items: [
            { id: 'a-mob-1', exerciseId: 'hip-90-90', sets: 1, repsTarget: RS(10), rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
          ],
        },
      ],
    },

    // ───────────────────────────── DAY B ─────────────────────────────
    {
      id: 'day-b', letter: 'B', label: 'Day B — Upper Strength', type: 'strength',
      blocks: [
        {
          id: 'b-prep', kind: 'prep', mode: 'checklist', label: 'Prep', targetMinutes: 5, restSeconds: 0,
          instructions: 'dead hang 30s → band pull-apart x20 → thoracic rotations 10/side → push-ups x10',
          items: [
            { id: 'b-prep-1', exerciseId: 'dead-hang',         sets: 1, repsTarget: SEC(30), rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'b-prep-2', exerciseId: 'band-pull-apart',   sets: 1, repsTarget: R(20),   rpeTarget: null, loadType: 'band',       logMetric: 'none', logged: false, substitutions: [] },
            { id: 'b-prep-3', exerciseId: 'thoracic-rotation', sets: 1, repsTarget: RS(10),  rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'b-prep-4', exerciseId: 'push-up',           sets: 1, repsTarget: R(10),   rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
          ],
        },
        {
          id: 'b-main', kind: 'main', mode: 'straight-sets', label: 'Main lift', targetMinutes: 12, restSeconds: 150,
          instructions: 'Weighted pull-up — 4 x 5 @ RPE 8, rest 2.5 min. If you can\'t do 5 weighted, do 4 sets of max bodyweight reps and add weight when you clear 10.',
          items: [
            { id: 'b-main-1', exerciseId: 'weighted-pull-up', sets: 4, repsTarget: R(5), rpeTarget: 8, loadType: 'weighted-bodyweight', logMetric: 'reps', logged: true,
              substitutions: ['pull-up'],
              notes: 'Can\'t do 5 weighted? Switch to Pull-up (bodyweight): 4 sets of max reps; add weight when you clear 10.' },
          ],
          // Substitution 'pull-up' slot override (applied when user taps the substitution):
          // { exerciseId:'pull-up', sets:4, repsTarget:{kind:'amrap', display:'max reps'}, rpeTarget:null, loadType:'bodyweight', logMetric:'reps' }
        },
        {
          id: 'b-ss-a', kind: 'superset', mode: 'rounds', label: 'Superset A', targetMinutes: 6, rounds: 3, restSeconds: 60, // [AMBIGUOUS] rest not stated → 60s
          instructions: 'Superset A, 3 rounds',
          items: [
            { id: 'b-ss-a-1', exerciseId: 'overhead-press',      sets: 3, repsTarget: R(6),  rpeTarget: null, loadType: 'barbell',  logMetric: 'reps', logged: true, substitutions: [] },
            { id: 'b-ss-a-2', exerciseId: 'chest-supported-row', sets: 3, repsTarget: R(10), rpeTarget: null, loadType: 'dumbbell', logMetric: 'reps', logged: true, substitutions: [] },
          ],
        },
        {
          id: 'b-ss-b', kind: 'superset', mode: 'rounds', label: 'Superset B', targetMinutes: 4, rounds: 2, restSeconds: 60, // [AMBIGUOUS] rest not stated → 60s
          instructions: 'Superset B, 2 rounds',
          items: [
            { id: 'b-ss-b-1', exerciseId: 'dip',       sets: 2, repsTarget: AMRAP('near failure'), rpeTarget: null, loadType: 'weighted-bodyweight', logMetric: 'reps', logged: true, substitutions: [], notes: 'near failure, weighted if you clear 12' },
            { id: 'b-ss-b-2', exerciseId: 'face-pull', sets: 2, repsTarget: R(15),                 rpeTarget: null, loadType: 'cable',               logMetric: 'reps', logged: true, substitutions: [] },
          ],
        },
        {
          id: 'b-fin', kind: 'finisher', mode: 'carry', label: 'Finisher', targetMinutes: 7, restSeconds: 60, // [AMBIGUOUS] rest between trips not stated → 60s
          instructions: 'Farmer\'s carry — 4 trips x 40 m, heaviest you can hold without the grip failing early',
          items: [
            { id: 'b-fin-1', exerciseId: 'farmers-carry', sets: 4, repsTarget: TRIPS(4,40), rpeTarget: null, loadType: 'dumbbell', logMetric: 'distance', distanceM: 40, logged: true, substitutions: [], notes: 'heaviest you can hold without the grip failing early' },
          ],
        },
        {
          id: 'b-mob', kind: 'mobility', mode: 'checklist', label: 'Mobility', targetMinutes: 3, restSeconds: 0,
          instructions: 'dead hang 60s accumulated',
          items: [
            { id: 'b-mob-1', exerciseId: 'dead-hang', sets: 1, repsTarget: ACC(60), rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
          ],
        },
      ],
    },

    // ───────────────────────────── DAY C ─────────────────────────────
    {
      id: 'day-c', letter: 'C', label: 'Day C — Sprint + Rotation', type: 'conditioning',
      note: 'This is your conditioning day. Don\'t add lifting to it.',
      blocks: [
        {
          id: 'c-prep', kind: 'prep', mode: 'checklist', label: 'Prep', targetMinutes: 8, restSeconds: 0,
          instructions: 'jog 3 min → leg swings → A-skips 2 x 20 m → build-up runs 3 x 40 m at 60%, 75%, 85%',
          items: [
            { id: 'c-prep-1', exerciseId: 'jog',          sets: 1, repsTarget: MIN(3),                                   rpeTarget: null, loadType: 'none', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'c-prep-2', exerciseId: 'leg-swing',    sets: 1, repsTarget: {kind:'reps-each-side', min:10, max:10, display:'10/side, front-back and side-to-side'}, rpeTarget: null, loadType: 'none', logMetric: 'none', logged: false, substitutions: [] }, // [AMBIGUOUS] no count in program; 10/side recommended
            { id: 'c-prep-3', exerciseId: 'a-skip',       sets: 2, repsTarget: {kind:'meters', min:2, value:20, display:'2 × 20 m'}, rpeTarget: null, loadType: 'none', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'c-prep-4', exerciseId: 'build-up-run', sets: 3, repsTarget: {kind:'meters', min:3, value:40, display:'3 × 40 m at 60%, 75%, 85%'}, rpeTarget: null, loadType: 'none', logMetric: 'none', logged: false, substitutions: [] },
          ],
        },
        {
          id: 'c-sprints', kind: 'sprints', mode: 'interval', label: 'Sprints', targetMinutes: 16, restSeconds: 90,
          instructions: '8-10 efforts of 15-20 seconds. Hill if you have one, flat track otherwise. Walk back and take a full 90 seconds between. If the last two are noticeably slower than the first two, stop. This is quality work, not conditioning-by-exhaustion.',
          interval: { effortsMin: 8, effortsMax: 10, effortSecondsMin: 15, effortSecondsMax: 20, effortSecondsDefault: 20, restSeconds: 90,
                      stopRule: 'If the last two are noticeably slower than the first two, stop.' },
          items: [
            { id: 'c-sprints-1', exerciseId: 'sprint', sets: 8, repsTarget: {kind:'seconds', value:20, display:'8–10 × 15–20 s'}, rpeTarget: null, loadType: 'none', logMetric: 'time', logged: true, substitutions: [] },
          ],
        },
        {
          id: 'c-rot', kind: 'superset', mode: 'rounds', label: 'Rotational circuit', targetMinutes: 8, rounds: 2, restSeconds: 60, // [AMBIGUOUS] rest not stated → 60s
          instructions: 'Rotational circuit, 2 rounds',
          items: [
            { id: 'c-rot-1', exerciseId: 'landmine-rotation', sets: 2, repsTarget: RS(8),    rpeTarget: null, loadType: 'barbell',    logMetric: 'reps', logged: true, substitutions: [] },
            { id: 'c-rot-2', exerciseId: 'pallof-press',      sets: 2, repsTarget: RS(12),   rpeTarget: null, loadType: 'cable',      logMetric: 'reps', logged: true, substitutions: [] },
            { id: 'c-rot-3', exerciseId: 'side-plank',        sets: 2, repsTarget: SECS(30), rpeTarget: null, loadType: 'bodyweight', logMetric: 'time', seconds: 30, logged: true, substitutions: [] },
          ],
        },
        {
          id: 'c-mob', kind: 'mobility', mode: 'checklist', label: 'Mobility', targetMinutes: 3, restSeconds: 0,
          instructions: 'deep squat hold 2 min accumulated',
          items: [
            { id: 'c-mob-1', exerciseId: 'deep-squat-hold', sets: 1, repsTarget: ACC(120), rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
          ],
        },
      ],
    },

    // ───────────────────────────── DAY D ─────────────────────────────
    {
      id: 'day-d', letter: 'D', label: 'Day D — Squat + Press + Kettlebell', type: 'strength',
      blocks: [
        {
          id: 'd-prep', kind: 'prep', mode: 'checklist', label: 'Prep', targetMinutes: 5, restSeconds: 0,
          instructions: '90/90 x8/side → goblet squat x10 → scap push-ups x10',
          items: [
            { id: 'd-prep-1', exerciseId: 'hip-90-90',     sets: 1, repsTarget: RS(8), rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'd-prep-2', exerciseId: 'goblet-squat',  sets: 1, repsTarget: R(10), rpeTarget: null, loadType: 'kettlebell', logMetric: 'none', logged: false, substitutions: [] },
            { id: 'd-prep-3', exerciseId: 'scap-push-up',  sets: 1, repsTarget: R(10), rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
          ],
        },
        {
          id: 'd-main', kind: 'main', mode: 'straight-sets', label: 'Main lift', targetMinutes: 12, restSeconds: 150,
          instructions: 'Front squat — 4 x 6 @ RPE 8, rest 2.5 min. Goblet or safety-bar squat if front rack mobility is limiting you.',
          items: [
            { id: 'd-main-1', exerciseId: 'front-squat', sets: 4, repsTarget: R(6), rpeTarget: 8, loadType: 'barbell', logMetric: 'reps', logged: true,
              substitutions: ['goblet-squat', 'safety-bar-squat'],
              notes: 'Goblet or safety-bar squat if front rack mobility is limiting you.' },
          ],
        },
        {
          id: 'd-ss', kind: 'superset', mode: 'rounds', label: 'Superset', targetMinutes: 10, rounds: 3, restSeconds: 60, // [AMBIGUOUS] rest not stated → 60s
          instructions: 'Superset, 3 rounds',
          items: [
            { id: 'd-ss-1', exerciseId: 'dumbbell-bench-press',    sets: 3, repsTarget: R(8),   rpeTarget: null, loadType: 'dumbbell', logMetric: 'reps', logged: true, substitutions: [] },
            { id: 'd-ss-2', exerciseId: 'single-arm-dumbbell-row', sets: 3, repsTarget: RS(10), rpeTarget: null, loadType: 'dumbbell', logMetric: 'reps', logged: true, substitutions: [] },
          ],
        },
        {
          id: 'd-fin', kind: 'finisher', mode: 'straight-sets', label: 'Finisher', targetMinutes: 7, restSeconds: 60, // [AMBIGUOUS] rest not stated → 60s
          instructions: 'Turkish get-up — 3 x 2 each side, slow, moderate bell. Or suitcase carry 4 x 40 m if you\'re short on time.',
          items: [
            { id: 'd-fin-1', exerciseId: 'turkish-get-up', sets: 3, repsTarget: {kind:'reps-each-side', min:2, max:2}, rpeTarget: null, loadType: 'kettlebell', logMetric: 'reps', logged: true, substitutions: ['suitcase-carry'], notes: 'slow, moderate bell' },
          ],
          alternateLabel: 'Short on time? Suitcase carry 4 × 40 m',
          alternateMode: 'carry',
          alternateItems: [
            { id: 'd-fin-alt-1', exerciseId: 'suitcase-carry', sets: 4, repsTarget: TRIPS(4,40), rpeTarget: null, loadType: 'kettlebell', logMetric: 'distance', distanceM: 40, logged: true, substitutions: [] },
          ],
        },
        {
          id: 'd-mob', kind: 'mobility', mode: 'checklist', label: 'Mobility', targetMinutes: 3, restSeconds: 0,
          instructions: 'thoracic rotations 10/side',
          items: [
            { id: 'd-mob-1', exerciseId: 'thoracic-rotation', sets: 1, repsTarget: RS(10), rpeTarget: null, loadType: 'bodyweight', logMetric: 'none', logged: false, substitutions: [] },
          ],
        },
      ],
    },

    // ───────────────────────────── DAY E ─────────────────────────────
    {
      id: 'day-e', letter: 'E', label: 'Day E — Optional', type: 'optional',
      note: 'Pick one.',
      blocks: [], // resolved from the chosen option at session start
      options: [
        {
          id: 'sport', label: 'Sport',
          description: 'Climbing, boxing, jiu-jitsu, pickup soccer. Best option. Trains reaction and coordination nothing on this list does.',
          blocks: [
            { id: 'e-sport', kind: 'freeform', mode: 'freeform', label: 'Sport', targetMinutes: 40, restSeconds: 0,
              instructions: 'Log the activity and how long you played.',
              items: [] },
          ],
        },
        {
          id: 'kb-complex', label: 'Kettlebell complex',
          description: '20 min. Per round: 5 cleans, 5 presses, 5 front squats, 10 swings — each side. Rest 90s. 5-6 rounds.',
          blocks: [
            { id: 'e-kb', kind: 'superset', mode: 'rounds', label: 'Kettlebell complex', targetMinutes: 20, rounds: 5, roundsMax: 6, restSeconds: 90,
              instructions: 'Per round: 5 cleans, 5 presses, 5 front squats, 10 swings — each side. Rest 90s. 5-6 rounds.',
              sharedLoad: true, // one bell for the whole round; load stepper shown once per round
              items: [
                { id: 'e-kb-1', exerciseId: 'kettlebell-clean',       sets: 5, repsTarget: {kind:'reps-each-side', min:5,  max:5},  rpeTarget: null, loadType: 'kettlebell', logMetric: 'reps', logged: true, substitutions: [] },
                { id: 'e-kb-2', exerciseId: 'kettlebell-press',       sets: 5, repsTarget: {kind:'reps-each-side', min:5,  max:5},  rpeTarget: null, loadType: 'kettlebell', logMetric: 'reps', logged: true, substitutions: [] },
                { id: 'e-kb-3', exerciseId: 'kettlebell-front-squat', sets: 5, repsTarget: {kind:'reps-each-side', min:5,  max:5},  rpeTarget: null, loadType: 'kettlebell', logMetric: 'reps', logged: true, substitutions: [] },
                { id: 'e-kb-4', exerciseId: 'kettlebell-swing',       sets: 5, repsTarget: {kind:'reps-each-side', min:10, max:10}, rpeTarget: null, loadType: 'kettlebell', logMetric: 'reps', logged: true, substitutions: [], notes: 'one-arm swings, 10 each side' },
              ] },
          ],
        },
        {
          id: 'nothing', label: 'Nothing',
          description: 'If the four days are hard and you\'re in a deficit, a fifth day is optional for a reason.',
          blocks: [],
        },
      ],
    },
  ],
};
```

### 3.1 Cross-check table (program line → seed value)

| Program text | Seed field | Value |
|---|---|---|
| Day A prep: deep squat hold 60s | a-prep-1 | SEC(60) |
| 90/90 rotations 8/side | a-prep-2 | RS(8) |
| glute bridge x15 | a-prep-3 | R(15) |
| 2 sets of 3 box jumps | a-prep-4 | sets 2, SETSOF(2,3) |
| Trap bar deadlift 4 x 5 @ RPE 8, rest 2.5 min | a-main-1 | sets 4, R(5), rpe 8, restSeconds 150 |
| Superset 3 rounds, 60s | a-ss | rounds 3, restSeconds 60 |
| BSS 8 each leg | a-ss-1 | RS(8), dumbbell |
| Hanging leg raise 10 | a-ss-2 | R(10) |
| KB swings EMOM 8 min, 12/min | a-fin | emom {8, 12}, rounds 8 |
| 90/90 x10/side | a-mob-1 | RS(10) |
| Day B prep: dead hang 30s | b-prep-1 | SEC(30) |
| band pull-apart x20 | b-prep-2 | R(20) |
| thoracic rotations 10/side | b-prep-3 | RS(10) |
| push-ups x10 | b-prep-4 | R(10) |
| Weighted pull-up 4 x 5 @ RPE 8, rest 2.5 | b-main-1 | sets 4, R(5), rpe 8, rest 150, weighted-bodyweight |
| fallback: 4 sets max BW reps, add weight when clear 10 | b-main-1.substitutions | 'pull-up' AMRAP, threshold 10 |
| Superset A 3 rounds: OHP 6 / CS row 10 | b-ss-a | rounds 3; R(6); R(10) |
| Superset B 2 rounds: dips near failure / face pull 15 | b-ss-b | rounds 2; AMRAP; R(15) |
| Farmer's carry 4 trips x 40 m | b-fin-1 | sets 4, TRIPS(4,40), distanceM 40 |
| dead hang 60s accumulated | b-mob-1 | ACC(60) |
| Day C prep 8 min: jog 3 min | c-prep, c-prep-1 | targetMinutes 8; MIN(3) |
| A-skips 2 x 20 m | c-prep-3 | sets 2, 20 m |
| build-up runs 3 x 40 m at 60/75/85% | c-prep-4 | sets 3, 40 m |
| Sprints 8-10 × 15-20 s, 90 s between | c-sprints.interval | 8/10, 15/20, rest 90 |
| Rotational circuit 2 rounds | c-rot | rounds 2 |
| Landmine rotation 8 each side | c-rot-1 | RS(8) |
| Pallof press 12 each side | c-rot-2 | RS(12) |
| Side plank 30s each side | c-rot-3 | SECS(30) |
| deep squat hold 2 min accumulated | c-mob-1 | ACC(120) |
| Day D prep: 90/90 x8/side, goblet x10, scap push-ups x10 | d-prep-1..3 | RS(8), R(10), R(10) |
| Front squat 4 x 6 @ RPE 8, rest 2.5 | d-main-1 | sets 4, R(6), rpe 8, rest 150 |
| subs: goblet / safety-bar | d-main-1.substitutions | ['goblet-squat','safety-bar-squat'] |
| Superset 3 rounds: DB bench 8 / 1-arm row 10 each side | d-ss | rounds 3; R(8); RS(10) |
| TGU 3 x 2 each side | d-fin-1 | sets 3, reps-each-side 2 |
| or suitcase carry 4 x 40 m | d-fin.alternateItems | TRIPS(4,40) |
| thoracic rotations 10/side | d-mob-1 | RS(10) |
| Day E KB complex: 5/5/5/10 each side, rest 90 s, 5-6 rounds, 20 min | e-kb | rounds 5 (max 6), rest 90, 20 min |

Program-level constants that live in `Settings` (not the program object): 2650 kcal start; protein 190–200 g; fat 70–80 g floor; carbs ≈290 g at 2650; steps 8,000–10,000; weight loss target 0.7–1.0 lb/week; "nothing moving after 2 weeks → drop 300"; "can't progress for two straight weeks → add 200"; waist target 32.3 in → 30.5–31 in; Evolt scan every 6–8 weeks.

---

## 4. Exercise library (every referenced movement)

38 distinct exercises are referenced once prep, mobility, substitutions and the Day E complex are counted (the spec's "roughly 25" undercounts prep drills). All are seeded with `isCustom:false`, `userClipRef:null`. `mediaRef` is always `'svg-' + id`. Increment: barbell 5 lb, dumbbell 5 lb (per hand), weighted-bodyweight 2.5 lb **[AMBIGUOUS — not in spec; 2.5 lb plate on a belt is the smallest common jump; recommended]**, cable 5 lb, kettlebell → snap list. SVG frames are drawn in a 120×120 viewBox with a 6-segment stick figure (head circle r=8, torso, upper/lower arm, thigh/shin), 3 px stroke, `currentColor`; equipment in a second accent colour. Each symbol contains `<g class="f0">` (start) and `<g class="f1">` (end).

| # | id | name | pattern | equipment | cues (imperative) | mistakes | SVG frame 0 → frame 1 | isMain | unit/inc |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `deep-squat-hold` | Deep squat hold | mobility | none | Sink hips below knees; Push knees out with elbows; Keep heels down; Chest tall, breathe slow | Heels lifting off the floor; Rounding the low back to get depth | Standing figure, feet shoulder-width → figure in full squat, elbows inside knees, torso upright | no | — |
| 2 | `hip-90-90` | 90/90 hip rotation | mobility | none | Sit tall on both sit bones; Rotate knees together side to side; Keep feet on the floor; Lead with the hips, not the shoulders | Leaning back and using the hands to swing the legs; Letting the trailing knee lift high off the floor | Seated figure, front shin at 90°, rear shin at 90° to the right → same figure with knees swept to the left | no | — |
| 3 | `glute-bridge` | Glute bridge | hinge | none | Drive through heels; Squeeze glutes at the top; Ribs down, don't arch; Pause one second at the top | Pushing with the low back instead of the glutes (over-arching); Knees caving inward | Figure lying supine, knees bent, hips on floor → hips lifted so knee–hip–shoulder form a straight line | no | — |
| 4 | `box-jump` | Box jump | jump | box | Swing arms back then up; Land soft, knees over toes; Stick the landing then step down; Add height only if the landing stays quiet | Landing in a deep squat to fake height; Jumping down off the box | Figure in quarter squat beside a box, arms back → figure standing on the box, knees slightly bent, arms forward | no | — |
| 5 | `trap-bar-deadlift` | Trap bar deadlift | hinge | trap bar | Push the floor away; Ribs down, brace before you pull; Hips and shoulders rise together; Lock out tall, don't lean back | Hips shooting up first so it turns into a stiff-leg pull; Yanking the bar off the floor without tension | Figure inside a hexagonal bar, hips back, flat back, hands at sides → figure standing tall, bar at hip height | yes | lb / 5 |
| 6 | `bulgarian-split-squat` | Bulgarian split squat | squat | dumbbell, bench | Front shin vertical; Drop the back knee straight down; Torso slightly forward, weight in the front heel; Drive up through the front foot | Front foot too close to the bench so the knee shoots forward; Bouncing off the back leg | Figure standing, rear foot on a bench, dumbbells at sides → front knee ~90°, rear knee near floor | no | lb / 5 |
| 7 | `hanging-leg-raise` | Hanging leg raise | core | pull-up bar | Pull the ribs down first; Raise legs with control, don't swing; Lift until the hips curl up; Lower slow, stop before the swing starts | Swinging with momentum; Only lifting the legs without posterior pelvic tilt | Figure hanging from a bar, legs straight down → legs raised to horizontal, pelvis tucked | no | — |
| 8 | `kettlebell-swing` | Kettlebell swing | hinge | kettlebell | Hinge, don't squat; Hike the bell back high between the thighs; Snap the hips, squeeze glutes at the top; Arms are ropes, bell floats to chest height | Lifting the bell with the arms and shoulders; Squatting the swing (knees forward, hips down) | Figure hinged, bell behind the knees → figure standing tall, bell at chest height, arms straight | no | kg / snap |
| 9 | `dead-hang` | Dead hang | mobility | pull-up bar | Full grip, thumbs around the bar; Let the shoulders rise to the ears; Legs loose, breathe; Build time before adding anything else | Shrugging tension into the neck; Dropping off instead of a controlled release | Figure standing under the bar reaching up → figure hanging fully extended, feet off the floor | no | — |
| 10 | `band-pull-apart` | Band pull-apart | pull-h | band | Arms straight at shoulder height; Pull the band to the chest by squeezing the shoulder blades; Ribs down; Control the return | Shrugging the shoulders up; Bending the elbows to make it a row | Figure with arms straight forward holding a band → arms spread wide, band across the chest | no | — |
| 11 | `thoracic-rotation` | Thoracic rotation | mobility | none | Quadruped, hand behind head; Rotate the elbow toward the ceiling; Follow the elbow with your eyes; Keep hips square and still | Rotating from the low back instead of the upper back; Rushing the reps | Quadruped figure, one hand behind head, elbow pointing down → elbow rotated up toward ceiling, chest open | no | — |
| 12 | `push-up` | Push-up | push-h | none | Hands under shoulders; Body one straight line, glutes tight; Elbows ~45° from the body; Chest to the floor, then push the floor away | Hips sagging or piking; Flaring elbows to 90° | Figure in top plank → figure with chest near the floor, elbows bent | no | — |
| 13 | `weighted-pull-up` | Weighted pull-up | pull-v | pull-up bar, belt | Start from a dead hang; Pull elbows down to the ribs; Chin over the bar without craning; Lower under control to full extension | Kipping or swinging; Cutting the range short at the bottom | Figure hanging with a plate on a belt → chin over the bar, elbows down | yes | lb / 2.5 (added) |
| 14 | `pull-up` | Pull-up (bodyweight) | pull-v | pull-up bar | Dead hang start; Drive elbows down and back; Chin clears the bar; Full extension every rep | Kipping; Half reps from the top | Same as 13 without the plate | yes | — |
| 15 | `overhead-press` | Standing overhead press | push-v | barbell | Bar on the collarbone, forearms vertical; Brace glutes and abs; Push the head through once the bar clears the face; Lock out with biceps by the ears | Leaning back and pressing into an arch; Bar drifting forward away from the face | Figure standing, bar at collarbone → bar locked out overhead, head through | no | lb / 5 |
| 16 | `chest-supported-row` | Chest-supported row | pull-h | dumbbell, bench | Chest glued to the pad; Pull elbows toward the hips; Squeeze the shoulder blades at the top; Lower slow to a full stretch | Lifting the chest off the pad to heave; Shrugging to the ears | Figure prone on an incline bench, dumbbells hanging → dumbbells rowed to the hips, shoulder blades together | no | lb / 5 |
| 17 | `dip` | Dips | push-v | dip station | Lean forward slightly, shoulders down; Lower until the upper arm is parallel; Elbows track back, not out; Press to a full lockout | Dropping too deep and letting the shoulders roll forward; Flaring elbows | Figure supported on parallel bars, arms locked → elbows bent, torso lowered | no | lb / 2.5 (added) |
| 18 | `face-pull` | Face pull | pull-h | cable, rope | Pull the rope to the forehead; Elbows high and wide; Finish with a double-biceps pose; Light weight, slow reps | Pulling with the low back leaning away; Elbows dropping below the shoulders | Figure standing, rope cable extended forward → rope at the face, elbows high, hands apart | no | lb / 5 |
| 19 | `farmers-carry` | Farmer's carry | carry | dumbbell, trap bar, kettlebell | Stand tall, shoulders down and back; Crush the handles; Short quick steps; Breathe behind the brace | Leaning to one side or shrugging; Walking with long strides that swing the load | Figure standing with heavy handles at sides → same figure mid-stride, motion lines | no | lb / 5 per hand |
| 20 | `jog` | Easy jog | sprint | track/hill | Conversational pace; Relaxed shoulders and hands; Quick light feet; Three minutes, then stop | Starting too fast; Heavy heel striking | Figure jogging, low knee lift → same, opposite leg forward | no | — |
| 21 | `leg-swing` | Leg swing | mobility | none | Hold a wall or post; Swing the leg from the hip, torso still; Front-to-back then side-to-side; Increase range each rep | Arching the back to gain height; Swinging with momentum from the trunk | Figure holding a post, leg swung forward → leg swung back | no | — |
| 22 | `a-skip` | A-skip | sprint | track/hill | Drive the knee to hip height; Toe up, foot under the knee; Strike the ground under the hips; Quick arm swing, tall posture | Leaning back; Reaching the foot out in front | Figure skipping, one knee high, opposite arm forward → legs switched | no | — |
| 23 | `build-up-run` | Build-up run | sprint | track/hill | Start easy, add speed every 10 m; Stay tall and relaxed; Hit the target percentage in the last third; Coast down, don't stop dead | Reaching top speed immediately; Tensing the face and shoulders | Figure running at moderate lean → figure at near-sprint stride with a speed arrow | no | — |
| 24 | `sprint` | Sprint | sprint | track/hill | Drive out of the start with a forward lean; Punch the knees forward and down; Arms cheek-to-pocket; Relax the jaw and hands; Stop when quality drops | Over-striding with the foot landing far ahead of the hips; Running efforts to exhaustion | Figure in a drive-phase lean, one knee high → upright full-speed stride | no | — |
| 25 | `landmine-rotation` | Landmine rotation | rotation | landmine, barbell | Arms straight, bar at chest height; Rotate hips and shoulders together; Pivot the rear foot; Control the arc, no jerking | Rotating only with the arms; Bending the elbows to make it a press | Figure holding the end of a landmine bar to the left, hips rotated → bar arced to the right, rear foot pivoted | no | lb / 5 |
| 26 | `pallof-press` | Pallof press | anti-rotation | cable, band | Stand side-on to the anchor; Press the handle straight out from the chest; Hold two seconds, resist the pull; Ribs down, hips square | Letting the torso twist toward the anchor; Pressing high or low instead of straight | Figure side-on with handle at chest → arms extended straight ahead, torso unchanged | no | lb / 5 |
| 27 | `side-plank` | Side plank | anti-rotation | none | Elbow under the shoulder; Push the hip up and forward; Stack feet or stagger them; Squeeze the bottom glute | Hips sagging toward the floor; Rolling the shoulder forward | Figure lying on its side, hip on the floor → hips lifted into a straight line | no | — |
| 28 | `goblet-squat` | Goblet squat | squat | kettlebell, dumbbell | Hold the bell against the chest; Elbows inside the knees at the bottom; Push the knees out; Stand up through the whole foot | Rounding as the elbows drop; Rising onto the toes | Figure standing, bell held at chest → full squat with elbows between knees | no | kg / snap |
| 29 | `scap-push-up` | Scap push-up | push-h | none | Arms locked straight the whole time; Let the chest sink between the shoulder blades; Push the floor away to spread the blades; Small range, slow | Bending the elbows into a normal push-up; Sagging the hips | Top plank with shoulder blades pinched, chest low → shoulder blades spread, upper back rounded high | no | — |
| 30 | `front-squat` | Front squat | squat | barbell, rack | Elbows high, bar on the shoulders not the wrists; Big breath, brace; Sit straight down, knees forward and out; Drive the elbows up out of the hole | Elbows dropping so the bar rolls forward; Letting the chest fall onto the thighs | Figure standing with bar racked on the front delts, elbows up → full-depth squat, torso upright, elbows still up | yes | lb / 5 |
| 31 | `safety-bar-squat` | Safety-bar squat | squat | barbell (SSB), rack | Hands on the handles, chest up; Brace before every rep; Sit down between the heels; Drive the upper back into the bar out of the hole | Folding forward as the bar pulls you over; Cutting depth | Figure with a cambered yoke bar on the shoulders, standing → full-depth squat | yes | lb / 5 |
| 32 | `dumbbell-bench-press` | Dumbbell bench press | push-h | dumbbell, bench | Feet planted, shoulder blades tucked; Lower to the sides of the chest; Elbows ~45° from the body; Press up and slightly in | Letting the shoulders roll forward at the top; Bouncing the dumbbells off the chest | Figure supine on a bench, dumbbells at chest level → arms extended above the chest | no | lb / 5 |
| 33 | `single-arm-dumbbell-row` | Single-arm dumbbell row | pull-h | dumbbell, bench | Hand and knee on the bench, back flat; Pull the elbow to the hip; Keep the hips square, don't twist; Full stretch at the bottom | Rotating the torso to heave the weight up; Shrugging instead of pulling with the back | Figure braced on a bench, dumbbell hanging → dumbbell rowed to the hip | no | lb / 5 |
| 34 | `turkish-get-up` | Turkish get-up | core | kettlebell | Eyes on the bell until you're standing; Roll to the elbow, then the hand; Sweep the leg through, then stand tall; Reverse every step just as slowly | Rushing the transitions; Letting the elbow bend and the bell drift | Figure lying supine, one arm locked out with the bell overhead → figure standing tall, bell still overhead | no | kg / snap |
| 35 | `suitcase-carry` | Suitcase carry | carry | kettlebell, dumbbell | Load in one hand, stand perfectly upright; Don't let the hip lean into the load; Ribs down, opposite obliques tight; Switch hands halfway or each trip | Leaning away from the bell; Shrugging the loaded shoulder | Figure standing with a bell in one hand → same figure mid-stride, torso vertical | no | kg / snap |
| 36 | `kettlebell-clean` | Kettlebell clean | hinge | kettlebell | Hike the bell back like a swing; Snap the hips, keep the elbow tight; Let the bell roll around the wrist, not flop onto it; Catch in the rack with the forearm vertical | Casting the bell in a wide arc so it bangs the forearm; Pulling with the arm | Figure hinged, bell between the legs → figure standing, bell racked at the shoulder | no | kg / snap |
| 37 | `kettlebell-press` | Kettlebell press | push-v | kettlebell | Rack position, wrist straight; Brace glutes and abs; Press up and slightly out, biceps by the ear; Pull it back down into the rack | Leaning sideways to press; Bending the wrist back | Figure with bell racked at the shoulder → bell locked out overhead | no | kg / snap |
| 38 | `kettlebell-front-squat` | Kettlebell front squat | squat | kettlebell | Bell racked, elbow in; Sit down between the heels; Knees out, chest tall; Stand tall and re-brace | Twisting toward the loaded side; Letting the elbow flare so the bell pulls you forward | Figure standing with bell racked → full-depth squat, bell still racked | no | kg / snap |

Notes:
- `isMain: true` is set on trap-bar-deadlift, weighted-pull-up, pull-up, front-squat, safety-bar-squat (goblet-squat is a listed substitution but is kept `isMain:false` because Progress charts an e1RM per *main lift*; when a sub is logged inside a main block it still counts toward that block's exercise history under the sub's own id — see §8.3 fallback).
- Progress e1RM charts are grouped by **main-lift family**: `{ 'trap-bar-deadlift': ['trap-bar-deadlift'], 'pull-up': ['weighted-pull-up','pull-up'], 'front-squat': ['front-squat','safety-bar-squat','goblet-squat'] }`. Each family chart plots each member id as its own series so a substitution is visible, not merged.
- Custom exercises: user supplies name, pattern, equipment (chips), optional cues/mistakes (keyboard allowed here — not part of a session). Custom exercises get `mediaRef:'svg-generic-' + pattern` (one generic two-frame figure per pattern, 13 total) so AC4 still holds.

---

## 5. Module decomposition (single file, ordered)

File layout, top to bottom. Each JS module is an IIFE assigned to a property of a single `AC` namespace (`window.AC = {}` for test access). Order matters: later modules may reference earlier ones at call time only, never at definition time.

```
<!doctype html>
<html lang="en">
<head> <meta charset> <meta viewport width=device-width,initial-scale=1,viewport-fit=cover>
       <meta name="theme-color"> <title>Athletic Cut</title>
       <style> …all CSS… </style>
</head>
<body>
  <svg id="sprite" style="display:none"> …38 exercise symbols + 13 generic + UI icons… </svg>
  <div id="app"></div>
  <div id="overlay" hidden></div>
  <script> …modules 1–14 in order, then boot… </script>
</body>
```

| # | Module | Public signatures |
|---|---|---|
| 1 | `AC.util` | `uid(prefix)→string`; `todayKey(d=new Date())→'YYYY-MM-DD'` (local); `isoWeekKey(dateKey)→'2026-W36'`; `weekStart(dateKey)→dateKey` (Monday); `addDays(dateKey,n)`; `fmtLoad(n,unit)`; `fmtClock(sec)→'m:ss'`; `clamp(n,lo,hi)`; `round(n,step)`; `h(tag, attrs, ...children)→HTMLElement` (tiny DOM builder, escapes text); `el(id)`; `on(el,evt,sel,fn)` (delegation). |
| 2 | `AC.store` | `load()→AppState` (reads LS_KEY, parses, `migrate`, memoises); `get()→AppState`; `save()→void` (sync `localStorage.setItem`, bumps `updatedAt`; throws → toast on QuotaExceeded); `update(mutatorFn)→AppState` (mutator(state) then save; the ONLY write path); `subscribe(fn)`; `reset()`; `exportJSON({includeClips})→Promise<string>`; `importJSON(text)→Promise<{ok, error}>`. |
| 3 | `AC.migrations` | `MIGRATIONS` map; `migrate(raw)→AppState` (§2.8). |
| 4 | `AC.seed` | `SEED_PROGRAM`, `SEED_EXERCISES`, `freshState()→AppState`, `reconcile(state)→AppState`, rep-target helpers `R, RS, SEC, …`. |
| 5 | `AC.media` | `open()→Promise<IDBDatabase>` (lazy); `putClip(exerciseId, file)→Promise<void>`; `getClip(exerciseId)→Promise<Blob|null>`; `deleteClip(exerciseId)→Promise<void>`; `listClipIds()→Promise<string[]>`; `objectUrl(exerciseId)→Promise<string|null>`; `revokeAll()`. |
| 6 | `AC.svg` | `use(symbolId, cls)→SVGElement` (`<svg><use href="#…"/></svg>`); `figure(exercise)→HTMLElement` (two-frame animated figure, `.anim` class toggles CSS keyframes, tap = pause/step). Sprite itself is static HTML. |
| 7 | `AC.router` | `go(route, params={})`; `current()→{route, params}`; `back()`; `onChange(fn)`. Hash-based (`#/home`, `#/session`, `#/library`, `#/exercise/:id`, `#/progress`, `#/metrics`, `#/nutrition`, `#/history`, `#/settings`). `#/session` redirects to `#/home` when `activeSession` is null. |
| 8 | `AC.ui` | Shared components: `stepper({value, step, min, max, format, onChange, snap})→HTMLElement`; `rpePicker({value,onChange})`; `bigButton(label, onTap, variant)`; `tile(label, value, sub)`; `sheet(contentEl)→{close}` (bottom sheet/overlay); `toast(msg, ms=2000)`; `confirm(msg)→Promise<boolean>` (custom, not `window.confirm`); `chips(options, selected, onChange)`; `dateStrip(dateKey,onChange)` (± day, no keyboard). |
| 9 | `AC.timer` | `startRest(seconds, {label, onDone})`; `extend(seconds)`; `skip()`; `startEmom(minutes, onMinute, onDone)`; `startInterval(plan, onPhase, onDone)`; `resume(persistedTimer)`; `tick()` (rAF-driven); `state()→{kind, endAt, phaseEndAt, …}`. All timers are wall-clock; state mirrors into `activeSession.timer` (§7). |
| 10 | `AC.audio` | `unlock()` (first pointerdown, creates AudioContext, plays silent buffer, starts keepalive); `beep(pattern='rest'|'minute'|'go'|'stop')` (immediate); `scheduleBeepAt(epochMs, pattern)→handle`; `cancel(handle)`; `vibrate(pattern)`; `isUnlocked()`. |
| 11 | `AC.wake` | `acquire()`; `release()`; `bind()` (installs visibilitychange re-acquire); `supported()`. |
| 12 | `AC.engine` | Session state machine: `start(dayId, {optionId})`; `resume()`; `current()→View` (derived: block, slot, set index, phase); `dispatch(action)` where action ∈ §6.3; `abandon()`; `complete({sessionRPE, notes})→SessionLog`; `lastFor(exerciseId)→SetLog[]|null`; `suggestLoad(exerciseId, slot)→{load, unit, suggested:boolean, reason}`. |
| 13 | `AC.stats` | `epley(load, reps)`; `e1rmSeries(exerciseId, {bodyweightAware})→[{date, value, sessionId}]`; `rolling7(metricsByDay)→[{date, value, avg}]`; `weeklyVolume({weekKey})→{pattern→lb}`; `daysTrainedThisWeek()`; `stallCheck()→StallCard[]`; `weeklyReview()→Review|null`; `nutritionSummary()→{avg, onTargetDays}`; `bodyweight7()`; `beatLast(session)→string[]`. |
| 14 | `AC.charts` | `line(container, spec)`; `bars(container, spec)`; `markers(container, spec)` — see §9. |
| 15 | `AC.screens` | One render function per route: `home()`, `session()`, `library()`, `exercise(id)`, `progress()`, `metrics()`, `nutrition()`, `history()`, `settings()`; each returns an HTMLElement and registers its own listeners; `render()` swaps `#app` content. |
| 16 | `AC.io` | `download(filename, text)` (Blob + `<a download>`; also a "copy to clipboard" fallback since some sandboxes block downloads); `pickFile(accept)→Promise<File>`. |
| 17 | boot | `AC.store.load(); AC.router.onChange(AC.screens.render); AC.audio.installUnlockListener(); AC.wake.bind(); if (state.activeSession) AC.engine.resume(); AC.router.go(state.activeSession ? 'session' : 'home'); AC.media.listClipIds().then(reconcileClipRefs)` |

CSS: custom properties for colour (dark theme default — gym lighting), `--tap: 44px`, safe-area insets, `prefers-reduced-motion` disables the SVG frame animation (frames then swap on tap only), large type (base 18 px, numbers 40–56 px), sticky bottom action bar.

---

## 6. Session engine state machine

### 6.1 Top-level session states

```
idle ──start(dayId[,optionId])──▶ running ──complete()──▶ finished (SessionLog appended, activeSession=null)
                                    │
                                    └──abandon()──▶ finished(status 'abandoned')
running ⟷ (refresh) : resume() rebuilds from activeSession with no loss
```

`running` walks `blocks[blockIndex]`. Each block has its own sub-machine keyed on `block.mode`. Advancing past the last block moves to `wrapup` (a view, still `running`) which shows the session-RPE prompt.

Block sequence transition (all modes): `NEXT_BLOCK` when the block sub-machine emits `done`; a "Skip block" button also emits `NEXT_BLOCK` (records `blockResults` with `skipped:true`). "Previous block" is allowed for checklist/straight-sets/rounds (does not delete logged sets; it only moves the cursor).

### 6.2 Block sub-machines

**checklist (prep, mobility)**
- State: `{ checked: Set<slotId> }`
- `TOGGLE_ITEM(slotId)` → flips; `save()`.
- `done` when the user taps "Done" (not automatically when all are checked — box jumps may be skipped deliberately). All-checked highlights the Done button.
- Each item row shows the exercise thumbnail (mini SVG) and first cue; tapping the name opens the overlay (AC4).

**straight-sets (main lift, Day D TGU finisher)**
- State: `{ slotIndex:0, setIndex:0..sets-1, phase:'input'|'rest', draft:{load, reps, rpe}, timer }`
- `input`: steppers show `draft`; `LOG_SET` → append SetLog, `setIndex++`, if `setIndex < sets` → `phase='rest'`, `timer.startRest(block.restSeconds)`; else → `done`.
- `rest`: countdown; `SKIP_REST` or timer zero → `phase='input'`, draft.load carried over, draft.reps reset to `repsTarget.min`, draft.rpe reset to null. `EXTEND_REST` adds 30 s to `timer.endAt`.
- `SUBSTITUTE(exerciseId)` (only while `setIndex===0`): swaps `slot` for the substitution override (§3 note on b-main-1); recorded as `activeSession.substitutions[slotId]=exerciseId`.
- `EDIT_LAST_SET` re-opens the last SetLog into the draft (removing it from entries) — tap-only correction path.
- Weighted-bodyweight slots show the stepper as "+ 25 lb" and allow 0.

**rounds (superset, circuit, KB complex)**
- State: `{ round:0..rounds-1, itemIndex:0..items.length-1, phase:'input'|'rest', drafts:{[slotId]:{load,reps,rpe,seconds}}, timer }`
- Display: "Round 2 of 3 · A → **B**" with the next item's name; "Next: Hanging leg raise" chip.
- `LOG_SET` → append SetLog(setIndex=round); `itemIndex++`; if `itemIndex < items.length` → stay `input` (no rest, next item shown immediately); else `itemIndex=0`, `round++`; if `round < rounds` → `phase='rest'`, `timer.startRest(block.restSeconds)`; else `done`.
- `ADD_ROUND` (only when `block.roundsMax > rounds`, i.e. KB complex 5 → 6): increments `rounds` for this session.
- Time-based items (side plank 30 s) show a "Hold" button that starts a 30 s work timer (beep at end) and then logs `seconds:30`; steppers show seconds instead of reps.
- `sharedLoad` blocks show one load stepper per round and copy it to every item's SetLog.

**emom (Day A finisher)**
- State: `{ status:'ready'|'running'|'finished', load, minute:0..minutes-1, roundsCompleted, repsPerMinute, startedAt:null|epochMs, timer }`
- `ready`: shows bell-size stepper (kettlebell snap), "12 swings at the top of every minute for 8 minutes", **Start** button.
- `START_EMOM` → `startedAt=Date.now()`, `timer.startEmom(minutes)`. Display: big countdown of the current minute (`60 − (elapsed mod 60)`), "Minute 3 of 8", swings due banner for first ~10 s, `roundsCompleted` counter.
- Every minute boundary: beep `'minute'` + vibrate; `roundsCompleted` auto-increments **unless** the user tapped **Missed** during that minute (`MARK_MISSED` sets `missed[minute]=true`). This makes a normal EMOM zero-tap after Start.
- At `minutes*60` elapsed: beep `'stop'`, `status='finished'`, one SetLog per completed minute (`reps:12`, `load:bell`, `setIndex:minute`) plus a BlockResult `{roundsCompleted, roundsTarget:8, load}`. **Done** → `done`. `STOP_EARLY` finishes at the current minute.

**interval (Day C sprints)**
- State: `{ status:'ready'|'effort'|'rest'|'finished', effortSeconds:20, restSeconds:90, effortsTarget:8, effortsMax:10, effortIndex:0, slower:[], timer }`
- `ready`: steppers for effort seconds (15–20 by 5) and target efforts (8–10); **Start** (10 s "get set" countdown with 'go' beep).
- `effort`: countdown `effortSeconds`; at zero → beep `'stop'` → `rest` with `timer.startRest(restSeconds)`; the rest screen shows two big buttons: **Good** and **Slower**. `MARK_SLOWER` pushes `effortIndex` into `slower`. If `slower.length ≥ 2` and both are in the last two efforts, show the stop suggestion card: "Last two were slower. The program says stop here." with **Stop** / **One more**.
- Rest zero → beep `'go'` → `effortIndex++` → `effort`; after `effortsTarget` efforts → `finished` (with **+1 effort** available up to `effortsMax`). `STOP_SPRINTS` → `finished`.
- Output: SetLog per effort (`seconds`, `reps:null`, `load:null`), BlockResult `{efforts, slowerFlags, stoppedEarly}`.

**carry (farmer's carry, suitcase carry)**
- State: identical to straight-sets but the input panel shows a **load stepper** (per hand for farmers) and a **distance stepper** (default 40 m, step 5 m); "Log trip" appends SetLog with `distanceM`, `reps:null`. Rest timer between trips uses `block.restSeconds` (60 s). Suitcase carry alternates hand each trip ("Trip 3 · left hand" label).

**freeform (Day E sport)**
- State: `{ minutes:40, activity:'' }` — minutes stepper (±5), optional activity text (keyboard optional), **Done** → BlockResult `{minutes, activity}`; no SetLogs.

**Day D finisher alternate**: at block entry the runner shows a two-option chooser "Turkish get-up 3×2/side" / "Suitcase carry 4×40 m"; the choice rewrites `activeSession.blocks[i]` to the alternate mode/items for this session (`chosenAlternate:true`).

**Day E**: `start('day-e')` first shows the option chooser; `'nothing'` logs a SessionLog with no entries and `status:'complete'` and does NOT advance the schedule (5.4).

### 6.3 Action vocabulary (everything `dispatch` accepts)

`START`, `RESUME`, `TOGGLE_ITEM`, `SET_DRAFT{field,value}`, `LOG_SET`, `EDIT_LAST_SET`, `SKIP_REST`, `EXTEND_REST`, `SUBSTITUTE`, `CHOOSE_ALTERNATE`, `ADD_ROUND`, `START_EMOM`, `MARK_MISSED`, `STOP_EARLY`, `START_INTERVAL`, `MARK_SLOWER`, `MARK_GOOD`, `ADD_EFFORT`, `STOP_SPRINTS`, `START_HOLD`, `NEXT_BLOCK`, `PREV_BLOCK`, `SKIP_BLOCK`, `SET_SESSION_RPE`, `SET_NOTES`, `COMPLETE`, `ABANDON`.

Every action runs through `AC.store.update(s => reduce(s.activeSession, action))` and therefore **synchronously writes the whole blob to localStorage** before the DOM re-renders. There is no debouncing on the session path.

### 6.4 Persisted `ActiveSession` shape

```js
activeSession = {
  sessionId: 'ses_…',           // becomes SessionLog.id
  dayId: 'day-a',
  dayOptionId: null,            // Day E
  startedAt: '2026-09-01T17:02:11.000Z',
  bodyweightAtTime: 199.1,      // snapshot at start (7-day avg) or null
  blocks: [ /* deep copy of the day's blocks at start, with any alternate/substitution applied */ ],
  substitutions: { 'b-main-1': 'pull-up' },
  blockIndex: 1,
  block: {                      // sub-machine state for blocks[blockIndex] — exactly one of the shapes in §6.2
    mode: 'straight-sets', slotIndex: 0, setIndex: 2, phase: 'rest',
    draft: { load: 245, unit: 'lb', reps: 5, rpe: null },
    suggestion: { load: 250, flagged: true, reason: 'last final set 5 @ RPE 8' }
  },
  blockResults: [ { blockId:'a-prep', mode:'checklist', checked:['a-prep-1','a-prep-2','a-prep-3','a-prep-4'] } ],
  entries: [ /* SetLog[] logged so far */ ],
  timer: {                      // null when no timer running
    kind: 'rest',               // 'rest' | 'emom' | 'interval-effort' | 'interval-rest' | 'hold' | 'getset'
    startedAt: 1756746310000,   // epoch ms
    endAt: 1756746460000,       // epoch ms (rest: start + restSeconds + extensions)
    extendedBy: 0,              // seconds added via +30
    label: 'Rest',
    fired: false                // true once the zero-cue has played (prevents double beep after resume)
  },
  wrapup: null                  // { sessionRPE, notes } once the wrap-up screen is reached
};
```

### 6.5 What each user action writes

| Action | Mutation written to localStorage (synchronously, before render) |
|---|---|
| Start Session | `activeSession` created with `blockIndex:0`, block state initialised, `timer:null` |
| Tap a checklist item | `block.checked` toggled |
| Stepper tap (load/reps/RPE/seconds/distance) | `block.draft.<field>` updated (yes — every stepper tap is persisted, so the draft survives refresh) |
| Log Set | `entries.push(setLog)`, `block.setIndex++` (or round/itemIndex), `block.phase='rest'`, `timer={kind:'rest', startedAt, endAt}` |
| +30 s | `timer.endAt += 30000`, `timer.extendedBy += 30` |
| Skip rest | `timer=null`, `block.phase='input'` |
| Timer reaches zero (tick) | `timer.fired=true` then on next render `timer=null`, `block.phase='input'` |
| Substitute | `substitutions[slotId]`, `blocks[i].items[j]` replaced |
| Start EMOM | `block.status='running'`, `block.startedAt`, `timer={kind:'emom', startedAt, endAt: startedAt+minutes*60000}` |
| Missed (EMOM) | `block.missed[minute]=true` |
| Start sprints / Good / Slower | `block.status`, `block.effortIndex`, `block.slower`, `timer` phase |
| Done / Next block | `blockResults.push(...)`, `blockIndex++`, `block` re-initialised for the new block, `timer=null` |
| Session RPE chip | `wrapup.sessionRPE` |
| Complete | `sessions.push(SessionLog)`, `activeSession=null`, `schedule.nextRequiredIndex` advanced (§8.7), `flags` updated |
| Abandon | `sessions.push({…status:'abandoned'})`, `activeSession=null` (schedule unchanged) |

### 6.6 Resume algorithm (`engine.resume()`)

1. `s = store.get().activeSession`; if null → home.
2. Re-derive the view purely from `s` (block, phase, draft). Nothing is kept only in memory.
3. If `s.timer` is non-null: `remaining = s.timer.endAt − Date.now()`. If `remaining > 0` → `AC.timer.resume(s.timer)` (re-schedules the audio cue at `endAt`). If `remaining ≤ 0` and `!s.timer.fired` → play the cue now (the user missed it), set `fired`, transition as if the timer hit zero. For EMOM: `minute = floor((now − startedAt)/60000)`; if `minute ≥ minutes` → finish; else continue with the correct minute and rounds credited for elapsed minutes not marked missed.
4. Re-acquire Wake Lock (no user gesture needed for re-acquire on visibility, but the first acquire needs one — the resume screen shows a single "Continue" tap which doubles as the audio-unlock and wake-lock gesture).

Refresh test in words: log set 2 of 4 at 245×5, wait 20 s of a 150 s rest, refresh → the runner shows "Main lift · Set 3 of 4", "Rest 2:10" still counting, load stepper 245. Tapping a stepper 3 times then refreshing shows the tapped value.

---

## 7. Timer, audio, vibration, Wake Lock design

### 7.1 Wall-clock timers

- Every timer is defined by absolute epoch milliseconds (`startedAt`, `endAt`, EMOM `startedAt + n*60000`), never by a decremented counter.
- One driver: `requestAnimationFrame` loop while the page is visible, computing `remaining = endAt − Date.now()` each frame and updating the DOM text only when the integer second changes. When hidden, a 1000 ms `setInterval` fallback keeps the state machine moving if the browser allows it (it may not; that is fine because…).
- `visibilitychange → visible` and `pageshow` and `focus` all call `AC.timer.tick()` immediately, which catches up: if `Date.now() ≥ endAt` the zero-transition happens on the spot, including any EMOM minutes elapsed while hidden (loop from the last processed minute to the current one, crediting rounds).
- Interval mode chains phases by computing `phaseEndAt` from the previous `phaseEndAt` (not from `Date.now()` at the moment the tick noticed), so drift does not accumulate across efforts: `effort k` starts at `T0 + 10 s + k·(effort+rest)`.
- Countdown display uses `Math.ceil(remaining/1000)` so it never shows 0 while a fraction remains, and the fire condition is `remaining ≤ 0`.

### 7.2 Audio (Web Audio only, no files)

- `unlock()` is installed as a once-listener on the first `pointerdown` / `touchend` / `keydown` anywhere: creates `new (window.AudioContext || window.webkitAudioContext)()`, calls `ctx.resume()`, plays a 1-sample silent buffer, and starts a **keepalive**: a looping silent `AudioBufferSourceNode` (or an oscillator through a `GainNode` at gain 0) running for the life of the session. A running context is what keeps the audio clock alive when the tab is hidden and, on Android Chrome, when the screen is off.
- Beep synthesis: `OscillatorNode` (type `'sine'`, 880 Hz for rest-done, 660 Hz for minute-top, 990 Hz for 'go', 440 Hz for 'stop') → `GainNode` with an envelope (`gain.setValueAtTime(0, t); linearRampToValueAtTime(0.6, t+0.01); exponentialRampToValueAtTime(0.001, t+0.25)`) → destination. Patterns: rest = 3 × 250 ms beeps 150 ms apart; minute = 1 beep; go = 2 rising beeps; stop = 1 long 600 ms.
- **Pre-scheduling** is the key to "fires with screen off": at the moment a timer starts (or is extended/resumed), `scheduleBeepAt(endAtMs)` converts to audio time: `t = ctx.currentTime + (endAtMs − Date.now())/1000` and calls `osc.start(t); osc.stop(t + dur)` for each pulse. The audio thread fires these even if the JS main thread is throttled. The returned handle holds the nodes so `cancel()` can `osc.stop()` them on Skip/extend (then a new schedule is made). Nodes are recreated per schedule (oscillators are single-use).
- If the context is `suspended` when a timer starts (no gesture yet, or iOS auto-suspend), a visible "Tap to enable sound" pill is shown on the runner; tapping it runs `unlock()` and re-schedules.
- `beep()` is also called at the zero-transition detected by `tick()`; the `timer.fired` flag prevents doubling when both the pre-scheduled and the tick path run (the scheduled path sets `fired=true` via a `setTimeout` at the same time if the page is visible, and `tick()` checks the flag).

### 7.3 Vibration

- `navigator.vibrate && navigator.vibrate(pattern)` with patterns rest `[200,100,200,100,400]`, minute `[150]`, go `[100,50,100]`, stop `[500]`. Only works while the page is visible (spec-level limitation), so it is fired from `tick()` (foreground path) and again from the visibility catch-up if `!fired`. Guarded by `settings.vibrate`.

### 7.4 Wake Lock

- `AC.wake.acquire()`: `if ('wakeLock' in navigator) navigator.wakeLock.request('screen').then(l => { lock = l; l.addEventListener('release', () => lock = null) }).catch(noop)`.
- Called after `START`/`RESUME` from within the user's gesture handler (Chrome requires a visible document; a gesture is not strictly required but the first acquire happens in a tap anyway).
- `bind()` installs `document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && store.get().activeSession) acquire(); })` — the lock is automatically released by the browser when the page is hidden, so this re-acquires on return.
- `release()` on `COMPLETE`/`ABANDON` and on navigating away from `#/session`.
- Unsupported (iOS < 16.4, Firefox desktop): silent no-op; the settings screen shows "Wake Lock: unsupported — keep the screen on in system settings".

### 7.5 Persistence of timers

`activeSession.timer` (see §6.4) is written whenever a timer starts, is extended, is skipped, or fires. On resume, `AC.timer.resume(timerState)` re-derives everything and re-schedules audio. There is no in-memory timer state that isn't also in the blob.

---

## 8. Algorithms (exact)

All dates are local calendar days (`todayKey`), weeks are ISO weeks starting Monday (`settings.weekStartsOn = 1`).

### 8.1 Epley estimated 1RM

```
e1rm(load, reps) = reps === 1 ? load : load * (1 + reps / 30)
```
- Inputs in the SetLog's unit; convert kg→lb (`× 2.20462`) before charting so all series share an axis.
- `isWarmup` sets are excluded. Sets with `reps > 12` are excluded from e1RM (Epley is unreliable there) but still count for volume.
- **Weighted-bodyweight** (pull-up, dip) **[AMBIGUOUS]**: recommended `e1rm_total = (bw + load) * (1 + reps/30)` and the chart plots `e1rm_total − bw` ("added-load 1RM") where `bw = session.bodyweightAtTime ?? latest bodyweight ?? null`; if `bw` is null, fall back to `load * (1 + reps/30)` and mark the point hollow. Bodyweight-only pull-ups (load 0) therefore still produce a meaningful trend.
- Per session, per exercise: `sessionBest = max over sets of e1rm`. That is the single point plotted per session.

### 8.2 7-day rolling average (bodyweight, nutrition)

```
avg7(dayKey) = mean of values with date in (dayKey − 6 … dayKey) inclusive, where a value exists
             = null if fewer than 1 value in the window
```
- **[AMBIGUOUS]** "7-day average" could be last-7-entries or last-7-calendar-days; recommended: **calendar-day window, trailing, requires ≥ 1 entry**, and the Home tile shows "(n/7)" when n < 4 so a sparse week is visible. Multiple entries on one day: the latest one wins (upsert by date+type).
- Bodyweight chart: compute `avg7` for every day from first entry to today (so the line is continuous even on days without an entry — it carries the window, not the last value; days with zero entries in the window produce a gap).
- Nutrition "7-day average": same window over `calories`, `proteinG`, `steps` separately, ignoring null fields.

### 8.3 Load suggestion (spec 5.1)

```
last = most recent SessionLog with status 'complete' containing a non-warmup SetLog for exerciseId
      (search sessions newest→oldest; if the exercise was substituted last time, `last` is null → fall back to
       most recent set of the SAME exercise anywhere, else no prefill)
if !last → prefill: barbell/dumbbell/cable 45 lb bar? NO — prefill 0 and show "first time" (never guess a load)
sets  = last.entries.filter(e => e.exerciseId === id && !e.isWarmup) in logged order
top   = max(sets.map(e => e.load))               // "top-set weight"
final = sets[sets.length − 1]                     // "final set"
inc   = increment(exercise, loadType)             // 5 lb barbell/dumbbell/cable; 2.5 lb weighted-bodyweight; kettlebell → next size in snap list
hitTop = final.reps >= slot.repsTarget.max && (final.rpe === null ? false : final.rpe <= 8)
prefill = hitTop ? top + inc : top
flagged = hitTop
```
- **[AMBIGUOUS]** whether a skipped RPE (null) counts as "≤ 8": recommended **no** — a suggestion needs positive evidence. Alternative: treat null as 8 (program default). Implementer may toggle via `settings.assumeRpe8WhenSkipped` (default false).
- "Never auto-apply a decrease": `prefill = max(prefill, top)`; the user can step down manually.
- For `repsTarget.kind === 'amrap'` (dips, BW pull-ups): `hitTop` uses the program's explicit thresholds: dips → `final.reps >= 12`, pull-up → `final.reps >= 10` (`slot.weightThreshold`), and the flag text reads "Cleared 12 — add weight".
- Kettlebell: `inc` = next element of `settings.kettlebellSizesKg` after `top`; if `top` is the largest size, no increase.
- The flag is rendered as a green up-arrow badge on the load stepper with the reason text; tapping the badge reverts to `top`.
- Also used for the "Last:" line: `Last: ${final.load} ${unit} × ${final.reps} @ RPE ${final.rpe ?? '–'}` and, when sets differ, a secondary line listing all sets `245×5, 245×5, 245×5, 245×4`.

### 8.4 Stall detection (spec 5.2)

```
for each exercise with isMain (and any exercise with ≥ 3 weeks of data):
  wk[k] = max sessionBest e1rm in ISO week k (undefined when no session that week)
  W = current ISO week (this week), W1 = W−1, W2 = W−2, W3 = W−3
  define "week k improved" = wk[k] > max(wk[j] for j < k, j ≥ k−4)   // beat the trailing 4-week best
  stalled = wk[W1] defined && wk[W2] defined && !improved(W1) && !improved(W2)
```
- i.e. two **consecutive completed weeks** with at least one session each where the weekly best e1RM did not exceed the prior best. **[AMBIGUOUS]** "two consecutive weeks" could include the current partial week; recommended: only completed weeks, evaluated once the current week begins, so the card shows Monday after the second flat week.
- Card text: `${exercise.name} has stalled two weeks. In a deficit this usually means calories, not effort. Consider adding 200/day.` (program: "Add 200 calories back").
- Show once: key `${exerciseId}:${isoWeekKey(W)}` in `flags.dismissedStalls`; the card is not re-shown for the same key; a further flat week produces a new key next Monday.
- Surface on Home (top of screen) and on the exercise detail page.

### 8.5 Weekly review (spec 5.3 + program nutrition rules)

Trigger: on Home render, if `isoWeekKey(today) !== flags.lastWeeklyReviewWeek` **and** at least 7 days have elapsed since `programStartDate` **and** the prior week has ≥ 3 bodyweight entries (else show "Log more weights to get a review"). Setting `flags.lastWeeklyReviewWeek` on dismiss makes it once per week.

```
avgPrev  = mean(bodyweight values in ISO week W−1)
avgPrev2 = mean(bodyweight values in ISO week W−2)
avgPrev3 = mean(bodyweight values in ISO week W−3)
lossThisWeek = avgPrev2 − avgPrev          // positive = losing
lossLastWeek = avgPrev3 − avgPrev2
inTarget = 0.7 ≤ lossThisWeek ≤ 1.0
tooFast2 = lossThisWeek > 1.5 && lossLastWeek > 1.5
stalled2 = lossThisWeek < 0.2 && lossLastWeek < 0.2   // "nothing moving after 2 weeks"
suggestion =
  tooFast2 ? { delta:+250, text:'Losing more than 1.5 lb/week two weeks running. At your body fat that comes out of muscle. Eat more — add ~250 kcal/day.' }   // [AMBIGUOUS] spec gives no number for the eat-more case; +250 recommended (splits the program's 200/300 steps)
  : lossThisWeek > 1.0 && lossThisWeek ≤ 1.5 ? { delta:0, text:'Slightly fast. Hold and watch next week.' }
  : inTarget ? { delta:0, text:'On target (0.7–1.0 lb/week). Hold at ' + settings.calorieTarget + ' kcal.' }
  : stalled2 ? { delta:−300, text:'Nothing moved for two weeks. Drop 300 kcal/day.' }                     // program rule
  : { delta:0, text:'Under 0.7 lb/week this week. Hold one more week before changing anything.' }
liftStall2 = every main-lift family has no improved week in W−1 and W−2 (from 8.4)
if liftStall2 → append: 'No main lift progressed for two weeks — the program says add 200 kcal/day, not more training.'
```
- The review card shows: week avg (`avgPrev`), change vs prior week (`−0.8 lb`), a 4-week mini bar, the target band, the suggestion, and **Apply** (updates `settings.calorieTarget += delta`) / **Dismiss**.
- Waist prompt: if `isoWeekKey(today) !== flags.lastWaistPromptWeek` and no waist entry this week → small "Weekly waist" tile with a stepper defaulting to the last waist value (±0.1 in), one tap to save.

### 8.6 Days trained this week

`sessions.filter(s => s.status === 'complete' && isoWeekKey(s.completedAt local) === isoWeekKey(today) && !(s.dayId === 'day-e' && s.dayOptionId === 'nothing')).length` (distinct sessions, not distinct days — two sessions in one day count as 2; **[AMBIGUOUS]**, recommended as stated since it matches "sessions completed").

### 8.7 Schedule advancement (spec 5.4)

```
onComplete(session):
  if session.dayId === program.optionalDayId → no change (Day E never advances or blocks)
  else if session.dayId === program.requiredDayIds[schedule.nextRequiredIndex]:
       schedule.nextRequiredIndex = (nextRequiredIndex + 1) % 4
       if nextRequiredIndex === 0 → schedule.cycleCount++
  else (user started an out-of-order required day, e.g. Day C on a Day B slot):
       schedule.nextRequiredIndex = (indexOf(session.dayId) + 1) % 4   // resume the sequence after the day actually done
  schedule.lastCompletedDate = todayKey(); schedule.lastCompletedDayId = session.dayId
abandoned sessions never change the schedule.
```
Home logic **[AMBIGUOUS — "Rest day"]**: the program is not date-locked, so "today's session" is simply `requiredDayIds[nextRequiredIndex]`. Recommended: if `lastCompletedDate === today` show "Rest day — next: Day B tomorrow (Tue 2 Sep)" with Start Session still available (secondary style) plus a "Day E (optional)" link; otherwise show the next day as today's session with a primary Start Session. Missing a day changes nothing (the pointer just waits) — that is the "shifts everything" behaviour. The Start screen always offers "Start a different day" (any of A–E) as a small link.

### 8.8 Volume by movement pattern (Progress)

```
volume(set) = (set.load ?? 0) * (set.reps ?? 0)                    // lb·reps, kg converted to lb
             for weighted-bodyweight: (bw + load) * reps  (bw as in 8.1; if null, load*reps)
             for bodyweight: bw * reps (if bw known) else 0
             for distance: 0 in the volume chart (carries are shown as a separate "metres carried" number)
weeklyVolume[weekKey][exercise.pattern] += volume(set)   for all non-warmup sets in complete sessions
```
Chart: stacked bars per ISO week for the last 8 weeks, one colour per pattern, legend by pattern; a second small table gives sets per pattern per week (sets are a better imbalance metric than lb·reps when bodyweight work is involved).

Session total volume (summary card) = Σ volume(set) over the session, shown in lb; "beat last session" list (`stats.beatLast`) = for each exercise in the session, if `sessionBest e1rm > previous sessionBest e1rm` or `total reps at same/greater load > previous`, emit `"${name}: ${prev} → ${now}"`.

### 8.9 Kettlebell snapping

```
KB = settings.kettlebellSizesKg = [8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48]
snapKb(x)   = KB.reduce((a,b) => Math.abs(b − x) < Math.abs(a − x) ? b : a)
kbUp(x)     = KB.find(v => v > x) ?? x
kbDown(x)   = [...KB].reverse().find(v => v < x) ?? x
```
- Stepper for `loadType === 'kettlebell'` uses `kbUp/kbDown` instead of ± arithmetic and displays kg; imported/legacy values are `snapKb`'d on display. The spec names 16/20/24/28/32; the list is a superset **[AMBIGUOUS — recommended superset so TGU/Pallof/goblet with a lighter bell and heavy swings both work; the five spec sizes are highlighted in the stepper]**.

### 8.10 Barbell / dumbbell / other increments

```
increment(loadType) = { barbell:5, dumbbell:5, cable:5, 'weighted-bodyweight':2.5, kettlebell:'snap', bodyweight:null, band:null, none:null }[loadType]   // lb
stepper min = 0; max = 1000; long-press (400 ms then every 120 ms) auto-repeats the step for fast changes without a keyboard
kg mode (settings.loadUnit === 'kg'): barbell/dumbbell/cable 2.5 kg, weighted-bodyweight 1.25 kg
```

---

## 9. Charts (hand-rolled inline SVG)

### 9.1 API

```js
AC.charts.line(container, {
  width: container.clientWidth || 358, height: 220,
  padding: { top: 12, right: 12, bottom: 28, left: 40 },
  series: [
    { id:'raw',  type:'dots', data:[{x:'2026-08-01', y:199.7}, …], r: 3, class:'c-raw' },
    { id:'avg7', type:'line', data:[{x:'2026-08-01', y:199.2}, …], class:'c-avg', width: 3 },
    { id:'scan', type:'markers', data:[{x, y, label:'Evolt'}], class:'c-scan' }
  ],
  x: { type:'date', min?: 'YYYY-MM-DD', max?: 'YYYY-MM-DD' },
  y: { min?: number, max?: number, ticks: 4, format: v => v.toFixed(1), unit:'lb' },
  band?: { from: number, to: number, class:'c-band' },   // e.g. target weight band
  emptyText: 'No entries yet'
}) → SVGSVGElement (replaces container's children)

AC.charts.bars(container, { width, height, padding, groups:[{x:'2026-W35', parts:[{key:'hinge', value:12400}, …]}], colors:{pattern→cssClass}, y:{…} })
```

### 9.2 Scale math

```
innerW = width − padding.left − padding.right
innerH = height − padding.top − padding.bottom
xMin = min date across series (or x.min), xMax = max date or today; if xMin === xMax → xMax = xMin + 1 day
xs(d) = padding.left + (dayIndex(d) − dayIndex(xMin)) / (dayIndex(xMax) − dayIndex(xMin)) * innerW
yMin = y.min ?? floor((min y across series − pad) / step) * step, yMax = y.max ?? ceil((max + pad)/step)*step,
       pad = 0.05 * (max − min) or 1 unit if range is 0; step = niceStep((max−min)/y.ticks) where niceStep picks from {1,2,2.5,5,10}·10^k
ys(v) = padding.top + (yMax − v) / (yMax − yMin) * innerH
```
- Y ticks: `y.ticks` evenly spaced from `yMin` to `yMax`, drawn as light horizontal gridlines with labels left; X ticks: every 7 days for ranges ≤ 10 weeks, else monthly, labels `'1 Sep'`.
- Time ranges: pills "4w · 8w · 12w · All" set `x.min = today − n weeks`.

### 9.3 Drawing raw dots vs rolling average

- Raw daily values: `<circle r=3>` with `fill: var(--muted)`, `opacity: .55` — deliberately subdued.
- 7-day average: a single `<path d="M x0 y0 L x1 y1 …">` with `stroke-width: 3`, `stroke: var(--accent)`, `stroke-linejoin: round`, no fill; gaps (null avg) break the path with a new `M`. The last average point gets a larger filled circle (r=5) and a text label with the value so the "number that matters" is visually dominant (AC5).
- Evolt scans: `markers` series draws a diamond (`<path d="M x y−6 L x+6 y L x y+6 L x−6 y Z">`) at the scan weight and, on the separate lean-mass and body-fat % charts, the same diamonds joined by a thin dashed line; tapping a marker shows a tooltip `<text>` with the date and value (pointer events on the marker, not hover).
- e1RM chart: one line per exercise id within the family, dots at every session, best value labelled.
- Accessibility: each `<svg>` gets `role="img"` and an `<title>` with a textual summary ("Bodyweight, 7-day average 197.4 lb, down 0.8 this week").
- Rendering is pure: the function builds strings with `h()`-style helpers and sets `innerHTML` once; no per-point event listeners except markers.

---

## 10. Build order (implementable chunks with acceptance tests)

Each chunk ends with a check that can be run by loading `file:///home/user/TESTES/athletic-cut/index.html` in headless Chromium and evaluating JS (`window.AC` is the test surface).

| # | Chunk | Deliverable | Acceptance test (DOM / JS) |
|---|---|---|---|
| 1 | Skeleton + store + migrations + import/export | HTML shell, CSS tokens, `AC.util`, `AC.store`, `AC.migrations`, `AC.io`, Settings screen with Export/Import buttons | `AC.store.get().schemaVersion === 1`; `localStorage.getItem('athletic-cut:v1')` parses; `AC.store.update(s=>{s.settings.sound=false})` then reload → still false; `JSON.parse(await AC.store.exportJSON()).state` deep-equals `AC.store.get()`; `importJSON(export)` returns `{ok:true}` and state is identical; corrupt blob (`localStorage.setItem(key,'{')`) → app boots with fresh state and a toast, backup key not touched. |
| 2 | Seed data + exercise library data + SVG sprite | `AC.seed` with §3 program and §4 exercises; sprite with 38 + 13 generic symbols | `AC.store.get().program.days.length === 5`; day A blocks ids `['a-prep','a-main','a-ss','a-fin','a-mob']`; `program.days[0].blocks[1].items[0]` has `sets 4, repsTarget.min 5, rpeTarget 8`, block `restSeconds 150`; every `slot.exerciseId` exists in `exercises`; every exercise has `cues.length ≥ 3 && ≤ 5`, `mistakes.length ≥ 1`; `document.querySelector('#svg-' + id)` exists for all 38 and contains `.f0` and `.f1` groups. |
| 3 | Home + session runner, main-lift block only (prep/mobility checklists included so Day A is walkable end-to-end minus superset/EMOM) | `AC.router`, `AC.ui.stepper/rpePicker`, `AC.engine` (checklist, straight-sets), `AC.timer.startRest`, `AC.audio`, `AC.wake`, wrap-up screen, History list | Start Day A → header text "Prep"; tick 4 items, Done → header "Main lift · Set 1 of 4"; stepper +5 ×3 → load text "15 lb"; Log Set → `AC.store.get().activeSession.entries.length === 1`, `activeSession.timer.kind === 'rest'`, `endAt − startedAt === 150000`; reload → header still "Main lift · Set 2 of 4" and timer remaining within 2 s of expected; skip → set 2; log 3 more → block advances; complete with RPE chip → `sessions.length === 1`, `activeSession === null`, `schedule.nextRequiredIndex === 1`. **Ship checkpoint (spec §8: use for a week).** |
| 4 | Superset rounds, EMOM, interval, carry, freeform, Day E chooser, Day D alternate, substitutions | remaining sub-machines in `AC.engine`, `AC.timer.startEmom/startInterval` | Day A superset: after Log Set on A the header shows "Round 1 of 3 · next: Hanging leg raise" and `timer === null`; after B → `timer.kind === 'rest'` (60 s); EMOM: Start → `block.status==='running'`; fake clock (`AC.timer._now = () => startedAt + 61000`, tick) → `block.minute === 1`, `roundsCompleted === 1`; after `8*60000+1` → `status 'finished'`, 8 SetLogs with reps 12. Day C: Start → 'getset'; advance 10 s → effort; +20 s → rest; Slower ×2 on efforts 7–8 → stop-suggestion card visible. Day B carry: Log trip records `distanceM 40`, `reps null`. |
| 5 | Exercise library + detail + clips (IndexedDB) + custom exercise | `AC.media`, library/exercise screens, overlay from the runner | Library lists 38 items; filter pattern 'hinge' → 4 items (glute-bridge, trap-bar-deadlift, kettlebell-swing, kettlebell-clean); search "row" → 2; `await AC.media.putClip('front-squat', new Blob([..],{type:'video/mp4'}))` → detail page renders `<video>` instead of `<svg>`, `exercises['front-squat'].userClipRef === 'front-squat'`; delete → SVG back. Add custom "Zercher squat" pattern squat → appears in list with generic squat SVG. |
| 6 | Progress charts | `AC.charts`, `AC.stats.e1rmSeries/rolling7/weeklyVolume`, Progress screen | Seed 14 weight entries → `#chart-weight svg` contains ≥ 14 `circle` and exactly 1 `path.c-avg` whose `d` has 14 points; average of days 8–14 matches manual mean to 0.01; e1RM chart shows a path per logged main lift; volume chart has 1 `g.week` per ISO week with data. |
| 7 | Body metrics + Evolt form + nutrition | metrics/nutrition screens, weekly waist prompt | Quick weight: default date = today, stepper defaults to last value, Save → metrics length +1, Home tile updates; Evolt form saves 1 scan + 3 metrics rows with `source 'evolt-scan'`; nutrition: enter 7 days → averages equal manual means; days-on-target counts only days with `calories ≤ 2650+100 && ≥ 2650−100`… **[AMBIGUOUS — "on target" tolerance; recommended: calories within ±100 of target, protein ≥ 190, steps ≥ 8000; a day is on target when all three logged values pass]**. |
| 8 | Load suggestion, stall detection, weekly review, beat-last | `AC.engine.suggestLoad`, `AC.stats.stallCheck/weeklyReview/beatLast` wired into Home and runner | Seed a complete Day A with final TBDL set 245×7 @8 → new session prefill 250 with `.suggest` badge; 245×5 @8 → 245 no badge; 245×7 @9 → 245; 245×7 rpe null → 245. Seed e1RM flat over weeks W−2, W−1 → Home shows stall card containing "Trap bar deadlift has stalled two weeks"; dismiss → `flags.dismissedStalls` has the key, reload → not shown. Seed weights losing 1.8 lb/wk for two weeks → review card contains "Eat more". |
| 9 | Polish + performance + offline audit | Reduced-motion, safe-area, long-press steppers, size budget | File size ≤ 400 KB; Playwright `page.on('request')` after load sees 0 requests; Lighthouse-style TTI proxy < 1 s with 4× CPU throttle; axe-lite: all buttons have accessible names. |

---

## 11. Test plan (Playwright, Chromium at /opt/pw-browsers/chromium)

Runner: a Node script `tests/run.mjs` using `playwright` (already available via `PLAYWRIGHT_BROWSERS_PATH`); device emulation `{ viewport:{width:390,height:844}, deviceScaleFactor:3, isMobile:true, hasTouch:true }`; page opened as `file://` URL. Because it is file://, `localStorage` is origin-scoped to `file://` — each test starts with `context.clearCookies()` and `page.evaluate(() => localStorage.clear())` followed by reload. Where a clock is needed, use `page.clock.install()` (Playwright ≥ 1.45) to fast-forward wall-clock time, or the `AC.timer._now` hook for older versions.

Helpers used below: `tapText(re)` = `page.getByRole('button', {name: re}).tap()`; `hdr()` = `page.locator('[data-test=block-header]').innerText()`; `state()` = `page.evaluate(() => AC.store.get())`.

| ID | Check | Steps → assertion |
|---|---|---|
| T01 network | AC7 | `const reqs=[]; page.on('request', r => reqs.push(r.url())); goto(file://…); wait 3 s; run a full Day A (T04); expect reqs.filter(u => !u.startsWith('file://')).length === 0` and `reqs.length === 1`. Also `expect(await page.content()).not.toMatch(/https?:\/\//)` except inside comments/licence text — enforce with a grep of `src=`/`href=` attributes. |
| T02 single file | R1 | `fs.statSync(index.html).size < 400*1024`; `grep -c '<script src' === 0`; `grep -c '<link' === 0`. |
| T03 boot time | AC8 | `page.evaluate(() => performance.timing.domInteractive − performance.timing.navigationStart) < 1000` with `client.send('Emulation.setCPUThrottlingRate', {rate:4})`; and `#app` has a `button` within 1000 ms of goto. |
| T04 full Day A, no keyboard | AC1 | Install a keyboard guard: `page.on('console')` not needed — instead `page.evaluate(() => { window.__keys=0; addEventListener('keydown', ()=>window.__keys++, true); })` and never call `page.keyboard`/`fill`. Steps: tap Start Session → tap each of the 4 prep rows → tap Done → main lift: tap load "+" 49 times? No — use long-press: `page.touchscreen` tap-and-hold on "+" for 2.5 s → expect load ≥ 200; tap RPE "8"; tap Log Set ×4 (tap "Skip rest" after each) → superset: for round 1..3: tap Log Set (A), tap Log Set (B), tap Skip rest → EMOM: tap bell "+" to 24 kg, tap Start, `page.clock.runFor(8*60*1000 + 500)` → tap Done → mobility: tick 1, Done → wrap-up: tap RPE "7", tap Finish → summary card visible with text /Total volume/. Assertions: `state().sessions[0].status === 'complete'`, `entries.length === 4 + 6 + 8 = 18`, `window.__keys === 0`, `schedule.nextRequiredIndex === 1`. Also assert every element tapped had bounding box ≥ 44×44. |
| T05 restore mid-session | AC2/R5 | Start Day A, finish prep, log 2 TBDL sets at 245×5 (stepper taps), tap "+" once more (draft 250) while in rest, wait 5 s; `const before = state().activeSession`; `page.reload()`; `expect(hdr()).toContain('Main lift · Set 3 of 4')`; `expect(state().activeSession).toEqual({...before})` except `timer.fired`; the countdown text ≈ `ceil((endAt−now)/1000)` ±2; load stepper shows "250 lb". Then `context.close()` and open a **new context** with the same `storageState` persisted via `context.storageState()` → same assertions ("closing the browser"). |
| T06 timer accuracy backgrounded | R16 | Log a set (rest 150 s); `page.clock.runFor(60_000)` with the page hidden (`page.evaluate(()=>document.dispatchEvent(new Event('visibilitychange')))` after `Object.defineProperty(document,'visibilityState',{value:'hidden'})`); make visible again; assert displayed remaining is 90 ±1 s. Then run past zero → phase 'input', `timer === null`, and `AC.audio._lastBeep === 'rest'`. |
| T07 audio path | AC3 (proxy) | Monkeypatch before load: `page.addInitScript` wrapping `AudioContext.prototype.createOscillator` to record `start(t)` calls. After Log Set assert an oscillator was scheduled with `t ≈ ctx.currentTime + 150` (±0.5 s) — proving pre-scheduling, which is what fires with the screen off. Skip rest → the scheduled node's `stop()` was called. |
| T08 Wake Lock | R6 | `addInitScript` stubbing `navigator.wakeLock.request` to record calls; Start Session → 1 call; dispatch hidden→visible → 2 calls; Complete → `release()` called. Also run with `wakeLock` deleted → no exception, session still works. |
| T09 every exercise visual + cues | AC4 | For each day, start it and for each block assert the runner shows `svg use[href^="#svg-"]` or `video` and ≥ 3 `.cue` items before the first Log Set / first checklist tap; separately iterate `AC.store.get().exercises` and assert `cues.length` in [3,5], `mistakes.length` in [1,2], `document.getElementById(mediaRef)` non-null. |
| T10 bodyweight chart | AC5 | Seed 21 daily weights via `AC.store.update`; open Progress; assert `svg .c-avg` path exists and its `d` has 21 coordinate pairs (or 15 if the implementer starts the average at day 7 — assert per §8.2: 21); computed y for the last point equals `ys(mean(last 7))` ±0.5 px; the raw dots have lower opacity than the line (`getComputedStyle`). |
| T11 export/import round-trip | AC6 | Seed sessions, metrics, nutrition; `const json = await page.evaluate(() => AC.store.exportJSON())`; clear localStorage; reload; `await page.evaluate(j => AC.store.importJSON(j), json)`; `expect(JSON.stringify(state())).toBe(JSON.stringify(JSON.parse(json).state))` after normalising `updatedAt`. Also test UI path: tap Export → intercept via `page.waitForEvent('download')`; tap Import → `setInputFiles` on the hidden file input (test-only use of a file chooser; not keyboard). |
| T12 EMOM engine | R17 | As chunk-4 test: fake clock forward by 61 s → minute 1, rounds 1; tap Missed then +60 s → rounds still 1; +6 min → finished, 8 SetLogs, `blockResults` has `roundsCompleted 7`. |
| T13 sprint engine | R17 | Start sprints (8 × 20 s / 90 s); advance through 6 efforts tapping Good; efforts 7 and 8 tap Slower → stop card visible; tap Stop → `blockResults.efforts === 8`, `slowerFlags [6,7]`, `stoppedEarly false` (target reached). Alternative: Slower on 5 and 6 → card, Stop → `stoppedEarly true`, `efforts 6`. |
| T14 superset flow | R17 | After A-log: no timer, header "next: Hanging leg raise"; after B-log: timer 60 s. Round 3 B-log → block done, no rest. |
| T15 carry | R17 | Day B finisher: distance stepper default 40, load stepper; Log trip ×4 → 4 SetLogs with `distanceM 40`, `reps null`, `load > 0`. |
| T16 load suggestion | R23 | Table-driven: seed last session final set (reps, rpe) ∈ {(7,8),(7,9),(5,8),(7,null),(6,7)} → prefill {250,245,245,245,245} for slot max 7; KB swing last 24 kg with rounds all done → prefill 24 (EMOM never suggests); pull-up AMRAP last 11 reps → badge "Cleared 10 — add weight". |
| T17 stall card | R24 | Seed 3 weeks of TBDL sessions with e1RM 300, 300, 300 → Home shows card with exact text; dismiss; reload → absent; add week 4 at 300 → new card next Monday only (fake Date). |
| T18 weekly review | R25 | Seed weights: week −3 avg 199.0, −2 197.2, −1 195.4 (1.8/wk twice) → card contains "Eat more"; seed 199.0/198.2/197.4 → "On target"; seed flat → "Drop 300"; `Apply` changes `settings.calorieTarget`. Card shows once per ISO week. |
| T19 schedule | R26 | Complete A → next B; complete C directly (start a different day) → next D; complete E → next unchanged; abandon B → next unchanged; complete D → next A, `cycleCount 1`. |
| T20 tap targets | R2 | On each screen, all visible interactive elements have bbox ≥ 44×44 (allow 40 for chips in a scroll strip? No — enforce 44). |
| T21 corruption & quota | Robustness | Set blob to `'null'`, `'{}'`, `'{"schemaVersion":99}'` → boots, toast, no throw; stub `localStorage.setItem` to throw QuotaExceededError → Log Set shows an error toast and the in-memory state stays consistent. |
| T22 non-goals grep | R27 | `grep -E 'fetch\(|XMLHttpRequest|WebSocket|serviceWorker|Notification\(' index.html` → 0 matches. |

---

## 12. Risks and mitigations

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| 1 | **iOS Safari suspends the AudioContext when the screen locks**, so the pre-scheduled beep never plays (AC3 fails on iPhone). | High on iOS, low on Android | Keepalive silent loop (§7.2) keeps the context "playing" which iOS honours for background audio in most versions; on return, the catch-up path beeps immediately so the cue is never lost, only late. Document the limitation in Settings ("On iPhone keep the screen on; Wake Lock is enabled"). Wake Lock reduces the case where the screen is off at all. Test on the real phone in week 1 (spec §8 ship-after-step-3). |
| 2 | **Timers drifting or freezing when backgrounded** | Medium | Wall-clock `endAt` design, visibility catch-up, no counters (§7.1). Tested in T06. |
| 3 | **localStorage write on every stepper tap becomes slow as history grows** (JSON.stringify of the whole blob) | Low for years of data (~2 KB/session → 300 sessions ≈ 600 KB, stringify ≈ 5 ms) | Keep blob lean (no cues/SVG in state — exercise cues are in state per the data model but small); if `save()` exceeds 16 ms measured, switch to a two-key layout (`activeSession` in its own key) via migration 2. Guarded by a `performance.now()` sample logged in Settings → Diagnostics. |
| 4 | **localStorage quota (5–10 MB) or eviction** (iOS evicts after 7 days without use for non-installed pages under ITP) | Medium on iOS | Clips already in IDB; export reminder every 4 weeks on Home; recommend "Add to Home Screen" (a manifest-less PWA still can be added; storage for installed home-screen pages is exempt from ITP eviction). |
| 5 | **Wake Lock unsupported / denied** (iOS < 16.4, low battery mode) | Medium | Graceful no-op + advisory text; timers still correct because they're wall-clock. |
| 6 | **file:// origin quirks in tests** (localStorage may be disabled for file:// in some Chromium builds; `download` events blocked) | Medium | Test runner serves the file via a one-line local HTTP server (`node -e http…`) for tests while T01 still asserts zero *external* requests; export test uses the `AC.store.exportJSON()` API and the copy-to-clipboard path. |
| 7 | **Refresh during a state transition** leaves a half-written blob | Very low (setItem is atomic) | Single `setItem` per action; JSON parse failure → backup key restore attempt → fresh state with toast, never a crash. |
| 8 | **Stepper-only input makes big load jumps tedious** (first session starting at 0) | High without care | Long-press auto-repeat with acceleration (5 → 25 lb steps after 2 s); first-time exercise shows quick-pick chips for common loads (95/135/185/225/275 lb; 16/24/32 kg); plate-math tap sheet (bar + plates per side) as a bonus. |
| 9 | **Ambiguous rep ranges alter suggestion behaviour** | Medium | Ranges are data (`repsTarget.max`), editable in Settings → Program per slot; default +2 rule flagged. |
| 10 | **SVG figures unclear** → user doesn't trust visuals | Medium | Cues are primary (spec §2); consistent stick-figure language; frame labels "start"/"end"; the record-my-own-clip slot replaces it permanently. |
| 11 | **IndexedDB unavailable** (private mode on older Safari) | Low | `AC.media.open()` rejects → clip buttons hidden with a note; nothing else depends on IDB. |
| 12 | **Bodyweight-aware e1RM introduces noise** from daily weight swings | Medium | Use `bodyweightAtTime` snapshot (7-day avg) not the day's raw weight; chart labelled "added-load e1RM". |
| 13 | **Overlay/video keeps object URLs alive → memory growth** | Low | Revoke on overlay close; `revokeAll()` on route change. |
| 14 | **Week boundary/timezone bugs** (UTC vs local) around midnight | Medium | All date keys are constructed from local `getFullYear/getMonth/getDate`; ISO week computed from those; never `toISOString().slice(0,10)` for dates. Unit-test `isoWeekKey('2026-01-01')`, `('2026-12-28')`. |
| 15 | **User marks a session complete accidentally** | Medium | Complete requires the wrap-up screen + Finish; an "Undo last session" on History for 10 minutes rewinds the schedule pointer. |
| 16 | **Spec vs program disagreement on progression rule** (final set vs all sets at top of range) | Certain | Implement the spec's rule (final set) as default; expose `settings.progressionRule: 'final-set' | 'all-sets'` so the program's stricter rule is one toggle away. |

---

## 13. Ambiguities flagged (roundup) and recommended interpretations

1. **Rep ranges** — program gives fixed reps; suggestion logic needs a range. → `max = min + 2` for all rep slots; editable.
2. **Superset/circuit/finisher rest** not stated for Day B A/B, Day C circuit, Day D superset/TGU, farmer's carry trips. → 60 s (matches Day A's explicit value and "minimal rest").
3. **Block/loadType/pattern enums** need extension: kinds `sprints`, `freeform`; loadTypes `cable`, `band`, `none`; pattern `core`. → extend, keep spec values intact.
4. **Sprint defaults** (8–10 efforts, 15–20 s). → 8 efforts × 20 s, 90 s rest, +1 effort up to 10; effort seconds stepper 15/20.
5. **Leg swings count** not given. → 10/side each direction, checklist only.
6. **Weighted-bodyweight increment** not in spec. → 2.5 lb.
7. **Skipped RPE in load suggestion**. → does not count as ≤ 8 (setting to flip).
8. **e1RM for weighted-bodyweight** → bodyweight-aware "added-load 1RM" with fallback.
9. **7-day average** definition → trailing 7 calendar days, ≥ 1 entry.
10. **Stall window** → two completed consecutive weeks, each with a session, neither beating the trailing 4-week best; card keyed per exercise per week.
11. **"Eat more" calorie delta** unspecified → +250 kcal; "drop 300" and "add 200 for lift stall" from the program.
12. **"Rest day"** on a non-date-locked schedule → shown when a session was completed today; next day dated tomorrow.
13. **Days trained this week** → count of complete sessions (excluding Day E "nothing") in the ISO week.
14. **Nutrition "on target"** → calories within ±100 of target, protein ≥ 190 g, steps ≥ 8,000; all three logged.
15. **Kettlebell sizes** → superset list 8–48 kg with the spec's 16–32 highlighted.
16. **Clips in export** → optional base64 toggle, default off.
17. **localStorage vs IndexedDB** → structured data in localStorage only; binary blobs in IDB only.
18. **Day E "nothing"** → logs a zero-entry complete session for the record; never advances the schedule.
19. **Progression rule** spec (final set) vs program (all sets) → spec default, setting for the stricter rule.
20. **Exercise count** → 38 seeded (spec estimated ~25); all drawn as SVG.
