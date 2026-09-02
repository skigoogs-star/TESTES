# Athletic Cut

A single-file, offline web app that runs and tracks the Athletic Cut training
program: 12–14 weeks, four required days plus one optional, 35–40 minutes each.

Open `index.html` on a phone and add it to the home screen. There is no account,
no server, and no network traffic after the page loads.

## What it does

- **Runs the session.** Prep checklist → main lift → superset → finisher →
  mobility, with the right input for each: straight sets, superset rounds, an
  EMOM finisher, sprint intervals, loaded carries, and timed holds.
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
- **Charts what matters.** Bodyweight as raw dots under a dominant 7-day rolling
  average, waist against its target band, estimated 1RM per main lift (Epley),
  and weekly volume by movement pattern.
- **Shows every movement.** All 38 exercises have a two-frame stick-figure
  drawing, 3–5 coaching cues and the common mistakes. You can replace any figure
  with a clip of yourself, stored on-device in IndexedDB.

## Data

Everything lives in one versioned `localStorage` key (`athletic-cut:v1`) with a
`schemaVersion` and a migration ladder, so future changes upgrade your history
instead of wiping it. Video clips go in IndexedDB. Export and import the whole
thing as JSON from **More**.

## Tests

```
node tests/run.mjs      # 173 checks, headless Chromium
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
It also asserts the drawing invariants directly: no stick figure may change a
limb's length between its two frames, and the light theme must resolve the
darker amber used for non-text graphics.

## Deliberately not built

Accounts, social sharing, a food database, video streaming, an AI coach, system
notifications, cloud sync. Nutrition is three numbers a day, because anything
more does not get used.
