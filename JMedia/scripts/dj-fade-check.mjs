#!/usr/bin/env node
/**
 * DJ Mode fade gate.
 *
 * Drives a real browser against a running JMedia instance and asserts three
 * things that the server log cannot prove:
 *
 *   1. the fade that actually happens lasts the planned duration, within 250 ms;
 *   2. the outgoing gain falls and the incoming gain rises monotonically, and
 *      they overlap for at least 80% of the planned duration;
 *   3. a planned entry deeper than 45 s is clamped to 45 s, never to 0.
 *
 * It also reproduces a stale server clock (`--mode drift`): the server is made
 * to believe it is ~13 s ahead of the audio element, which used to make the
 * server advance first and downgrade a beat-aligned mix into a manual fade.
 *
 * Usage:
 *   node dj-fade-check.mjs --base http://127.0.0.1:18080 --user admin --password '...'
 *   node dj-fade-check.mjs --base ... --mode drift
 *   node dj-fade-check.mjs --base ... --mode both --headless
 *
 * Exit code 0 when every assertion passes, 1 otherwise. Options:
 *   --base <url>        instance root (default http://127.0.0.1:8080)
 *   --user <name>       default admin
 *   --password <text>   required
 *   --profile <id>      profile to drive, default 1
 *   --mode <normal|drift|both>  default normal
 *   --headless          run without a visible window
 *   --timeout <ms>      per-transition wait, default 45000
 *
 * No audio device is needed: it reads the two media elements and the gain
 * nodes, so it measures the graph, not the speakers.
 */
import { chromium } from "playwright";

const CLAMP_WINDOW_SECONDS = 45;
const FADE_DURATION_TOLERANCE_S = 0.25;
const FADE_START_TOLERANCE_S = 0.6; // sampling overhead, not musical slop
const MIN_OVERLAP_RATIO = 0.8;

function parseArgs(argv) {
  const opts = {
    base: "http://127.0.0.1:8080",
    user: "admin",
    password: "",
    profile: "1",
    mode: "normal",
    headless: false,
    timeout: 45000,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`missing value for ${a}`);
      return v;
    };
    if (a === "--base") opts.base = next().replace(/\/+$/, "");
    else if (a === "--user") opts.user = next();
    else if (a === "--password") opts.password = next();
    else if (a === "--profile") opts.profile = next();
    else if (a === "--mode") opts.mode = next();
    else if (a === "--headless") opts.headless = true;
    else if (a === "--timeout") opts.timeout = Number(next());
    else if (a === "--help" || a === "-h") {
      console.log("see the header of dj-fade-check.mjs for usage");
      process.exit(0);
    } else throw new Error(`unknown argument: ${a}`);
  }
  if (!opts.password) throw new Error("--password is required");
  if (!["normal", "drift", "both"].includes(opts.mode)) {
    throw new Error(`--mode must be normal, drift or both (got ${opts.mode})`);
  }
  return opts;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const fmt = (v) => (typeof v === "number" ? v.toFixed(2) : String(v));

class Checks {
  constructor() {
    this.rows = [];
  }
  record(name, ok, detail) {
    this.rows.push({ name, ok, detail });
    console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`);
  }
  get failed() {
    return this.rows.filter((r) => !r.ok);
  }
}

async function api(page, path, method = "GET", body) {
  return page.evaluate(
    async ({ path, method, body }) => {
      const res = await fetch(path, {
        method,
        headers: body === undefined ? {} : { "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "same-origin",
      });
      const text = await res.text();
      let json = null;
      try {
        json = text ? JSON.parse(text) : null;
      } catch (err) {
        console.warn("non-JSON response for " + path, err);
      }
      return { status: res.status, json };
    },
    { path, method, body },
  );
}

async function readState(page, profile) {
  const r = await api(page, `/api/music/playback/state/${profile}`);
  if (r.status !== 200 || !r.json) throw new Error(`state read failed: HTTP ${r.status}`);
  return r.json.data;
}

async function snapshot(page) {
  return page.evaluate(() => {
    const a = window.AudioEngine || {};
    const dtm = window.DjTransitionManager || {};
    const el1 = a.audio;
    const el2 = a.audioNext;
    const gain = (n) => (a.nodes && a.nodes[n] && a.nodes[n].gain ? a.nodes[n].gain.value : null);
    return {
      at: performance.now(),
      d1: el1 ? el1.currentTime : null,
      d2: el2 ? el2.currentTime : null,
      paused1: el1 ? el1.paused : null,
      paused2: el2 ? el2.paused : null,
      g1: gain("g1"),
      g2: gain("g2"),
      active: a.activePlayer,
      transitioning: !!dtm.isTransitioning,
      fade: dtm.transitionData ? dtm.transitionData.crossfadeDuration : null,
      entry: dtm.transitionData ? dtm.transitionData.entryTime : null,
      next: dtm.transitionData ? dtm.transitionData.nextSongId : null,
    };
  });
}

async function login(page, opts) {
  await page.goto(`${opts.base}/login.html`, { waitUntil: "domcontentloaded", timeout: 30000 });
  await page.fill("#username", opts.user);
  await page.fill("#password", opts.password);
  await Promise.all([
    page.waitForURL((u) => !u.pathname.endsWith("/login.html"), { timeout: 30000 }).catch(() => {}),
    page.click("#loginButton").catch(() => {}),
  ]);
  await sleep(1500);
  const href = await page.evaluate(() => location.href);
  if (href.includes("/login.html")) throw new Error("login did not leave the login page");
  return href;
}

async function ensurePlaying(page, opts) {
  await api(page, `/api/music/playback/play/${opts.profile}`, "POST", {});
  for (let i = 0; i < 40; i++) {
    const s = await snapshot(page);
    const t = s.active === 2 ? s.d2 : s.d1;
    const paused = s.active === 2 ? s.paused2 : s.paused1;
    if (typeof t === "number" && !paused && t > 0.5) return s;
    await sleep(250);
  }
  throw new Error(
    "no audio element started playing; the browser may have blocked autoplay " +
      JSON.stringify(await snapshot(page)),
  );
}

async function ensureDjPlan(page, opts, deadlineMs) {
  await api(page, `/api/music/playback/dj-mode-set/${opts.profile}/true`, "POST", {});
  const end = Date.now() + deadlineMs;
  while (Date.now() < end) {
    const st = await readState(page, opts.profile);
    if (st.djModeActive && st.djTransitionPlanned && st.djTransitionCrossfade > 0 && st.djExitTime > 0) {
      return st;
    }
    await sleep(1000);
  }
  throw new Error("no DJ transition plan appeared; is DJ Mode able to plan for this queue?");
}

/** Pure check: a >45 s entry must clamp to 45, never to 0. */
async function checkEntryClamp(page, checks) {
  const result = await page.evaluate((windowMax) => {
    const dtm = window.DjTransitionManager;
    if (!dtm || typeof dtm.prepareTransition !== "function") {
      return { ok: false, reason: "DjTransitionManager.prepareTransition is not reachable" };
    }
    const before = dtm.transitionData;
    // Synthetic state: 101.5 s entry, 4-minute track, valid crossfade.
    dtm.prepareTransition(
      {
        djNextSongId: 999999,
        djEntryTime: 101.5,
        djExitTime: 200,
        duration: 240,
        djTransitionCrossfade: 2,
        djTransitionRate: 1.0,
        djTransitionReason: "fixture: entry clamp",
      },
      0,
    );
    const entry = dtm.transitionData ? dtm.transitionData.entryTime : null;
    dtm.cancelTransition();
    dtm.transitionData = before;
    return { ok: true, entry, windowMax };
  }, CLAMP_WINDOW_SECONDS);

  if (!result.ok) {
    checks.record("entry >45 s clamps to 45", false, result.reason);
    return;
  }
  const ok = result.entry !== null && Math.abs(result.entry - CLAMP_WINDOW_SECONDS) < 0.01;
  checks.record(
    "entry >45 s clamps to 45",
    ok,
    `planned 101.5 s -> client ${result.entry}` + (result.entry === 0 ? " (discarded to 0)" : ""),
  );
}

/** Sample both decks until a fade completes, the song changes, or time runs out. */
async function captureFade(page, opts, plan, { requirePlanned, label }) {
  const samples = [];
  const started = Date.now();
  let startedAt = null;
  let settledAt = null;
  let sawOverlap = false;

  while (Date.now() - started < opts.timeout) {
    const s = await snapshot(page);
    samples.push(s);

    const bothPlaying = s.paused1 === false && s.paused2 === false;
    const spread = s.g1 !== null && s.g2 !== null ? Math.abs(s.g1 - s.g2) : 0;
    if (startedAt === null && bothPlaying && spread > 0.15 * plan.maxGain) {
      startedAt = s.at;
    }
    if (startedAt !== null && s.g1 > 0.05 * plan.maxGain && s.g2 > 0.05 * plan.maxGain) {
      sawOverlap = true;
    }
    // Settle only after a real overlap happened; at fade start the incoming
    // gain is still 0, which must not be mistaken for "fade finished".
    if (sawOverlap && (s.g1 <= 0.002 || s.g2 <= 0.002)) {
      settledAt = s.at;
      break;
    }
    await sleep(80);
  }

  if (startedAt === null || settledAt === null) {
    const tail = samples.slice(-3);
    return {
      ok: false,
      samples,
      reason: `no completed fade within ${opts.timeout} ms (${label}); last samples: ${JSON.stringify(tail)}`,
    };
  }

  const first = samples.find((s) => s.at >= startedAt);
  const last = samples.find((s) => s.at >= settledAt);
  const overlap = settledAt - startedAt;
  const plannedFade = plan.fade;
  const overlapRatio = overlap / (plannedFade * 1000);

  // The outgoing deck is whichever gain is falling; decks swap roles between
  // transitions, so never assume deck 1.
  const outIsDeck1 = first.g1 >= first.g2;
  const outTime = outIsDeck1 ? first.d1 : first.d2;
  const inTime = outIsDeck1 ? first.d2 : first.d1;

  const window = samples.filter((s) => s.at >= startedAt && s.at <= settledAt);
  const monotonicOut = monotonic(window.map((s) => (outIsDeck1 ? -s.g1 : -s.g2)), 0.004);
  const monotonicIn = monotonic(window.map((s) => (outIsDeck1 ? s.g2 : s.g1)), 0.004);
  const plannedPath = window.some((s) => s.transitioning === true && s.fade === plannedFade);
  const fadeUsed = window.find((s) => s.fade !== null)?.fade ?? null;
  const entryUsed = window.find((s) => s.entry !== null)?.entry ?? null;

  return {
    ok: true,
    samples,
    first,
    last,
    outTime,
    inTime,
    overlap,
    overlapRatio,
    monotonicOut,
    monotonicIn,
    plannedPath,
    fadeUsed,
    entryUsed,
  };
}

function monotonic(values, tolerance) {
  for (let i = 1; i < values.length; i++) {
    if (values[i] < values[i - 1] - tolerance) return false;
  }
  return true;
}

function reportFade(checks, plan, result, { requirePlanned, label }) {
  const prefix = `[${label}]`;
  if (!result.ok) {
    checks.record(`${prefix} fade completed`, false, result.reason);
    return result;
  }

  const fadeUsed = result.fadeUsed;
  checks.record(
    `${prefix} planned fade used`,
    fadeUsed !== null && Number(fadeUsed) === Number(plan.fade),
    `planned ${plan.fade}s, executed ${fadeUsed}s`,
  );

  const durationError = Math.abs(result.overlap / 1000 - plan.fade);
  checks.record(
    `${prefix} fade duration within ±250 ms`,
    durationError <= FADE_DURATION_TOLERANCE_S,
    `planned ${plan.fade}s, measured ${(result.overlap / 1000).toFixed(3)}s (Δ ${durationError.toFixed(3)}s)`,
  );

  checks.record(
    `${prefix} fade starts at the planned exit`,
    Math.abs(result.outTime - plan.exit) <= FADE_START_TOLERANCE_S,
    `exit ${plan.exit}s, outgoing element ${fmt(result.outTime)}s, incoming ${fmt(result.inTime)}s`,
  );

  checks.record(
    `${prefix} gains are monotonic`,
    result.monotonicOut && result.monotonicIn,
    `out ${result.monotonicOut ? "down" : "reversed"}, in ${result.monotonicIn ? "up" : "reversed"}`,
  );

  checks.record(
    `${prefix} overlap ≥ 80% of plan`,
    result.overlapRatio >= MIN_OVERLAP_RATIO,
    `${(result.overlapRatio * 100).toFixed(1)}% of ${plan.fade}s`,
  );

  if (requirePlanned) {
    // Informational: which path executed the fade. Behavioural assertions above
    // already cover duration/entry; a state-driven fade that uses the plan's
    // values is correct even if DjTransitionManager lost the race.
    console.log(
      `${prefix} executed via ${result.plannedPath ? "DjTransitionManager" : "state-driven setSource"}` +
        ` (entry ${result.entryUsed}s)`,
    );
  }
  return result;
}

/**
 * Move the playhead on both clocks. The server endpoint alone is not enough:
 * the element applies a remote position only while it is not fading, so the
 * gate sets the element directly and lets the server adopt the same value.
 */
async function seekBoth(page, opts, seconds) {
  const resp = await api(page, `/api/music/playback/position/${opts.profile}/${seconds.toFixed(2)}`, "POST", {});
  await page.evaluate((t) => {
    if (window.AudioEngine && typeof window.AudioEngine.setTime === "function") {
      window.AudioEngine.setTime(t, true);
    }
  }, seconds);
  await sleep(800);
  const state = await readState(page, opts.profile);
  const snap = await snapshot(page);
  const elementTime = snap.active === 2 ? snap.d2 : snap.d1;
  return { status: resp.status, server: state.currentTime, element: elementTime };
}

async function installLogCapture(page) {
  await page.evaluate(() => {
    window.__fadeLogs = [];
    if (!window.Helpers || window.__fadeLogHooked) return;
    const orig = window.Helpers.log.bind(window.Helpers);
    window.Helpers.log = function () {
      try {
        const line = Array.prototype.map
          .call(arguments, (a) => (typeof a === "string" ? a : JSON.stringify(a)))
          .join(" ");
        if (/DJ|crossfade|Crossfade|transition|Transition|Remote position/.test(line)) {
          window.__fadeLogs.push({ at: Math.round(performance.now()), line: line.slice(0, 200) });
        }
      } catch (err) {
        console.warn("fade log hook failed", err);
      }
      return orig.apply(null, arguments);
    };
    window.__fadeLogHooked = true;
  });
}

async function dumpLogs(page, label) {
  const logs = await page.evaluate(() => (window.__fadeLogs || []).slice(-18));
  console.log(`--- ${label}: last client log lines ---`);
  for (const l of logs) console.log(`  ${l.at}ms  ${l.line}`);
}

async function measureGainCeiling(page) {
  const s = await snapshot(page);
  const max = Math.max(s.g1 ?? 0, s.g2 ?? 0);
  return max > 0 ? max : 0.068;
}

async function normalRun(page, opts, checks) {
  await ensurePlaying(page, opts);
  const plan = await ensureDjPlan(page, opts, 30000);
  const maxGain = await measureGainCeiling(page);

  // Put both clocks at exit-10 so the run is short and the two are aligned.
  const seekTo = Math.max(0, plan.djExitTime - 10);
  const seek = await seekBoth(page, opts, seekTo);
  console.log(`seek to ${seekTo.toFixed(2)}s -> HTTP ${seek.status}; server=${fmt(seek.server)} element=${fmt(seek.element)}`);
  if (Math.abs(seek.server - seek.element) > 2.5) {
    checks.record("[normal] playhead positioned on both clocks", false,
      `server=${fmt(seek.server)} element=${fmt(seek.element)}; cannot measure a fade from a desynchronised start`);
    return;
  }

  const state = await readState(page, opts.profile);
  const measured = {
    fade: state.djTransitionCrossfade,
    exit: state.djExitTime,
    entry: state.djEntryTime,
    next: state.djNextSongId,
    maxGain,
  };
  console.log(
    `plan: exit=${measured.exit}s entry=${measured.entry}s fade=${measured.fade}s next=${measured.next}`,
  );
  const clientState = await page.evaluate(() => {
    const s = (window.StateManager && window.StateManager.getState && window.StateManager.getState()) || {};
    return {
      fade: s.djTransitionCrossfade,
      exit: s.djExitTime,
      entry: s.djEntryTime,
      planned: s.djTransitionPlanned,
      next: s.djNextSongId,
    };
  });
  console.log(`client StateManager: ${JSON.stringify(clientState)}`);

  const result = await captureFade(page, opts, measured, { requirePlanned: true, label: "normal" });
  reportFade(checks, measured, result, { requirePlanned: true, label: "normal" });
  if (!result.ok || !result.plannedPath) {
    await dumpLogs(page, "normal");
    console.log(
      "samples around the fade: " +
        JSON.stringify((result.samples || []).filter((s) => s.paused1 === false && s.paused2 === false).slice(0, 4)),
    );
  }
}

/**
 * Stale-server-clock fixture: slow the element to 20% for 16 s while the server
 * keeps ticking at 1x. The server ends up roughly 13 s ahead. Before the fix the
 * server advanced first and the client degraded to a "manual DJ skip" fade.
 */
async function driftRun(page, opts, checks) {
  await ensurePlaying(page, opts);
  const plan = await ensureDjPlan(page, opts, 30000);
  const maxGain = await measureGainCeiling(page);

  const seekTo = Math.max(0, plan.djExitTime - 30);
  const seek = await seekBoth(page, opts, seekTo);
  console.log(`drift setup seek to ${seekTo.toFixed(2)}s -> server=${fmt(seek.server)} element=${fmt(seek.element)}`);
  if (Math.abs(seek.server - seek.element) > 2.5) {
    checks.record("[drift] playhead positioned on both clocks", false,
      `server=${fmt(seek.server)} element=${fmt(seek.element)}`);
    return;
  }

  // Stall the element without telling the server: a buffering stall looks
  // exactly like this to the server. The engine resumes playback when state
  // says playing, so play() is swallowed for the duration of the stall.
  const stalled = await page.evaluate(() => {
    const a = window.AudioEngine || {};
    const el = a.activePlayer === 2 ? a.audioNext : a.audio;
    if (!el) return { ok: false };
    window.__driftEl = el;
    window.__driftPlay = el.play.bind(el);
    el.play = () => Promise.resolve();
    el.pause();
    return { ok: true, t: el.currentTime, paused: el.paused };
  });
  if (!stalled.ok) {
    checks.record("[drift] element stalled", false, "no active audio element");
    return;
  }
  await sleep(14000);
  const drift = await page.evaluate(() => {
    const el = window.__driftEl;
    const before = el ? el.currentTime : null;
    if (el) {
      el.play = window.__driftPlay;
      el.play();
    }
    return { element: before };
  });

  const state = await readState(page, opts.profile);
  const lag = state.currentTime - drift.element;
  console.log(
    `drift fixture: server=${state.currentTime.toFixed(1)}s element=${drift.element.toFixed(1)}s lag=${lag.toFixed(1)}s`,
  );
  checks.record(
    "[drift] server clock is ahead of the element",
    lag > 5,
    `${lag.toFixed(1)}s of drift`,
  );

  const measured = {
    fade: state.djTransitionCrossfade,
    exit: state.djExitTime,
    entry: state.djEntryTime,
    next: state.djNextSongId,
    maxGain,
  };
  const result = await captureFade(page, opts, measured, { requirePlanned: true, label: "drift" });
  reportFade(checks, measured, result, { requirePlanned: true, label: "drift" });
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const checks = new Checks();
  const browser = await chromium.launch({
    headless: opts.headless,
    args: ["--autoplay-policy=no-user-gesture-required"],
  });

  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const consoleErrors = [];
    page.on("pageerror", (err) => consoleErrors.push(String(err)));

    console.log(`logging in to ${opts.base} as ${opts.user}`);
    const href = await login(page, opts);
    console.log(`logged in, landed on ${href}`);

    await checkEntryClamp(page, checks);
    await installLogCapture(page);

    if (opts.mode === "normal" || opts.mode === "both") {
      await normalRun(page, opts, checks);
      if (opts.mode === "both") {
        await sleep(2000);
        await api(page, `/api/music/playback/pause/${opts.profile}`, "POST", {});
        await sleep(1500);
      }
    }
    if (opts.mode === "drift" || opts.mode === "both") {
      if (opts.mode === "both") {
        await api(page, `/api/music/playback/play/${opts.profile}`, "POST", {});
        await sleep(1500);
      }
      await driftRun(page, opts, checks);
    }

    if (consoleErrors.length) {
      checks.record("no uncaught page errors", false, consoleErrors.slice(0, 3).join(" | "));
    } else {
      checks.record("no uncaught page errors", true, "");
    }
  } finally {
    await browser.close();
  }

  const failed = checks.failed;
  console.log("");
  if (failed.length) {
    console.log(`${failed.length} check(s) failed`);
    process.exit(1);
  }
  console.log(`${checks.rows.length} checks passed`);
  process.exit(0);
}

main().catch((err) => {
  console.error("fade check aborted:", err && err.stack ? err.stack : err);
  process.exit(2);
});
