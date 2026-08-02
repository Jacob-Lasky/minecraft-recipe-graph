// Which Chromium the audits drive, and where to find one.
//
// WHY THIS EXISTS. Both audits used a bare `chromium.launch()`, which uses the browser
// `playwright install chromium` downloads. On an Arch-family host that download does not
// work -- the headless-shell build Playwright fetches will not run there -- so
// `pnpm run audit:mobile` died on "Executable doesn't exist" and pointed at the very
// command that had just failed. That is not a small papercut: CLAUDE.md makes a phone audit
// mandatory before shipping any UI change, so a tool that cannot start on the machine where
// the UI is developed turns a required check into one that gets skipped.
//
// A SYSTEM BROWSER IS TRIED FIRST, then the bundled one. Playwright drives any recent
// Chromium over CDP, and the audits assert layout and DOM state rather than pixel-exact
// rendering, so the build does not have to be the one Playwright shipped. `PLAYWRIGHT_BROWSER`
// overrides everything, for a machine whose browser is somewhere else entirely.
//
// The order is deliberate: Brave, then Chromium, then Chrome. Nothing here depends on the
// brand -- it is the order they are likely to be installed in on the machines this repo is
// worked on, and the first hit wins.

const fs = require('fs');
const { chromium } = require('playwright');

const CANDIDATES = [
  '/usr/bin/brave',
  '/usr/bin/brave-browser',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/google-chrome',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
];

function systemBrowser() {
  const named = process.env.PLAYWRIGHT_BROWSER;
  if (named) {
    if (!fs.existsSync(named)) {
      throw new Error(`PLAYWRIGHT_BROWSER=${named} does not exist`);
    }
    return named;
  }
  return CANDIDATES.find(p => fs.existsSync(p)) || null;
}

// Launch, saying WHICH browser it used. The audits' whole output is a verdict about a UI,
// and a verdict is worth less when the reader cannot tell what rendered it.
async function launch(options = {}) {
  const executablePath = systemBrowser();
  if (executablePath) {
    console.log(`browser: ${executablePath} (system)`);
    return chromium.launch({ ...options, executablePath });
  }
  console.log('browser: playwright bundled chromium');
  return chromium.launch(options);
}

module.exports = { launch, systemBrowser };
