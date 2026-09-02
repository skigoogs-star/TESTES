# Correctness review, round 2 — athletic-cut/index.html

Reviewer: Fable (correctness). Review only; no repo file was modified.

Reviewed: `athletic-cut/index.html` at commit `5c70f16` (md5 `0adaf602…`, 5296 lines), `tests/run.mjs` (165 checks, all passing, T11 = regression cases). Line numbers below are for that version; function names are given so they can be re-found with grep.

Reproduction scripts (Playwright over http, scratch only): `/tmp/claude-0/-home-user-TESTES/abbbe096-801b-5190-9b54-e2b871521ec8/scratchpad/probe2.mjs`, `probe3.mjs`, `probe4.mjs`; raw output in `probe2-out.json` and the terminal logs next to them.

**Bottom line.** Every round-1 finding was addressed and the big ones (data-loss on "Start a different day", bricking imports, UTC day buckets, the blind hold timer, the abandoned-session prefill, EMOM length, kg conversion, KB complex rounds) are genuinely fixed and verified end to end. Two of the fixes are incomplete in ways that still lose or corrupt a set (**N1**, **N2**), one fix regressed a path it did not test (**N5**), and the audio scheduling has a leak (**N3**). Full Day B, Day C and Day E-complex sessions driven through the real UI all complete with correct logs, totals and schedule advancement.

---

## A. Verification of round-1 findings

| # | Finding | Status | Evidence |
|---|---|---|---|
| **C1** | "Start a different day" / History empty state overwrite an in-progress session | **FIXED** | `engine.start` (L2566) returns `null` when `activeSession` exists unless `{replace:true}`; `startFlow` (L3673) shows `ui.confirm` first, then `abandon()` + recursive `startFlow`. Verified: Home → Start a different day → Day B (T11); History empty-state "Start Day A" with an active Day A → confirm shown, after "Abandon and start Day A" the old session is in `sessions` as `abandoned` with its 1 set and `dateKey`, new Day A session active (probe `c1_history_empty_state`); Day E chooser → confirm → "Keep going" routes to `#/session` with the session intact; "Abandon and start Day E" → option sheet → "Nothing" logs the rest choice (probe `c1_dayE_chooser`). Nit: choosing "Nothing" now forces abandoning the paused session (see N11). Regression test adequacy: T11 pins the engine refusal and the confirm text; it does not check that the abandoned session actually reaches history — acceptable. |
| **C2** | Malformed but well-marked backup imports "ok" and bricks boot; inner `schemaVersion` mismatch wipes data; `reconcile` throws out of `importJSON` | **FIXED (import path)** / **PARTIALLY FIXED (boot path)** | `store.validate` (L828) checked in `importJSON` (L864) *before* anything is written, try/catch restores `state = previous`, `.then(ok, err)` handler in `doImport` (L5042), inner future `schemaVersion` rejected by `validate`. All five round-1 repros now return `{ok:false}` with data untouched (T11). Legitimate imports still work: a real mid-session export with a running timer, a never-trained partial state `{schemaVersion, onboarded, settings, metrics}` (program/schedule filled by `reconcile`, unit preserved), a state with no `schemaVersion` at all, `nextRequiredIndex: 3`, and a Day E "nothing" log without `dateKey` all import `ok:true` (probe `c2_legit_and_partial_imports`). Fresh first boot renders onboarding with a saved fresh state (probe `fresh_boot_text`). `render()` (L5157) catches `build()` and shows `recovery()` (L5198); the recovery screen's Export / Discard / Reset buttons work (probe `recovery_discard_text`). **Gaps:** on *boot*, a damaged or newer blob is copied to `:damaged` / `:newer` and the user silently lands on onboarding with no toast (probe `c2_damaged_and_newer_boot`: `toast: null`) — see N8; and `render()`'s catch neither logs nor rethrows, so the error is invisible to `console`/`pageerror` — see N7. |
| **M1** | Undo after a block transition pops the previous block's set and corrupts the new draft | **PARTIALLY FIXED** | `undoLastSet` (L2916) now detects `last.blockId !== blk.id`, rewinds `blockIndex`, filters `blockResults`, calls `rebuildBlockState` (L2898), clears the timer. Verified for straight-sets (T11), rounds→rounds (Day B SS-A → SS-B: back to round 3 item 2, entries 5, `blockResults` trimmed, re-log finishes the block once with `setIndex [0,0,1,1,2,2]`), carry→checklist toast Undo (back to trip 4, `setIndex 3`), after a substitution (goblet squat: draft 32 kg, unit kg), with a warm-up set (warm-up excluded from the count, second undo lands on `setIndex 2`), and twice in a row (probes `m1_*`). **Two holes remain:** (1) for a *rounds* block the popped set's load/reps/RPE are not copied back into `drafts[last.slotId]`, so the draft shows the fresh suggestion (0 lb on a first session) and re-logging records that — probe `rounds_relog_loads_after_cross_undo`: rows logged `[40, 40, 0]` (**N2**); (2) at wrap-up `currentBlock()` is `null`, the cross-block branch is skipped and the same-block branch dereferences `aa.block.mode` on `null` → TypeError after `entries.pop()` has already run, so memory (19) and localStorage (20) diverge (**N1**). T11's regression only pins the straight-sets path; it is weaker than the bug. |
| **M2** | Stepper display defaults never reach the draft: kettlebell logs 0 kg, AMRAP dips log `reps: null` | **PARTIALLY FIXED** | `makeDraft` (L2507): `load = KB_DEFAULT (24)` when the suggestion is 0 and `loadType === 'kettlebell'`; `reps = 0` when `targetReps` is null and `logMetric === 'reps'`. TGU logs 24 kg, dips log 0 (T11); Day E complex drafts are all `24 kg`; TGU e1RM/volume maths is sane (`setE1rm` 56.4 lb for 24 kg × 2, `e1rmSeries` one point). **Regression:** the straight-sets branch of `logSet` (L2864) resets `d.reps = targetReps(slot)` after every set, which is `null` for an AMRAP slot, so the bodyweight pull-up fallback logs `reps: 8` for set 1 and **`reps: null` for sets 2–4** while the stepper shows 0 (probe `m2_amrap_straight_sets`) — **N5**. T11 tests dips (rounds mode, drafts are not reset) and TGU, so it does not catch this. |
| **M3** | Bodyweight slots never show "Last:", pull-up "clear 10 → add weight" rule can't fire | **PARTIALLY FIXED** | `suggestLoad` (L2430) computes `last/lastSets` before the unloaded early return and sets `suggested/reason` when `reps >= weightThreshold`; HLR shows `LAST × 12`, pull-up after 11 reps returns `suggested:true, reason:'Cleared 10 — time to add weight'` (T11). **But the UI renders it as `↑ 0 sugg.`** (`lastLine` L3819 prints `U.num(s.load,0)`; the stepper/hint that would carry the reason is not rendered for bodyweight slots), and nothing offers the swap back to weighted pull-ups (probe `m3_bodyweight_badge_ui`) — **N6**. T11 is engine-only. |
| **M4** | UTC calendar day for sessions and ISO weeks | **FIXED** | `toLog` stamps `dateKey: U.todayKey()` (L2603); `stats.sessionDay` (L2113) uses it and converts old logs via `todayKey(new Date(iso))`; every `.slice(0,10)` on a session is gone (only `summary()` on `exportedAt` remains, which is fine). Verified in `America/Los_Angeles` on a Sunday 21:00 (T11) and for an old log without `dateKey` (`2026-09-07T04:00Z` → `2026-09-06`, W36). Abandoned sessions and imports carry the key. |
| **M5** | Timed hold: no countdown, logs the wrong item at zero | **FIXED** | `startHold` stamps `timer.slotId` (L3182), `logHold` refuses if the on-screen slot differs, `setsBlock` renders a `dial--work` and hides Log/Start while holding, `tickUI` drives `refs.holdDial`. T11 covers the dial and slot binding; probe `m5_reload_mid_hold`: reload at 5 s → dial back, Log hidden, "Stop hold" shown; at 30 s the side plank is logged with `seconds: 30` and the round rest starts. |
| **M6** | `prevBlock` re-inits the cursor but keeps entries → duplicate `setIndex` | **PARTIALLY FIXED** | `prevBlock` (L3242) uses `rebuildBlockState`; straight-sets/carry/rounds are rebuilt from entries (T11, probes). EMOM, interval, checklist and freeform blocks are still re-initialised: after a finished EMOM, Mobility → Previous block shows "Start EMOM" again with the 8 swing sets still in `entries`; starting it logs 8 more (probe `m6_prevBlock_into_emom_and_interval`) — **N10**. |
| m1 | Abandoned sessions drive the prefill | **FIXED** | `lastFor` (L2227) prefers `complete`, abandoned only as fallback (T11: 245 not 135). |
| m2 | lb→kg switch reuses the raw number | **FIXED** | `conv()` in `suggestLoad` + rounding to the increment. kg mode end to end (probe `kg_end_to_end`): draft 0 kg first time, entry `100 kg × 7`, `totals.volumeLb 6173`, e1RM series 271.9 lb, next-session suggestion 102.5 kg (`suggested`, "Hit 7 @ RPE 8"), lb mode 225 lb, stepper shows 102.5, library row "e1RM 272 lb". Nit: `lastLine` badge prints "↑ 103 sugg." for 102.5 (`U.num(s.load, 0)`). |
| m3 | Reload during the GO window leaves the panel stuck | **FIXED** | `resume` (L2644): `fired && kind==='rest'` → `endRest()`; other fired kinds → `stopTimer()`. |
| m4 | Rest cue not re-armed after audio unlock | **PARTIALLY FIXED** | `installUnlockListener` (L1674) calls `engine.rescheduleAudio()` after `unlock()`, but `unlock()` returns `true` synchronously while `ctx.resume()` is still pending, so `scheduleAt` sees `state === 'suspended'` and returns `null` (probe `m4_unlock_reschedule`: `{p:'rest', r:null, state:'suspended'}`, ctx is `running` 200 ms later). The cue is still not pre-scheduled after a reload; only the main-thread `tick()` beep remains — **N9**. |
| m5 | EMOM minute cues main-thread only; timer ignores edited minutes | **FIXED** (with a leak, N3) | `startEmom` (L3032) uses `b.minutes`, `scheduleEmomCues` pre-schedules 7 `minute` + 1 `done`; `tick` no longer beeps for `emom`. Verified by intercepting `AC.audio.scheduleAt`. |
| m6 | Interval catch-up chains phases from `now` | **PARTIALLY FIXED** | `onTimerZero('interval-effort')` passes `timer.endAt` as `anchor` to `startIntervalRest`; probe `interval_catchup` (clock jumped 3 min): rest `startedAt === effort.endAt`, and the second tick rolls through to `ready-effort`. `getset → startEffort` is still un-anchored (10 s, negligible). |
| m7 | No debounce on Log in rounds mode | **FIXED** (with a wedge, N4) | 400 ms same-slot guard in `logSet` (L2845) plus `btn.disabled` in `setsBlock`. Rounds A→B fast taps are not swallowed (different slots). |
| m8 | `roundsMax` / `sharedLoad` unimplemented | **FIXED** | `addRound` (L3062) + "Add round 6" row on the last round; one shared Bell stepper writes every draft. Day E complex through the UI: 1 bell stepper, +1 tap → all four drafts 28, header "ROUND 5 OF 6" after adding, 24 entries all `28 kg`, schedule untouched (probe `e2e_day_e_complex`). |
| m9 | "End early" credits the current partial minute | **FIXED** | `finishEmom` (L3075): `whole + (into >= 15000 ? 1 : 0)`. |
| m10 | Day A EMOM bell prefilled from Day E one-arm swings | **FIXED** | `suggestLoadForSlot` (L2493) prefers the same `slotId` from complete sessions. |
| m11 | Quota: render on every failed save; no backup fallback; newer schema wiped silently | **PARTIALLY FIXED** | `save()` notifies only on transitions; `load()` falls back to `LS_BACKUP` on parse failure (note: that key is only ever written by a version migration, so the fallback is inert until a v2 exists); newer blob copied to `:newer` — but with no toast and nothing that reads the key back (N8). |
| m12 | `setVolume` counts bodyweight as 0 | **FIXED** | bw × reps for `loadType` bodyweight (non-mobility) — L2184. |
| m13 | `substitute()` mid-block | **WON'T-FIX-OK** | Toast "Swapped. Sets already logged keep the old exercise." documents the behaviour. |
| m14 | Body-fat % not plotted | **FIXED** | Second chart in `progress()` (L4806). |
| m15 | Wake Lock not released on "Pause and leave" | **FIXED** | `leaveSheet` (L4397) calls `AC.wake.release()`. |
| m16 | Warm-up rows labelled as a working set | **FIXED** | `setList` prints "Warm-up"; `setIndex` still shared but no longer visible. |
| nit | `blockResult` dead line `r.efforts = b.effortIndex + (… ? 0 : 0)` | **NOT FIXED** | Still at L3206. |
| nit | `lastLine` `l.reps !== null` lets `undefined` through | **NOT FIXED** | Probe `nits`: "200 lb × undefined" for a hand-edited entry. |
| nit | `engine.progress()` hard-codes `8` efforts | **NOT FIXED** | L3288. |
| nit | `rpeNote` drops the program's first sentence | **NOT FIXED** | L1261 unchanged. |
| nit | `Seed.reconcile` never updates existing slots (comment) | **NOT FIXED** | No note added. |
| nit | `AC.audio.scheduleAt` uses `Date.now()` vs `ticker.now()` | **NOT FIXED** | L1662; identical today. |
| nit | `tick()` relies on the live store reference | **NOT FIXED** | Unchanged; harmless. |

---

## B. New findings (introduced or exposed by the fixes)

### Major

#### N1. Undo on the wrap-up screen throws mid-mutation and leaves memory and storage out of sync; the last set is then lost on Finish
- **Where:** `undoLastSet` L2916–2975. The cross-block branch is guarded by `if (blk && last.blockId !== blk.id)`; at wrap-up `currentBlock()` is `null`, so control falls to the same-block branch, whose `store.update` callback pops the entry and then reads `aa.block.mode` with `aa.block === null`. The throw happens *inside* `store.update` after `entries.pop()` and before `save()`.
- **Reach:** the "Logged … · Undo" toast (`onLogged`, 6 s) is shown whenever the last block that logs sets is not followed by a rest. Day E complex: the fifth round's last set goes straight to wrap-up with the toast still up (probe `m1_undo_toast_at_wrapup_via_ui`: toast "Logged 24 × 10 / Undo" on the FINISH screen). Any other day: last carry trip / TGU set → mobility checklist → Continue within 6 s → same toast is still visible on wrap-up (seen in the Day B run's wrap-up text: "Logged 0 Undo").
- **Repro:** Day E complex, log 20 sets, on FINISH tap Undo → `pageerror: Cannot read properties of null (reading 'mode')`; `activeSession.entries` in memory = 19, in localStorage = 20. Tapping a session-RPE chip then `complete()` writes the 19-entry log — the set the user did not mean to remove is gone.
- **Fix:** make the cross-block branch handle `blk === null`: `if (!blk || last.blockId !== blk.id)` (it already rewinds to the owning block). Additionally hide the toast's Undo when `!AC.engine.canUndo()` (the helper exists at L3322 and is unused) and call `ui.hideToast()` in `finishBlock`. Add a T11 case: log the final set of the Day E complex, `undoLastSet()`, assert no throw, `blockIndex === 0`, entries 19 in both memory and `localStorage`.

#### N2. Cross-block Undo into a *rounds* block drops the set's values, so re-logging records the suggestion (0 lb on a first session) instead of what was lifted
- **Where:** `undoLastSet` cross-block branch restores `last.load/reps/rpe/distanceM` into the draft only for `straight-sets`/`carry` (L2941–2947). `rebuildBlockState` builds rounds drafts from `makeDraft` (history suggestion), so the popped set's values are discarded. The same-block branch does restore them (L2967–2971), so the two undo paths disagree.
- **Repro (probe `rounds_relog_loads_after_cross_undo`):** Day B Superset A, OHP 95 / CS row 40 for three rounds; on the transition rest panel tap Undo → screen shows Round 3 · Chest-supported row with the load stepper at **0**; tap Log without touching it → row entries `[40, 40, 0]`. In a later session the draft shows last week's top load instead of today's, which is much easier to miss.
- **Fix:** in the cross-block branch, when `bb.mode === 'rounds'`, copy `last.*` into `bb.drafts[last.slotId]` exactly as the same-block branch does (and clear `suggested`). Extend T11's M1 case to a rounds block and assert the draft equals the popped set.

### Minor

#### N3. EMOM cues leak after skip/abandon, are not re-armed after a reload, and triple up at the end
- **Where:** `emomHandles` (L3031) is cleared only in `finishEmom`. `finishBlock`/`skipBlock`, `abandon`, `prevBlock` and `complete` all leave the scheduled oscillators alive: skipping a running EMOM leaves 8 cues live (probe `emom_cue_leaks`: `cancelled 3, live 8`); abandoning leaves 8 (probe `emom_abandon_leak`: `leaked 8`) — the phone keeps beeping every minute for up to 8 minutes after the user has left the session. `resume()`/`rescheduleAudio` only reschedule the timer's end cue, so after a reload mid-EMOM the top-of-minute tones are gone (`rescheduled: [['minute', 366]]`), leaving only the main-thread vibrate/announce. At the end, `scheduleTimerAudio` schedules a `minute` pattern at `endAt`, `scheduleEmomCues` schedules `done` at the same instant, and `finishEmom` beeps `done` again on the main thread (probe `emom_end_triple_cue`).
- **Fix:** call `clearEmomCues()` from `finishBlock`, `abandon`, `prevBlock` and `complete`; in `resume()` (and `rescheduleAudio`) re-run `scheduleEmomCues` for a running EMOM but only for minute boundaries still in the future (note `scheduleAt` *beeps immediately* for past instants, so filter `startedAt + m*60000 > now`); make `scheduleTimerAudio` skip `kind === 'emom'`; drop the main-thread `beep('done')` when a scheduled `done` exists.

#### N4. The Log button can wedge itself: a log swallowed by the 400 ms guard still disables the button and nothing re-renders
- **Where:** `setsBlock` Log handler (L3963) sets `btn.disabled = true` and calls `onLogged()` unconditionally; `logSet` (L2845) returns silently when the same slot was logged < 400 ms ago. Stepper/RPE taps do not re-render (`store.subscribe` has no subscribers), so the disabled state persists.
- **Repro (probe `guard_log_undo_log`):** Log → rest-panel Undo → Log within 400 ms: the second log is swallowed, the freshly rendered button is `disabled`, and a tap 600 ms later is still ignored (`entries 0`). Recovery requires something that emits (More sheet action, leave and resume).
- **Plausibility:** three taps in 0.4 s is rare for a human, but the failure mode is a dead primary button. Also note Playwright's own Log → Skip rest → Log cadence measured 434–453 ms (probe `guard_ui_latency`), so T03 is running a few tens of milliseconds above the guard — a faster CI box could make T03 flaky by swallowing a set.
- **Fix:** have `logSet` return `true` when it logged; only disable the button and call `onLogged()` on `true`. Consider keying the guard on "same slot *and* `entries.length` unchanged since the last accepted log" rather than wall time, or lower it to ~250 ms.

#### N5. Bodyweight pull-up fallback logs `reps: null` for sets 2–4 (M2 regression on the straight-sets path)
- **Where:** `logSet` straight-sets branch L2864: `d.reps = targetReps(slot)` → `null` for `amrap`. `makeDraft`'s `reps = 0` only applies to the first set.
- **Repro (probe `m2_amrap_straight_sets`):** Day B main, swap to pull-up, set 1 draft 0 → set 8 → log; set 2 draft is `null`; log → entry `{exerciseId:'pull-up', reps:null}` while the stepper displays 0.
- **Fix:** `d.reps = targetReps(slot); if (d.reps === null && slot.logMetric === 'reps') d.reps = last.reps` (carrying the previous AMRAP count forward is the more useful default), or at least `0`. Add a T11 case that logs two pull-up sets and asserts both have numeric reps.

#### N6. The pull-up "add weight" badge renders as "↑ 0 sugg." and offers no way to act on it (M3 UI half)
- **Where:** `lastLine` L3840 prints `'↑ ' + U.num(s.load, 0) + ' sugg.'` whenever `s.suggested`; for an unloaded slot `s.load` is 0. The `reason` text is only shown through `loadStepper`'s hint, which is not rendered for bodyweight slots.
- **Repro (probe `m3_bodyweight_badge_ui`):** after a session with 11 bodyweight pull-ups, Day B main → swap to pull-up → "LAST × 11 ↑ 0 sugg.".
- **Fix:** in `lastLine`, when the slot is unloaded show `s.reason` instead of the number; make it tappable to swap back to the slot's original exercise (record `originalExerciseId` in `substitute()` so the swap-back target is known — the current `substitutions` list only contains `pull-up`).

#### N7. `render()`'s recovery path hides the exception from the console and from `pageerror`
- **Where:** L5164–5168: `catch (err) { node = recovery(err); }` — no `console.error`, no rethrow. The message is shown on screen only.
- **Effect:** the test harness's `pageerror` listener (which counts as a failure) is now blind to any exception in any screen builder; probe `c2_recovery_observability` confirms `consoleErrs: [], pageErrs: []` while the recovery screen is showing. A future rendering bug would silently degrade to the recovery screen in CI.
- **Fix:** `console.error(err); setTimeout(function () { throw err; });` inside the catch (the rethrow surfaces it to `window.onerror`/`pageerror` without affecting the recovery render).

#### N8. Damaged or newer blob on boot: data is quietly copied aside and the user is dropped onto onboarding with no message
- **Where:** `migrate` L737–745 writes `:newer` / `:damaged` and returns `freshState()`; unlike the JSON-parse failure path there is no toast, and nothing in Settings surfaces those keys.
- **Repro (probe `c2_damaged_and_newer_boot`):** `program.days = []` → reload → onboarding, `toast: null`, key `athletic-cut:v1:damaged` present; `schemaVersion: 7` with one session → reload → onboarding, sessions 0, `:newer` written, no toast.
- **Fix:** schedule the same 6 s toast as the parse-failure path ("Saved data was unreadable — a copy was kept" / "…was saved by a newer version…"), and in Settings → Data show an "Export the kept copy" button when either key exists.

#### N9. Audio unlock race: the rest cue is still not re-armed after a reload (m4 remains open in practice)
- **Where:** `installUnlockListener` L1675: `unlock()` returns `true` after calling `ctx.resume()` without awaiting it; `rescheduleAudio` → `scheduleAt` sees `ctx.state === 'suspended'` and returns `null`.
- **Evidence (probe `m4_unlock_reschedule`):** at the first tap `{pattern:'rest', result:null, state:'suspended'}`; 200 ms later the context is `running`.
- **Fix:** in `unlock()`, if the state was suspended, `ctx.resume().then(function () { AC.engine.rescheduleAudio(); })`; or hook `ctx.onstatechange`.

#### N10. `prevBlock`/cross-block undo into an EMOM or interval block re-initialises it, so the block can be run again on top of its logged sets (M6 remainder)
- **Repro (probe `m6_prevBlock_into_emom_and_interval`):** Day A EMOM finished (8 entries) → Done → More → Previous block → status `ready`, "Start EMOM" visible, 8 entries still present; starting it again appends 8 more with duplicate `setIndex`.
- **Fix:** in `rebuildBlockState`, when entries exist for an `emom` block set `status:'finished', minute: minutes, roundsCompleted: mine.length`; for `interval` set `status:'finished', effortIndex: mine.length`; for `checklist` restore `checked` from the filtered `blockResults` before filtering them out.

### Nits
- **N11.** With a paused session, Day E → "Nothing" now demands abandoning it first (`startFlow` guard runs before the option chooser). Let the `nothing` branch bypass the guard.
- **N12.** `lastLine` badge rounds the suggestion (`↑ 103 sugg.` for 102.5 kg); `lastLine`/`setList` still print `× undefined` for entries without `reps`; `engine.canUndo` is defined but never used; `blockResult` dead line; `progress()` hard-coded 8.
- **N13.** Cross-block undo that jumps over an intervening *skipped* block leaves that block's `blockResult` in place, so it is pushed a second time when the user walks forward again (duplicate `blockResults` rows in the saved log).
- **N14.** The "Logged … · Undo" toast survives into the summary screen (seen in the Day B run); tapping it there is a no-op, but it should be hidden on `complete()`.

---

## C. End-to-end sanity pass (real browser, UI-driven)

| Session | Result |
|---|---|
| **Day B** (probe `e2e_day_b`) | Prep 4/4 → Main "SET 1 OF 4", weighted pull-up +10 lb via stepper, RPE 8, 4 sets each starting a 150 s rest, transition rest into Superset A → 3 rounds (OHP/CS row) → 60 s transition → Superset B: dips stepper shows 0, +8 → logged `[0 lb, 8]` × 2, face pulls × 2 → Finisher "TRIP 1 OF 4" → 4 trips at 40 m (`setIndex 0–3`) → Mobility → Finish → RPE 7 → summary "TOTAL VOLUME 22,966 lb · 18 sets · HELD ALL LIFTS" → Done. Saved: `complete`, 18 entries, `dateKey` today, `nextRequiredIndex` 1 → 2, `activeSession` null. Correct. |
| **Day C** (probe `e2e_day_c`, fake clock) | Prep → "SPRINTS · EFFORT 1 OF 8" → Start sprints → get-set 10 s → effort 20 s auto-ends → rest → "felt slower" → Skip rest → efforts 2–8 via Start/End effort (effort 2 also marked slower → "Stop sprints" suggestion shown), rest 90 s → "EFFORT 8 OF 8", buttons Finish / One more → Finish → Done → circuit 2 rounds (landmine, Pallof, side plank via "Start 30s hold" + 30 s) → Mobility → Finish. Saved: 14 entries (8 sprints `seconds 20`, 2 + 2 + 2 circuit with plank `seconds 30`), `blockResults.c-sprints = {efforts 8, slowerFlags [0,1], stoppedEarly false}`, schedule 2 → 3. Correct. |
| **Day E kettlebell complex** (probe `e2e_day_e_complex`) | Home → Start a different day → Day E → Kettlebell complex → one shared Bell stepper showing 24; + → 28 in all four drafts → rounds 1–4 with 90 s rests → "Add round 6" visible on round 5, tapped → header "ROUND 5 OF 6" → round 6 → Finish → RPE 7 → Done. Saved: 24 entries, every load `28 kg`, `setIndex` up to 5, `nextRequiredIndex` unchanged (0). Correct. |

No JS errors in any of the three runs. Misbehaviour observed during the pass: the "Logged … · Undo" toast from the last logged block is still on screen at wrap-up (N1/N14).

---

## Recommended regression tests to add (T11)
1. Day E complex: log the 20th set, `undoLastSet()` at wrap-up — no throw, back in `e-kb` round 5 item 4, entries 19 in memory **and** in `localStorage`.
2. Day B Superset A: three rounds with custom loads, cross-block `undoLastSet()`, assert `drafts['b-ss-a-2'].load === 40`, re-log, assert the row load is 40.
3. Pull-up substitution: log two sets without touching reps; both `reps` numeric.
4. EMOM: intercept `AC.audio.scheduleAt/cancel`, start EMOM, `skipBlock()` and separately `abandon()`, assert every scheduled id was cancelled.
5. Force a throw inside a screen builder and assert `pageerror` fires (once N7 is applied) — otherwise keep a screen-text assertion so the recovery path stays observable.
