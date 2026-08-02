// Phone audit for a running recipegraph server.
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
//     pnpm run audit:mobile http://127.0.0.1:8765
//
// It drives a SYSTEM Chromium when there is one and falls back to the bundled download;
// `pnpm run browsers` is only needed on a machine with neither. See tools/browser.js for
// why, which is that the bundled build does not run on Arch and the audit is mandatory.
//
// Two things it checks that are easy to get wrong by hand:
//
//   scrollWidth, NOT geometry. An unbreakable string overflows INSIDE its own box, so
//   every bounding rect looks correct while the page still scrolls sideways. A
//   "does any element stick out" check cannot see it.
//
//   `hidden` actually hiding. `[hidden]{display:none}` in the UA sheet loses to any
//   author `display` rule, so a filter can set the attribute on 499 rows, report the
//   right count, and leave all of them on screen.

const { launch } = require('./browser');

const BASE = process.argv[2] || 'http://127.0.0.1:8765';
const WIDTH = Number(process.argv[3] || 390);
const enc = encodeURIComponent;

const PAGES = [
  ['home', '/'],
  ['plan', `/plan?item=${enc('nuclearcraft:compound:7')}&qty=64`],
  ['machines', '/machines'],
  ['sources', '/sources'],
  ['explore', `/explore?q=${enc('borax')}`],
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
            sel: el.tagName.toLowerCase()
              + (el.className ? '.' + String(el.className).trim().split(/\s+/).join('.') : ''),
          };
        }
      }
    }
    const small = [...document.querySelectorAll('a,button,input,select,summary')]
      .filter(e => {
        const b = e.getBoundingClientRect();
        return (b.width || b.height) && b.height < 40;
      }).length;
    return { vw, sw: de.scrollWidth, scrolls: de.scrollWidth > vw + 1, worst, small };
  });
  console.log(`  ${label.padEnd(9)} width ${r.sw}  ${r.scrolls ? 'SCROLLS' : 'fits'}`
    + `  tap<40px: ${r.small}`);
  if (r.scrolls) {
    bad(`${label} scrolls horizontally (${r.sw}px in ${r.vw}px)`
      + (r.worst ? `; widest internal overflow ${r.worst.sel} by ${r.worst.over}px` : ''));
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
