// Phone audit for a running recipegraph server, and for the files its CLI writes.
//
// WHY THIS IS CHECKED IN. The UI is used on a phone, and three separate phone bugs
// shipped because the layout was only ever looked at on a desktop. A fourth shipped
// because the first audit LOADED every page and never typed into one, so it missed a
// search-result row whose name was squeezed to zero width. Static pages are not the
// whole surface; interactive state has to be exercised too.
//
// Dev tooling only. The tool itself is Python 3 stdlib and CI stays stdlib-only; this
// lives behind package.json so its one dependency is pinned rather than whatever the
// machine happened to have.
//
//     corepack enable pnpm && pnpm install
//     RECIPEGRAPH_GRAPH=data/graph.json pnpm run audit:mobile http://127.0.0.1:8765
//
// The graph is for the standalone leg at the bottom, which runs the real CLI. Without one
// that leg FAILS rather than skipping, because a skip is what let #138 ship.
//
// It drives a SYSTEM Chromium when there is one and falls back to the bundled download;
// `pnpm run browsers` is only needed on a machine with neither. See tools/browser.js for
// why, which is that the bundled build does not run on Arch and the audit is mandatory.
//
// Three things it checks that are easy to get wrong by hand:
//
//   scrollWidth, NOT geometry. An unbreakable string overflows INSIDE its own box, so
//   every bounding rect looks correct while the page still scrolls sideways. A
//   "does any element stick out" check cannot see it.
//
//   `hidden` actually hiding. `[hidden]{display:none}` in the UA sheet loses to any
//   author `display` rule, so a filter can set the attribute on 499 rows, report the
//   right count, and leave all of them on screen.
//
//   THE LAYOUT VIEWPORT IS THE DEVICE VIEWPORT. A page with no `<meta name=viewport>`
//   lays out at the UA default 980px and then "fits", because it is being measured
//   against its own 980px viewport rather than against the phone. #138 hid inside this
//   audit's own output for exactly that reason: `plan 980 fits`. A page that fits at
//   980 is a page a phone shows zoomed out.
//
// AND IT MEASURES THE FILES THE CLI WRITES, not only the server. `plan --html` and
// `explore --html` produce whole documents that people open on a phone -- #36 inlines
// sprites specifically so they survive being published as a Claude Artifact -- and this
// audit drove the server only, so that delivery path had never been measured once. #138
// was the result: an unmeasured surface behind a rule everyone was following. Each page
// costs one CLI run over the whole graph: measured, 10 to 12s with the file in the page
// cache and 2m28s cold. That is the price of measuring the bytes the tool really ships
// rather than a reconstruction of them.

const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { launch } = require('./browser');

const BASE = process.argv[2] || 'http://127.0.0.1:8765';
const WIDTH = Number(process.argv[3] || 390);
const enc = encodeURIComponent;

const REPO = path.dirname(__dirname);
// Where the graph is, for the standalone leg. `RECIPEGRAPH_GRAPH` first, because the
// documented deployment mounts it somewhere else entirely.
const GRAPH = process.env.RECIPEGRAPH_GRAPH || path.join(REPO, 'data', 'graph.json');

const PAGES = [
  ['home', '/'],
  ['plan', `/plan?item=${enc('nuclearcraft:compound:7')}&qty=64`],
  ['machines', '/machines'],
  ['sources', '/sources'],
  ['explore', `/explore?q=${enc('borax')}`],
];

// One CLI invocation each, named by the subcommand and its argument. Deliberately the
// real entry point: a helper that called `render_html` itself would be a second writer
// and could not catch a CLI that forgot to ask for a document head.
//
// `osiris_spinel` because it is the plan #174 was reported on, so this leg draws the two
// panels and the two box shapes that are easiest to get wrong at 390px: measured on the
// reference pack it carries 2 placeholder nodes and 5 arbitrary-pick keys.
const FILES = [
  ['plan', ['plan', 'contenttweaker:osiris_spinel', '--qty', '1']],
  ['explore', ['explore', 'borax']],
];

let failures = 0;
const bad = (msg) => { failures++; console.log('  FAIL ' + msg); };

async function measure(page, label) {
  const r = await page.evaluate(() => {
    const de = document.documentElement;
    const vw = de.clientWidth;
    let worst = null;
    for (const el of document.querySelectorAll('*')) {
      // Internal overflow: the box fits, its content does not.
      if (el.scrollWidth > el.clientWidth + 1) {
        const over = el.scrollWidth - el.clientWidth;
        if (!worst || over > worst.over) {
          worst = {
            over,
            el,
            sel: el.tagName.toLowerCase()
              + (el.className ? '.' + String(el.className).trim().split(/\s+/).join('.') : ''),
          };
        }
      }
    }
    if (worst) {
      // Whether something is meant to scroll it. A row inside `.tree.scroll` overflowing is
      // the design (the card scrolls, the page does not); the same row with no scroller above
      // it is content nobody can reach.
      worst.contained = (function (el) {
        for (let p = el.parentElement; p; p = p.parentElement) {
          const s = getComputedStyle(p);
          if (s.overflowX === 'auto' || s.overflowX === 'scroll') return true;
        }
        return false;
      })(worst.el);
      delete worst.el;
    }
    const small = [...document.querySelectorAll('a,button,input,select,summary')]
      .filter(e => {
        const b = e.getBoundingClientRect();
        return (b.width || b.height) && b.height < 40;
      }).length;
    return { vw, sw: de.scrollWidth, scrolls: de.scrollWidth > vw + 1, worst, small,
             metas: document.querySelectorAll('meta[name=viewport]').length };
  });
  // The internal overflow is REPORTED even when the page fits, because that is the state it
  // is usually in: a row wider than its own box inside a card that scrolls is the design, and
  // the same row with nothing scrollable above it is content nobody can reach. Printing only
  // on failure hid the difference, and the two look identical from the page width alone.
  const over = r.worst
    ? `  widest internal overflow ${r.worst.sel} +${r.worst.over}px`
      + `${r.worst.contained ? ' (in a scroller)' : ' (NOT SCROLLABLE)'}`
    : '';
  console.log(`  ${label.padEnd(9)} layout ${r.vw}  width ${r.sw}`
    + `  ${r.scrolls ? 'SCROLLS' : 'fits'}  tap<40px: ${r.small}${over}`);
  if (r.scrolls) {
    bad(`${label} scrolls horizontally (${r.sw}px in ${r.vw}px)`
      + (r.worst ? `; widest internal overflow ${r.worst.sel} by ${r.worst.over}px` : ''));
  }
  // BEFORE trusting "fits". Every number above is measured against `r.vw`, so a page laying
  // out at 980 is being asked whether it fits a desktop and answering yes.
  if (r.vw > WIDTH + 1) {
    bad(`${label} lays out at ${r.vw}px in a ${WIDTH}px viewport, so it renders zoomed out`
      + ` and every measurement above is against the wrong width`
      + ` (${r.metas} viewport meta tags)`);
  }
  return r;
}

(async () => {
  const browser = await launch();
  const ctx = await browser.newContext({
    viewport: { width: WIDTH, height: 844 }, isMobile: true, hasTouch: true,
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(String(e.message)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  console.log(`\n== static pages at ${WIDTH}px ==`);
  for (const [name, path] of PAGES) {
    await page.goto(BASE + path, { waitUntil: 'load', timeout: 180000 });
    await page.waitForTimeout(600);
    await measure(page, name);
  }

  console.log(`\n== files the CLI writes, at ${WIDTH}px ==`);
  await standalone(page);

  console.log('\n== interactive state ==');

  // Typing into search must produce rows that still have a visible name.
  await page.goto(BASE + '/', { waitUntil: 'load', timeout: 180000 });
  const box = await page.$('input[type=search]');
  await box.click();
  await box.type('borax', { delay: 50 });
  // Wait for the rows, do not sleep and hope. The typeahead debounces and then fetches,
  // and a fixed 2s wait reported "search returned no rows" on a loaded server, which is
  // a flaky probe reporting a bug that is not there. That is worse than no probe.
  try {
    await page.waitForSelector('#hits li', { timeout: 30000 });
  } catch (e) {
    bad('search produced no rows within 30s');
  }
  const search = await page.evaluate(() => {
    const first = document.querySelector('#hits li .nm2');
    const r = first && first.getBoundingClientRect();
    return { rows: document.querySelectorAll('#hits li').length,
             nameWidth: r ? Math.round(r.width) : -1,
             nameText: first ? first.textContent.trim() : null };
  });
  console.log(`  search    ${search.rows} rows, first name "${search.nameText}" `
    + `${search.nameWidth}px wide`);
  if (search.rows === 0) bad('search returned no rows');
  if (search.nameWidth <= 0) bad('search result name collapsed to zero width');
  await measure(page, 'search+');

  // Filtering must actually hide rows, not just set the attribute.
  await page.goto(BASE + '/machines', { waitUntil: 'load', timeout: 180000 });
  await page.waitForTimeout(800);
  const chip = await page.$('.chip-btn[data-state=unavailable]:not([disabled])');
  if (chip) {
    // A SHORT TIMEOUT AND A CATCH, because a chip that cannot be clicked must not end the
    // run. On a graph with no unavailable machines the chip renders disabled, and the
    // default 30s click timeout then threw and took every later check down with it --
    // losing the audit's whole verdict over one absent fixture.
    try {
      await chip.click({ timeout: 5000 });
    } catch (e) {
      console.log('  filter    chip present but not clickable; skipped');
      return finish();
    }
    await page.waitForTimeout(500);
    const f = await page.evaluate(() => {
      const rows = [...document.querySelectorAll('#mbody tr[data-state]')];
      return { attr: rows.filter(r => r.hidden).length,
               visible: rows.filter(r => r.getBoundingClientRect().height > 0).length,
               total: rows.length };
    });
    console.log(`  filter    ${f.total} rows, ${f.attr} marked hidden, ${f.visible} visible`);
    if (f.attr && f.visible !== f.total - f.attr) {
      bad(`hidden rows are still displayed: ${f.visible} visible with ${f.attr} hidden. `
        + 'An author `display` rule is beating [hidden]{display:none}');
    }
    // Unselecting must clear every trace, not just the background.
    const before = await chip.evaluate(e => getComputedStyle(e).borderColor);
    await chip.click();
    await page.waitForTimeout(300);
    const after = await chip.evaluate(e => getComputedStyle(e).borderColor);
    console.log(`  unselect  border ${before} -> ${after}`);
    if (before === after) {
      bad('the chip keeps its selected border after unselecting, which is sticky :hover');
    }
  }

  return finish();

  // `plan --html` and `explore --html`, measured as a browser opens them: from disk, with
  // no server involved. A FAILURE and not a skip when the graph is missing, because a
  // silent skip is how this surface went unmeasured through every audit before #138.
  async function standalone(page) {
    if (!fs.existsSync(GRAPH)) {
      bad(`no graph at ${GRAPH}, so the files the CLI writes went unmeasured. `
        + 'Point RECIPEGRAPH_GRAPH at one; this leg is the whole reason #138 shipped.');
      return;
    }
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rg-audit-'));
    for (const [name, args] of FILES) {
      const out = path.join(dir, `${name}.html`);
      const started = Date.now();
      try {
        // stderr is CAPTURED rather than discarded, and reported on failure. A leg that says
        // only "command failed" leaves the reader to re-run it by hand to find out why, and
        // the likely why -- a graph the tool cannot read -- is a sentence the CLI already
        // writes.
        execFileSync('python3', ['-m', 'recipegraph', '--graph', GRAPH, ...args,
          '--html', out], { cwd: REPO, timeout: 900000,
          stdio: ['ignore', 'ignore', 'pipe'] });
      } catch (e) {
        const why = String(e.stderr || '').trim().split('\n').slice(-3).join(' | ');
        bad(`${name} --html did not write a file: ${e.message}${why ? '; ' + why : ''}`);
        continue;
      }
      const secs = ((Date.now() - started) / 1000).toFixed(0);
      const bytes = fs.statSync(out).size;
      console.log(`  wrote ${name}.html  ${(bytes / 1024).toFixed(0)} KB in ${secs}s`);
      await page.goto('file://' + out, { waitUntil: 'load', timeout: 180000 });
      await page.waitForTimeout(600);
      await measure(page, name + '.html');
    }
    fs.rmSync(dir, { recursive: true, force: true });
  }

  // The verdict, and the ONE place the run ends. Extracted so a step that has to bail --
  // an absent or disabled fixture -- still reports what it measured rather than throwing
  // the whole audit away, which is what a bare `return` in the middle would do.
  async function finish() {
    if (errors.length) { failures++; console.log('\n  console errors:', errors); }
    console.log(failures ? `\n${failures} PROBLEM(S)\n` : '\nall clear\n');
    await browser.close();
    process.exit(failures ? 1 : 0);
  }
})();
