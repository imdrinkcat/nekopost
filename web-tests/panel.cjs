const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

const url = process.env.NEKOPOST_TEST_PANEL;
const shots = process.env.NEKOPOST_SCREENSHOTS || "";
let shooting = false;
const messages = (process.env.NEKOPOST_TEST_MESSAGES || "").split(",").filter(Boolean);

// Every page is checked for horizontal overflow; with NEKOPOST_SCREENSHOTS set
// it is also captured full-page in the light and dark theme for review.
async function capture(page, label, name) {
  for (const colorScheme of ["light", "dark"]) {
    await page.emulateMedia({ colorScheme, reducedMotion: "reduce" });
    const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
    assert.equal(fits, true, `${label} ${name} (${colorScheme}) scrolls horizontally`);
    if (shots && shooting) {
      await page.screenshot({ path: path.join(shots, `${label}-${name}-${colorScheme}.png`), fullPage: true, animations: "disabled" });
    }
  }
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
}

async function tab(page, label, link, heading, name) {
  await page.getByRole("navigation", { name: "面板" }).getByRole("link", { name: link, exact: true }).click();
  await page.getByRole("heading", { name: heading, exact: true }).waitFor();
  await capture(page, label, name);
}

async function run(browser, viewport, label, full) {
  shooting = full;
  const context = await browser.newContext({ viewport, ignoreHTTPSErrors: true, deviceScaleFactor: 1 });
  const issues = [];
  await context.exposeBinding("__nekopostCSP", (_source, text) => issues.push("CSP violation: " + text));
  await context.addInitScript(() => {
    document.addEventListener("securitypolicyviolation", event => {
      window.__nekopostCSP(event.violatedDirective + " " + event.blockedURI + " " + event.sourceFile + ":" + event.lineNumber);
    });
  });
  const page = await context.newPage();
  page.on("pageerror", error => issues.push(error.message));
  page.on("console", message => {
    if (/content security policy|trusted type|refused to/i.test(message.text())) issues.push(message.text());
  });
  page.on("dialog", dialog => dialog.accept());

  await page.goto(url);
  await page.getByRole("link", { name: "使用 Google 登录" }).waitFor();
  await capture(page, label, "01-login");

  if (full) {
    await page.goto(url + "/auth/google/callback");
    await page.getByRole("heading", { name: "登录已过期或无效，请重新开始" }).waitFor();
    await capture(page, label, "02-error");
    await page.goto(url);
  }

  // Hold the first-party finish request so the interstitial can be captured.
  let release;
  const held = new Promise(resolve => { release = resolve; });
  await page.route("**/auth/finish", async route => { await held; await route.continue(); });
  await page.getByRole("link", { name: "使用 Google 登录" }).click();
  await page.getByRole("heading", { name: "正在完成 Google 登录" }).waitFor();
  if (full) await capture(page, label, "03-finish");
  release();
  await page.waitForURL("**/panel/confirm");
  await page.unroute("**/auth/finish");
  await page.getByRole("heading", { name: "确认新浏览器" }).waitFor();
  await capture(page, label, "04-confirm");
  const pending = await context.request.get(url + "/panel");
  assert.equal(pending.status(), 403);
  assert.equal((await context.request.post(url + "/__test/approve")).status(), 204);
  await page.waitForURL("**/panel");
  await page.getByRole("heading", { name: "消息", exact: true }).waitFor();
  await capture(page, label, "10-inbox");

  await page.getByLabel("搜索正文").fill("浏览器演练");
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  await page.locator("#results a").first().waitFor();
  await capture(page, label, "11-inbox-search");
  await page.locator("#results a").first().click();
  const text = await page.locator("pre").textContent();
  assert.match(text, /<script>bad\(\)<\/script>⟨U\+202E⟩/);
  assert.equal(await page.locator("pre script").count(), 0);
  await capture(page, label, "12-message");

  if (full) {
    await page.getByRole("button", { name: "重新投递" }).click();
    await page.locator("#reauth-link").waitFor({ state: "visible" });
    await capture(page, label, "13-message-reauth");
    for (const [i, id] of messages.entries()) {
      await page.goto(url + "/panel/message/" + id);
      await page.locator("h1").waitFor();
      await capture(page, label, `14-message-${i + 1}`);
    }
  }

  await tab(page, label, "已过滤", "已过滤", "20-filtered");
  if (full) {
    await page.locator("main .row").first().click();
    await page.getByRole("button", { name: "找回并转发" }).waitFor();
    await capture(page, label, "21-message-filtered");
  }
  await tab(page, label, "总览", "系统总览", "30-status");
  await tab(page, label, "统计", "统计", "31-stats");
  await tab(page, label, "来源", "来源", "40-sources");
  await tab(page, label, "设备", "设备", "41-devices");
  await tab(page, label, "管理凭证", "管理凭证", "42-keys");
  await tab(page, label, "安全", "安全", "50-security");
  await tab(page, label, "设置", "设置", "51-settings");

  await page.getByRole("button", { name: "退出", exact: true }).click();
  await page.getByRole("link", { name: "使用 Google 登录" }).waitFor();
  assert.deepEqual(issues, []);
  await context.close();
}

(async () => {
  assert.ok(url);
  if (shots) fs.mkdirSync(shots, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  try {
    // Galaxy Z Fold8 cover screen, inner screen, desktop; then a small phone.
    await run(browser, { width: 412, height: 915 }, "0412", true);
    await run(browser, { width: 960, height: 720 }, "0960", true);
    await run(browser, { width: 1440, height: 900 }, "1440", true);
    await run(browser, { width: 360, height: 780 }, "0360", false);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
