/* Acceptance tests for athletic-cut/index.html.
   Run: node tests/run.mjs            (from the athletic-cut directory)
   Chromium comes from PLAYWRIGHT_BROWSERS_PATH; nothing is downloaded. */
import { chromium } from '/opt/node22/lib/node_modules/playwright/index.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = path.join(ROOT, 'index.html');

let pass = 0, fail = 0;
const fails = [];
function ok(name, cond, detail) {
  if (cond) { pass++; console.log('  \x1b[32mPASS\x1b[0m ' + name); }
  else { fail++; fails.push(name + (detail ? ' — ' + detail : '')); console.log('  \x1b[31mFAIL\x1b[0m ' + name + (detail ? ' — ' + detail : '')); }
}
function eq(name, actual, expected) {
  ok(name, JSON.stringify(actual) === JSON.stringify(expected), 'got ' + JSON.stringify(actual) + ' want ' + JSON.stringify(expected));
}

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];
  const f = url === '/' ? FILE : path.join(ROOT, url);
  if (!f.startsWith(ROOT) || !fs.existsSync(f)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(fs.readFileSync(f));
});
await new Promise(r => server.listen(0, r));
const BASE = 'http://127.0.0.1:' + server.address().port + '/';

const browser = await chromium.launch();
let P = null;
async function newPage(opts = {}) {
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 }, deviceScaleFactor: 3,
    isMobile: true, hasTouch: true, ...opts
  });
  const page = await ctx.newPage();
  page.on('pageerror', e => { fail++; fails.push('pageerror: ' + e.message); console.log('  \x1b[31mJS ERROR\x1b[0m ' + e.message); });
  P = page;
  return { ctx, page };
}
const tap = (page, name) => page.getByRole('button', { name }).first().click();
const state = page => page.evaluate(() => JSON.parse(JSON.stringify(AC.store.get())));
async function skipRest() {
  const s = await P.evaluate(() => {
    const a = AC.store.get().activeSession;
    return a && a.timer && a.timer.kind === 'rest';
  });
  if (s) await P.getByRole('button', { name: /Skip rest/i }).first().click();
  return s;
}
const hdr = () => P.locator('.runner-head .cap').first().innerText();

/* ---------------------------------------------------------------- T01 */
console.log('\nT01  static audit — single file, no network, no non-goals');
{
  const src = fs.readFileSync(FILE, 'utf8');
  ok('file under 400 KB', Buffer.byteLength(src) < 400 * 1024, Math.round(Buffer.byteLength(src) / 1024) + ' KB');
  ok('no <script src>', !/<script[^>]+src=/i.test(src));
  ok('no <link rel=stylesheet>', !/<link[^>]+stylesheet/i.test(src));
  ok('no @import', !/@import/.test(src));
  ok('no external http(s) resource attribute', !/(?:src|href)\s*=\s*["']https?:/i.test(src));
  const js = src.match(/<script>([\s\S]*)<\/script>/)[1];
  for (const bad of ['fetch(', 'XMLHttpRequest', 'WebSocket', 'serviceWorker', 'new Notification'])
    ok('no ' + bad, !js.includes(bad));
}

/* ---------------------------------------------------------------- T02 */
console.log('\nT02  boot, seed integrity, zero network requests');
{
  const { ctx, page } = await newPage();
  const reqs = [];
  page.on('request', r => reqs.push(r.url()));
  const t0 = Date.now();
  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForSelector('#app button');
  ok('interactive quickly', Date.now() - t0 < 2000, (Date.now() - t0) + ' ms');
  const domInteractive = await page.evaluate(() =>
    performance.timing.domInteractive - performance.timing.navigationStart);
  ok('domInteractive < 1000 ms', domInteractive < 1000, domInteractive + ' ms');
  ok('exactly one network request (the page)', reqs.length === 1, JSON.stringify(reqs));

  const s = await state(page);
  eq('5 days seeded', s.program.days.length, 5);
  eq('day A block ids', s.program.days[0].blocks.map(b => b.id), ['a-prep', 'a-main', 'a-ss', 'a-fin', 'a-mob']);
  const main = s.program.days[0].blocks[1];
  eq('trap bar 4 sets', main.items[0].sets, 4);
  eq('trap bar 5 reps', main.items[0].repsTarget.min, 5);
  eq('trap bar RPE 8', main.items[0].rpeTarget, 8);
  eq('main rest 150 s', main.restSeconds, 150);
  eq('EMOM 8 x 12', [s.program.days[0].blocks[3].emom.minutes, s.program.days[0].blocks[3].emom.repsPerMinute], [8, 12]);
  eq('front squat 4 x 6', [s.program.days[3].blocks[1].items[0].sets, s.program.days[3].blocks[1].items[0].repsTarget.min], [4, 6]);
  eq('sprints 8-10 x 15-20 s / 90 s',
    [s.program.days[2].blocks[1].interval.effortsMin, s.program.days[2].blocks[1].interval.effortsMax,
     s.program.days[2].blocks[1].interval.effortSecondsMin, s.program.days[2].blocks[1].interval.effortSecondsMax,
     s.program.days[2].blocks[1].interval.restSeconds], [8, 10, 15, 20, 90]);
  eq('farmer carry 4 x 40 m',
    [s.program.days[1].blocks[4].items[0].sets, s.program.days[1].blocks[4].items[0].distanceM], [4, 40]);
  eq('Day E has 3 options', s.program.days[4].options.length, 3);

  const exCount = Object.keys(s.exercises).length;
  ok('38 exercises seeded', exCount === 38, String(exCount));
  const badCues = Object.values(s.exercises).filter(e => !(e.cues.length >= 3 && e.cues.length <= 5));
  eq('every exercise has 3-5 cues', badCues.map(e => e.id), []);
  const badMist = Object.values(s.exercises).filter(e => !(e.mistakes.length >= 1 && e.mistakes.length <= 2));
  eq('every exercise has 1-2 mistakes', badMist.map(e => e.id), []);
  const missingFig = await page.evaluate(() =>
    Object.values(AC.store.get().exercises).filter(e => !AC.figures.F[e.mediaRef]).map(e => e.id));
  eq('every exercise has its own figure', missingFig, []);
  const unresolved = await page.evaluate(() => {
    const s = AC.store.get(), out = [];
    s.program.days.forEach(d => {
      const all = (d.blocks || []).concat((d.options || []).flatMap(o => o.blocks));
      all.forEach(b => (b.items || []).concat(b.alternateItems || []).forEach(i => {
        if (!s.exercises[i.exerciseId]) out.push(i.exerciseId);
        (i.substitutions || []).forEach(x => { if (!s.exercises[x]) out.push(x); });
      }));
    });
    return out;
  });
  eq('every slot resolves to a seeded exercise', unresolved, []);
  await ctx.close();
}

/* ---------------------------------------------------------------- T03 */
console.log('\nT03  full Day A, start to finish, zero keyboard input');
{
  const { ctx, page } = await newPage();
  await page.clock.install();
  await page.goto(BASE);
  await page.evaluate(() => { window.__keys = 0; addEventListener('keydown', () => window.__keys++, true); });
  await tap(page, /Begin week 1/i);
  await tap(page, /Start session/i);
  ok('runner opens on prep', (await hdr()).includes('PREP'));

  const smallTaps = [];
  async function tapChecked(locator, label) {
    const box = await locator.boundingBox();
    if (box && (box.width < 44 || box.height < 44)) smallTaps.push(label + ' ' + Math.round(box.width) + 'x' + Math.round(box.height));
    await locator.click();
  }
  const checks = page.locator('button.check');
  const n = await checks.count();
  for (let i = 0; i < n; i++) await tapChecked(checks.nth(i), 'checklist row');
  await tap(page, /^Continue/i);
  ok('advanced to main lift', (await hdr()).includes('MAIN LIFT · SET 1 OF 4'));

  const plus = page.locator('.stepper').first().locator('.step-btn').last();
  for (let i = 0; i < 9; i++) await tapChecked(plus, 'stepper +');
  const loadTxt = await page.locator('.stepper').first().locator('.step-val').innerText();
  eq('load stepped to 45 lb (9 x 5)', loadTxt.trim(), '45');
  await tapChecked(page.getByRole('radio', { name: /RPE 8/ }), 'RPE chip');
  for (let set = 1; set <= 4; set++) {
    await tapChecked(page.getByRole('button', { name: /^Log set/i }), 'log set');
    const st = await state(page);
    ok('set ' + set + ' started a 150 s rest timer', st.activeSession.timer &&
      st.activeSession.timer.endAt - st.activeSession.timer.startedAt === 150000);
    await skipRest();
  }
  let s = await state(page);
  eq('4 main-lift sets logged', s.activeSession.entries.length, 4);
  ok('advanced to superset', (await hdr()).includes('SUPERSET'));

  for (let round = 1; round <= 3; round++) {
    await tapChecked(page.getByRole('button', { name: /^Log/i }), 'log A');
    await tapChecked(page.getByRole('button', { name: /^Log/i }), 'log B');
    if (round < 3) {
      const st = await state(page);
      ok('round ' + round + ' rests 60 s', st.activeSession.timer &&
        st.activeSession.timer.endAt - st.activeSession.timer.startedAt === 60000);
    }
    await skipRest();
  }
  s = await state(page);
  eq('superset logged 6 sets', s.activeSession.entries.length, 10);
  ok('advanced to EMOM', (await hdr()).includes('EMOM'));

  await tapChecked(page.getByRole('button', { name: /Start EMOM/i }), 'start emom');
  await page.clock.runFor(8 * 60 * 1000 + 500);
  await page.waitForTimeout(50);
  s = await state(page);
  eq('EMOM logged 8 rounds', s.activeSession.entries.length - 10, 8);
  eq('EMOM reps are 12', s.activeSession.entries[10].reps, 12);
  await tapChecked(page.getByRole('button', { name: /^Done$/i }), 'emom done');
  ok('advanced to mobility', (await hdr()).includes('MOBILITY'));
  await tapChecked(page.locator('button.check').first(), 'mobility row');
  await tap(page, /^Continue/i);
  await tapChecked(page.getByRole('button', { name: /Session RPE 7/ }), 'session RPE');
  await page.waitForSelector('text=/Total volume/i');
  ok('summary card shows total volume', true);
  await tap(page, /^Done$/i);

  s = await state(page);
  eq('session complete', s.sessions[0].status, 'complete');
  eq('18 sets logged', s.sessions[0].entries.length, 18);
  eq('schedule advanced to Day B', s.schedule.nextRequiredIndex, 1);
  ok('total volume computed', s.sessions[0].totals.volumeLb > 0, String(s.sessions[0].totals.volumeLb));
  eq('zero keyboard events', await page.evaluate(() => window.__keys), 0);
  eq('no tap target under 44px', smallTaps, []);
  await ctx.close();
}

/* ---------------------------------------------------------------- T04 */
console.log('\nT04  refresh and browser-close mid-session restore exactly');
{
  const { ctx, page } = await newPage();
  await page.goto(BASE);
  await tap(page, /Begin week 1/i);
  await tap(page, /Start session/i);
  const checks = page.locator('button.check');
  for (let i = 0, n = await checks.count(); i < n; i++) await checks.nth(i).click();
  await tap(page, /^Continue/i);
  const plus = page.locator('.stepper').first().locator('.step-btn').last();
  for (let i = 0; i < 4; i++) await plus.click();          // 20 lb
  await page.getByRole('button', { name: /^Log set/i }).click();
  await page.waitForTimeout(1200);
  for (let i = 0; i < 2; i++) await page.locator('.restbar, .stepper').first().waitFor();
  const before = await state(page);
  const remainBefore = before.activeSession.timer.endAt - Date.now();

  await page.reload();
  await page.waitForSelector('.runner');
  ok('lands straight on the runner, not home', (await page.locator('.runner').count()) === 1);
  ok('restored to set 2 of 4', (await hdr()).includes('SET 2 OF 4'));
  const after = await state(page);
  eq('entries preserved', after.activeSession.entries.length, 1);
  eq('draft load preserved', after.activeSession.block.draft.load, 20);
  eq('timer endAt preserved', after.activeSession.timer.endAt, before.activeSession.timer.endAt);
  const remainAfter = after.activeSession.timer.endAt - Date.now();
  ok('rest timer kept counting across the refresh', Math.abs(remainAfter - remainBefore) < 3000,
    Math.round(remainBefore / 1000) + 's -> ' + Math.round(remainAfter / 1000) + 's');
  const shownRaw = await page.locator('.dial-num, .restbar .t').first().innerText();
  const parts = shownRaw.trim().split(':').map(Number);
  const shown = parts.length === 2 ? parts[0] * 60 + parts[1] : parts[0];
  ok('countdown on screen matches the clock', Math.abs(shown - remainAfter / 1000) < 3,
    shownRaw + ' vs ' + Math.round(remainAfter / 1000) + 's');

  // "closing the browser": brand new context reusing the same storage
  const storage = await ctx.storageState();
  await ctx.close();
  const { ctx: ctx2, page: page2 } = await newPage({ storageState: storage });
  await page2.goto(BASE);
  await page2.waitForSelector('.runner');
  const reopened = await state(page2);
  eq('reopened at the same set', reopened.activeSession.block.setIndex, 1);
  eq('reopened with the same draft', reopened.activeSession.block.draft.load, 20);
  await ctx2.close();
}

/* ---------------------------------------------------------------- T05 */
console.log('\nT05  timers are wall-clock and survive backgrounding');
{
  const { ctx, page } = await newPage();
  await page.clock.install();
  await page.goto(BASE);
  await tap(page, /Begin week 1/i);
  await tap(page, /Start session/i);
  await tap(page, /^Continue/i);
  await page.getByRole('button', { name: /^Log set/i }).click();
  await page.evaluate(() => Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true }));
  await page.clock.runFor(60_000);
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.waitForTimeout(50);
  const rem = await page.evaluate(() => AC.engine.timerRemaining());
  ok('90 s left after 60 s hidden', Math.abs(rem - 90) < 2, String(Math.round(rem)));
  await page.clock.runFor(95_000);
  await page.waitForTimeout(50);
  const s = await state(page);
  ok('timer cleared past zero', !s.activeSession.timer || s.activeSession.timer.fired);
  eq('a rest cue was played', await page.evaluate(() => AC.audio._last()), 'rest');
  await ctx.close();
}

/* ---------------------------------------------------------------- T06 */
console.log('\nT06  export / import round trip');
{
  const { ctx, page } = await newPage();
  await page.goto(BASE);
  await tap(page, /Begin week 1/i);
  await page.evaluate(() => {
    AC.store.update(s => {
      s.nutrition['2026-08-30'] = { calories: 2600, proteinG: 195, steps: 9000 };
      s.metrics.push({ id: 'm1', date: '2026-08-29', type: 'weight', value: 198.4, unit: 'lb', source: 'manual' });
      s.sessions.push({ id: 'x1', dayId: 'day-a', startedAt: '2026-08-29T10:00:00.000Z',
        completedAt: '2026-08-29T10:40:00.000Z', status: 'complete',
        entries: [{ blockId: 'a-main', slotId: 'a-main-1', exerciseId: 'trap-bar-deadlift', setIndex: 0,
          load: 245, loadUnit: 'lb', reps: 5, rpe: 8, distanceM: null, seconds: null, isWarmup: false,
          completedAt: '2026-08-29T10:20:00.000Z' }],
        sessionRPE: 8, bodyweightAtTime: 198.4, notes: '', blockResults: [],
        totals: { volumeLb: 1225, setsLogged: 1, durationSec: 2400 }, beatLast: [] });
    });
  });
  const json = await page.evaluate(() => AC.store.exportJSON());
  const before = await state(page);
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await page.waitForSelector('#app button');
  const wiped = await state(page);
  eq('wiped state has no sessions', wiped.sessions.length, 0);
  const res = await page.evaluate(j => AC.store.importJSON(j), json);
  eq('import reports ok', res, { ok: true });
  const after = await state(page);
  eq('sessions restored', after.sessions.length, before.sessions.length);
  eq('metrics restored', after.metrics.length, before.metrics.length);
  eq('nutrition restored', after.nutrition['2026-08-30'], before.nutrition['2026-08-30']);
  eq('set detail restored', after.sessions[0].entries[0].load, 245);
  const bad = await page.evaluate(() => AC.store.importJSON('{"app":"something-else"}'));
  ok('foreign file rejected', bad.ok === false, JSON.stringify(bad));
  const badver = await page.evaluate(() => AC.store.importJSON('{"app":"athletic-cut","schemaVersion":99,"state":{}}'));
  ok('future schema rejected', badver.ok === false, JSON.stringify(badver));
  await ctx.close();
}

/* ---------------------------------------------------------------- T07 */
console.log('\nT07  algorithms');
{
  const { ctx, page } = await newPage();
  await page.goto(BASE);
  await tap(page, /Begin week 1/i);
  const r = await page.evaluate(() => {
    const U = AC.util;
    const out = {};
    out.epley1 = AC.stats.epley(200, 1);
    out.epley5 = Math.round(AC.stats.epley(245, 5) * 100) / 100;
    out.week = U.isoWeekKey('2026-01-01');
    out.weekDec = U.isoWeekKey('2026-12-28');
    out.kbUp = AC.engine.kbUp(24);
    out.kbDown = AC.engine.kbDown(24);
    out.kbSnap = AC.engine.kbSnap(23);
    out.incBar = AC.engine.increment('barbell', 'lb');
    out.incWbw = AC.engine.increment('weighted-bodyweight', 'lb');
    out.incBarKg = AC.engine.increment('barbell', 'kg');
    return out;
  });
  eq('Epley at 1 rep is the load', r.epley1, 200);
  eq('Epley 245x5', r.epley5, 285.83);
  eq('ISO week 2026-01-01', r.week, '2026-W01');
  eq('ISO week 2026-12-28', r.weekDec, '2026-W53');
  eq('kettlebell up from 24', r.kbUp, 28);
  eq('kettlebell down from 24', r.kbDown, 20);
  eq('kettlebell snap 23 -> 24', r.kbSnap, 24);
  eq('barbell increment lb', r.incBar, 5);
  eq('weighted-bodyweight increment', r.incWbw, 2.5);
  eq('barbell increment kg', r.incBarKg, 2.5);

  // load suggestion table
  const sugg = await page.evaluate(() => {
    const slot = { exerciseId: 'trap-bar-deadlift', loadType: 'barbell',
      repsTarget: { kind: 'reps', min: 5, max: 7 }, substitutions: [] };
    function seed(reps, rpe) {
      AC.store.update(s => {
        s.sessions = [{ id: 'z', dayId: 'day-a', startedAt: '2026-08-01T10:00:00.000Z',
          completedAt: '2026-08-01T11:00:00.000Z', status: 'complete', sessionRPE: 8,
          bodyweightAtTime: 199, notes: '', blockResults: [], totals: {}, beatLast: [],
          entries: [
            { exerciseId: 'trap-bar-deadlift', load: 245, loadUnit: 'lb', reps: 5, rpe: 8, isWarmup: false, setIndex: 0, seconds: null, distanceM: null },
            { exerciseId: 'trap-bar-deadlift', load: 245, loadUnit: 'lb', reps: reps, rpe: rpe, isWarmup: false, setIndex: 1, seconds: null, distanceM: null }
          ] }];
      });
      const r = AC.engine.suggestLoad(slot);
      return [r.load, r.suggested];
    }
    return { hit: seed(7, 8), hard: seed(7, 9), short: seed(5, 8), noRpe: seed(7, null) };
  });
  eq('7 reps @ RPE 8 suggests +5', sugg.hit, [250, true]);
  eq('7 reps @ RPE 9 holds', sugg.hard, [245, false]);
  eq('5 reps @ RPE 8 holds', sugg.short, [245, false]);
  eq('7 reps with no RPE holds', sugg.noRpe, [245, false]);

  // 7-day rolling average
  const roll = await page.evaluate(() => {
    AC.store.update(s => {
      s.metrics = [];
      for (let i = 13; i >= 0; i--) {
        s.metrics.push({ id: 'w' + i, date: AC.util.addDays(AC.util.todayKey(), -i),
          type: 'weight', value: 200 - i * 0.1, unit: 'lb', source: 'manual' });
      }
    });
    const r = AC.stats.rolling7('weight');
    const last = r[r.length - 1];
    let manual = 0;
    for (let i = 0; i < 7; i++) manual += 200 - i * 0.1;
    return { points: r.length, avg: Math.round(last.avg * 1000) / 1000, manual: Math.round(manual / 7 * 1000) / 1000 };
  });
  eq('14 rolling points', roll.points, 14);
  eq('rolling average matches a manual mean', roll.avg, roll.manual);

  // stall detection + weekly review
  const cards = await page.evaluate(() => {
    const U = AC.util;
    AC.store.update(s => {
      s.sessions = [];
      s.flags.dismissedStalls = {};
      [3, 2, 1].forEach((wksAgo, i) => {
        const d = U.addDays(U.weekStart(U.todayKey()), -wksAgo * 7 + 1);
        s.sessions.push({ id: 'st' + i, dayId: 'day-a', startedAt: d + 'T10:00:00.000Z',
          completedAt: d + 'T11:00:00.000Z', status: 'complete', sessionRPE: 8,
          bodyweightAtTime: 199, notes: '', blockResults: [], totals: {}, beatLast: [],
          entries: [{ exerciseId: 'trap-bar-deadlift', load: 245, loadUnit: 'lb', reps: 5, rpe: 8,
            isWarmup: false, setIndex: 0, seconds: null, distanceM: null }] });
      });
    });
    return AC.stats.stallCheck().map(c => c.text);
  });
  ok('flat e1RM for two weeks raises a stall card', cards.length === 1 && /has stalled two weeks/.test(cards[0]), JSON.stringify(cards));

  const review = await page.evaluate(() => {
    const U = AC.util;
    function seedWeights(perWeek) {
      AC.store.update(s => {
        s.metrics = []; s.flags.lastWeeklyReviewWeek = null;
        s.schedule.programStartDate = U.addDays(U.todayKey(), -40);
        perWeek.forEach((val, wi) => {
          for (let d = 0; d < 4; d++) {
            const date = U.addDays(U.weekStart(U.todayKey()), -(wi + 1) * 7 + d);
            s.metrics.push({ id: 'r' + wi + d, date: date, type: 'weight', value: val, unit: 'lb', source: 'manual' });
          }
        });
      });
      const r = AC.stats.weeklyReview();
      return r ? r.text : null;
    }
    return {
      fast: seedWeights([195.4, 197.2, 199.0]),
      onTarget: seedWeights([197.6, 198.4, 199.2]),
      flat: seedWeights([199.0, 199.05, 199.1])
    };
  });
  ok('1.8 lb/wk twice triggers eat-more', /Eat more/.test(review.fast || ''), review.fast);
  ok('0.8 lb/wk reads on target', /On target/.test(review.onTarget || ''), review.onTarget);
  ok('flat two weeks says drop 300', /Drop 300/.test(review.flat || ''), review.flat);
  await ctx.close();
}

/* ---------------------------------------------------------------- T08 */
console.log('\nT08  schedule advancement');
{
  const { ctx, page } = await newPage();
  await page.goto(BASE);
  await tap(page, /Begin week 1/i);
  const r = await page.evaluate(() => {
    const out = [];
    function run(dayId, opt, status) {
      AC.engine.start(dayId, opt);
      if (status === 'abandon') AC.engine.abandon(); else AC.engine.complete({ sessionRPE: 7 });
      out.push(AC.store.get().schedule.nextRequiredIndex);
    }
    run('day-a'); run('day-c'); run('day-d'); run('day-e', 'kb-complex');
    const cyclesBefore = AC.store.get().schedule.cycleCount;
    run('day-b', null, 'abandon');
    return { seq: out, cycles: cyclesBefore, after: AC.store.get().schedule.nextRequiredIndex };
  });
  eq('A -> B, C -> D, D -> A, E unchanged, abandon unchanged', r.seq, [1, 3, 0, 0, 0]);
  eq('abandoning does not advance', r.after, 0);
  await ctx.close();
}

/* ---------------------------------------------------------------- T09 */
console.log('\nT09  remaining block modes');
{
  const { ctx, page } = await newPage();
  await page.clock.install();
  await page.goto(BASE);
  await tap(page, /Begin week 1/i);
  // Day B carry
  await page.evaluate(() => { AC.engine.start('day-b'); for (let i = 0; i < 4; i++) AC.engine.nextBlock(); AC.router.go('session'); });
  await page.waitForSelector('.runner');
  await page.waitForTimeout(30);
  ok('carry block reached', (await hdr()).includes('TRIP 1 OF 4'));
  const dist = page.locator('.stepper').nth(1).locator('.step-val');
  eq('distance defaults to 40 m', (await dist.innerText()).trim(), '40');
  for (let i = 0; i < 4; i++) {
    await skipRest();
    await page.getByRole('button', { name: /^Log trip/i }).click();
  }
  await skipRest();
  let s = await state(page);
  const trips = s.activeSession.entries.filter(e => e.blockId === 'b-fin');
  eq('4 trips logged', trips.length, 4);
  eq('trip records distance not reps', [trips[0].distanceM, trips[0].reps], [40, null]);
  await page.evaluate(() => AC.engine.abandon());

  // Day C sprints
  await page.evaluate(() => { AC.engine.start('day-c'); AC.engine.nextBlock(); AC.router.go('session'); });
  await page.waitForSelector('.runner');
  await page.waitForTimeout(30);
  ok('sprint block reached', (await hdr()).includes('EFFORT 1 OF 8'));
  await tap(page, /Start sprints/i);
  await page.clock.runFor(11_000);
  await page.waitForTimeout(30);
  eq('get-set rolls into the effort', (await state(page)).activeSession.block.status, 'effort');
  await page.clock.runFor(21_000);
  await page.waitForTimeout(30);
  s = await state(page);
  eq('effort logged with its duration', s.activeSession.entries[0].seconds, 20);
  eq('into the walk-back rest', s.activeSession.block.status, 'rest');
  await tap(page, /That one felt slower/i);
  await page.clock.runFor(91_000);
  await page.waitForTimeout(30);
  await tap(page, /Start effort 2/i);
  await page.clock.runFor(21_000);
  await page.waitForTimeout(30);
  await tap(page, /That one felt slower/i);
  await page.waitForTimeout(30);
  ok('two slow efforts raise the stop suggestion',
    (await page.locator('text=/The program says stop here/').count()) === 1);
  await tap(page, /Stop sprints/i);
  s = await state(page);
  eq('sprints finished with 2 efforts', s.activeSession.block.effortIndex, 2);
  eq('both marked slower', s.activeSession.block.slower, [0, 1]);
  await page.evaluate(() => AC.engine.abandon());

  // Day E freeform
  await page.evaluate(() => { AC.engine.start('day-e', 'sport'); AC.router.go('session'); });
  await page.waitForSelector('.runner');
  await page.waitForTimeout(30);
  ok('sport block reached', (await page.locator('text=/Log what you played/').count()) === 1);
  await ctx.close();
}

/* ---------------------------------------------------------------- T10 */
console.log('\nT10  charts, library, accessibility');
{
  const { ctx, page } = await newPage();
  await page.goto(BASE);
  await tap(page, /Begin week 1/i);
  await page.evaluate(() => {
    AC.store.update(s => {
      s.metrics = [];
      for (let i = 20; i >= 0; i--)
        s.metrics.push({ id: 'w' + i, date: AC.util.addDays(AC.util.todayKey(), -i),
          type: 'weight', value: 200 - i * 0.12, unit: 'lb', source: 'manual' });
    });
  });
  await page.evaluate(() => AC.router.go('progress'));
  await page.waitForSelector('.chart');
  const chart = await page.evaluate(() => {
    const svg = document.querySelectorAll('.chart')[0];
    return { dots: svg.querySelectorAll('.c-raw').length,
      avgPaths: svg.querySelectorAll('path.c-avg').length,
      avgPoints: (svg.querySelector('path.c-avg').getAttribute('d').match(/[ML]/g) || []).length,
      rawOpacity: getComputedStyle(svg.querySelector('.c-raw')).opacity,
      label: svg.getAttribute('aria-label') };
  });
  eq('21 raw dots plotted', chart.dots, 21);
  eq('exactly one rolling-average path', chart.avgPaths, 1);
  eq('average line spans all 21 days', chart.avgPoints, 21);
  ok('raw dots are visually subordinate to the average', parseFloat(chart.rawOpacity) < 1, chart.rawOpacity);
  ok('chart has a text label for screen readers', /7-day average/.test(chart.label), chart.label);

  await page.evaluate(() => AC.router.go('library'));
  await page.waitForSelector('.listrow');
  eq('library lists all 38', await page.locator('ul .listrow').count(), 38);
  await page.getByRole('radio', { name: 'hinge' }).click();
  const hinge = await page.locator('ul .listrow').count();
  eq('hinge filter narrows to 4', hinge, 4);
  await page.getByRole('searchbox').fill('row');
  await page.getByRole('radio', { name: 'All' }).first().click();
  await page.getByRole('searchbox').fill('row');
  eq('search "row" finds 2', await page.locator('ul .listrow').count(), 2);

  const unnamed = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('button').forEach(b => {
      const name = (b.getAttribute('aria-label') || b.textContent || '').trim();
      if (!name) out.push(b.className || b.outerHTML.slice(0, 60));
    });
    return out;
  });
  eq('every button has an accessible name', unnamed, []);
  await ctx.close();
}

await browser.close();
server.close();
console.log('\n' + (fail ? '\x1b[31m' : '\x1b[32m') + pass + ' passed, ' + fail + ' failed\x1b[0m');
if (fail) { console.log('\nFailures:'); fails.forEach(f => console.log('  - ' + f)); }
process.exit(fail ? 1 : 0);
