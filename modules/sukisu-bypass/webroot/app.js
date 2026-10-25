(() => {
  "use strict";
  const byId = (id) => document.getElementById(id);
  const bridge = typeof window.ksu !== "undefined" && typeof window.ksu.exec === "function";
  const controller = "/system/bin/sh '/data/adb/modules/miniwatts_bypass/control.sh'";
  const allowed = new Set(["status", "probe", "configure-mca", "on", "off", "limits", "diagnose"]);
  const stateKeys = ["mode", "configured", "session_active", "request_state", "raw_node_value", "watchdog", "recovery_pending", "power_path"];
  let current = null;
  let supported = false;
  let busy = false;
  let sequence = 0;
  let lastRead = "暂无";

  function parse(text) {
    const data = Object.create(null);
    for (const line of text.split(/\r?\n/)) {
      const match = /^([a-z_]+)=(.*)$/.exec(line);
      if (match) {
        if (Object.prototype.hasOwnProperty.call(data, match[1])) throw new Error("状态含重复字段");
        data[match[1]] = match[2];
      }
    }
    return data;
  }

  function integer(value, min, max) {
    if (!/^[0-9]+$/.test(value)) throw new Error("阈值必须是整数，不能含空格、小数或命令文本。");
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number < min || number > max) throw new Error("阈值超出允许范围。");
    return String(number);
  }

  function execute(command, args = []) {
    if (!bridge) return Promise.reject(new Error("浏览器预览没有控制接口。"));
    if (!allowed.has(command)) return Promise.reject(new Error("拒绝未允许的命令。"));
    let suffix = "";
    if (command === "limits") {
      if (args.length !== 2) throw new Error("阈值参数缺失。");
      suffix = ` '${integer(args[0], 10, 95)}' '${integer(args[1], 300, 500)}'`;
    } else if (args.length) throw new Error("此命令不接受参数。");
    return new Promise((resolve, reject) => {
      const callback = `miniwattsResult${++sequence}`;
      const timer = window.setTimeout(() => {
        delete window[callback];
        reject(new Error("等待后台超时，执行结果未知。刷新状态并读取诊断；不要假定已启用或关闭。"));
      }, 20000);
      window[callback] = (errno, stdout, stderr) => {
        window.clearTimeout(timer);
        delete window[callback];
        if (!Number.isInteger(Number(errno))) { reject(new Error("后台返回码无效，结果未知。")); return; }
        resolve({ code: Number(errno), stdout: String(stdout || ""), stderr: String(stderr || "") });
      };
      try {
        // Official SukiSU / KernelSU bridge: callback(errno, stdout, stderr).
        window.ksu.exec(`${controller} '${command}'${suffix}`, "{}", callback);
      } catch (error) {
        window.clearTimeout(timer);
        delete window[callback];
        reject(error);
      }
    });
  }

  function buttons() {
    const available = bridge && !busy;
    const known = current && !current.error && current.configured === "yes" && ["xiaomi-mca-policy", "native-boolean"].includes(current.mode);
    const idle = current && !current.error && ["yes", "no"].includes(current.configured) && current.session_active === "no" && current.recovery_pending === "no";
    for (const id of ["refresh", "probe", "diagnose"]) byId(id).disabled = !available;
    byId("off").disabled = !(available && current && (current.session_active === "yes" || current.recovery_pending === "yes"));
    byId("on").disabled = !(available && known && idle && current.request_state === "off" && current.watchdog === "running");
    byId("configure").disabled = !(available && supported && current && idle);
    byId("save-limits").disabled = !(available && known && idle);
  }

  function unknown(message) {
    current = null;
    for (const id of ["mode", "session", "request", "watchdog", "recovery", "raw"]) byId(id).textContent = "未知";
    byId("summary").textContent = message;
    byId("summary").dataset.error = "true";
    byId("updated").textContent = `上次成功读取：${lastRead} · 当前状态未知`;
    buttons();
  }

  function render(data) {
    if (stateKeys.some((key) => !Object.prototype.hasOwnProperty.call(data, key)) ||
        !["yes", "no", "unknown"].includes(data.configured) ||
        !["yes", "no", "unknown"].includes(data.session_active) ||
        !["on", "off", "unknown"].includes(data.request_state) ||
        !["yes", "no", "unknown"].includes(data.recovery_pending) ||
        !["xiaomi-mca-policy", "native-boolean", "unconfigured"].includes(data.mode) ||
        !["running", "unavailable"].includes(data.watchdog) || data.power_path !== "unverified") {
      throw new Error("后台状态格式不完整或不兼容，当前状态未知。");
    }
    current = data;
    const modes = { "xiaomi-mca-policy": "厂商 FCC 限流", "native-boolean": "原生布尔接口请求", unconfigured: "未配置" };
    byId("mode").textContent = modes[data.mode] || "未知模式";
    byId("session").textContent = ({ yes: "存在模块会话", no: "无模块会话", unknown: "未知" })[data.session_active];
    byId("request").textContent = ({ on: "开启请求", off: "关闭请求", unknown: "未知" })[data.request_state];
    byId("watchdog").textContent = data.watchdog === "running" ? "运行中" : "不可用 / 未确认";
    byId("recovery").textContent = ({ yes: "待恢复记录存在", no: "无待恢复记录", unknown: "未知" })[data.recovery_pending];
    byId("raw").textContent = data.raw_node_value === "unknown" ? "未知" : data.raw_node_value;
    let summary = "已读取状态，真实供电路径尚未验证。";
    const uncertain = data.configured === "unknown" || data.session_active === "unknown" || (data.configured !== "no" && data.request_state === "unknown") || data.recovery_pending === "unknown" || !!data.error;
    if (uncertain) summary = "状态未确认，请查看诊断；不要假定已关闭。";
    else if (data.recovery_pending === "yes") summary = "恢复记录仍在，请关注诊断与恢复结果。";
    else if (data.configured === "no") summary = "未配置，先只读探测再明确选择策略。";
    else if (data.session_active === "yes") summary = "模块会话存在，真实旁路效果尚未验证。";
    else if (data.request_state === "off") summary = "无模块会话，驱动回显为关闭请求。";
    else summary = "无模块会话，但驱动回显为开启；请排查其他控制器。";
    byId("summary").textContent = summary;
    byId("summary").dataset.error = String(uncertain || data.recovery_pending === "yes");
    lastRead = new Date().toLocaleString("zh-CN");
    byId("updated").textContent = `上次成功读取：${lastRead}`;
    if (/^[0-9]+$/.test(data.min_capacity || "")) byId("capacity").value = data.min_capacity;
    if (/^[0-9]+$/.test(data.max_temp_deci_c || "")) byId("temperature").value = data.max_temp_deci_c;
    buttons();
  }

  function resultText(command, result) {
    return `$ control.sh ${command}\n退出码：${result.code}\n${result.stdout}${result.stderr ? `\n错误输出：\n${result.stderr}` : ""}`;
  }

  async function readStatus() {
    const result = await execute("status");
    if (result.code !== 0) throw new Error(`状态读取失败（${result.code}）。\n${result.stdout}\n${result.stderr}`);
    render(parse(result.stdout));
    return result;
  }

  async function run(command, args = []) {
    if (!bridge || busy) return;
    busy = true;
    buttons();
    let output = "";
    byId("output").textContent = `正在执行 ${command}…`;
    try {
      const result = await execute(command, args);
      output = resultText(command, result);
      if (command === "status") {
        if (result.code !== 0) throw new Error(output);
        render(parse(result.stdout));
      } else if (command === "probe") {
        supported = false;
        const probe = parse(result.stdout);
        supported = result.code === 0 && probe.mca_supported === "yes" && probe.mode === "xiaomi-mca-policy" && probe.policy === "fcc_limit" && probe.power_path === "unverified";
        byId("probe-result").textContent = supported ? "匹配 dada 厂商 FCC 策略。支持不代表当前安全条件满足，启用时仍会检查。" : `未确认支持，禁止配置。原因：${probe.why || result.stderr || "状态未知"}`;
      }
      if (command !== "status") {
        const status = await readStatus();
        output += `\n\n${resultText("status", status)}`;
      }
      if (result.code !== 0) output += "\n\n命令失败；以上刷新结果只表示当前读值，不能把失败当作已启用或恢复成功。";
    } catch (error) {
      output += `\n${String(error.message || error)}`;
      unknown("读取或执行结果未知，请刷新状态并查看诊断。");
      if (command === "probe") { supported = false; byId("probe-result").textContent = "探测失败，支持状态未知。"; }
    } finally {
      byId("output").textContent = output;
      busy = false;
      buttons();
    }
  }

  byId("refresh").addEventListener("click", () => run("status"));
  for (const command of ["probe", "on", "off", "diagnose"]) byId(command).addEventListener("click", () => run(command));
  byId("configure").addEventListener("click", () => run("configure-mca"));
  byId("limits-form").addEventListener("submit", (event) => {
    event.preventDefault();
    try {
      const args = [integer(byId("capacity").value, 10, 95), integer(byId("temperature").value, 300, 500)];
      run("limits", args);
    } catch (error) { byId("output").textContent = error.message; }
  });
  byId("preview").hidden = bridge;
  buttons();
  if (bridge) run("status");
})();
