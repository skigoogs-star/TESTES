# Correctness review — athletic-cut/index.html

Reviewer: Fable (correctness). Review only; no files in the repo were modified.

Reviewed against: `bb98cea2-athleticcutprogram.md` (program), `11c5532d-trainingappspec.md` (spec), `.plan/ARCHITECTURE.md`, `athletic-cut/tests/run.mjs` (115 checks, all passing on the reviewed file).

**Provenance note.** `index.html` was being edited concurrently while this review ran (md5 `c1a4f63e…` → `fc7d32c1…` → `61cd6c2c…`, 4869 → 4976 lines, all within ~40 min; the diffs were CSS/visual, the RPE radio semantics, a swipe gesture and timer announcements — none touched the paths below). Every finding was re-reproduced on md5 `61cd6c2ca7ea3b16f3e1ccbae2984061` (4976 lines); **line numbers below refer to that version** and may drift by a few lines. Function names are given with every reference so they can be re-located with grep.

Reproduction script (Playwright, served over http): `/tmp/claude-0/-home-user-TESTES/abbbe096-801b-5190-9b54-e2b871521ec8/scratchpad/probe.mjs`; raw output in `probe-out.json` next to it. Each finding quotes the relevant probe result.

---

## Critical

### C1. "Start a different day" (and the History empty-state button) silently overwrites an in-progress session — logged sets are lost, not even saved as abandoned

- **Where:** `AC.engine.start` (~L2454) has no guard for an existing `activeSession`; `startFlow` (~L3449) and `pickDay` (~L3437) call it unconditionally. Home's primary button is guarded (`Resume session`), but the `Start a different day` link directly below it is not; `history()` (~L4700) empty state → `startFlow('day-a')` is also unguarded.
- **Repro (probe `overwrite`):** start Day A, log one TBDL set, go Home ("Session in progress" banner visible), tap *Start a different day* → *Day B*. Result: `activeSession.dayId === 'day-b'`, `activeSession.entries.length === 0`, `sessions.length === 0`. The Day A set is gone from every store.
- **Why Critical:** this is a one-tap data-loss path on the Home screen, adjacent to the resume button, on a phone held in one hand.
- **Fix:** in `AC.engine.start`, if `S().activeSession` exists, refuse (return null) unless an explicit `{replace:true}` is passed; in `startFlow`/`pickDay`, when a session is active show `ui.confirm` ("A Day A session is in progress — abandon it and start Day B?") and call `AC.engine.abandon()` first so the sets land in history as `abandoned`.

### C2. A malformed but well-marked backup imports "ok" and leaves the app throwing on every boot (blank screen, no recovery UI)

- **Where:** `AC.store.importJSON` (~L811) validates only `app`, `typeof state === 'object'`, and the *top-level* `schemaVersion`; `Seed.reconcile` (~L1381) fills only top-level keys (`if (!s.program) …`, `if (!s.schedule) …`) and never validates shapes. `render()` (~L4873) has no try/catch, so a throw inside `home()`/`sessionScreen()` leaves `#app` empty; because the bad state is already persisted, every reload repeats it. The only escape is clearing site data by hand.
- **Repro (probe `import1..5`):**
  - `{"app":"athletic-cut","state":{"program":{},"onboarded":true}}` → `{ok:true}`; reload → `TypeError: Cannot read properties of undefined (reading '0')` in `nextDay()` (`program.requiredDayIds[…]`), `#app` innerHTML length 0.
  - `state: {"schedule":{"programStartDate":"2026-08-01"}}` (no `nextRequiredIndex`) → ok; reload → `Cannot read properties of undefined (reading 'blocks')` in `home()`, blank.
  - `state: {"activeSession":{"dayId":"day-a"}}` → ok; reload forces `#/session`, `currentBlock()` throws `reading 'undefined'` (`a.blocks[a.blockIndex]`), runner never renders, and the user cannot leave the session route because the header is never built.
  - `state: {"exercises":"oops"}` → `importJSON` **throws synchronously** (`Cannot create property 'deep-squat-hold' on string`) from inside `reconcile`. `doImport` (~L4761) only handles a resolved `{ok:false}`; the throw propagates out of the `.then` chain as an unhandled rejection — the user sees nothing. (The pre-import backup has already been written by then.)
  - `{"app":"athletic-cut","schemaVersion":1,"state":{"schemaVersion":99,"sessions":[…]}}` → `migrate` sees inner `schemaVersion > 1`, returns `freshState()`, reports `{ok:true}`; the user's existing sessions are replaced by an empty state (probe: `sessions` 1 → 0).
- **Fix:**
  1. Add a `validate(state)` step in `importJSON` (and in `migrate` for the boot path) that checks: `program.days` is a non-empty array with `requiredDayIds` (4 strings) and `optionalDayId`; `exercises` is a plain object; `schedule.nextRequiredIndex` is an integer in `[0, requiredDayIds.length)`; `sessions/metrics/scans` arrays; `activeSession` is null or has `blocks` (array), `blockIndex` (int), `block`, `entries`, `blockResults`, `timer` (null|object). Reject with `{ok:false, error}` instead of persisting.
  2. Wrap the body of `importJSON` in try/catch and return `{ok:false, error:String(e)}`; in `doImport` add a `.catch` that shows the error sheet.
  3. Treat inner `state.schemaVersion > SCHEMA_VERSION` as a rejection, not a silent reset.
  4. In `render()`, catch exceptions, log them, and render a minimal recovery screen (Export backup / Discard active session / Reset) so a bad blob is never a dead end. `home()` should also fall back to `requiredDayIds[0]` when `nextDay()` resolves to nothing.

---

## Major

### M1. Undo after a block transition removes a set from the *previous* block, corrupts the new block's draft, and leaves no way to re-log it

- **Where:** `undoLastSet` (~L2766) pops `entries[last]` regardless of `last.blockId`, then adjusts the *current* block's cursor. The rest panel (`restPanel` ~L3987) and the "Logged … Undo" toast (`onLogged` ~L3742) both expose Undo after `finishBlock` has already advanced `blockIndex` (because `finishBlock` starts a transition rest on the next block).
- **Repro (probe `undoBefore/undoAfter`):** Day A main lift, log sets 1–4 (skip rests). After set 4 the header reads `SUPERSET · ROUND 1 OF 3` with the 150 s transition rest panel showing "Logged 0 lb × 5 · Undo". Tap Undo → `entries` 4 → 3, `blockIndex` still 2, `a-main` has 3 entries, the BSS draft now holds TBDL's `reps: 5` and `rpe`, the timer is cleared. There is no UI to log TBDL set 4 again; `prevBlock` (see M6) re-initialises to "Set 1 of 4".
- **Also:** when the next block is a checklist (no transition rest) the toast Undo is shown for 6 s and hitting it on the checklist block pops the entry with no cursor change at all (mode has no branch).
- **Fix:** in `undoLastSet`, if `last.blockId !== currentBlock().id`, either (a) refuse with a toast "Use Previous block to change that set", or (b) properly rewind: `blockIndex--`, pop the matching `blockResults` entry, rebuild the block state from the remaining entries (setIndex = count of non-warm-up entries for that block, round/itemIndex likewise) and clear the transition timer. Hide the Undo control in `restPanel`/toast when `last.blockId !== currentBlock().id` until that is done.

### M2. Stepper display defaults are never written to the draft: first-time kettlebell sets log **0 kg**; AMRAP dips log **reps: null**

- **Where:** `loadStepper` (~L3610) shows `draft.load || 24` for kettlebell and `setsBlock` (~L3626) shows `draft.reps || 0` for AMRAP, but `makeDraft` (~L2399) leaves `load: 0` (from `suggestLoad` "first time") and `reps: null`; `buildSetLog` (~L2699) then records `draft.load || 0` and `draft.reps === null ? null : …`.
- **Repro (probe `tguBellShown/tguDraft/tguEntry`):** Day D → Finisher, "SET 1 OF 3", bell stepper displays **24**, `draft.load === 0`; tap Log set → entry `{exerciseId:'turkish-get-up', load:0, loadUnit:'kg', reps:2}`. Probe `dipEntry`: Day B Superset B, tap Log without touching reps → `{exerciseId:'dip', load:0, reps:null}`.
- **Affected slots:** every kettlebell slot the first time it is seen (TGU, suitcase carry, all four KB-complex items, goblet squat after substitution), dips every session until the user taps the reps stepper. Silent bad data feeds `suggestLoad`, e1RM, volume and "beat last".
- **Fix:** make `makeDraft` produce the value the UI will show: `load = s.load || (loadType==='kettlebell' ? kbDefault(24) : 0)`, `reps = targetReps(slot) ?? (amrap ? 0 : null)`; and/or have `ui.stepper` call `onChange(value)` once on mount when the shown value differs from the supplied one. Disable Log when a required field (`reps` for `logMetric:'reps'`) is null.

### M3. Bodyweight-loaded slots never show "Last:" and the pull-up "add weight when you clear 10" rule can never fire

- **Where:** `suggestLoad` (~L2353) returns early for `loadType` `bodyweight|band|none` **before** calling `lastFor`, so `out.last`/`out.lastSets` stay null. `lastLine` (~L3578) renders `— first time` whenever `s.last` is null. `substitute('pull-up')` (~L2808) sets `loadType='bodyweight'` and `weightThreshold=10`, which then hits the same early return.
- **Repro (probe `bwLastLine`, `bwSuggest`, `pullup`):** with a prior complete session containing hanging-leg-raise 12 reps, the superset screen shows `LAST — first time`; `suggestLoad(HLR slot)` → `last:null`. After substituting pull-up and seeding 4 × 11 bodyweight reps, `suggestLoad` → `{suggested:false, reason:'', last:null}`; the architecture's T16 case ("pull-up AMRAP last 11 reps → badge 'Cleared 10 — add weight'") is not met.
- **Impact:** spec §4.2 requires last time's numbers "prominently" for the current exercise — fails for hanging leg raise, push-up, pull-up (bodyweight), side plank, and after any bodyweight substitution. Program line 45 (fallback rule) is not implemented end-to-end.
- **Fix:** move the early return *after* `lastFor` so `last/lastSets` are always populated; for bodyweight slots keep `load = 0` but still evaluate the AMRAP threshold and set `reason = 'Cleared 10 — swap back to Weighted pull-up'` (and make the badge tappable to `substitute('weighted-pull-up')`).

### M4. Session dates and week buckets use the UTC calendar day, not the local one

- **Where:** `(s.completedAt || s.startedAt).slice(0, 10)` in `daysTrainedThisWeek` (~L2099), `weeklyVolume` (~L2121), `setsByPattern` (~L2138), `weeklyBest` (~L2170, feeds `stallCheck`), `e1rmSeries` (~L2046), `history()` (~L4700), `sessionSheet` (~L4723), `exerciseScreen` history list. `completedAt` is `toISOString()` (UTC). ARCHITECTURE §12 risk 14 explicitly forbids this pattern.
- **Repro (probe `tz`, `tzHistoryRow`, context `timezoneId:'America/Los_Angeles'`, clock Sunday 2026-09-06 21:00 PDT):** complete a Day A session → `completedAt = 2026-09-07T04:00Z`, `todayKey = 2026-09-06` (W36) but the session is bucketed into **W37**; `daysTrainedThisWeek() === 0` immediately after training; `weeklyVolume()` is empty for W36; History row reads **"Mon, Sep 7"**. Meanwhile `schedule.lastCompletedDate` is the local `2026-09-06`, so Home says "Rest day" while the tile says 0/4. For a US user every session after 5 pm PDT / 8 pm EDT is on the wrong day, and Sunday-evening sessions are in the wrong ISO week (this also shifts stall detection by a week).
- **Fix:** store a local day key on the log (`toLog`: `dateKey: U.todayKey()`), fall back to `U.todayKey(new Date(iso))` for old logs, and use that everywhere instead of `.slice(0,10)`. Add a helper `stats.sessionDay(s)`.

### M5. Timed holds have no visible countdown and log the wrong item at zero

- **Where:** `startHold` (~L2971) starts a `hold` timer; `setsBlock` only checks `a.timer.kind === 'rest'`, `tickUI` (~L4903) only drives `restDial`/`restBar`/`emomDial`/`intervalDial`. `onTimerZero('hold')` → `logHold` → `logSet()` logs **whatever item is current at that moment**.
- **Repro (probe `holdDialCount`, `holdButtonsVisible`, `holdMidRound`):** Day C circuit, side plank: tap *Start 30s hold* → `timer.kind === 'hold'`, `.dial/.restbar/.restpanel` count **0**; the screen still shows *Start 30s hold* and *Log · then rest 60s* with no feedback. With a timed item that is not the last in the round (probe patched landmine to `logMetric:'time'`): start hold, tap Log during the hold (logs landmine, advances to Pallof), 31 s later the hold fires and **logs Pallof press with reps 12 that the user never did**. In the seeded program the side plank is always last in the round so the round-end rest timer happens to overwrite the hold timer, which is the only reason this does not double-log today.
- **Fix:** render a work dial when `timer.kind === 'hold'` (reuse `dial()`, `refs.holdDial`, update in `tickUI`), hide/disable *Log* and *Start hold* while it runs, and in `logHold` verify the timer's `slotId` (store it on the timer at `startHold`) matches the current item before logging.

### M6. `prevBlock` resets the cursor but keeps the entries → duplicate `setIndex` rows and double-counted volume

- **Where:** `prevBlock` (~L3025) does `aa.block = initBlock(…)` (setIndex 0) and filters `blockResults`, but leaves `entries` untouched. ARCHITECTURE §6.1 says "does not delete logged sets; it only moves the cursor".
- **Repro (probe `prevBlock`):** log 4 TBDL sets, `prevBlock()`, log once more → `a-main` entries have `setIndex [0,1,2,3,0]`, header says "Set 2 of 4" while `setList` shows five rows; volume and `setsLogged` count 5.
- **Fix:** on `prevBlock`, rebuild the block state from existing entries (`setIndex = entries.filter(blockId && !isWarmup).length`, clamp to `sets`; rounds: derive `round`/`itemIndex` from the count), or, if the intent is a redo, remove that block's entries with a confirm.

---

## Minor

### m1. Abandoned sessions drive the load suggestion and "Last:" line
`lastFor` (~L2152) only skips abandoned sessions with zero entries. Probe `abandonPollution`: a complete session at 245 lb followed by an abandoned one with a single 135 lb set → `suggestLoad` prefills **135**. Spec 5.1 says "last session's top-set weight"; ARCHITECTURE §8.3 says `status 'complete'`. Fix: prefer the most recent *complete* session; fall back to abandoned only when no complete one exists.

### m2. Switching `settings.loadUnit` lb→kg re-uses the raw number
Probe `kgSwitch`: prior 245 lb session, unit switched to kg → `suggestLoad` returns `{load:245, unit:'kg'}`. Fix: convert `top` with `U.lbToKg/kgToLb` when `e.loadUnit !== unit`, then round to the increment.

### m3. Reload during the 1.5 s "GO" window leaves the rest panel stuck on GO with the body inert
`resume` (~L2530): `remaining ≤ 0 && timer.fired` → nothing happens; the `setTimeout` from `onTimerZero` was lost with the page. Probe `stuckGo`: after reload the runner shows the rest panel with "GO", `phase:'rest'`, body `inert`, until the user taps *Skip rest*. Fix: in `resume`, if `timer.fired && remaining <= 0` call `endRest()` (or the kind-appropriate zero handler) immediately.

### m4. Pre-scheduled rest cue is lost across a reload and never re-scheduled after audio unlock
`resume` → `scheduleTimerAudio` → `AC.audio.scheduleAt` returns null when the context is not yet running (no gesture yet); `installUnlockListener` (~L1610) unlocks on the first tap but does not call back into the engine. The zero-cue then relies solely on the main-thread `tick()` path. Fix: after `unlock()` succeeds in the listener, if `activeSession.timer` exists and `!fired`, call `AC.engine.startTimer`-style rescheduling (expose `engine.rescheduleAudio()`).

### m5. EMOM: per-minute cues are main-thread only; timer length ignores the edited minutes
`scheduleTimerAudio` for `kind:'emom'` schedules one `'minute'` beep at the very end; the top-of-minute beeps come from `tick()` (no beep with the tab throttled/screen off, contrary to §7.2). `startEmom` (~L2852) uses `blk.emom.minutes` while `tick`/`finishEmom` use the editable `b.minutes`. Probe `emom/emomAt8/emomAt10`: minutes set to 10 → timer 480 s; at 8:00 `timer.fired`, a **'rest' pattern beep** plays mid-EMOM (`lastBeep:'rest'`), then the block runs on to 10 and finishes correctly. Fix: `startTimer('emom', b.minutes*60)`; pre-schedule one `'minute'` voice per minute boundary (`AC.audio.scheduleAt(startedAt + m*60000, 'minute')`) and `'done'` at the end; make `onTimerZero('emom')` a no-op that does not beep.

### m6. Interval catch-up chains phases from `now`, not from the previous `endAt`
`onTimerZero('interval-effort')` → `startIntervalRest` → `startTimer(…)` uses `AC.ticker.now()`. Close the tab during a 20 s effort, reopen 3 min later: the effort is logged and a **fresh full 90 s rest** starts from reopen (ARCHITECTURE §7.1 wanted `phaseEndAt` chaining). Same for `getset` → effort. Fix: pass the expired timer's `endAt` as the new phase's `startedAt`, and if that phase has also elapsed, roll through.

### m7. No debounce on Log in rounds mode
`setsBlock` re-renders synchronously so a double tap lands on the new button; for A→B (no rest) the second tap logs B with an untouched draft. Fix: ignore `logSet` calls within ~400 ms of the previous log (`lastLoggedAt` in engine).

### m8. KB complex: `roundsMax` and `sharedLoad` are seeded but unimplemented
Only reference is the seed (~L1311). No "add a 6th round" control (program: "5-6 rounds"); each of the four items has its own bell stepper. Fix: add `ADD_ROUND` when `round === rounds-1 && rounds < roundsMax`; when `sharedLoad`, show one bell stepper per round and copy to all drafts.

### m9. "End early" credits the current partial minute
`finishEmom` (~L2869): `elapsedMin = floor(elapsed/60000) + 1` → ending at 2:30 logs 3 rounds. Arguably right if the swings were done at the top; if not, the user must mark *Missed* first. Consider `+ (into < 15s ? 0 : 1)` or ask.

### m10. Day A EMOM bell prefill can come from Day E one-arm swings
`initBlock` emom → `suggestLoad(kettlebell-swing)` → `lastFor('kettlebell-swing')` matches the KB-complex swings (one-arm, lighter). Fix: restrict `lastFor` to the same `slotId`/`blockId` for prefill when available, falling back to exerciseId.

### m11. Quota-exceeded: every failing save triggers a full re-render
`save()` (~L764) dispatches `ac:writestate` on **every** failed write, and boot subscribes `render()` to it. Probe `quota`: 5 stepper taps → 5 full renders (breaks press-and-hold; the stepper under the finger is replaced). Also `load()` never tries `LS_BACKUP` on a corrupt blob (ARCHITECTURE §12 risk 7 / T21), and a blob with `schemaVersion > 1` on boot is replaced by `freshState()` with no backup written. Fix: dispatch only on transitions (`failed !== prevFailed`); on parse failure try `LS_BACKUP`; on newer schema, write the blob to a `…:newer` key before resetting and say so in the toast.

### m12. `setVolume` counts bodyweight work (other than pull-up/dip) as 0
ARCHITECTURE §8.8: bodyweight → `bw × reps` when bw is known. Hanging leg raise, push-ups, BW BSS log 0 lb·reps, so the pattern chart under-represents `core`/`push-h`. Fix: apply the bw rule for `loadType:'bodyweight'` slots with `reps`.

### m13. `substitute()` is allowed at any set index and re-uses the running `setIndex`
ARCHITECTURE §6.2 restricts SUBSTITUTE to `setIndex === 0`. Mid-block, sets already logged keep the old id, the new id gets sets 3–4, and `Object.assign(d, fresh)` wipes the draft's reps/rpe. Not wrong, but undocumented; either guard or show a confirm.

### m14. Body-fat % is not plotted
Spec §4.4: "lean mass and body fat percentage plotted separately". `progress()` (~L4447) plots lean mass only; body-fat % is a number. Add a second small chart (or a second y-axis series).

### m15. Wake Lock not released on "Pause and leave"
`leaveSheet` → `router.go('home')` without `AC.wake.release()`; §7.4 wanted release on leaving `#/session`. The screen stays awake on Home until the tab is hidden.

### m16. Warm-up sets share `setIndex` with the next working set
`logSet({warmup:true})` does not advance `setIndex`, so `setList` shows "Set 2" twice. Store `setIndex: -1`/`warmupIndex` or label "W".

---

## Nit

- `blockResult` (~L2990): `r.efforts = b.effortIndex + (b.status === 'finished' ? 0 : 0);` is dead code immediately overwritten.
- `lastLine`: `l.reps !== null` lets `undefined` through → "× undefined" on hand-edited/imported logs; use `!= null`.
- `engine.progress()` hard-codes `8` efforts for a non-current interval block; use `blk.interval.effortsMin`.
- `program.rpeNote` drops the program's first sentence ("The number in brackets is reps-in-reserve"); `Day.label` omits the "Day A —" prefix (composed in UI — fine, but `spec.Day.label` example includes it).
- `Seed.reconcile` never updates existing program slots, so any future correction to a rep/rest value needs a migration entry; worth a comment.
- `AC.audio.scheduleAt` computes its offset with `Date.now()` while the engine uses `AC.ticker.now()`; identical today, diverges if `_setNow` is used without the clock shim.
- `tick()` reads `a = AS()` then mutates through `store.update` on the same object reference — works, but relies on `store.get()` returning the live object; a future immutable store would break the `a.block.status === 'running'` re-check after `finishEmom`.

---

## Acceptance criteria (spec §7)

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| AC1 | Full Day A with no keyboard | **Met** | T03: 18 sets, `keydown` count 0, all taps ≥ 44 px. Caveat: EMOM bell default (24) *is* written to state (`initBlock`), so Day A is unaffected by M2; Day B/D are (dips reps null, TGU 0 kg). |
| AC2 | Close/reopen restores exact position | **Partially met** | T04 passes for the main-lift rest. Gaps: m3 (stuck GO panel if closed in the 1.5 s window), m4 (rest cue not rescheduled after reload), m6 (interval phases restart from reopen). Position/draft/entries are restored exactly. |
| AC3 | Rest timer fires audibly with the screen off | **Partially met (by design, platform-limited)** | `startTimer` → `scheduleAt` pre-schedules oscillators on the audio clock (~L1573); T05 confirms a `rest` cue. Not re-armed after reload until a gesture (m4); EMOM minute cues are main-thread only (m5); iOS behaviour is untestable here and documented in Settings. |
| AC4 | Visual + written cues before the first set | **Partially met** | `setsBlock` shows the figure and first 3 cues on set/round 0. `emomBlock`, `intervalBlock` and `checklistBlock` show the figure but cues only behind the thumbnail tap (`exerciseSheet`). ARCHITECTURE accepted "one tap away"; the spec wording is stricter. |
| AC5 | Bodyweight chart shows a rolling average | **Met** | T10: one `path.c-avg` spanning all 21 days, raw dots subdued. `rolling7` verified against a manual mean. |
| AC6 | Export as JSON, re-importable | **Partially met** | Round trip verified by T06 via `exportJSON/importJSON`. C2: malformed-but-marked files import "ok" and brick the app; inner `schemaVersion` mismatch wipes data with `ok:true`. The `<a download>` path is not exercised by tests (sandbox); a copy-to-clipboard fallback from ARCHITECTURE §5 is absent. |
| AC7 | Zero network requests after load | **Met** | T01 static grep + T02 request listener (exactly 1 request). No `fetch/XHR/WebSocket/serviceWorker/Notification`. |
| AC8 | Interactive < 1 s on a mid-range phone | **Met by proxy** | 253 KB single file; T02 `domInteractive < 1000 ms` in headless Chromium (no CPU throttle applied — ARCHITECTURE asked for 4×). Boot path is synchronous localStorage + seed build; IDB deferred via `setTimeout`. |

---

## Program fidelity (spec A) — line-by-line result

Every numeric value in `AC.seed.buildProgram()` (~L1174) was checked against the program markdown. **No numeric mismatches were found.** Table of what was verified:

| Program line | Seed | OK |
|---|---|---|
| L27 prep: deep squat 60 s / 90-90 8 per side / glute bridge 15 / 2×3 box jumps | `SEC(60)`, `RS(8,8)`, `R(15,15)`, sets 2 `sets-of 2×3` | ✓ |
| L29 trap bar 4×5 @ RPE 8, rest 2.5 min | sets 4, `R(5)` (min 5 / max 7), rpe 8, `restSeconds 150`, barbell | ✓ |
| L31–33 superset 3 rounds, 60 s; BSS 8/leg DB; HLR 10 | rounds 3, rest 60, `RS(8)` dumbbell, `R(10)` bodyweight | ✓ |
| L35 KB swings EMOM 8 min, 12/min | `emom {8,12}`, sets 8, `R(12,12)`, kettlebell | ✓ |
| L37 90/90 ×10/side | `RS(10,10)` | ✓ |
| L43 dead hang 30 s / pull-aparts 20 / t-rot 10/side / push-ups 10 | `SEC(30)`, `R(20,20)` band, `RS(10,10)`, `R(10,10)` | ✓ |
| L45 weighted pull-up 4×5 @8, 2.5 min; fallback max BW reps, add weight at 10 | sets 4, `R(5)`, rpe 8, 150, weighted-bodyweight; `substitutions:['pull-up']`, sub sets `amrap` + `weightThreshold 10` | ✓ data / **✗ behaviour (M3)** |
| L47–49 Superset A 3 rounds: OHP 6, CS row 10 | rounds 3, `R(6)` barbell, `R(10)` dumbbell; rest 60 (program silent — documented ambiguity) | ✓ |
| L51–53 Superset B 2 rounds: dips near failure (weighted >12), face pull 15 | rounds 2, `AMRAP`, weighted-bodyweight, `weightThreshold 12`; `R(15)` cable | ✓ |
| L55 farmer's carry 4 × 40 m | sets 4, `TRIPS(4,40)`, `distanceM 40`, mode carry, rest 60 (ambiguity) | ✓ |
| L57 dead hang 60 s accumulated | `ACC(60)` | ✓ |
| L65 prep 8 min: jog 3 / leg swings / A-skips 2×20 m / build-ups 3×40 m 60-75-85 % | `targetMinutes 8`, `MIN(3)`, 10/side (ambiguity noted), `METERS(2,20)`, `METERS(3,40,'…60%, 75%, 85%')` | ✓ |
| L67 sprints 8–10 × 15–20 s, 90 s walk-back, stop rule | `interval {8,10,15,20, default 20, rest 90, stopRule}`; `shouldSuggestStop` = last two both slower | ✓ |
| L69–72 circuit 2 rounds: landmine 8/side, Pallof 12/side, side plank 30 s/side | rounds 2, `RS(8)` barbell, `RS(12)` cable, `SECS(30)` seconds 30 | ✓ (runner bug M5) |
| L74 deep squat 2 min accumulated | `ACC(120)` | ✓ |
| L80 prep 90/90 8/side, goblet 10, scap push-up 10 | `RS(8,8)`, `R(10,10)` kettlebell, `R(10,10)` | ✓ |
| L82 front squat 4×6 @8, 2.5 min; goblet/SSB subs | sets 4, `R(6)` (max 8), rpe 8, 150; `['safety-bar-squat','goblet-squat']` | ✓ |
| L84–86 superset 3 rounds: DB bench 8, 1-arm row 10/side | rounds 3, `R(8)`, `RS(10)` dumbbell | ✓ |
| L88 TGU 3 × 2/side; or suitcase carry 4 × 40 m | sets 3 `RS(2,2)` kettlebell; `alternateItems` `TRIPS(4,40)` carry | ✓ |
| L90 t-rot 10/side | `RS(10,10)` | ✓ |
| L97–99 Day E: sport / KB complex 20 min, 5-5-5-10 each side, 90 s, 5–6 rounds / nothing | freeform 40 min; rounds 5 `roundsMax 6` rest 90 `RS(5,5)×3`, `RS(10,10)`; `nothing` → zero-entry complete log | ✓ data / **✗ 6th round not selectable (m8)** |
| Header table minutes (5/12/10/6–8/3) | A 5-12-10-8-3, B 5-12-6+4-7-3, C 8-16-8-3, D 5-12-10-7-3 | ✓ |
| Nutrition constants 2650 / 190–200 g / 70 g fat / 8–10 k steps | `defaultSettings` | ✓ |
| Progression "hit top of range → add weight"; "+2 rep range" | `max = min + 2`, `progressionRule` setting | ✓ (ambiguity documented) |

Deliberate deviations worth confirming with the coach: a full `restSeconds` of the finished block is inserted before the next non-checklist block (`finishBlock`), so a 150 s rest runs between the last TBDL set and the superset — sensible, but the program does not say it.

---

## Verified correct (so the implementer knows the coverage)

- **Epley** (`stats.epley`, `setE1rm`): 1 rep = load; `reps > 12` and warm-ups excluded; weighted-bodyweight `epley(bw+load) − bw` with fallback to `load` when bw unknown and null when both unknown; kg → lb conversion before charting.
- **7-day rolling average** (`rolling7`): trailing 7 calendar days inclusive, ≥ 1 entry, latest entry per date wins, continuous from first entry to today; empty → `[]`, single point → one row with `avg === value`. `bodyweight7` picks the last non-null.
- **`suggestLoad`** for loaded slots: top-set prefill, final-set rule with `RPE ≤ 8`, null RPE ⇒ no bump unless `assumeRpe8WhenSkipped`, `all-sets` rule, kettlebell `kbUp`, dips AMRAP threshold 12 (weighted-bodyweight path), never below `top`. `kbUp/kbDown/kbSnap` and `increment()` tables match §8.9/8.10.
- **`stallCheck`**: W1 and W2 both present and neither beating the trailing 4-week best; key `${family}:${thisWeek}` dismissed once per week; families per §4. **`weeklyReview`**: gating on `programStartDate + 7 d` and ≥ 3 weights in W1; all five branches incl. `>1.5 ×2 → +250`, `<0.2 ×2 → −300`, lift-stall sentence; `lastWeeklyReviewWeek` set on Apply/Dismiss.
- **`daysTrainedThisWeek`** counting rule (complete sessions, Day E "nothing" excluded) — correct apart from the UTC-day bug (M4).
- **Schedule advancement** (`complete`): required day → `(idx+1) % 4`, out-of-order day resumes after the day done, Day E never advances, abandon never advances, `cycleCount++` on wrap, `lastCompletedDate` drives "Rest day". T08 covers it.
- **Straight-sets / rounds / carry counters and what gets logged**: `setIndex`/`round`/`itemIndex` increments, `setIndex = round` for rounds mode, rest only after the pair, no rest after the final round, block finish on the last set, `entries` count 4 + 6 + 8 for Day A — all correct. Within-block `undoLastSet` (same block, first set, during rest, warm-up) is correct.
- **EMOM catch-up**: `tick` credits elapsed minutes while hidden, `finishEmom` logs one set per non-missed minute with the bell in kg; whole-duration-elapsed-while-closed path finishes with 8 sets (T03 runs it through the fake clock; `resume → tick(true)` takes the same branch).
- **Interval engine**: get-set 10 s → effort → walk-back; `slower` marks per effort; stop suggestion only when the last two are both slower; `+1 effort` capped at `effortsMax`; effort SetLogs carry `seconds` and `reps:null`.
- **Timers are wall-clock** (`startedAt/endAt` epoch ms), `+30` extends `endAt` and clears `fired`, `tick` handles zero crossings after `visibilitychange/pageshow/focus`, rest zero → 1.5 s GO → `endRest`; T05 confirms 60 s hidden → 90 s left.
- **Persistence**: every engine action writes through `store.update` synchronously; `activeSession` restores block/draft/timer exactly (T04, new context with copied storage); `migrate` ladder shape matches §2.8 with pre-migrate backup; `reconcile` adds missing seed exercises and settings keys without overwriting; export payload matches §2.10 and round-trips (T06); foreign file and future top-level `schemaVersion` are rejected.
- **Null-safety on paths tests do not hit**: Day E "nothing" (no `engine.start`, direct log), Day E sport freeform (blocks = 1, `items: []` → `currentSlot()` null handled in `moreSheet`), a session with zero entries completing (`beatLast` → `[]`, volume 0, summary renders), `beatLast` with no prior session, `lastFor` on a substituted exercise (falls back to the sub's own history), `history()`/`sessionSheet` with unknown `dayId`/`exerciseId` (guarded), `exerciseScreen` with a missing id (redirects), custom-exercise deletion (custom ids cannot enter the program, so no dangling `activeSession` reference is reachable), `summaryScreen` when the last session is abandoned (`totals: {}` tolerated).
- **Non-goals**: no accounts/social/food DB/streaming/AI/notifications/sync; grep-verified.
