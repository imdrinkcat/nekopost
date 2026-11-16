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
  const shoot = shots && shooting && !label.endsWith("-360");
  for (const colorScheme of ["light", "dark"]) {
    await page.emulateMedia({ colorScheme, reducedMotion: "reduce" });
    const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
    assert.equal(fits, true, `${label} ${name} (${colorScheme}) scrolls horizontally`);
    if (shoot) {
      await page.screenshot({ path: path.join(shots, `${label}-${name}-${colorScheme}.png`), fullPage: true, animations: "disabled" });
    }
  }
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
}

// Check the current page at 360px too, then restore the run's viewport.
async function narrow(page, label, name) {
  const size = page.viewportSize();
  await page.setViewportSize({ width: 360, height: 780 });
  await capture(page, label + "-360", name);
  await page.setViewportSize(size);
}

async function tab(page, label, link, heading, name) {
  await page.getByRole("navigation", { name: "面板" }).getByRole("link", { name: link, exact: true }).click();
  await page.getByRole("heading", { name: heading, exact: true }).waitFor();
  await capture(page, label, name);
}

async function run(browser, viewport, label, full, sweep = false) {
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
  const dialogs = [];
  page.on("dialog", dialog => { dialogs.push(dialog.type() + ":" + dialog.message()); return dialog.accept(); });

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
  if (sweep) await narrow(page, label, "03-finish");
  release();
  await page.waitForURL("**/panel/confirm");
  await page.unroute("**/auth/finish");
  await page.getByRole("heading", { name: "确认新浏览器" }).waitFor();
  await capture(page, label, "04-confirm");
  if (sweep) await narrow(page, label, "04-confirm");
  const pending = await context.request.get(url + "/panel");
  assert.equal(pending.status(), 403);
  assert.equal((await context.request.post(url + "/__test/approve")).status(), 204);
  await page.waitForURL("**/panel");
  await page.getByRole("heading", { name: "消息", exact: true }).waitFor();
  await capture(page, label, "10-inbox");

  await page.getByLabel("搜索正文").fill("浏览器演练");
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  // Strict locator: the search must return exactly this one message.
  await page.locator("#results a").waitFor();
  await page.getByRole("heading", { name: "搜索结果" }).waitFor();
  await capture(page, label, "11-inbox-search");
  await page.locator("#results a").click();
  const text = await page.locator("pre").textContent();
  assert.match(text, /<script>bad\(\)<\/script>⟨U\+202E⟩/);
  assert.equal(await page.locator("pre script").count(), 0);
  await capture(page, label, "12-message");

  if (full) {
    await page.getByRole("button", { name: "重新投递" }).click();
    try {
      await page.locator("#reauth-link").waitFor({ state: "visible", timeout: 10000 });
    } catch (error) {
      const state = await page.evaluate(() => {
        const b = document.querySelector('button[data-action="retry"]');
        return [b?.disabled, b?.closest("[data-row]")?.dataset.row, document.body.dataset.csrf?.length].join(",");
      });
      throw new Error("re-auth prompt missing; result: " + await page.locator("#management-result").textContent() +
        "; dialogs: " + dialogs.join(" | ") + "; state: " + state + "; " + issues.join(" | "));
    }
    // Only the re-auth card shows; it is scrolled into view and focused.
    assert.equal(await page.locator("#management-result").isVisible(), false);
    assert.equal(await page.evaluate(() => document.activeElement?.id), "reauth-link");
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
  if (full) {
    // Pairing result: text, QR code and copy button stack in the banner.
    await page.getByLabel("设备名称").fill("演示平板");
    await page.getByRole("button", { name: "创建配对" }).click();
    await page.getByText(/手动配对码 /).waitFor();
    await page.getByRole("img", { name: "设备配对二维码" }).waitFor();
    await capture(page, label, "41-devices-pairing");
  }
  await tab(page, label, "管理凭证", "管理凭证", "42-keys");
  if (full) {
    await page.getByLabel("凭证名称").fill("演示脚本");
    await page.getByRole("button", { name: "创建凭证" }).click();
    await page.getByText("请现在保存凭证，页面只显示一次").waitFor();
    await page.locator("#management-result code").waitFor();
    await capture(page, label, "42-keys-created");
  }
  await tab(page, label, "安全", "安全", "50-security");
  await tab(page, label, "设置", "设置", "51-settings");

  if (sweep) {
    // A small phone: every page must still fit without horizontal scrolling.
    await page.setViewportSize({ width: 360, height: 780 });
    const tabs = ["", "?tab=filtered", "?tab=status", "?tab=stats", "?tab=sources", "?tab=devices", "?tab=keys", "?tab=security", "?tab=settings"];
    for (const target of [...tabs.map(t => "/panel" + t), ...messages.map(id => "/panel/message/" + id)]) {
      await page.goto(url + target);
      await capture(page, label + "-360", target);
    }
  }

  await page.getByRole("button", { name: "退出", exact: true }).click();
  await page.getByRole("link", { name: "使用 Google 登录" }).waitFor();
  if (sweep) {
    // Signed-out entry pages at 360px, reusing this context (no extra login).
    await page.setViewportSize({ width: 360, height: 780 });
    await capture(page, label + "-360", "01-login");
    await page.goto(url + "/auth/google/callback");
    await page.getByRole("heading", { name: "登录已过期或无效，请重新开始" }).waitFor();
    await capture(page, label + "-360", "02-error");
  }
  assert.deepEqual(issues, []);
  await context.close();
}

(async () => {
  assert.ok(url);
  if (shots) fs.mkdirSync(shots, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  try {
    // Galaxy Z Fold8 cover screen, inner screen and desktop.
    await run(browser, { width: 412, height: 915 }, "0412", true);
    await run(browser, { width: 960, height: 720 }, "0960", true);
    await run(browser, { width: 1440, height: 900 }, "1440", true, true);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
