const assert = require("node:assert/strict");
const { chromium } = require("playwright");

(async () => {
  const url = process.env.NEKOPOST_TEST_PANEL;
  assert.ok(url);
  const browser = await chromium.launch({ headless: true });
  try {
    for (const viewport of [{ width: 1280, height: 820 }, { width: 360, height: 780 }]) {
      const context = await browser.newContext({ viewport, ignoreHTTPSErrors: true });
      const page = await context.newPage();
      const issues = [];
      page.on("pageerror", error => issues.push(error.message));
      page.on("console", message => {
        if (/content security policy|trusted types/i.test(message.text())) issues.push(message.text());
      });
      await page.goto(url);
      await page.getByRole("link", { name: "使用 Google 登录" }).click();
      await page.waitForURL("**/panel/confirm");
      await page.getByRole("heading", { name: "确认新浏览器" }).waitFor();
      const pending = await context.request.get(url + "/panel");
      assert.equal(pending.status(), 403);
      assert.equal((await context.request.post(url + "/__test/approve")).status(), 204);
      await page.waitForURL("**/panel");
      await page.getByRole("heading", { name: "消息", exact: true }).waitFor();

      await page.getByLabel("搜索正文").fill("浏览器演练");
      await page.getByRole("button", { name: "搜索", exact: true }).click();
      await page.locator("#results a").click();
      const text = await page.locator("pre").textContent();
      assert.match(text, /<script>bad\(\)<\/script>⟨U\+202E⟩/);
      assert.equal(await page.locator("pre script").count(), 0);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);

      await page.getByRole("link", { name: "总览", exact: true }).click();
      await page.getByRole("heading", { name: "系统总览" }).waitFor();
      await page.getByRole("link", { name: "统计", exact: true }).click();
      await page.getByRole("heading", { name: "统计", exact: true }).waitFor();
      await page.getByRole("button", { name: "退出", exact: true }).click();
      await page.getByRole("link", { name: "使用 Google 登录" }).waitFor();
      assert.deepEqual(issues, []);
      await context.close();
    }
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
