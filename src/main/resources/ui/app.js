// Factorio Optimizer UI: a settings form built from the job and tuning types (/api/schema), runs, and a layout viewer.
"use strict";

const $ = (s) => document.querySelector(s);
const api = async (path, opts) => {
  const r = await fetch(path, opts);
  const t = await r.text();
  let j; try { j = JSON.parse(t); } catch { j = t; }
  if (!r.ok) throw new Error((j && j.error) || r.statusText);
  return j;
};
const el = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kids.flat()) if (k !== null && k !== undefined) e.append(k.nodeType ? k : document.createTextNode(String(k)));
  return e;
};
const store = {
  get(k, d) { try { const v = localStorage.getItem("fo." + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem("fo." + k, JSON.stringify(v)); } catch { /* private window */ } },
};

let schema = null, game = null;
let job = {}, jobPath = "";

// ---------------------------------------------------------------------------------------------------------------
// Tabs

document.querySelectorAll("nav button").forEach((b) => b.addEventListener("click", () => showTab(b.dataset.tab)));
function showTab(name) {
  document.querySelectorAll("nav button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
  document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t.id === "tab-" + name));
  store.set("tab", name);
  if (name === "runs") { loadRuns(); viewer.resize(); }
}

// ---------------------------------------------------------------------------------------------------------------
// Jobs: list, form, JSON

async function loadJobs() {
  const list = await api("/api/jobs");
  const f = $("#jobFilter").value.toLowerCase();
  const ul = $("#jobList"); ul.textContent = "";
  for (const p of list.filter((p) => p.toLowerCase().includes(f))) {
    ul.append(el("li", { class: p === jobPath ? "active" : "", title: p, onclick: () => openJob(p) }, p));
  }
}
$("#jobFilter").addEventListener("input", loadJobs);

async function openJob(p) {
  const text = await api("/api/file?path=" + encodeURIComponent(p));
  job = typeof text === "string" ? JSON.parse(text) : text;
  jobPath = p; $("#jobPath").value = p;
  store.set("job", p);
  $("#runOut").value = "solves/" + p.split("/").pop().replace(/\.json$/, "");
  renderForm(); syncJson(); loadJobs();
  msg("#jobMsg", "");
}
$("#newJob").addEventListener("click", () => {
  job = { targets: [{ name: "automation-science-pack", amount: 1 }], machines: ["assembling-machine-2"], belt_tier: "transport-belt" };
  jobPath = ""; $("#jobPath").value = "jobs/new_job.json";
  renderForm(); syncJson();
});

function msg(sel, text, kind) { const m = $(sel); m.textContent = text; m.className = "msg" + (kind ? " " + kind : ""); }

$("#saveJob").addEventListener("click", async () => {
  const p = $("#jobPath").value.trim();
  if (!p) return msg("#jobMsg", "name the file first", "bad");
  try {
    await api("/api/file?path=" + encodeURIComponent(p), { method: "POST", body: JSON.stringify(job, null, 2) + "\n" });
    jobPath = p; msg("#jobMsg", "saved " + p, "ok"); loadJobs();
  } catch (e) { msg("#jobMsg", e.message, "bad"); }
});
$("#validateJob").addEventListener("click", async () => {
  const r = await api("/api/validate", { method: "POST", body: JSON.stringify(job) });
  msg("#jobMsg", r.ok ? "the job reads fine" : r.error, r.ok ? "ok" : "bad");
});
$("#startRun").addEventListener("click", async () => {
  const p = $("#jobPath").value.trim();
  if (!p) return msg("#runMsg", "save the job first", "bad");
  try {
    await api("/api/file?path=" + encodeURIComponent(p), { method: "POST", body: JSON.stringify(job, null, 2) + "\n" });
    const r = await api("/api/run", { method: "POST", body: JSON.stringify({ config: p, out: $("#runOut").value.trim(), ram: $("#runRam").value.trim(), resume: $("#runResume").checked ? "true" : "false" }) });
    msg("#runMsg", "started in " + r.started, "ok");
    store.set("run", r.started);
    setTimeout(() => { showTab("runs"); openRun(r.started); }, 400);
  } catch (e) { msg("#runMsg", e.message, "bad"); }
});

// JSON pane: what the form makes; editing it re-reads the form.
let jsonTimer = null;
function syncJson() { $("#jsonText").value = JSON.stringify(job, null, 2); }
$("#jsonText").addEventListener("input", () => {
  clearTimeout(jsonTimer);
  jsonTimer = setTimeout(() => {
    try { job = JSON.parse($("#jsonText").value); renderForm(); $("#jsonText").classList.remove("bad"); }
    catch { $("#jsonText").classList.add("bad"); }
  }, 500);
});
$("#onlySet").addEventListener("change", renderForm);

// The form. Each field: set (in the job) or left at its default (unset, shown greyed with the default).
const ITEM_FIELDS = /^(name|item|items|targets|forbidden_recipes|recipe|recipes|machine|machines|allowed|modules|machine_modules|fallback_module|beacon|belt_tier|weave_tier|fuel|raw_resources|split_at)$/;
function suggestionsFor(name) {
  if (!game) return null;
  if (/machine/.test(name)) return game.machines;
  if (/recipe/.test(name)) return game.recipes;
  if (/belt_tier|weave_tier/.test(name)) return Object.keys(game.entities).filter((n) => n.endsWith("transport-belt"));
  if (/module|beacon/.test(name)) return game.items.filter((i) => /module|beacon/.test(i));
  if (/allowed/.test(name)) return Object.keys(game.entities).filter((n) => n.endsWith("inserter"));
  if (ITEM_FIELDS.test(name)) return game.items.concat(game.fluids);
  return null;
}
let dlCount = 0;
function datalist(options) {
  const id = "dl" + (dlCount++);
  const dl = el("datalist", { id });
  for (const o of options) dl.append(el("option", { value: o }));
  document.body.append(dl);
  return id;
}
const dlCache = new Map();
function listId(name) {
  const opts = suggestionsFor(name);
  if (!opts) return null;
  const key = opts.length + ":" + name.replace(/s$/, "");
  if (!dlCache.has(key)) dlCache.set(key, datalist(opts));
  return dlCache.get(key);
}

function renderForm() {
  const form = $("#form"); form.textContent = "";
  if (!schema) return;
  const collapsed = store.get("collapsed", {});
  const top = objectEditor(schema.job, job, (v) => { job = v; syncJson(); }, "job", collapsed, 0, true);
  form.append(top);
}

function schemaOf(name, s) {
  // The job's tuning overrides are free JSON in the type; the tuning schema describes them.
  if (name === "tuning" && s.kind === "json") return Object.assign({}, schema.tuning, { nullable: true });
  return s;
}

function objectEditor(s, value, onChange, path, collapsed, depth, topLevel = false) {
  const obj = value && typeof value === "object" ? value : {};
  const box = topLevel ? el("div") : el("fieldset", { class: collapsed[path] === false ? "" : (depth >= 1 && collapsed[path] !== true ? "collapsed" : (collapsed[path] ? "collapsed" : "")) });
  if (!topLevel) {
    const lg = el("legend", { onclick: () => { box.classList.toggle("collapsed"); const c = store.get("collapsed", {}); c[path] = box.classList.contains("collapsed"); store.set("collapsed", c); } },
      path.split(".").pop(), el("span", { class: "type" }, s.type));
    box.append(lg);
  }
  for (const f of s.fields || []) {
    const fs = schemaOf(f.name, f.schema);
    const isSet = Object.prototype.hasOwnProperty.call(obj, f.name);
    if ($("#onlySet").checked === false || true) { /* every field is shown; unset ones greyed */ }
    const lab = el("label", { class: isSet ? "" : "unset", title: f.doc || "" });
    if (f.optional) {
      const cb = el("input", { type: "checkbox" }); cb.checked = isSet;
      cb.addEventListener("change", () => {
        const o = Object.assign({}, obj);
        if (cb.checked) o[f.name] = initial(fs, f.schema.default); else delete o[f.name];
        onChange(o); renderForm();
      });
      lab.append(cb);
    }
    lab.append(f.name);
    const ctl = el("div", { class: "ctl" });
    const set = (v) => { const o = Object.assign({}, obj); o[f.name] = v; Object.assign(obj, o); onChange(o); };
    ctl.append(valueEditor(fs, isSet ? obj[f.name] : (fs.default ?? f.schema.default), (v) => { if (!isSet) { lab.classList.remove("unset"); const cb = lab.querySelector("input"); if (cb) cb.checked = true; } set(v); },
      path + "." + f.name, collapsed, depth + 1, f.name, !isSet));
    const row = el("div", { class: "field" }, lab, ctl);
    if (f.doc) row.append(el("div", { class: "doc" }, f.doc));
    box.append(row);
  }
  return box;
}

function initial(s, def) {
  if (def !== undefined && def !== null) return JSON.parse(JSON.stringify(def));
  switch (s.kind) {
    case "boolean": return false;
    case "integer": case "number": return 0;
    case "string": return "";
    case "list": return [];
    case "map": return {};
    case "object": return {};
    default: return null;
  }
}

function valueEditor(s, value, onChange, path, collapsed, depth, name, unset) {
  switch (s.kind) {
    case "boolean": {
      const cb = el("input", { type: "checkbox" }); cb.checked = !!value;
      cb.addEventListener("change", () => onChange(cb.checked));
      return el("span", {}, cb, unset ? el("span", { class: "default" }, " default") : null);
    }
    case "integer": case "number": {
      const i = el("input", { type: "number", step: s.kind === "integer" ? "1" : "any", value: value ?? "", placeholder: s.nullable ? "none" : "" });
      if (unset) i.style.opacity = .6;
      i.addEventListener("change", () => onChange(i.value === "" ? null : (s.kind === "integer" ? parseInt(i.value) : parseFloat(i.value))));
      return i;
    }
    case "string": {
      const i = el("input", { type: "text", value: value ?? "", placeholder: s.nullable ? "none" : "", list: listId(name) });
      if (unset) i.style.opacity = .6;
      i.addEventListener("change", () => onChange(i.value === "" && s.nullable ? null : i.value));
      return i;
    }
    case "list": return listEditor(s, Array.isArray(value) ? value : [], onChange, path, collapsed, depth, name);
    case "object": return objectEditor(s, value || {}, onChange, path, collapsed, depth);
    case "map": {
      const t = el("textarea", { spellcheck: "false" }); t.value = JSON.stringify(value ?? {}, null, 1);
      t.addEventListener("change", () => { try { onChange(JSON.parse(t.value)); t.classList.remove("bad"); } catch { t.classList.add("bad"); } });
      return t;
    }
    default: {
      const t = el("textarea", { spellcheck: "false" }); t.value = value === undefined ? "" : JSON.stringify(value, null, 1);
      t.addEventListener("change", () => { try { onChange(t.value.trim() ? JSON.parse(t.value) : null); } catch { /* keep typing */ } });
      return t;
    }
  }
}

function listEditor(s, arr, onChange, path, collapsed, depth, name) {
  const box = el("div", { class: "list-ctl" });
  const items = s.items || { kind: "json" };
  arr.forEach((v, i) => {
    const ed = valueEditor(items, v, (nv) => { const a = arr.slice(); a[i] = nv; arr.splice(0, arr.length, ...a); onChange(a); },
      path + "[" + i + "]", collapsed, depth, name, false);
    if (ed.tagName === "FIELDSET") ed.classList.remove("collapsed");
    box.append(el("div", { class: "list-row" }, ed,
      el("button", { class: "mini", title: "remove", onclick: () => { const a = arr.slice(); a.splice(i, 1); onChange(a); renderForm(); } }, "×")));
  });
  box.append(el("div", {}, el("button", { class: "mini", onclick: () => { const a = arr.slice(); a.push(initial(items, items.default)); onChange(a); renderForm(); } }, "+ add")));
  return box;
}

// ---------------------------------------------------------------------------------------------------------------
// Runs

let runDir = null, runTimer = null, runInfo = null;
async function loadRuns() {
  const list = await api("/api/runs");
  const f = $("#runFilter").value.toLowerCase();
  const ul = $("#runList"); ul.textContent = "";
  for (const r of list.filter((r) => r.dir.toLowerCase().includes(f))) {
    ul.append(el("li", { class: r.dir === runDir ? "active" : "", title: r.dir + "\n" + new Date(r.modified).toLocaleString(), onclick: () => openRun(r.dir) },
      r.dir.replace(/^solves\//, ""), r.running ? el("span", { class: "tag" }, "running") : null, r.factory ? el("span", { class: "tag" }, "blocks") : null));
  }
}
$("#runFilter").addEventListener("input", loadRuns);
$("#refreshRuns").addEventListener("click", loadRuns);

async function openRun(dir) {
  runDir = dir; store.set("run", dir);
  $("#runTitle").textContent = dir;
  runInfo = null; viewer.set(null); $("#runCtl").hidden = true; $("#statusBox").textContent = ""; $("#starveBox").hidden = true; $("#blocksBox").textContent = ""; $("#logBox").textContent = "";
  delete $("#view").dataset.shown;
  loadRuns();
  await refreshRun(true);
}

async function refreshRun(first) {
  clearTimeout(runTimer);
  if (!runDir) return;
  let info;
  const asked = runDir;
  try { info = await api("/api/run?dir=" + encodeURIComponent(runDir)); } catch (e) { $("#runState").textContent = e.message; return; }
  if (asked !== runDir) return;   // another run was picked meanwhile; its own refresh is under way
  const changed = !runInfo || runInfo.dir !== info.dir || JSON.stringify(runInfo.files) !== JSON.stringify(info.files) ||
    (info.status && runInfo.status && info.status.elapsed !== runInfo.status.elapsed && (info.files || []).includes("best_blueprint.json"));
  runInfo = info;
  // Live while its status.json keeps being written (also while finishing, so "Stop now" can follow "Finish").
  const alive = !info.ended && !!info.status && (info.statusAge ?? 0) < 20;
  $("#stopRun").disabled = !info.running && !alive;
  $("#stopRun").hidden = alive;   // a live factory has its own row of controls
  $("#runCtl").hidden = !alive;
  if (alive) renderControls(info);
  $("#runState").textContent = info.running ? "running" : alive ? "running (started elsewhere)" : "";
  renderStatus(info);
  $("#verifyRun").disabled = info.verifying || !(info.files || []).includes("test_output_string.txt");
  $("#verifyBox").hidden = !info.verifyLog;
  if (info.verifyLog) { $("#verifyText").textContent = info.verifyLog; if (info.verifying) $("#verifyBox").open = true; }
  if (info.verifying) $("#runState").textContent = "verifying in game…";
  if (first || changed) fillLayoutPick(info, first);
  if (first) await drawPicked();
  else if (changed && $("#layoutPick").value === "best") await drawPicked();
  const live = info.running || info.verifying || alive;
  loadHistory(live);
  runTimer = setTimeout(() => refreshRun(false), live ? 2500 : 15000);
}

// --- live controls of a factory run: the same commands as the terminal's keys, through the run's control file ---

async function sendCmd(cmd) {
  if (!runDir) return;
  try {
    await api("/api/control?dir=" + encodeURIComponent(runDir) + "&cmd=" + encodeURIComponent(cmd), { method: "POST" });
    $("#runState").textContent = "sent: " + cmd;
    setTimeout(() => refreshRun(false), 1200);
  } catch (e) { $("#runState").textContent = e.message; }
}
document.querySelectorAll("#runCtl button[data-cmd]").forEach((b) => b.addEventListener("click", () => sendCmd(b.dataset.cmd)));
$("#ctlLimitSet").addEventListener("click", () => {
  const m = parseFloat($("#ctlLimit").value);
  const st = runInfo && runInfo.status;
  if (!(m >= 0) || !st) return;
  sendCmd("limit " + Math.round(st.elapsed + m * 60));   // the limit counts from the run's start
});
$("#ctlLimit").addEventListener("keydown", (e) => { if (e.key === "Enter") $("#ctlLimitSet").click(); });
$("#ctlLanesDown").addEventListener("click", () => { const st = runInfo && runInfo.status; if (st) sendCmd("lanes " + Math.max(1, st.lanes - 1)); });
$("#ctlLanesUp").addEventListener("click", () => { const st = runInfo && runInfo.status; if (st) sendCmd("lanes " + Math.min(st.threads, st.lanes + 1)); });
$("#ctlKill").addEventListener("click", async () => {
  if (!runDir || !confirm("Kill the run's process? Nothing more is saved (Stop now keeps the best factory so far).")) return;
  try { await api("/api/stop?force=1&dir=" + encodeURIComponent(runDir), { method: "POST" }); $("#runState").textContent = "killed"; }
  catch (e) { $("#runState").textContent = e.message; }
});

function renderControls(info) {
  const st = info.status;
  const pause = $("#ctlPause");
  pause.textContent = st.paused ? "Resume" : "Pause"; pause.dataset.cmd = st.paused ? "resume" : "pause";
  pause.classList.toggle("on", !!st.paused);
  $("#ctlLeft").textContent = st.left < 0 ? "no limit" : fmtTime(st.left) + " left";
  $("#ctlLanes").textContent = st.lanes;
  $("#ctlLanesDown").disabled = st.lanes <= 1; $("#ctlLanesUp").disabled = st.lanes >= st.threads;
  const focused = (st.blocks || []).some((b) => b.state === "focus");
  $("#ctlFocusOff").disabled = !focused;
  $("#ctlKill").disabled = !info.running;
  const flags = [];
  if (st.stopping) flags.push("finishing");
  if (st.paused) flags.push("paused");
  if (st.stitching) flags.push("stitching a preview");
  else if (st.sinceStitch >= 0) flags.push("stitched " + fmtTime(st.sinceStitch) + " ago");
  $("#ctlFlags").textContent = flags.join(" · ");
}
$("#verifyRun").addEventListener("click", async () => {
  if (!runDir) return;
  try { await api("/api/verify?dir=" + encodeURIComponent(runDir), { method: "POST" }); $("#runState").textContent = "verifying in game…"; refreshRun(false); }
  catch (e) { $("#runState").textContent = e.message; }
});
$("#starveRun").addEventListener("click", async () => {
  if (!runDir) return;
  $("#starveBox").hidden = false; $("#starveText").textContent = "tracing…";
  const r = await api("/api/starve?dir=" + encodeURIComponent(runDir));
  $("#starveText").textContent = r.text;
});
$("#stopRun").addEventListener("click", async () => {
  if (!runDir) return;
  await api("/api/stop?dir=" + encodeURIComponent(runDir), { method: "POST" });
  $("#runState").textContent = "stopping…";
});

// --- history of a factory run, read from its factory.txt: phases, previews, the factory's size, block progress ---

let historyAt = 0, historyDir = null;
async function loadHistory(live) {
  if (!runDir) return;
  const now = Date.now();
  if (historyDir === runDir && now - historyAt < (live ? 10000 : 60000)) return;
  historyAt = now; historyDir = runDir;
  const asked = runDir;
  let text;
  try { text = await (await fetch("/api/file?path=" + encodeURIComponent(runDir + "/factory.txt"))).text(); } catch { text = null; }
  if (asked !== runDir) return;
  if (!text || text.startsWith("{")) { $("#historyBox").hidden = true; return; }
  $("#historyBox").hidden = false;
  renderHistory(parseHistory(text));
}

function parseHistory(text) {
  const num = (x) => parseFloat(String(x).replace(",", "."));
  const h = { phases: [], previews: [], finals: [], blocks: new Map(), steering: [], end: 0 };
  let stitchFrom = null, lastPreviewLine = null;
  const phase = (name, t) => { const p = h.phases[h.phases.length - 1]; if (p && p.name === name) return; h.phases.push({ name, t }); };
  phase("plan and split", 0);
  for (const line of text.split("\n")) {
    const m = line.match(/^\[\s*([\d.,]+)s\] (.*)$/);
    if (!m) continue;
    const t = num(m[1]), msg = m[2];
    h.end = Math.max(h.end, t);
    let b;
    if ((b = msg.match(/^block (\d+)(?: x([\d.,]+))?: (first layout )?(\d+) x (\d+)(?: \(was (\d+) x (\d+)\))?$/))) {
      if (!h.phases.some((p) => p.name === "first layouts")) phase("first layouts", t);
      const key = b[1] + (b[2] ? " ×" + num(b[2]).toFixed(1) : "");
      if (!h.blocks.has(key)) h.blocks.set(key, []);
      h.blocks.get(key).push({ t, w: +b[4], h: +b[5] });
    } else if (/^every block has a layout/.test(msg)) { phase("refining", t); stitchFrom = t; }
    else if (/^stitching a preview$/.test(msg) || /^stitch requested$/.test(msg)) { stitchFrom = stitchFrom ?? t; }
    else if ((b = msg.match(/^preview: (\d+) x (\d+) \((\d+) tiles\)(?: in ([\d.,]+) s)?;/))) {
      const from = b[4] ? t - num(b[4]) : (stitchFrom ?? lastPreviewLine ?? t);
      h.previews.push({ t, from, w: +b[1], h: +b[2], tiles: +b[3], ok: true }); stitchFrom = null; lastPreviewLine = null;
    } else if ((b = msg.match(/^preview: did not stitch(?: \(([\d.,]+) s\))?/))) {
      const from = b[1] ? t - num(b[1]) : (stitchFrom ?? lastPreviewLine ?? t);
      h.previews.push({ t, from, ok: false }); stitchFrom = null; lastPreviewLine = null;
    } else if (/^preview:/.test(msg)) { lastPreviewLine = lastPreviewLine ?? t; }
    else if (/^final factory/.test(msg)) phase("final factory", t);
    else if ((b = msg.match(/^factory(?: \(compacting\))?: (\d+) x (\d+) \((\d+) tiles\), saved/))) h.finals.push({ t, w: +b[1], h: +b[2], tiles: +b[3] });
    else if ((b = msg.match(/^done: (\d+) x (\d+)/))) { phase("done", t); h.done = { t, w: +b[1], h: +b[2] }; }
    else if (/^(stop|stopping|paused|resumed|limit:|focus|lanes:|control:|time is up)|skipped$|back in turn$/.test(msg) || /^block \d+.* (skipped|back in turn)$/.test(msg))
      h.steering.push({ t, msg });
  }
  return h;
}

function renderHistory(h) {
  const box = $("#historyBody"); box.textContent = "";
  // Phases and how long each took.
  const pt = el("table", {}, el("tr", {}, el("th", {}, "phase"), el("th", {}, "from"), el("th", {}, "took")));
  h.phases.forEach((p, i) => {
    const next = h.phases[i + 1];
    const took = p.name === "done" ? "" : fmtTime((next ? next.t : h.end) - p.t) + (next ? "" : " so far");
    pt.append(el("tr", {}, el("td", {}, p.name), el("td", { class: "num" }, fmtClock(p.t)), el("td", { class: "num" }, took)));
  });
  box.append(el("h4", {}, "Phases"), pt);
  // The factory's size over time: previews, then the final factory's saves.
  const pts = h.previews.filter((p) => p.ok).map((p) => ({ t: p.t, tiles: p.tiles, label: `preview ${p.w} × ${p.h}` }))
    .concat(h.finals.map((f) => ({ t: f.t, tiles: f.tiles, label: `final ${f.w} × ${f.h}` })));
  if (pts.length) box.append(el("h4", {}, "Factory size (tiles)"), sizeChart(pts, h.previews.filter((p) => !p.ok)));
  // Previews: when, what, how long the stitch took.
  if (h.previews.length) {
    const t = el("table", {}, el("tr", {}, el("th", {}, "preview at"), el("th", {}, "factory"), el("th", {}, "tiles"), el("th", {}, "took")));
    for (const p of h.previews) t.append(el("tr", {}, el("td", { class: "num" }, fmtClock(p.t)), el("td", {}, p.ok ? `${p.w} × ${p.h}` : "did not stitch"),
      el("td", { class: "num" }, p.ok ? p.tiles.toLocaleString() : ""), el("td", { class: "num" }, fmtTime(p.t - p.from))));
    box.append(el("h4", {}, `Previews (${h.previews.length})`), t);
  }
  // Blocks: first layout, now, how much smaller, how many times it improved and when last.
  if (h.blocks.size) {
    const rows = [...h.blocks.entries()].map(([k, v]) => {
      const a = v[0], z = v[v.length - 1];
      return { k, a, z, n: v.length - 1, gain: 1 - (z.w * z.h) / (a.w * a.h), v };
    }).sort((x, y) => y.gain - x.gain);
    const t = el("table", {}, el("tr", {}, ...["block", "first", "now", "smaller", "better", "last at"].map((x) => el("th", {}, x))));
    for (const r of rows) {
      const tr = el("tr", { class: "pick", title: r.v.map((s) => `${fmtTime(s.t)}: ${s.w} × ${s.h}`).join("\n") },
        el("td", {}, r.k), el("td", {}, `${r.a.w}×${r.a.h}`), el("td", {}, `${r.z.w}×${r.z.h}`),
        el("td", { class: "num" }, (100 * r.gain).toFixed(0) + "%"), el("td", { class: "num" }, r.n + "×"), el("td", { class: "num" }, fmtClock(r.z.t)));
      tr.addEventListener("click", () => {
        // "9" is block 9; "9 ×0.6" its variant with that factor (found in the live status, else by the order of the factors).
        const [blk, f] = r.k.split(" ×");
        let variant = 0;
        if (f) {
          const st = runInfo && runInfo.status && (runInfo.status.blocks || []).find((b) => b.block === +blk && Math.abs(b.factor - parseFloat(f)) < 0.051);
          variant = st ? st.variant : 1 + [...new Set([...h.blocks.keys()].filter((k) => k.startsWith(blk + " ×")).map((k) => parseFloat(k.split(" ×")[1])))].sort((a, b) => a - b).indexOf(parseFloat(f));
        }
        pickLayout(blockDir(+blk, variant));
      });
      t.append(tr);
    }
    box.append(el("h4", {}, "Blocks (hover a row for every step)"), t);
  }
  if (h.steering.length) {
    const t = el("table", {});
    for (const s of h.steering) t.append(el("tr", {}, el("td", { class: "num" }, fmtClock(s.t)), el("td", {}, s.msg)));
    box.append(el("h4", {}, "Steering and stops"), t);
  }
}

/** One series: the factory's tiles at each preview and final save, with a crosshair tooltip; failed previews as rings on the axis. */
function sizeChart(pts, fails) {
  const ns = "http://www.w3.org/2000/svg";
  const W = 360, H = 150, L = 46, R = 10, T = 8, B = 20;
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`); svg.setAttribute("class", "hist-chart");
  const mk = (tag, attrs) => { const e = document.createElementNS(ns, tag); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v); svg.append(e); return e; };
  const tMax = Math.max(...pts.map((p) => p.t), ...fails.map((f) => f.t), 1);
  const lo = Math.min(...pts.map((p) => p.tiles)), hi = Math.max(...pts.map((p) => p.tiles));
  const pad = Math.max(1, (hi - lo) * 0.1), y0 = Math.max(0, lo - pad), y1 = hi + pad;
  const x = (t) => L + (t / tMax) * (W - L - R), y = (v) => T + (1 - (v - y0) / (y1 - y0)) * (H - T - B);
  for (let i = 0; i <= 3; i++) {
    const v = y0 + (i / 3) * (y1 - y0);
    mk("line", { x1: L, x2: W - R, y1: y(v), y2: y(v), class: "grid" });
    mk("text", { x: L - 4, y: y(v) + 3, "text-anchor": "end", class: "axis" }).textContent = Math.round(v / 1000) + "k";
  }
  for (let i = 0; i <= 4; i++) mk("text", { x: x((i / 4) * tMax), y: H - 5, "text-anchor": i === 0 ? "start" : i === 4 ? "end" : "middle", class: "axis" }).textContent = fmtClock((i / 4) * tMax);
  mk("path", { d: pts.map((p, i) => `${i ? "L" : "M"}${x(p.t)},${y(p.tiles)}`).join(""), class: "line" });
  for (const p of pts) mk("circle", { cx: x(p.t), cy: y(p.tiles), r: 4, class: "dot" });
  for (const f of fails) mk("circle", { cx: x(f.t), cy: H - B, r: 4, class: "fail" });
  const cross = mk("line", { y1: T, y2: H - B, class: "cross", visibility: "hidden" });
  const hit = mk("rect", { x: L, y: T, width: W - L - R, height: H - T - B, class: "hit" });
  const wrap = el("div", { class: "hist-wrap" }); wrap.append(svg);
  const tip = el("div", { class: "hist-tip", hidden: true }); wrap.append(tip);
  hit.addEventListener("mousemove", (e) => {
    const r = svg.getBoundingClientRect(), sx = ((e.clientX - r.left) / r.width) * W;
    const p = pts.reduce((a, q) => Math.abs(x(q.t) - sx) < Math.abs(x(a.t) - sx) ? q : a);
    cross.setAttribute("x1", x(p.t)); cross.setAttribute("x2", x(p.t)); cross.setAttribute("visibility", "visible");
    tip.hidden = false; tip.textContent = `${fmtTime(p.t)}: ${p.label}, ${p.tiles.toLocaleString()} tiles`;
    const px = (x(p.t) / W) * r.width;
    tip.style.left = Math.min(px + 8, r.width - tip.offsetWidth - 4) + "px"; tip.style.top = "4px";
  });
  hit.addEventListener("mouseleave", () => { cross.setAttribute("visibility", "hidden"); tip.hidden = true; });
  return wrap;
}

function renderStatus(info) {
  const box = $("#statusBox"); box.textContent = "";
  const st = info.status;
  const kv = el("div", { class: "kv" });
  const add = (k, v) => kv.append(el("span", {}, k), el("span", {}, v));
  if (st) {
    add("phase", st.phase || "");
    add("elapsed", fmtTime(st.elapsed));
    if (st.left > 0) add("left", fmtTime(st.left));
    if (st.factoryW) add("factory", st.factoryW + " × " + st.factoryH);
    add("threads", st.threads);
  }
  if (info.ports) {
    add("box", info.ports.box ? info.ports.box.w + " × " + info.ports.box.h : "");
    for (const o of info.ports.outputs || []) add("out", `${o.item} ${(+o.rate).toFixed(2)}/s ${o.edge}`);
  }
  if (info.verify && info.verify.outputs) {
    const per = {};
    for (const o of info.verify.outputs) for (const [k, v] of Object.entries(o.per_s || {})) per[k] = (per[k] || 0) + v;
    for (const [k, v] of Object.entries(per)) add("in game", `${k} ${v.toFixed(3)}/s`);
  }
  box.append(kv);
  if (info.plan) {
    const total = Object.values(info.plan).reduce((a, b) => a + b, 0);
    const t = el("table", {}, el("tr", {}, el("th", {}, "recipe (machine)"), el("th", {}, "machines")));
    for (const [k, v] of Object.entries(info.plan)) t.append(el("tr", {}, el("td", {}, k), el("td", {}, v)));
    box.append(el("details", {}, el("summary", {}, `Plan: ${total} machines, ${Object.keys(info.plan).length} recipes`), t));
  }
  const bb = $("#blocksBox");
  const wasOpen = bb.querySelector("details") ? bb.querySelector("details").open : true;
  bb.textContent = "";
  if (st && st.blocks && st.blocks.length) {
    const live = !$("#runCtl").hidden;
    const heads = ["block", "thr", "mach", "size", "first", "turns", "better ago", "spent", "state"].concat(live ? [""] : []);
    const t = el("table", {}, el("tr", {}, ...heads.map((h) => el("th", {}, h))));
    for (const b of st.blocks) {
      const name = blockDir(b.block, b.variant);
      const id = b.variant > 0 ? `${b.block}:${b.variant}` : String(b.block);
      const label = b.variant > 0 ? `${b.block} ×${(+b.factor).toFixed(1)}` : String(b.block);
      const cells = [
        el("td", {}, label), el("td", { class: "num" }, b.threads || ""), el("td", { class: "num" }, b.machines),
        el("td", {}, b.w ? b.w + "×" + b.h : "—"), el("td", { class: "muted" }, b.firstW ? b.firstW + "×" + b.firstH : ""),
        el("td", { class: "num" }, b.turns), el("td", { class: "num", title: "since it last got smaller" }, b.sinceBetter >= 0 ? fmtClock(b.sinceBetter) : ""),
        el("td", { class: "num" }, fmtClock(b.spent)), el("td", {}, b.state || ""),
      ];
      if (live) {
        const stop = (f) => (e) => { e.stopPropagation(); f(); };
        const focus = el("button", { class: "mini" + (b.state === "focus" ? " on" : ""), title: "Every thread on this block (again: back to normal)",
          onclick: stop(() => sendCmd(b.state === "focus" ? "focus off" : "focus " + id)) }, "focus");
        const skip = el("button", { class: "mini" + (b.state === "skipped" ? " on" : ""), title: "Leave this block out of the turns (again: back in)",
          onclick: stop(() => sendCmd((b.state === "skipped" ? "unskip " : "skip ") + id)) }, b.state === "skipped" ? "unskip" : "skip");
        cells.push(el("td", { class: "acts" }, focus, " ", skip));
      }
      const tr = el("tr", { class: "pick" + ($("#layoutPick").value === name ? " sel" : "") + (b.critical ? " crit" : ""),
        title: b.critical ? "sets the factory's width or height" : "", onclick: () => pickLayout(name) }, ...cells);
      t.append(tr);
    }
    bb.append(el("details", { open: wasOpen }, el("summary", {}, `Blocks (${st.blocks.length}) — click a row to view, * sets the factory's size`), t));
  }
  const lb = $("#logBox");
  const atEnd = lb.scrollTop + lb.clientHeight >= lb.scrollHeight - 10;
  lb.textContent = info.log || "";
  if (atEnd) lb.scrollTop = lb.scrollHeight;
}
/** m:ss or h:mm:ss, for tables. */
function fmtClock(s) {
  if (s === undefined || s === null || s < 0) return "";
  s = Math.round(s); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(x).padStart(2, "0")}` : `${m}:${String(x).padStart(2, "0")}`;
}
function fmtTime(s) {
  if (s === undefined || s === null) return "";
  s = Math.round(s); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return h ? `${h} h ${m} min` : m ? `${m} min ${x} s` : `${x} s`;
}

/** "block 4", or "block 4 ×0.6" for its first shape variant (the factor from the live status when there is one). */
function blockLabel(name, info) {
  const m = name.match(/^block_(\d+)(?:\/shape_(\d+))?$/);
  if (!m) return name;
  if (!m[2]) return "block " + m[1];
  const st = info && info.status && (info.status.blocks || []).find((b) => b.block === +m[1] && b.variant === +m[2]);
  return `block ${m[1]} ` + (st ? `×${(+st.factor).toFixed(1)}` : `shape ${m[2]}`);
}
/** The layout folder of a block row: block_N, or block_N/shape_K for a shape variant. */
function blockDir(block, variant) { return "block_" + block + (variant > 0 ? "/shape_" + variant : ""); }
function pickLayout(name) {
  if (![...$("#layoutPick").options].some((o) => o.value === name)) { $("#runState").textContent = "no layout saved yet for " + name; return; }
  $("#layoutPick").value = name; drawPicked();
}

function fillLayoutPick(info, first) {
  const sel = $("#layoutPick"); const old = sel.value; sel.textContent = "";
  const files = info.files || [];
  if (files.includes("best_blueprint.json")) sel.append(el("option", { value: "best" }, "best layout"));
  if (files.includes("test_output_string.txt")) sel.append(el("option", { value: "test" }, "test copy (with feeds)"));
  for (const b of info.blocks || []) if (b.blueprint) sel.append(el("option", { value: b.name }, blockLabel(b.name, info)));
  if (!sel.options.length) sel.append(el("option", { value: "" }, "no layout yet"));
  if (!first && [...sel.options].some((o) => o.value === old)) sel.value = old;
}
$("#layoutPick").addEventListener("change", drawPicked);
$("#showVerify").addEventListener("change", () => viewer.draw());

async function drawPicked() {
  const v = $("#layoutPick").value;
  if (!v || !runDir) { viewer.set(null); return; }
  try {
    let bp;
    if (v === "best") bp = await api("/api/file?path=" + encodeURIComponent(runDir + "/best_blueprint.json"));
    else if (v === "test") bp = await decodeBlueprint(await api("/api/file?path=" + encodeURIComponent(runDir + "/test_output_string.txt")));
    else bp = await api("/api/file?path=" + encodeURIComponent(runDir + "/" + v + "/best_blueprint.json"));
    let report = null, blocks = null;
    if (v === "test" && (runInfo.files || []).includes("verify_report.json")) report = await api("/api/file?path=" + encodeURIComponent(runDir + "/verify_report.json"));
    if (v === "best" && (runInfo.files || []).includes("blocks.json")) blocks = await api("/api/file?path=" + encodeURIComponent(runDir + "/blocks.json"));
    // Port labels: the run's own for its best layout, a block's from its folder (the test copy has its harness instead).
    let ports = null;
    if (v === "best") ports = runInfo.ports || null;
    else if (v !== "test") ports = await api("/api/file?path=" + encodeURIComponent(runDir + "/" + v + "/ports.json")).catch(() => null);
    viewer.set(bp, report, blocks, runDir + "|" + v !== $("#view").dataset.shown, ports);
    $("#view").dataset.shown = runDir + "|" + v;
  } catch (e) { viewer.set(null); $("#runState").textContent = e.message; }
}

async function decodeBlueprint(s) {
  s = String(s).trim();
  const bytes = Uint8Array.from(atob(s.slice(1)), (c) => c.charCodeAt(0));
  const ds = new DecompressionStream("deflate");
  const text = await new Response(new Blob([bytes]).stream().pipeThrough(ds)).text();
  return JSON.parse(text);
}

// ---------------------------------------------------------------------------------------------------------------
// Layout viewer: entities drawn to scale, with the game's icons when available.

const sprites = new Map();
function sprite(name) {
  if (!$("#useSprites").checked || !schema || !schema.gameIcons) return null;
  let s = sprites.get(name);
  if (!s) {
    s = new Image(); s.ok = false;
    s.onload = () => { s.ok = true; viewer.draw(); };
    s.src = "/api/sprite/" + encodeURIComponent(name) + ".png";
    sprites.set(name, s);
  }
  return s.ok ? s : null;
}
$("#useSprites").addEventListener("change", () => { store.set("sprites", $("#useSprites").checked); viewer.draw(); });

const BELT_COLORS = { "transport-belt": "#d9a400", "fast-transport-belt": "#d0402b", "express-transport-belt": "#2f7fd8", "turbo-transport-belt": "#52b043" };
const STATUS_COLORS = { working: "#3c9a3f", item_ingredient_shortage: "#d23c2f", fluid_ingredient_shortage: "#d23c2f", full_output: "#d98a19", no_power: "#7a3cd2", low_power: "#7a3cd2" };
function hashColor(s) {
  let h = 0; for (const c of s) h = (h * 31 + c.charCodeAt(0)) | 0;
  return `hsl(${Math.abs(h) % 360} 38% 62%)`;
}
function tierOf(name) {
  for (const k of Object.keys(BELT_COLORS)) if (name.startsWith(k.replace("transport-belt", ""))) {
    const base = k.replace("transport-belt", "");
    if (base === "" ? !/^(fast|express|turbo)-/.test(name) : true) return BELT_COLORS[k];
  }
  return "#999";
}

const viewer = (() => {
  const cv = $("#view"); const ctx = cv.getContext("2d");
  let ents = [], bounds = null, grid = new Map(), report = null, blocks = null, ports = [];
  let scale = 16, ox = 0, oy = 0;

  function size(e) {
    const g = game && game.entities[e.name];
    let w = g ? g.w : 1, h = g ? g.h : 1;
    if (e.name.endsWith("splitter")) { w = 2; h = 1; }
    const d = e.direction || 0;
    if ((d === 4 || d === 12) && w !== h) [w, h] = [h, w];
    return [w, h];
  }

  /** [prt]: ports.json of the layout (inputs and outputs on its edge, drawn and labelled), or null. */
  function set(bp, rep, blk, fit, prt) {
    report = rep || null; blocks = blk || null;
    ports = prt ? [...(prt.inputs || []).map((p) => ({ ...p, out: false })), ...(prt.outputs || []).map((p) => ({ ...p, out: true }))] : [];
    ents = []; grid = new Map();
    const list = bp && bp.blueprint ? bp.blueprint.entities || [] : [];
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const e of list) {
      const [w, h] = size(e);
      const x = e.position.x - w / 2, y = e.position.y - h / 2;
      const it = { e, x, y, w, h };
      ents.push(it);
      x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x + w); y1 = Math.max(y1, y + h);
      for (let i = 0; i < w; i++) for (let j = 0; j < h; j++) grid.set(Math.floor(x + i) + "," + Math.floor(y + j), it);
    }
    if (report) {
      for (const m of report.machines || []) {
        const it = grid.get(Math.floor(m.x) + "," + Math.floor(m.y));
        if (it) it.status = m;
      }
    }
    for (const p of ports) { x0 = Math.min(x0, p.x); x1 = Math.max(x1, p.x + 1); y0 = Math.min(y0, p.y); y1 = Math.max(y1, p.y + 1); }
    bounds = ents.length ? { x0, y0, x1, y1 } : null;
    if (fit || !bounds) fitView();
    draw();
  }

  function fitView() {
    resize();
    if (!bounds) return;
    const W = cv.clientWidth, H = cv.clientHeight;
    // Room in pixels for the port labels beside the box.
    ctx.font = "12px sans-serif";
    const pad = { west: 8, east: 8, north: 8, south: 8 };
    for (const p of ports) {
      const e = edgeOf(p);
      const w = ctx.measureText(portLabel(p)).width + 30;   // room for "n ×" when grouped
      if (e === "west" || e === "east") pad[e] = Math.max(pad[e], w + 10);
      else pad[e] = Math.min(H / 4, pad[e] + 15);
    }
    const aw = Math.max(50, W - pad.west - pad.east), ah = Math.max(50, H - pad.north - pad.south);
    scale = Math.max(0.2, Math.min(aw / (bounds.x1 - bounds.x0 + 2), ah / (bounds.y1 - bounds.y0 + 2)));
    ox = pad.west + aw / 2 - ((bounds.x0 + bounds.x1) / 2) * scale;
    oy = pad.north + ah / 2 - ((bounds.y0 + bounds.y1) / 2) * scale;
  }

  function resize() {
    const r = window.devicePixelRatio || 1;
    cv.width = cv.clientWidth * r; cv.height = cv.clientHeight * r;
    ctx.setTransform(r, 0, 0, r, 0, 0);
  }

  function arrow(cx, cy, d, len, color, width) {
    const a = (d / 16) * Math.PI * 2 - Math.PI / 2;
    const dx = Math.cos(a), dy = Math.sin(a);
    ctx.strokeStyle = color; ctx.lineWidth = width;
    ctx.beginPath();
    ctx.moveTo(cx - dx * len / 2, cy - dy * len / 2); ctx.lineTo(cx + dx * len / 2, cy + dy * len / 2);
    const hx = cx + dx * len / 2, hy = cy + dy * len / 2, s = len * 0.35;
    ctx.moveTo(hx, hy); ctx.lineTo(hx - dx * s - dy * s * 0.8, hy - dy * s + dx * s * 0.8);
    ctx.moveTo(hx, hy); ctx.lineTo(hx - dx * s + dy * s * 0.8, hy - dy * s - dx * s * 0.8);
    ctx.stroke();
  }

  function draw() {
    const W = cv.clientWidth, H = cv.clientHeight;
    ctx.clearRect(0, 0, W, H);
    if (!bounds) {
      ctx.fillStyle = getComputedStyle(document.body).getPropertyValue("--muted");
      ctx.fillText("no layout to show", 20, 30); return;
    }
    const css = getComputedStyle(document.body);
    const line = css.getPropertyValue("--line"), ink = css.getPropertyValue("--ink");
    // Box outline and grid when zoomed in.
    ctx.strokeStyle = line; ctx.lineWidth = 1;
    if (scale >= 8) {
      ctx.beginPath();
      for (let x = Math.floor(bounds.x0); x <= bounds.x1; x++) { ctx.moveTo(ox + x * scale, oy + bounds.y0 * scale); ctx.lineTo(ox + x * scale, oy + bounds.y1 * scale); }
      for (let y = Math.floor(bounds.y0); y <= bounds.y1; y++) { ctx.moveTo(ox + bounds.x0 * scale, oy + y * scale); ctx.lineTo(ox + bounds.x1 * scale, oy + y * scale); }
      ctx.globalAlpha = 0.35; ctx.stroke(); ctx.globalAlpha = 1;
    }
    const showStatus = $("#showVerify").checked && report;
    for (const it of ents) {
      const e = it.e, n = e.name;
      const x = ox + it.x * scale, y = oy + it.y * scale, w = it.w * scale, h = it.h * scale;
      const cx = x + w / 2, cy = y + h / 2;
      if (n.endsWith("transport-belt")) {
        ctx.fillStyle = tierOf(n); ctx.globalAlpha = 0.25; ctx.fillRect(x + 1, y + 1, w - 2, h - 2); ctx.globalAlpha = 1;
        arrow(cx, cy, e.direction || 0, scale * 0.7, tierOf(n), Math.max(1, scale / 8));
      } else if (n.endsWith("underground-belt")) {
        ctx.fillStyle = tierOf(n); ctx.fillRect(x + w * 0.15, y + h * 0.15, w * 0.7, h * 0.7);
        ctx.fillStyle = "#0008"; ctx.font = `${Math.max(7, scale * 0.5)}px sans-serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
        if (scale >= 10) ctx.fillText(e.type === "input" ? "▼" : "▲", cx, cy);
      } else if (n.endsWith("splitter")) {
        ctx.fillStyle = tierOf(n); ctx.fillRect(x + 1, y + 1, w - 2, h - 2);
        arrow(cx, cy, e.direction || 0, scale * 0.7, "#fff", Math.max(1, scale / 8));
      } else if (n.endsWith("inserter")) {
        const col = n.startsWith("long") ? "#c0392b" : n.startsWith("fast") ? "#2f7fd8" : n.startsWith("bulk") || n.startsWith("stack") ? "#52b043" : "#d9a400";
        ctx.fillStyle = col; ctx.globalAlpha = 0.3; ctx.fillRect(x + w * 0.2, y + h * 0.2, w * 0.6, h * 0.6); ctx.globalAlpha = 1;
        // An inserter's direction is where it picks from; it drops on the opposite side.
        arrow(cx, cy, ((e.direction || 0) + 8) % 16, scale * (n.startsWith("long") ? 0.9 : 0.6), col, Math.max(1, scale / 10));
      } else if (/pole|substation/.test(n)) {
        ctx.fillStyle = "#8a5a2b"; ctx.beginPath(); ctx.arc(cx, cy, Math.min(w, h) * 0.25, 0, Math.PI * 2); ctx.fill();
      } else if (/pipe|tank|pump/.test(n)) {
        ctx.fillStyle = "#7d8790"; ctx.fillRect(x + w * 0.25, y + h * 0.25, w * 0.5, h * 0.5);
        if (n.includes("to-ground") && scale >= 10) arrow(cx, cy, e.direction || 0, scale * 0.6, "#fff", 1);
      } else {
        // Machines and everything else: a box, the recipe's icon (or the entity's), and the in-game status border.
        const key = e.recipe || n;
        ctx.fillStyle = e.recipe ? hashColor(e.recipe) : (n === "beacon" ? "#9b7fd0" : "#b8bcc2");
        ctx.globalAlpha = 0.55; ctx.fillRect(x + 1, y + 1, w - 2, h - 2); ctx.globalAlpha = 1;
        ctx.strokeStyle = ink; ctx.globalAlpha = 0.5; ctx.lineWidth = 1; ctx.strokeRect(x + 1, y + 1, w - 2, h - 2); ctx.globalAlpha = 1;
        const img = sprite(key) || (e.recipe ? null : null);
        const ent = sprite(n);
        if (img || ent) {
          const s = Math.min(w, h) * (e.recipe ? 0.62 : 0.8);
          if (ent && e.recipe && scale >= 14) ctx.drawImage(ent, 0, 0, 64, 64, x + 2, y + 2, Math.min(w, h) * 0.32, Math.min(w, h) * 0.32);
          ctx.drawImage(img || ent, 0, 0, 64, 64, cx - s / 2, cy - s / 2, s, s);
        } else if (scale * Math.min(it.w, it.h) >= 30) {
          ctx.fillStyle = ink; ctx.font = `${Math.max(9, Math.min(12, scale * 0.4))}px sans-serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
          wrapText(key, cx, cy, w - 4, Math.max(10, scale * 0.45));
        }
        if (showStatus && it.status) {
          ctx.strokeStyle = STATUS_COLORS[it.status.usual_status] || "#888"; ctx.lineWidth = Math.max(2, scale / 6);
          ctx.strokeRect(x + 2, y + 2, w - 4, h - 4);
        }
      }
    }
    drawPorts();
    if (blocks) {
      ctx.lineWidth = 2; ctx.setLineDash([6, 4]);
      for (const b of blocks) {
        ctx.strokeStyle = "#c4661f"; ctx.strokeRect(ox + b.x * scale, oy + b.y * scale, b.w * scale, b.h * scale);
        if (b.w * scale > 60) {
          ctx.fillStyle = "#c4661f"; ctx.font = "bold 12px sans-serif"; ctx.textAlign = "left"; ctx.textBaseline = "top";
          ctx.fillText("block " + b.block, ox + b.x * scale + 3, oy + b.y * scale + 3);
        }
      }
      ctx.setLineDash([]);
    }
  }

  function portLabel(p) { return `${p.out ? "out" : "in"}: ${p.item} ${(+p.rate).toFixed(2)}/s`; }
  function groupLabel(g) {
    if (g.ports.length === 1) return portLabel(g.ports[0]);
    const total = g.ports.reduce((a, p) => a + +p.rate, 0);
    return `${g.out ? "out" : "in"}: ${g.item} ${g.ports.length} × ${(total / g.ports.length).toFixed(2)}/s`;
  }
  const EDGES = ["west", "east", "north", "south"];
  const edgeOf = (p) => EDGES.includes(p.edge) ? p.edge : "west";

  /**
   * Labels for one edge: one per port, or one per item (with a line to each of its ports) when one per port would
   * not fit beside the box.
   */
  function edgeGroups(edge) {
    const mine = ports.filter((p) => edgeOf(p) === edge);
    const side = edge === "west" || edge === "east";
    const room = side ? (bounds.y1 - bounds.y0) * scale + 60 : Infinity;
    const single = mine.map((p) => ({ item: p.item, out: p.out, ports: [p] }));
    if (!side || single.length * 15 <= room) return single;
    const by = new Map();
    for (const p of mine) { const k = (p.out ? "o:" : "i:") + p.item; if (!by.has(k)) by.set(k, { item: p.item, out: p.out, ports: [] }); by.get(k).ports.push(p); }
    return [...by.values()];
  }

  /**
   * Inputs (green) and outputs (orange): a marked tile where the item crosses the edge, its icon, and a label beside
   * the box; labels close together are spread apart, each with a line to its port(s).
   */
  function drawPorts() {
    const fs = 12, gap = fs + 3;
    ctx.font = `${fs}px sans-serif`; ctx.textBaseline = "middle";
    const bg = getComputedStyle(document.body).getPropertyValue("--bg") || "#fff";
    for (const p of ports) {
      const px = ox + p.x * scale, py = oy + p.y * scale, col = p.out ? "#d9822b" : "#2e9b4f";
      const s = Math.max(scale, 3);
      ctx.fillStyle = col; ctx.globalAlpha = 0.35; ctx.fillRect(px, py, s, s); ctx.globalAlpha = 1;
      ctx.strokeStyle = col; ctx.lineWidth = scale >= 6 ? 2 : 1; ctx.strokeRect(px + 0.5, py + 0.5, s - 1, s - 1);
      const img = sprite(p.item);
      if (img && scale >= 10) ctx.drawImage(img, 0, 0, 64, 64, px + scale * 0.1, py + scale * 0.1, scale * 0.8, scale * 0.8);
    }
    for (const edge of EDGES) {
      const side = edge === "west" || edge === "east";
      const gs = edgeGroups(edge).map((g) => {
        const cx = g.ports.reduce((a, p) => a + ox + (p.x + 0.5) * scale, 0) / g.ports.length;
        const cy = g.ports.reduce((a, p) => a + oy + (p.y + 0.5) * scale, 0) / g.ports.length;
        const label = groupLabel(g);
        return { ...g, cx, cy, label, tw: ctx.measureText(label).width };
      });
      if (!gs.length) continue;
      if (side) {
        // Spread down from where each label wants to be, then back up if the last runs past the box.
        gs.sort((a, b) => a.cy - b.cy);
        gs.forEach((g, i) => { g.ly = i ? Math.max(g.cy, gs[i - 1].ly + gap) : g.cy; });
        const bottom = oy + bounds.y1 * scale + 30;
        for (let i = gs.length - 1; i >= 0; i--) {
          const lim = i === gs.length - 1 ? bottom : gs[i + 1].ly - gap;
          if (gs[i].ly > lim) gs[i].ly = lim;
        }
        gs.forEach((g) => { g.align = edge === "west" ? "right" : "left"; g.lx = edge === "west" ? ox + bounds.x0 * scale - 10 : ox + bounds.x1 * scale + 10; });
      } else {
        gs.sort((a, b) => a.cx - b.cx);
        const rows = [];
        for (const g of gs) {
          const want = g.cx - g.tw / 2;
          let r = rows.findIndex((end) => end + 6 <= want);
          if (r < 0) { r = rows.length; rows.push(-Infinity); }
          rows[r] = want + g.tw;
          g.align = "center"; g.lx = g.cx;
          g.ly = edge === "north" ? oy + bounds.y0 * scale - 10 - r * gap : oy + bounds.y1 * scale + 10 + r * gap;
        }
      }
      for (const g of gs) {
        const col = g.out ? "#d9822b" : "#2e9b4f";
        const ex = g.align === "right" ? g.lx + 2 : g.align === "left" ? g.lx - 2 : g.lx;
        const ey = g.align === "center" ? g.ly + (edge === "north" ? fs / 2 : -fs / 2) : g.ly;
        ctx.strokeStyle = col; ctx.lineWidth = 1; ctx.globalAlpha = 0.6; ctx.beginPath();
        for (const p of g.ports) { ctx.moveTo(ox + (p.x + 0.5) * scale, oy + (p.y + 0.5) * scale); ctx.lineTo(ex, ey); }
        ctx.stroke(); ctx.globalAlpha = 1;
        const bx = g.align === "right" ? g.lx - g.tw : g.align === "left" ? g.lx : g.lx - g.tw / 2;
        ctx.fillStyle = bg; ctx.globalAlpha = 0.9; ctx.fillRect(bx - 2, g.ly - fs * 0.65, g.tw + 4, fs * 1.3); ctx.globalAlpha = 1;
        ctx.textAlign = g.align; ctx.fillStyle = col; ctx.fillText(g.label, g.lx, g.ly);
      }
    }
  }

  function wrapText(t, cx, cy, maxW, lh) {
    const words = t.split("-"); const lines = []; let cur = "";
    for (const w of words) { const nx = cur ? cur + "-" + w : w; if (ctx.measureText(nx).width > maxW && cur) { lines.push(cur); cur = w; } else cur = nx; }
    lines.push(cur);
    lines.forEach((l, i) => ctx.fillText(l, cx, cy + (i - (lines.length - 1) / 2) * lh));
  }

  // Pan, zoom, hover.
  let drag = null;
  cv.addEventListener("mousedown", (ev) => { drag = { x: ev.clientX, y: ev.clientY, ox, oy }; cv.classList.add("drag"); });
  window.addEventListener("mouseup", () => { drag = null; cv.classList.remove("drag"); });
  cv.addEventListener("mousemove", (ev) => {
    if (drag) { ox = drag.ox + ev.clientX - drag.x; oy = drag.oy + ev.clientY - drag.y; draw(); return; }
    const r = cv.getBoundingClientRect();
    const tx = Math.floor((ev.clientX - r.left - ox) / scale), ty = Math.floor((ev.clientY - r.top - oy) / scale);
    const it = grid.get(tx + "," + ty);
    const tip = $("#tip");
    if (!it) { tip.style.display = "none"; return; }
    const e = it.e;
    let t = `${e.name}${e.recipe ? " → " + e.recipe : ""}\n(${tx}, ${ty})${e.type ? " " + e.type : ""}`;
    if (e.items) t += "\nmodules: " + JSON.stringify(e.items).slice(0, 120);
    if (it.status) {
      const s = it.status;
      t += `\nin game: ${s.usual_status}, crafted ${s.crafts}`;
      if (s.input) t += "\nholds " + JSON.stringify(s.input);
      if (s.output && Object.keys(s.output).length) t += "\noutput " + JSON.stringify(s.output);
    }
    tip.textContent = t; tip.style.display = "block";
    tip.style.left = (ev.clientX - r.left + 14) + "px"; tip.style.top = (ev.clientY - r.top + 14) + "px";
  });
  cv.addEventListener("mouseleave", () => { $("#tip").style.display = "none"; });
  cv.addEventListener("wheel", (ev) => {
    ev.preventDefault();
    const r = cv.getBoundingClientRect(); const mx = ev.clientX - r.left, my = ev.clientY - r.top;
    const f = Math.exp(-ev.deltaY * 0.0015);
    const ns = Math.max(0.2, Math.min(120, scale * f));
    ox = mx - (mx - ox) * (ns / scale); oy = my - (my - oy) * (ns / scale); scale = ns;
    draw();
  }, { passive: false });
  cv.addEventListener("dblclick", () => { fitView(); draw(); });
  window.addEventListener("resize", () => { resize(); draw(); });
  return { set, draw, resize: () => { resize(); draw(); }, fit: () => { fitView(); draw(); } };
})();

// ---------------------------------------------------------------------------------------------------------------

(async function init() {
  $("#useSprites").checked = store.get("sprites", true);
  [schema, game] = await Promise.all([api("/api/schema"), api("/api/game")]);
  if (!schema.gameIcons) { $("#useSprites").checked = false; $("#useSprites").disabled = true; $("#useSprites").parentElement.title = "Factorio not found (set FACTORIO_DATA)"; }
  await loadJobs();
  const last = store.get("job", null);
  if (last) { try { await openJob(last); } catch { /* gone */ } }
  showTab(store.get("tab", "jobs"));
  const lastRun = store.get("run", null);
  if (lastRun) openRun(lastRun).catch(() => {});
})();

// The run panel's width: drag the divider; remembered.
(() => {
  const root = document.documentElement, drag = $("#sideDrag");
  const saved = store.get("sideW");
  if (saved) root.style.setProperty("--side-w", saved + "px");
  drag.addEventListener("mousedown", (e) => {
    e.preventDefault(); drag.classList.add("on"); document.body.style.userSelect = "none";
    const move = (ev) => {
      const w = Math.max(280, Math.min(window.innerWidth * 0.75, window.innerWidth - ev.clientX));
      root.style.setProperty("--side-w", w + "px"); store.set("sideW", Math.round(w));
    };
    const up = () => { drag.classList.remove("on"); document.body.style.userSelect = ""; window.removeEventListener("mousemove", move); window.removeEventListener("mouseup", up); viewer.fit(); };
    window.addEventListener("mousemove", move); window.addEventListener("mouseup", up);
  });
})();
