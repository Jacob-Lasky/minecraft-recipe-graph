// Machines-page filter audit for a running recipegraph server.
//
// WHY THIS IS CHECKED IN. The two filters on /machines are entirely client-side, so the
// Python suite cannot reach a line of them: it can assert the markup the page ships and
// nothing about what happens when you click. Three behaviours there were each reported by
// hand after shipping (#16, #32), and all three are one-line regressions away at any time.
//
// Sibling of tools/mobile-audit.js and run the same way. Dev tooling only; the tool itself
// is Python 3 stdlib and CI stays stdlib-only.
//
//     corepack enable pnpm && pnpm install && pnpm run browsers
//     pnpm run audit:filters http://127.0.0.1:8765
//
// What it checks, and why each one is easy to get wrong by hand:
//
//   THE COUNTS NARROW EACH OTHER. Picking a state has to recount the mod dropdown, or
//   choosing a mod from it produces an empty table with no hint why (#16).
//
//   EMPTY MODS SINK, AND STAY. They sort below the ones with matches but are never
//   removed: removing makes the list jump under the cursor and a visible zero is an
//   answer (#32 over #16). Both halves have to hold at once, which is what makes this
//   worth a script -- "it moved" and "it is still there" are easy to satisfy separately.
//
//   THE SELECTION SURVIVES THE REORDER. `appendChild` MOVES a node, so reordering the
//   options can silently drop the chosen mod and quietly widen the table.

const { chromium } = require('playwright');

const BASE = process.argv[2] || 'http://127.0.0.1:8765';

let failures = 0;
const bad = (msg) => { failures++; console.log('  FAIL ' + msg); };
const ok = (msg) => console.log('  ok   ' + msg);

// The dropdown as the user sees it: label, count, whether it is selectable, in DOM order.
const readOptions = (page) => page.$$eval('#mmod option', (opts) => opts.map((o) => ({
  value: o.value,
  label: o.textContent,
  count: Number((/\((\d+)\)\s*$/.exec(o.textContent) || [0, -1])[1]),
  disabled: o.disabled,
})));

const shownCount = (page) =>
  page.$eval('#mshown', (el) => Number(el.textContent.replace(/,/g, '')));

async function run() {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  await page.goto(`${BASE}/machines`, { waitUntil: 'networkidle' });

  const before = await readOptions(page);
  const mods = before.filter((o) => o.value);
  if (mods.length < 2) {
    console.log('  SKIP only %d mod(s) in this graph; nothing to order', mods.length);
    await browser.close();
    return 0;
  }
  ok(`${mods.length} mods, ${await shownCount(page)} categories`);

  if (before[0].value !== '') {
    bad('the "every mod" entry is not first; it is the way OUT of a filter');
  } else {
    ok('"every mod" leads the list');
  }

  // Narrow to one state. Whichever chip has the fewest non-zero categories makes the
  // sharpest test, because it is the case where the live mods are rarest.
  const chips = await page.$$eval('.chip-btn[data-state]', (bs) => bs.map((b) => ({
    state: b.dataset.state,
    n: Number(b.querySelector('.n').textContent.replace(/,/g, '')),
  })));
  const target = chips.filter((c) => c.n > 0).sort((a, b) => a.n - b.n)[0];
  if (!target) { bad('no state chip has any categories'); await browser.close(); return 1; }

  await page.click(`.chip-btn[data-state="${target.state}"]`);
  const after = await readOptions(page);
  const live = after.filter((o) => o.value && o.count > 0);
  const dead = after.filter((o) => o.value && o.count === 0);
  ok(`state "${target.state}": ${live.length} mods with matches, ${dead.length} without`);

  // ---- the same list, no shorter ----
  if (after.length !== before.length) {
    bad(`the list changed length under the cursor: ${before.length} -> ${after.length}`);
  } else {
    ok('every mod is still in the list');
  }
  if (dead.some((o) => !o.disabled)) {
    bad('a mod with no matches is still selectable, so picking it empties the table');
  } else if (dead.length) {
    ok('mods with no matches are disabled, not removed');
  }

  // ---- ordered: live first, then by count, then by name ----
  const order = after.filter((o) => o.value);
  const firstDead = order.findIndex((o) => o.count === 0);
  const lastLive = order.map((o) => o.count > 0).lastIndexOf(true);
  if (firstDead !== -1 && lastLive > firstDead) {
    bad(`a mod with matches (index ${lastLive}) sorts below one without (index ${firstDead})`);
  } else {
    ok('mods with matches all sort above the ones without');
  }
  const live_counts = live.map((o) => o.count);
  if (live_counts.some((c, i) => i && c > live_counts[i - 1])) {
    bad('the live mods are not in descending count order');
  } else {
    ok('live mods are biggest-first');
  }

  // ---- the selection survives a reorder ----
  // Pick the SMALLEST live mod: it is the one furthest from where it started, so it is
  // the one a lost `sel.value` would show up on.
  const pick = live[live.length - 1].value;
  await page.selectOption('#mmod', pick);
  await page.click(`.chip-btn[data-state="${target.state}"]`);   // release, forcing a reorder
  await page.click(`.chip-btn[data-state="${target.state}"]`);   // re-apply
  const still = await page.$eval('#mmod', (el) => el.value);
  if (still !== pick) {
    bad(`the chosen mod was dropped by the reorder: "${pick}" -> "${still}"`);
  } else {
    ok(`the chosen mod survives a reorder ("${pick}")`);
  }

  // ---- and the table agrees with the dropdown ----
  const rows = await page.$$eval('#mbody tr[data-state]', (trs) =>
    trs.filter((t) => !t.hidden).map((t) => t.dataset.mod));
  const wrong = rows.filter((m) => m !== pick);
  if (wrong.length) {
    bad(`${wrong.length} visible rows belong to another mod, e.g. "${wrong[0]}"`);
  } else if (rows.length !== await shownCount(page)) {
    bad(`the count says ${await shownCount(page)} and ${rows.length} rows are visible`);
  } else {
    ok(`${rows.length} rows shown, all from "${pick}"`);
  }

  await browser.close();
  return failures;
}

run().then((n) => {
  console.log(n ? `\n${n} failure(s)` : '\nall checks passed');
  process.exit(n ? 1 : 0);
}, (err) => { console.error(err); process.exit(2); });
