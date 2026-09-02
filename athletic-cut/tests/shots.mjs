import { chromium } from '/opt/node22/lib/node_modules/playwright/index.mjs';
import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path';
import { fileURLToPath } from 'node:url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'tests', 'shots');
fs.mkdirSync(OUT, { recursive: true });
const server = http.createServer((q, r) => { r.writeHead(200, {'Content-Type':'text/html'}); r.end(fs.readFileSync(path.join(ROOT,'index.html'))); });
await new Promise(r => server.listen(0, r));
const BASE = 'http://127.0.0.1:' + server.address().port + '/';
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport:{width:390,height:844}, deviceScaleFactor:2, isMobile:true, hasTouch:true,
  colorScheme: process.env.LIGHT ? 'light' : 'dark' });
const page = await ctx.newPage();
const shot = async n => { await page.waitForTimeout(120); await page.screenshot({ path: path.join(OUT, n + (process.env.LIGHT?'-light':'') + '.png') }); };
const tap = n => page.getByRole('button', { name: n }).first().click();
async function skipRest() {
  const r = await page.evaluate(() => { const a = AC.store.get().activeSession; return !!(a && a.timer && a.timer.kind === 'rest'); });
  if (r) { await page.getByRole('button', { name: /Skip rest/i }).first().click(); await page.waitForTimeout(80); }
}

await page.goto(BASE);
await shot('01-onboarding');
await tap(/Begin week 1/i);
// seed some history so home/progress aren't empty
await page.evaluate(() => {
  const U = AC.util;
  AC.store.update(s => {
    s.metrics = [];
    for (let i = 24; i >= 0; i--) s.metrics.push({ id:'w'+i, date:U.addDays(U.todayKey(),-i), type:'weight',
      value: 199.7 - i*0.02 - (i%3)*0.35 + (i%5)*0.2, unit:'lb', source:'manual' });
    [21,14,7,0].forEach((d,i)=>s.metrics.push({ id:'wa'+i, date:U.addDays(U.todayKey(),-d), type:'waist', value:32.3-i*0.3, unit:'in', source:'manual' }));
    s.nutrition[U.todayKey()] = { calories: 2640, proteinG: 195, steps: 8400 };
    for (let i=1;i<7;i++) s.nutrition[U.addDays(U.todayKey(),-i)] = { calories: 2600+i*20, proteinG: 188+i*2, steps: 7600+i*260 };
    [24,17,10,3].forEach((d,i)=>{
      const date = U.addDays(U.todayKey(),-d);
      s.sessions.push({ id:'h'+i, dayId:'day-a', startedAt:date+'T10:00:00.000Z', completedAt:date+'T10:38:00.000Z',
        status:'complete', sessionRPE:8, bodyweightAtTime:199-i, notes:'', blockResults:[],
        totals:{volumeLb:8800+i*180,setsLogged:18,durationSec:2280}, beatLast:[],
        entries:[
          {blockId:'a-main',slotId:'a-main-1',exerciseId:'trap-bar-deadlift',setIndex:0,load:235+i*5,loadUnit:'lb',reps:5,rpe:8,isWarmup:false,seconds:null,distanceM:null,completedAt:date+'T10:15:00.000Z'},
          {blockId:'a-main',slotId:'a-main-1',exerciseId:'trap-bar-deadlift',setIndex:1,load:235+i*5,loadUnit:'lb',reps:5,rpe:8,isWarmup:false,seconds:null,distanceM:null,completedAt:date+'T10:18:00.000Z'},
          {blockId:'a-ss',slotId:'a-ss-1',exerciseId:'bulgarian-split-squat',setIndex:0,load:40+i*5,loadUnit:'lb',reps:8,rpe:null,isWarmup:false,seconds:null,distanceM:null,completedAt:date+'T10:25:00.000Z'},
          {blockId:'a-fin',slotId:'a-fin-1',exerciseId:'kettlebell-swing',setIndex:0,load:24,loadUnit:'kg',reps:12,rpe:null,isWarmup:false,seconds:null,distanceM:null,completedAt:date+'T10:32:00.000Z'}
        ]});
    });
  });
  AC.screens.render();
});
await shot('02-home');
await tap(/Start session/i);
await shot('03-prep');
await tap(/^Continue/i);
await shot('04-mainlift');
await page.getByRole('radio', { name: /RPE 8/ }).click();
await page.getByRole('button', { name: /^Log set/i }).click();
await page.waitForTimeout(400);
await shot('05-rest');
await page.getByRole('button', { name: /adjust next set/i }).click();
await shot('06-rest-collapsed');
await page.getByRole('button', { name: /Skip/i }).first().click();
await page.locator('.thumbbtn').first().click();
await shot('07-exercise-sheet');
await page.keyboard.press('Escape');
await page.evaluate(() => { for (let i=0;i<2;i++) AC.engine.nextBlock(); AC.screens.render(); });
await page.waitForTimeout(100);
await skipRest();
await tap(/Start EMOM/i);
await page.waitForTimeout(1200);
await shot('08-emom');
await page.evaluate(() => AC.engine.abandon());
await page.evaluate(() => { AC.engine.start('day-c'); AC.engine.nextBlock(); AC.router.go('session'); });
await page.waitForTimeout(150);
await shot('09-sprints-setup');
await tap(/Start sprints/i);
await page.waitForTimeout(900);
await shot('10-sprints-running');
await page.evaluate(() => { AC.engine.abandon(); AC.router.go('library'); });
await page.waitForTimeout(200);
await shot('11-library');
await page.evaluate(() => AC.router.go('exercise/trap-bar-deadlift'));
await page.waitForTimeout(250);
await shot('12-exercise-detail');
await page.evaluate(() => AC.router.go('progress'));
await page.waitForTimeout(250);
await shot('13-progress');
await page.evaluate(() => window.scrollTo(0, 99999));
await page.waitForTimeout(150);
await shot('14-progress-lower');
await page.evaluate(() => AC.router.go('body'));
await page.waitForTimeout(200);
await shot('15-body');
await page.evaluate(() => AC.router.go('nutrition'));
await page.waitForTimeout(200);
await shot('16-nutrition');
await page.evaluate(() => AC.router.go('more'));
await page.waitForTimeout(200);
await shot('17-settings');
await browser.close(); server.close();
console.log('shots written to', OUT);
