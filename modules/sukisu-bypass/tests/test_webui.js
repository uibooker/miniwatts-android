"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../webroot/app.js"), "utf8");
const ids = ["refresh", "probe", "diagnose", "off", "on", "configure", "save-limits", "mode", "session", "request", "watchdog", "recovery", "raw", "summary", "updated", "capacity", "temperature", "output", "probe-result", "limits-form", "preview"];
const writable = ["on", "off", "configure", "save-limits"];
const baseline = {
  mode: "xiaomi-mca-policy", configured: "yes", session_active: "no",
  request_state: "off", raw_node_value: "80", watchdog: "running",
  recovery_pending: "no", power_path: "unverified", policy: "fcc_limit",
};
const encode = (data) => Object.entries(data).map(([key, value]) => `${key}=${value}`).join("\n") + "\n";
const flush = () => new Promise((resolve) => setImmediate(resolve));

function setup(bridge = true) {
  const elements = Object.fromEntries(ids.map((id) => [id, {
    disabled: false, hidden: false, textContent: "", value: "", dataset: {}, handlers: {},
    addEventListener(name, callback) { this.handlers[name] = callback; },
  }]));
  const commands = [];
  const pending = [];
  const timers = new Map();
  let timerId = 0;
  const window = {
    setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
    clearTimeout(id) { timers.delete(id); },
  };
  if (bridge) window.ksu = { exec(command, options, callback) {
    assert.equal(options, "{}");
    commands.push(command);
    pending.push({ command, callback });
  } };
  vm.runInNewContext(source, { window, document: { getElementById(id) {
    assert.ok(elements[id], `unknown DOM ID ${id}`);
    return elements[id];
  } }, Date, Promise, Set, Number, Object, String, Error });
  return {
    elements, commands, pending,
    async reply(stdout, code = 0, stderr = "") {
      assert.ok(pending.length, "expected bridge request");
      const request = pending.shift();
      window[request.callback](code, stdout, stderr);
      await flush();
      return request.command;
    },
    async click(id) {
      assert.equal(elements[id].disabled, false, `${id} should be clickable`);
      elements[id].handlers.click();
      await flush();
    },
    async submit(capacity, temperature) {
      elements.capacity.value = capacity;
      elements.temperature.value = temperature;
      elements["limits-form"].handlers.submit({ preventDefault() {} });
      await flush();
    },
    async timeout() {
      assert.equal(timers.size, 1);
      [...timers.values()][0]();
      timers.clear();
      await flush();
    },
  };
}

test("preview has no bridge calls and disables every action", () => {
  const ui = setup(false);
  assert.equal(ui.elements.preview.hidden, false);
  assert.deepEqual(ui.commands, []);
  for (const id of [...writable, "refresh", "probe", "diagnose"]) assert.equal(ui.elements[id].disabled, true, id);
});

test("startup performs only status and waits for valid state before enabling", async () => {
  const ui = setup();
  assert.deepEqual(ui.commands, ["/system/bin/sh '/data/adb/modules/miniwatts_bypass/control.sh' 'status'"]);
  for (const id of writable) assert.equal(ui.elements[id].disabled, true, id);
  await ui.reply(encode(baseline));
  assert.equal(ui.elements.on.disabled, false);
  assert.equal(ui.elements.off.disabled, true);
  assert.equal(ui.elements.configure.disabled, true);
  assert.equal(ui.elements["save-limits"].disabled, false);
});

test("missing, duplicate, invalid state and failed status keep actions unavailable", async () => {
  for (const key of ["mode", "configured", "session_active", "request_state", "raw_node_value", "watchdog", "recovery_pending", "power_path"]) {
    const ui = setup();
    const data = { ...baseline };
    delete data[key];
    await ui.reply(encode(data));
    for (const id of writable) assert.equal(ui.elements[id].disabled, true, key + ":" + id);
    assert.equal(ui.elements.request.textContent, "未知");
  }
  for (const [output, code] of [[encode(baseline) + "configured=no\n", 0], [encode({ ...baseline, configured: "maybe" }), 0], [encode({ ...baseline, mode: "unknown-driver" }), 0], [encode({ ...baseline, watchdog: "maybe" }), 0], [encode(baseline), 1], [encode(baseline), "invalid"]]) {
    const ui = setup();
    await ui.reply(output, code, "read failed");
    for (const id of writable) assert.equal(ui.elements[id].disabled, true, id);
  }
});

test("foreign request is never owned; pending recovery permits off retry", async () => {
  const foreign = setup();
  await foreign.reply(encode({ ...baseline, request_state: "on", raw_node_value: "1104" }));
  assert.equal(foreign.elements.on.disabled, true);
  assert.equal(foreign.elements.off.disabled, true);
  const recovery = setup();
  await recovery.reply(encode({ ...baseline, session_active: "unknown", request_state: "unknown", recovery_pending: "yes", error: "recovery_node_unreadable_or_invalid" }));
  assert.equal(recovery.elements.on.disabled, true);
  assert.equal(recovery.elements.off.disabled, false);
  assert.equal(recovery.elements["save-limits"].disabled, true);
  await recovery.click("off");
  assert.match(recovery.commands.at(-1), / 'off'$/);
  await recovery.reply("restore failed", 1);
  assert.match(recovery.commands.at(-1), / 'status'$/);
  await recovery.reply(encode({ ...baseline, session_active: "unknown", request_state: "unknown", recovery_pending: "yes" }));
  assert.equal(recovery.elements.off.disabled, false);
});

test("probe and configure require explicit separate actions, never enable", async () => {
  const ui = setup();
  await ui.reply(encode(baseline));
  await ui.click("probe");
  await ui.reply("mca_supported=yes\nmode=xiaomi-mca-policy\npolicy=fcc_limit\npower_path=unverified\nwhy=matched_vendor_policy\n");
  assert.equal(ui.elements.configure.disabled, true, "status refresh still pending");
  await ui.reply(encode(baseline));
  assert.equal(ui.elements.configure.disabled, false);
  await ui.click("configure");
  await ui.reply("saved profile; no request enabled");
  await ui.reply(encode(baseline));
  assert.deepEqual(ui.commands.map((command) => command.match(/ '([^']+)'$/)[1]), ["status", "probe", "status", "configure-mca", "status"]);
  await ui.click("probe");
  await ui.reply("mca_supported=no\nwhy=unsupported_kernel\n");
  await ui.reply(encode(baseline));
  assert.equal(ui.elements.configure.disabled, true);
});

test("limits reject malformed or out-of-range values before bridge invocation", async () => {
  const ui = setup();
  await ui.reply(encode(baseline));
  for (const [capacity, temperature] of [["9", "450"], ["96", "450"], ["30", "299"], ["30", "501"], ["30.5", "450"], ["-30", "450"], [" 30", "450"], ["30 ", "450"], ["", "450"], ["30", ""], ["30;id", "450"], ["30", "$(id)"], ["30", "450\nreboot"]]) {
    const count = ui.commands.length;
    await ui.submit(capacity, temperature);
    assert.equal(ui.commands.length, count, `${capacity}/${temperature}`);
  }
  for (const [capacity, temperature] of [["10", "300"], ["95", "500"], ["030", "0450"]]) {
    await ui.submit(capacity, temperature);
    assert.equal(ui.commands.at(-1), `/system/bin/sh '/data/adb/modules/miniwatts_bypass/control.sh' 'limits' '${Number(capacity)}' '${Number(temperature)}'`);
    await ui.reply("limits saved");
    await ui.reply(encode(baseline));
  }
});

test("on failure refreshes actual state without optimistic success and busy blocks duplicates", async () => {
  const ui = setup();
  await ui.reply(encode(baseline));
  await ui.click("on");
  assert.equal(ui.elements.on.disabled, true);
  assert.equal(ui.elements.off.disabled, true);
  await ui.reply("write failed", 1);
  assert.match(ui.commands.at(-1), / 'status'$/);
  assert.equal(ui.elements.request.textContent, "关闭请求");
  await ui.reply(encode({ ...baseline, session_active: "unknown", request_state: "unknown", recovery_pending: "yes" }));
  assert.equal(ui.elements.request.textContent, "未知");
  assert.match(ui.elements.output.textContent, /命令失败/);
  assert.equal(ui.elements.on.disabled, true);
  assert.equal(ui.elements.off.disabled, false);
});

test("timed-out or failed refresh invalidates previously usable state", async () => {
  const ui = setup();
  await ui.reply(encode(baseline));
  await ui.click("refresh");
  await ui.timeout();
  for (const id of writable) assert.equal(ui.elements[id].disabled, true, id);
  assert.equal(ui.elements.request.textContent, "未知");
});

test("compatibility error cannot leave enable or profile writes available", async () => {
  const ui = setup();
  await ui.reply(encode({ ...baseline, error: "unsupported_kernel" }));
  for (const id of ["on", "configure", "save-limits"]) assert.equal(ui.elements[id].disabled, true, id);
});
