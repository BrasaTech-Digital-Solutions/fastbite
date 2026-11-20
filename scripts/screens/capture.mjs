#!/usr/bin/env node
// Reproducible screenshot capture for FastBite restaurant screens.
// Uses Playwright already installed on the machine (no npm install in repo).
//
// Usage:
//   node scripts/screens/capture.mjs <fixture.env> [--out=<dir>] [--themes=fastfood,cafe,modern] [--sample]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

function resolvePlaywright() {
  const candidates = [
    process.env.PLAYWRIGHT_DIR,
    path.join(os.homedir(), 'AppData', 'Local', 'npm-cache', '_npx', 'e41f203b7505f1fb', 'node_modules', 'playwright'),
  ].filter(Boolean);
  for (const dir of candidates) {
    if (fs.existsSync(dir)) {
      return require(dir);
    }
  }
  throw new Error(`Playwright not found. Set PLAYWRIGHT_DIR or check ${candidates.join(', ')}`);
}

function parseArgs(argv) {
  const opts = { fixture: null, out: null, themes: ['fastfood', 'cafe', 'modern'], sample: false };
  for (const arg of argv.slice(2)) {
    if (arg === '--sample') opts.sample = true;
    else if (arg.startsWith('--out=')) opts.out = arg.slice(6);
    else if (arg.startsWith('--themes=')) opts.themes = arg.slice(9).split(',').map(s => s.trim()).filter(Boolean);
    else if (!opts.fixture) opts.fixture = arg;
  }
  if (!opts.fixture) {
    console.error('Usage: node scripts/screens/capture.mjs <fixture.env> [--out=<dir>] [--themes=fastfood,cafe,modern] [--sample]');
    process.exit(2);
  }
  return opts;
}

function parseEnv(filePath) {
  const env = {};
  for (const line of fs.readFileSync(filePath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq > 0) env[trimmed.slice(0, eq)] = trimmed.slice(eq + 1);
  }
  return env;
}

function normalizePath(p) {
  // Convert WSL /mnt/d/... or Git Bash /d/... path to D:/... on Windows
  if (process.platform === 'win32') {
    if (/^\/mnt\/[a-zA-Z]\//.test(p)) return p[5].toUpperCase() + ':' + p.slice(6);
    if (/^\/[a-zA-Z]\//.test(p)) return p[1].toUpperCase() + ':' + p.slice(2);
  }
  return p;
}

function parseNetscapeCookies(jarPath, baseUrl) {
  const realPath = normalizePath(jarPath);
  if (!fs.existsSync(realPath)) return [];
  const originHost = new URL(baseUrl).hostname;
  const cookies = [];
  for (let line of fs.readFileSync(realPath, 'utf8').split(/\r?\n/)) {
    let httpOnly = false;
    if (line.startsWith('#HttpOnly_')) {
      httpOnly = true;
      line = line.slice('#HttpOnly_'.length);
    }
    if (!line || line.startsWith('#')) continue;
    const parts = line.split('\t');
    if (parts.length < 7) continue;
    const [domain, , cookiePath, secure, expires, name, value] = parts;
    const cleanDomain = domain.replace(/^\./, '');
    if (cleanDomain !== originHost && !originHost.endsWith('.' + cleanDomain)) continue;
    const expNum = Number(expires);
    const c = {
      name,
      value,
      domain: cleanDomain,
      path: cookiePath || '/',
      httpOnly,
      secure: secure === 'TRUE',
    };
    if (expNum > 0) c.expires = expNum;
    cookies.push(c);
  }
  return cookies;
}

async function switchTheme(context, base, tenant, themeId) {
  const page = await context.newPage();
  try {
    await page.goto(`${base}/owner/console?view=location-settings&section=appearance&location=${tenant}`, { waitUntil: 'domcontentloaded' });
    const status = await page.evaluate(async ({ tenant, themeId }) => {
      const csrf = document.querySelector('meta[name="csrf-token"]')?.content || '';
      const body = new URLSearchParams({ _csrf: csrf, theme: themeId });
      const res = await fetch(`/owner/workspace/${tenant}/appearance`, {
        method: 'POST',
        body,
        credentials: 'same-origin',
      });
      return res.status;
    }, { tenant, themeId });
    if (status < 200 || status >= 400) {
      throw new Error(`Failed to switch theme to ${themeId}: HTTP ${status}`);
    }
  } finally {
    await page.close();
  }
}

async function waitStable(page) {
  await page.evaluate(async () => {
    const imgs = Array.from(document.images);
    for (const img of imgs) {
      if (img.loading === 'lazy') {
        img.loading = 'eager';
        if (img.naturalWidth === 0 && img.getAttribute('src')) {
          const s = img.getAttribute('src');
          img.removeAttribute('src');
          img.setAttribute('src', s);
        }
      }
    }
    const imgPromises = imgs.map(img => (
      img.complete ? Promise.resolve() : new Promise(r => {
        img.addEventListener('load', r, { once: true });
        img.addEventListener('error', r, { once: true });
      })
    ).then(() => (img.naturalWidth > 0 && img.decode ? img.decode().catch(() => {}) : undefined)));
    await Promise.race([
      Promise.all(imgPromises),
      new Promise(r => setTimeout(r, 5000)),
    ]);
    await document.fonts.ready;
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  });
  await page.waitForTimeout(250);
}

const FIXED_TIME = new Date('2026-11-20T12:00:00Z');

// Clicks through the DOM, not the mouse: no scroll-into-view and no hover state (hover zooms and
// lifts cards, and Chromium then rasterises the scaled layer differently from run to run).
async function tap(locator) {
  await locator.first().evaluate(el => el.click());
}

// A dialog shot must show its dialog: fail loudly instead of capturing the page behind it.
async function shown(page, selector, what) {
  await page.locator(selector).first().waitFor({ state: 'visible', timeout: 10000 })
    .catch(() => { throw new Error(`${what}: expected ${selector} to be visible on ${page.url()}`); });
}

// Toasts live for 3 s after an action; wait them out so the shot does not depend on timing.
async function noToasts(page) {
  await page.waitForFunction(() => !document.querySelector('#toastContainer > *'), null, { timeout: 10000 });
}

async function addCounterProduct(p) {
  await p.locator('#products-grid .product-card').first().waitFor({ state: 'visible', timeout: 10000 });
  await tap(p.locator('#products-grid .product-card'));
  await p.waitForTimeout(250);
  const addModalBtn = p.locator('#btn-add-customized-item');
  if (await addModalBtn.isVisible().catch(() => false)) {
    await tap(addModalBtn);
    await p.waitForTimeout(250);
  }
  await shown(p, '#btn-proceed-payment:enabled', 'counter cart');
}

async function captureShot(context, outFile, width, height, url, action, { fullPage = true, clearStorage = false } = {}) {
  const page = await context.newPage();
  await page.setViewportSize({ width, height });
  await page.clock.setFixedTime(FIXED_TIME);
  try {
    await page.goto(url, { waitUntil: 'load', timeout: 30000 });
    if (clearStorage) {
      await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
      await page.reload({ waitUntil: 'load', timeout: 30000 });
    }
    await page.addStyleTag({
      content: '*, *::before, *::after { transition-duration: 0s !important; animation-duration: 0s !important; animation-delay: 0s !important; }',
    });
    await waitStable(page);
    if (action) {
      await action(page);
      await page.mouse.move(0, 0);
      await page.evaluate(() => { if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur(); });
    }
    // Page scripts and reload's scroll restoration can leave the page a pixel or more down, which
    // moves the sticky banner and fixed buttons in a full-page shot. Every shot starts at the top.
    await page.evaluate(() => window.scrollTo(0, 0));
    await waitStable(page);
    fs.mkdirSync(path.dirname(outFile), { recursive: true });
    await page.screenshot({ path: outFile, animations: 'disabled', caret: 'hide', fullPage });
    console.log(`SHOT ${path.relative(path.dirname(path.dirname(outFile)), outFile)}`);
  } finally {
    await page.close();
  }
}

async function main() {
  const opts = parseArgs(process.argv);
  const env = parseEnv(normalizePath(opts.fixture));
  const base = env.BASE;
  const tenant = env.TENANT;
  const outRoot = normalizePath(opts.out || path.dirname(normalizePath(opts.fixture)));

  const { chromium } = resolvePlaywright();
  // Software raster: with GPU raster, photos are resampled differently depending on decode timing
  // (1-8 levels off on whole product images between identical runs).
  const launchOpts = {
    headless: true,
    args: ['--disable-gpu', '--force-color-profile=srgb', '--disable-partial-raster', '--disable-lcd-text'],
  };
  if (process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE) {
    launchOpts.executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
  } else if (!fs.existsSync(chromium.executablePath())) {
    const msDir = path.join(os.homedir(), 'AppData', 'Local', 'ms-playwright');
    if (fs.existsSync(msDir)) {
      const builds = fs.readdirSync(msDir)
        .filter(d => d.startsWith('chromium-'))
        .sort()
        .reverse();
      for (const b of builds) {
        const exe = path.join(msDir, b, 'chrome-win64', 'chrome.exe');
        if (fs.existsSync(exe)) {
          launchOpts.executablePath = exe;
          break;
        }
      }
    }
  }
  const browser = await chromium.launch(launchOpts);

  const makeContext = async (jarPath) => {
    const ctx = await browser.newContext({
      deviceScaleFactor: 1,
      reducedMotion: 'reduce',
      locale: 'es-ES',
      extraHTTPHeaders: { 'Accept-Language': 'es-ES,es;q=0.9' },
    });
    if (jarPath) {
      const cookies = parseNetscapeCookies(jarPath, base);
      if (cookies.length) await ctx.addCookies(cookies);
    }
    return ctx;
  };

  const ownerCtx = await makeContext(env.OWNER_JAR);
  const managerCtx = await makeContext(env.MANAGER_JAR);
  const guestCtx = await makeContext(env.GUEST_JAR);
  if (!env.ONLINE_GUEST_JAR) throw new Error('Fixture is missing its paid online guest session');
  const onlineGuestCtx = await makeContext(env.ONLINE_GUEST_JAR);
  const cancelledCtx = await makeContext(env.GUEST_CANCELLED_JAR);

  const guestSizes = opts.sample ? [[375, 812], [1280, 800]] : [[375, 812], [1280, 800], [1884, 871]];
  const staffCoreSizes = opts.sample ? [[1884, 871]] : [[768, 1024], [1024, 768], [1280, 800], [1884, 871]];
  const staffOtherSizes = opts.sample ? [] : [[1280, 800], [1884, 871]];
  const themes = opts.sample ? ['cafe'] : opts.themes;

  try {
    for (const theme of themes) {
      console.log(`=== Theme: ${theme} ===`);
      await switchTheme(ownerCtx, base, tenant, theme);
      const themeDir = path.join(outRoot, theme);

      // 1. Guest menu
      for (const [w, h] of guestSizes) {
        await captureShot(guestCtx, path.join(themeDir, `menu-${w}x${h}.png`), w, h, `${base}/${tenant}/menu`, null, { clearStorage: true });
      }

      // Guest menu states: allergen filter modal, customization dialog, cart open (viewport-only dialog shots)
      if (!opts.sample) {
        for (const [w, h] of [[375, 812], [1280, 800]]) {
          await captureShot(guestCtx, path.join(themeDir, `menu-allergen-filter-${w}x${h}.png`), w, h, `${base}/${tenant}/menu`, async (p) => {
            await tap(p.locator('.allergen-filter-btn'));
            await shown(p, '#allergenModal.show', 'allergen filter dialog');
          }, { fullPage: false, clearStorage: true });
          // The customization dialog opens from a cart line whose product has customizations.
          await captureShot(guestCtx, path.join(themeDir, `menu-customize-${w}x${h}.png`), w, h, `${base}/${tenant}/menu`, async (p) => {
            const product = p.locator('[data-customizations]:not([data-customizations=""])').first();
            const id = await product.getAttribute('id', { timeout: 5000 })
              .catch(() => { throw new Error('menu has no product with customizations'); });
            await tap(p.locator(`[id="add-${id}-to-cart"]`));
            await tap(p.locator('#navBarCheckoutBtn'));
            await shown(p, `.edit-cart-button[data-product-id="${id}"]:enabled`, 'cart line edit button');
            await tap(p.locator(`.edit-cart-button[data-product-id="${id}"]`));
            await shown(p, '#customizationsModal.show', 'customization dialog');
            await noToasts(p);
          }, { fullPage: false, clearStorage: true });
          await captureShot(guestCtx, path.join(themeDir, `menu-cart-${w}x${h}.png`), w, h, `${base}/${tenant}/menu`, async (p) => {
            await tap(p.locator('button[id^="add-"][id$="-to-cart"]:enabled'));
            await tap(p.locator('#navBarCheckoutBtn'));
            await shown(p, '#cartSection .edit-cart-button', 'cart with one line');
            await noToasts(p);
          }, { fullPage: false, clearStorage: true });
        }

        // 2. Allergens, paymentSelection, confirmation, orderCancelled
        for (const [w, h] of guestSizes) {
          await captureShot(guestCtx, path.join(themeDir, `allergens-${w}x${h}.png`), w, h, `${base}/${tenant}/allergens`);
          await captureShot(guestCtx, path.join(themeDir, `paymentSelection-${w}x${h}.png`), w, h, `${base}/${tenant}/select-payment`);
          await captureShot(onlineGuestCtx, path.join(themeDir, `confirmation-${w}x${h}.png`), w, h, `${base}/${tenant}/order-confirmation`);
          await captureShot(cancelledCtx, path.join(themeDir, `orderCancelled-${w}x${h}.png`), w, h, `${base}/${tenant}/select-payment`);
        }
      }

      // 3. Counter (with 1 product added to cart so Pay button is active)
      for (const [w, h] of staffCoreSizes) {
        await captureShot(managerCtx, path.join(themeDir, `counter-${w}x${h}.png`), w, h, `${base}/${tenant}/counter`, addCounterProduct);
      }

      if (!opts.sample) {
        await captureShot(managerCtx, path.join(themeDir, `counter-payment-1280x800.png`), 1280, 800, `${base}/${tenant}/counter`, async (p) => {
          await addCounterProduct(p);
          await tap(p.locator('#btn-proceed-payment'));
          await shown(p, '#paymentModal.show', 'counter payment dialog');
        }, { fullPage: false });
      }

      // 4. Kitchen / Dashboard
      for (const [w, h] of staffCoreSizes) {
        await captureShot(managerCtx, path.join(themeDir, `dashboard-${w}x${h}.png`), w, h, `${base}/${tenant}/dashboard`, async (p) => {
          await p.waitForTimeout(400);
        });
      }

      if (!opts.sample) {
        await captureShot(managerCtx, path.join(themeDir, `dashboard-cancel-1280x800.png`), 1280, 800, `${base}/${tenant}/dashboard`, async (p) => {
          await shown(p, 'button[onclick^="showCancelModal"]', 'kitchen order cancel button');
          await tap(p.locator('button[onclick^="showCancelModal"]'));
          await shown(p, '#cancelModal.show', 'kitchen cancel dialog');
        }, { fullPage: false });
      }

      // 5. Other staff pages
      const freezeServerTimestamps = async (p) => {
        await p.waitForFunction(() => {
          const el = document.querySelector('[data-cash-out="asOf"]');
          return !el || el.textContent.trim().length > 0;
        }, { timeout: 5000 }).catch(() => {});
        await p.evaluate(() => {
          const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          let node;
          while ((node = walker.nextNode())) {
            if (/\d{2}:\d{2}/.test(node.nodeValue)) {
              node.nodeValue = node.nodeValue
                .replace(/\d{2}\/\d{2}\/\d{4}\s+\d{2}:\d{2}/g, '20/11/2026 12:00')
                .replace(/\b\d{2}:\d{2}\b/g, '12:00');
            }
          }
        });
      };
      for (const [w, h] of staffOtherSizes) {
        await captureShot(managerCtx, path.join(themeDir, `tickets-${w}x${h}.png`), w, h, `${base}/${tenant}/counter/tickets`);
        await captureShot(managerCtx, path.join(themeDir, `dayReport-${w}x${h}.png`), w, h, `${base}/${tenant}/counter/day-report`, freezeServerTimestamps);
        await captureShot(managerCtx, path.join(themeDir, `tableQrCodes-${w}x${h}.png`), w, h, `${base}/${tenant}/backoffice/tables/qr-codes`);
      }
    }
  } finally {
    await ownerCtx.close();
    await managerCtx.close();
    await guestCtx.close();
    await onlineGuestCtx.close();
    await cancelledCtx.close();
    await browser.close();
  }
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
