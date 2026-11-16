"use strict";

const { mode, csrf, approval, state } = document.body.dataset;
const notice = document.getElementById("notice");

function post(path, data) {
  return fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Nekopost-CSRF": csrf },
    body: JSON.stringify(data)
  });
}

async function session() {
  const response = await fetch("/v1/session");
  return response.ok ? response.json() : null;
}

if (mode === "home") {
  session().then(current => {
    if (current?.state === "active") location.replace("/panel");
    if (current?.state === "pending") location.replace("/panel/confirm");
  }).catch(() => {});
}

if (mode === "finish") {
  history.replaceState(null, "", "/");
  post("/auth/finish", {}).then(async response => {
    if (!response.ok) {
      notice.textContent = await response.text();
      return;
    }
    const current = await response.json();
    location.replace(current.state === "active" ? "/panel" : "/panel/confirm");
  }).catch(() => { notice.textContent = "连接中断，请重新登录。"; });
}

function approvalEndpoint(id) {
  if (!/^a_[A-Z2-7]{26}$/.test(id)) return null;
  return "/v1/approvals/".concat(encodeURIComponent(id));
}

async function pollApproval() {
  try {
    const path = approvalEndpoint(approval);
    if (!path) return;
    const response = await fetch(path);
    if (!response.ok) {
      notice.textContent = "确认已失效，请重新登录。";
      return;
    }
    const current = await response.json();
    if (current.state === "approved") {
      const result = await post("/auth/activate", {});
      if (result.ok) location.replace("/panel");
      else notice.textContent = "确认已失效，请重新登录。";
      return;
    }
    if (current.state !== "pending") {
      notice.textContent = "确认已结束，请重新登录。";
      return;
    }
  } catch {
    notice.textContent = "连接中断，正在重试。";
  }
  setTimeout(pollApproval, 2000);
}

if (mode === "confirm" && (state === "pending" || state === "approved")) pollApproval();

document.getElementById("logout")?.addEventListener("click", async event => {
  const button = event.currentTarget;
  button.disabled = true;
  try {
    const response = await post("/auth/logout", {});
    if (response.ok || response.status === 401) location.replace("/");
    else button.textContent = "重试退出";
  } catch {
    button.textContent = "重试退出";
  } finally {
    button.disabled = false;
  }
});

document.getElementById("search")?.addEventListener("submit", async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button");
  button.disabled = true;
  const list = document.getElementById("results");
  list.replaceChildren();
  notice.textContent = "搜索中";
  try {
    const response = await post("/panel/search", { query: document.getElementById("query").value });
    if (response.redirected || response.status === 401) {
      location.replace("/");
      return;
    }
    if (!response.ok) {
      notice.textContent = "搜索未完成，请重试。";
      return;
    }
    const data = await response.json();
    for (const hit of data.hits) {
      if (!/^m_[0-9A-HJKMNP-TV-Z]{26}$/.test(hit.ID)) continue;
      const item = document.createElement("li");
      const link = document.createElement("a");
      link.href = "/panel/message/".concat(encodeURIComponent(hit.ID));
      link.dir = "auto";
      link.textContent = hit.Source + " · " + hit.Preview;
      item.append(link);
      list.append(item);
    }
    notice.textContent = "找到 " + data.hits.length + " 条，已扫描 " + data.scanned + " 条。" +
      (data.exhausted ? "" : " 搜索达到本次上限，可缩小搜索范围。");
  } catch {
    notice.textContent = "连接中断，请重试。";
  } finally {
    button.disabled = false;
  }
});

function validSegment(value, pattern) {
  return pattern.test(value) ? encodeURIComponent(value) : null;
}

async function manage(method, path, data = {}, download = false) {
  const display = document.getElementById("management-result");
  const reauthLink = document.getElementById("reauth-link");
  display.hidden = false;
  display.textContent = "正在处理";
  reauthLink.hidden = true;
  try {
    const response = await fetch(path, {
      method,
      headers: { "Content-Type": "application/json", "X-Nekopost-CSRF": csrf },
      body: method === "DELETE" ? undefined : JSON.stringify(data),
    });
    if (response.status === 204) {
      location.reload();
      return;
    }
    const result = await response.json();
    if (!response.ok) {
      display.textContent = result.error?.message ?? result.message ?? "操作未完成";
      reauthLink.hidden = (result.error?.code ?? result.code) !== "reauth_required";
      return;
    }
    if (download) {
      const uri = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2)], { type: "application/json" }));
      const link = document.createElement("a");
      link.href = uri;
      link.download = "nekopost-export.json";
      link.click();
      setTimeout(() => URL.revokeObjectURL(uri), 1000);
      display.textContent = "导出已生成";
    } else if (result.short_code) {
      display.textContent = "手动配对码 " + result.short_code + "（5 分钟有效）";
      if (typeof result.qr === "string" && result.qr.startsWith("nekopost://enroll?")) {
        const copy = document.createElement("button");
        copy.type = "button";
        copy.textContent = "复制配对链接";
        copy.addEventListener("click", () => navigator.clipboard.writeText(result.qr));
        display.append(document.createElement("br"), copy);
      }
    } else if (result.token) {
      display.textContent = "请现在保存凭证，页面只显示一次\n" + result.token;
    } else display.textContent = "操作已完成";
  } catch {
    display.textContent = "无法确认操作结果，请刷新后检查。";
  }
}

function bindForm(id, action) {
  document.getElementById(id)?.addEventListener("submit", async event => {
    event.preventDefault();
    const button = event.currentTarget.querySelector("button");
    button.disabled = true;
    try { await action(); } finally { button.disabled = false; }
  });
}

bindForm("new-source", () => manage("POST", "/v1/admin/sources", {
  id: document.getElementById("source-id").value,
  name: document.getElementById("source-name").value,
}));
bindForm("new-key", () => manage("POST", "/v1/admin/keys", { name: document.getElementById("key-name").value }));
bindForm("new-pairing", () => manage("POST", "/v1/admin/pairings", {
  name: document.getElementById("device-name").value,
  role: document.getElementById("device-role").value,
  source: document.getElementById("device-source").value,
}));

document.addEventListener("click", async event => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const action = button.dataset.action;
  const raw = button.closest("[data-row]")?.dataset.row;
  let run;
  const source = validSegment(raw ?? "", /^[a-z0-9][a-z0-9_-]{0,31}$/);
  const message = validSegment(raw ?? "", /^m_[0-9A-HJKMNP-TV-Z]{26}$/);
  if (action === "rename-source" && source) {
    const name = prompt("输入新的来源名称");
    if (name?.trim()) run = () => manage("PATCH", "/v1/admin/sources/".concat(source), { name });
  } else if (action === "source-enabled" && source) {
    run = () => manage("PATCH", "/v1/admin/sources/".concat(source), { enabled: button.dataset.enabled !== "true" });
  } else if (action === "rotate-source" && source && confirm("轮换后旧来源凭证立即失效。继续吗？")) {
    run = () => manage("POST", "/v1/admin/sources/".concat(source, "/rotate"));
  } else if (action === "delete-source" && source && confirm("删除来源和它的凭证？")) {
    run = () => manage("DELETE", "/v1/admin/sources/".concat(source));
  } else if (action === "revoke-device" && confirm("吊销这台设备？")) {
    const id = validSegment(raw ?? "", /^d_[A-Z2-7]{26}$/);
    if (id) run = () => manage("DELETE", "/v1/admin/devices/".concat(id));
  } else if (action === "revoke-key" && confirm("吊销此管理凭证？")) {
    const id = validSegment(raw ?? "", /^mga_[A-Za-z0-9_-]{6}$/);
    if (id) run = () => manage("DELETE", "/v1/admin/keys/".concat(id));
  } else if (action === "retry" && message && confirm("重新投递这条消息？")) {
    run = () => manage("POST", "/v1/inbox/".concat(message, "/redeliver"));
  } else if (action === "rescue" && message && confirm("找回并转发这条消息？")) {
    run = () => manage("POST", "/v1/inbox/".concat(message, "/rescue"));
  } else if (action === "shred" && message && confirm("永久销毁这条消息的正文？")) {
    run = () => manage("DELETE", "/v1/inbox/".concat(message, "/body"));
  } else if (action === "export" && message && confirm("导出会包含明文正文。继续吗？")) {
    run = () => manage("POST", "/v1/inbox/export", { ids: [raw] }, true);
  }
  if (!run) return;
  button.disabled = true;
  try { await run(); } finally { button.disabled = false; }
});

document.getElementById("reauth-number")?.addEventListener("click", async event => {
  const button = event.currentTarget;
  const display = document.getElementById("management-result");
  button.disabled = true;
  try {
    const r = await post("/v1/session/reauth", {});
    if (!r.ok) throw new Error("start");
    const request = await r.json();
    const path = approvalEndpoint(request.id);
    if (!path) throw new Error("id");
    display.textContent = "请在 Telegram 或 CLI 选择数字 " + request.short_code;
    for (let n = 0; n < 150; n++) {
      const response = await fetch(path);
      if (!response.ok) throw new Error("poll");
      const { state } = await response.json();
      if (state === "approved") {
        const finish = await post("/v1/session/reauth/finish", {});
        if (!finish.ok) throw new Error("finish");
        display.textContent = "已重新认证，请再次执行刚才的操作。";
        document.getElementById("reauth-link").hidden = true;
        return;
      }
      if (state !== "pending") break;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    display.textContent = "确认已结束，请重新发起。";
  } catch {
    display.textContent = "重新认证未完成，请重试。";
  } finally {
    button.disabled = false;
  }
});
