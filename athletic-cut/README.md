# Athletic Cut

A single-file, offline web app that runs and tracks the Athletic Cut training
program: 12–14 weeks, four required days plus one optional, 35–40 minutes each.

Open `index.html` on a phone and add it to the home screen. There is no account,
no server, and no network traffic after the page loads.

## What it does

- **Runs the session.** Prep checklist → main lift → superset → finisher →
  mobility, with the right input for each: straight sets, superset rounds, an
  EMOM finisher, sprint intervals, loaded carries, and timed holds.
- **You decide when a block is over.** Log the prescribed sets and the block
  says it's done rather than moving on by itself: **+ Add a set** (or **+ Add a
  round** in a superset) keeps you there for as long as you have something left,
  and **Next** moves on when you're ready. Anything you add applies to today
  only — the program itself is untouched.
- **One Back button that undoes whatever you just did.** Logged a set by
  mistake, it removes that set and puts you back on it. Moved past an exercise
  by mistake, it returns you there with everything you logged intact, and a prep
  checklist keeps its ticks.
- **Never asks for the keyboard mid-workout.** Load, reps, distance and RPE are
  all steppers and chips. Tapping a number opens the app's own numeric pad, not
  the OS keyboard.
- **Rest timers you can trust.** Every timer is absolute wall-clock time, so
  backgrounding the app, locking the phone or refreshing the page does not
  change the countdown. The end-of-rest beep is scheduled on the Web Audio clock
  the moment the timer starts, so it fires even when the main thread is throttled.
- **Survives a refresh mid-set.** Every tap — including a stepper nudge you
  haven't logged yet — is written to `localStorage` before the screen redraws.
  Reopening lands you on the exact set with the timer still running.
- **Suggests load, and never suggests less.** If your final set hit the top of
  the rep range at RPE 8 or below, the next session prefills one increment
  higher and says why. A decrease is never applied for you.
- **Tells you when it's calories, not effort.** Two flat weeks on a main lift
  raises the program's own advice. The weekly review reads the bodyweight trend
  and warns when you're losing faster than 1.5 lb/week.
- **Exports the week as one Markdown document.** **More → Weekly report**, or the
  button at the top of Progress. It contains adherence against the four required
  days, every set with its load, reps and RPE, estimated 1RM per main lift
  against the previous week, tonnage and volume by movement pattern, daily
  bodyweight with the week-over-week change, the nutrition table with a
  days-on-target count, EMOM rounds and sprint efforts, and your session notes.
  Copy is the primary action, because pasting it into a chat is the point.
- **Charts what matters.** Bodyweight as raw dots under a dominant 7-day rolling
  average, waist against its target band, estimated 1RM per main lift (Epley),
  and weekly volume by movement pattern.
- **Shows every movement.** All 38 exercises have a two-frame stick-figure
  drawing, 3–5 coaching cues and the common mistakes. You can replace any figure
  with a clip of yourself, stored on-device in IndexedDB.

## What's new in v2

- **A calmer, clearer look.** Sentence-case labels, three surface levels with hairlines,
  and strict colour jobs: gold is the action, cyan is rest, green is saved/done/record.
- **Home shows the day.** Today's session as numbered steps (blocks, sets, finisher),
  a week strip with A–D plus the optional day, bigger stat tiles with a weekly ring
  ("4 sessions this week", no daily streaks), real Weight / Nutrition / History buttons,
  and **Resume session · Set 2 of 4** when a session is open.
- **The session tells you where you are.** A labelled phase bar (Prep · Lift · Superset ·
  Finish · Mobility), the set count on its own line, one cue with **More cues**, load and
  reps in one card, a quiet "Set 1 saved" check, then REST with the next set right under
  the timer, and a compact list of completed sets.
- **Plates per side.** Barbell loads show the plates for each side from your bar weight,
  trap-bar weight and the plates you own (More → Workout → Plate calculator).
- **Optional warm-up ramp** for the main barbell lifts (40/60/80 % of the working load);
  tap a row to log it as a warm-up. It never counts as a working set and starts no timer.
- **Lift history on the lift screen**: best set, the last three sessions and a small trend.
- **A real finish.** Duration, sets done against planned, volume, the strongest record as
  a big green card (load, rep and estimated-1RM records are detected separately), the
  real next day, then Done. **Finish early** saves the work and says the session ended;
  **Discard** asks first and keeps the sets in history as abandoned.
- **History you can fix.** Edit any past set's load or reps and the session note; the
  session's volume follows.
- **Library**: one filter row with Favorites (★ on any exercise) and Recent, a result
  count, and Start / Finish / Play controls on every demonstration.
- **Progress** opens with one takeaway line for the range and keeps the range controls
  in view. **More** is grouped into Workout, Appearance and Data, and the report is now
  **Export weekly report**.
- **Your data carries over.** v2 reads the same `athletic-cut:v1` key; new settings arrive
  with defaults and nothing else changes. A test imports a backup made by v1 and opens v2
  on a v1 phone's storage, and checks every session, metric, scan and setting survives.

## Data

Everything lives in one versioned `localStorage` key (`athletic-cut:v1`) with a
`schemaVersion` and a migration ladder, so future changes upgrade your history
instead of wiping it. Video clips go in IndexedDB. Export and import the whole
thing as JSON from **More**.

## Tests

```
node tests/run.mjs      # 356 checks, headless Chromium
node tests/shots.mjs    # screenshots of every screen into tests/shots/
```

The suite drives a complete Day A session with a keydown counter asserting zero
keyboard events, reloads mid-session and diffs the restored state, fast-forwards
the clock through the EMOM and sprint engines, round-trips an export, and checks
that no tap target is under 44×44 and no button lacks an accessible name.

It also pins the things that were caught in review and must not come back:
starting another day cannot discard an in-progress session, five shapes of
malformed backup are rejected without touching stored data, undo across a block
boundary rewinds correctly, a Sunday-evening session in `America/Los_Angeles`
counts toward the local week rather than the UTC one, a timed hold logs the item
it was started for, and the second exercise of a superset shows its own cues.
Back and Add-a-set have their own cases: a mistaken set is removed and returned
to, a block you moved past comes back whole, an added set extends only today's
session, and stepping back and forward through an EMOM logs nothing twice.
It also asserts the drawing invariants directly: no stick figure may change a
limb's length between its two frames, and the light theme must resolve the
darker amber used for non-text graphics.

## Deliberately not built

Accounts, social sharing, a food database, video streaming, an AI coach, system
notifications, cloud sync. Nutrition is three numbers a day, because anything
more does not get used.
