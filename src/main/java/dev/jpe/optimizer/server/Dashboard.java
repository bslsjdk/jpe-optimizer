package dev.jpe.optimizer.server;

/** Self-contained dashboard served at {@code /}. No external assets. */
final class Dashboard {
    private Dashboard() {}

    static final String HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>JPE density graph server</title>
<style>
  :root {
    --bg: #0d1117; --panel: #161b22; --line: #30363d; --text: #e6edf3;
    --muted: #8b949e; --accent: #58a6ff; --ok: #3fb950; --bad: #f85149;
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--text);
         font: 14px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; }
  header { padding: 18px 24px; border-bottom: 1px solid var(--line);
           display: flex; gap: 24px; align-items: baseline; flex-wrap: wrap; }
  h1 { font-size: 16px; margin: 0; letter-spacing: .04em; }
  .muted { color: var(--muted); }
  main { display: grid; grid-template-columns: 320px 1fr; gap: 20px; padding: 20px 24px; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 14px; }
  label { display: block; color: var(--muted); font-size: 12px; margin: 10px 0 4px; }
  select, input, button { width: 100%; background: #0d1117; color: var(--text);
    border: 1px solid var(--line); border-radius: 6px; padding: 7px 9px; font: inherit; }
  button { cursor: pointer; margin-top: 12px; }
  button.primary { background: #1f6feb; border-color: #1f6feb; }
  button:hover { border-color: var(--accent); }
  .row { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
  .viewport { height: 54vh; display: flex; align-items: center; justify-content: center;
              background: #000; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; }
  canvas { height: 100%; width: auto; max-width: 100%; image-rendering: pixelated; }
  .stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 12px; }
  .stat { border: 1px solid var(--line); border-radius: 6px; padding: 8px 10px; }
  .stat b { display: block; font-size: 16px; font-weight: 600; }
  .stat span { color: var(--muted); font-size: 11px; }
  #log { margin-top: 12px; max-height: 160px; overflow: auto; color: var(--muted);
         font-size: 12px; white-space: pre-wrap; }
  .ok { color: var(--ok); } .bad { color: var(--bad); }
</style>
</head>
<body>
<header>
  <h1>JPE DENSITY GRAPH SERVER</h1>
  <span class="muted" id="health">loading…</span>
</header>
<main>
  <section class="panel">
    <label for="scenario">Scenario</label>
    <select id="scenario"></select>
    <p class="muted" id="description" style="min-height:38px"></p>

    <label>Volume size (x, y, z)</label>
    <div class="row">
      <input id="sx" type="number" min="1" max="128" value="16">
      <input id="sy" type="number" min="1" max="128" value="16">
      <input id="sz" type="number" min="1" max="128" value="16">
    </div>
    <label>Minimum block coordinate</label>
    <div class="row">
      <input id="mx" type="number" value="-8">
      <input id="my" type="number" value="-8">
      <input id="mz" type="number" value="-8">
    </div>
    <label>Slice axis</label>
    <select id="axis">
      <option value="2" selected>Z (front view)</option>
      <option value="1">Y (top view)</option>
      <option value="0">X (side view)</option>
    </select>
    <label>Slice index</label>
    <input id="slice" type="number" value="0">

    <button class="primary" id="run">Evaluate</button>
    <button id="parity">Parity vs Java reference</button>
    <button id="bench">Benchmark (50 runs)</button>
  </section>

  <section class="panel">
    <div class="viewport"><canvas id="view" width="480" height="480"></canvas></div>
    <div class="stats">
      <div class="stat"><b id="ms">–</b><span>evaluate ms</span></div>
      <div class="stat"><b id="nsp">–</b><span>ns / sample</span></div>
      <div class="stat"><b id="range">–</b><span>min … max</span></div>
      <div class="stat"><b id="parityState">–</b><span>parity</span></div>
    </div>
    <div id="log"></div>
  </section>
</main>
<script>
const $ = id => document.getElementById(id);
const state = { scenario: null, values: null, size: [16,16,16], min: [-8,-8,-8] };
const log = message => { $('log').textContent = message + "\\n" + $('log').textContent; };

async function api(path, body) {
  const response = await fetch(path, body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || response.statusText);
  return data;
}

function geometry() {
  const size = [+$('sx').value, +$('sy').value, +$('sz').value];
  const min = [+$('mx').value, +$('my').value, +$('mz').value];
  return { size, min, step: [1,1,1] };
}

const RAMP = [[68,1,84],[59,82,139],[33,145,140],[94,201,98],[253,231,37]];
function colour(value, low, high) {
  const span = high - low;
  const u = span < 1e-9 ? 0.5 : Math.max(0, Math.min(1, (value - low) / span));
  const scaled = u * (RAMP.length - 1);
  const i = Math.min(RAMP.length - 2, Math.floor(scaled));
  const k = scaled - i;
  return [0,1,2].map(c => Math.round(RAMP[i][c] + (RAMP[i+1][c] - RAMP[i][c]) * k));
}

function render() {
  const canvas = $('view'), ctx = canvas.getContext('2d');
  if (!state.values) return;
  const [sx, sy, sz] = state.size;
  const axis = +$('axis').value;
  const index = Math.max(0, Math.min([sx,sy,sz][axis] - 1, +$('slice').value));
  const width = axis === 0 ? sz : sx;
  const height = axis === 1 ? sz : sy;
  const box = canvas.parentElement.getBoundingClientRect();
  const scale = Math.max(1, Math.floor(Math.min((box.width - 4) / width, (box.height - 4) / height)));
  canvas.width = width * scale;
  canvas.height = height * scale;
  canvas.style.width = (width * scale) + 'px';
  canvas.style.height = (height * scale) + 'px';

  const at = (x,y,z) => state.values[(z * sx + x) * sy + y];
  const cell = (i, j) => {
    const x = axis === 0 ? index : i;
    const z = axis === 1 ? j : (axis === 2 ? index : j);
    const y = axis === 1 ? index : (height - 1 - j);
    return at(x, y, z);
  };

  let low = Infinity, high = -Infinity;
  for (let j = 0; j < height; j++) {
    for (let i = 0; i < width; i++) {
      const v = cell(i, j);
      if (Number.isFinite(v)) { low = Math.min(low, v); high = Math.max(high, v); }
    }
  }
  for (let j = 0; j < height; j++) {
    for (let i = 0; i < width; i++) {
      const v = cell(i, j);
      const [r,g,b] = Number.isFinite(v) ? colour(v, low, high) : [255,0,255];
      ctx.fillStyle = `rgb(${r},${g},${b})`;
      ctx.fillRect(i * scale, j * scale, scale, scale);
    }
  }
  $('range').textContent = low.toFixed(2) + " \u2026 " + high.toFixed(2);
}

async function evaluate() {
  try {
    const body = { scenario: state.scenario, ...geometry() };
    const data = await api('/api/evaluate', body);
    state.values = data.values;
    state.size = data.geometry.size;
    $('ms').textContent = (data.nanos / 1e6).toFixed(2);
    $('nsp').textContent = data.nsPerSample.toFixed(2);
    $('slice').max = Math.max(...data.geometry.size) - 1;
    render();
    log(`evaluated ${data.id}: ${data.samples} samples in ${(data.nanos/1e6).toFixed(2)} ms`);
  } catch (error) { log('evaluate failed: ' + error.message); }
}

async function parity() {
  try {
    const data = await api('/api/parity', { scenario: state.scenario, ...geometry() });
    $('parityState').textContent = data.exact ? 'exact' : data.mismatches + ' bad';
    $('parityState').className = data.exact ? 'ok' : 'bad';
    log(`parity: ${data.samples} samples, ${data.mismatches} mismatches`);
  } catch (error) {
    $('parityState').textContent = 'n/a';
    log('parity failed: ' + error.message);
  }
}

async function bench() {
  try {
    const data = await api('/api/bench', { scenario: state.scenario, ...geometry(), iterations: 50 });
    log(`bench: p50 ${(data.p50Nanos/1e6).toFixed(3)} ms, p95 ${(data.p95Nanos/1e6).toFixed(3)} ms, `
        + `${data.p50NsPerSample.toFixed(2)} ns/sample`);
  } catch (error) { log('bench failed: ' + error.message); }
}

async function start() {
  try {
    const health = await api('/api/health');
    $('health').textContent = `IR v${health.irVersion} · ${health.library.split('/').pop()} · `
      + `${health.availableProcessors} cores · Java ${health.javaVersion}`;
    const { scenarios } = await api('/api/scenarios');
    const select = $('scenario');
    for (const scenario of scenarios) {
      const option = document.createElement('option');
      option.value = scenario.id; option.textContent = scenario.name;
      select.appendChild(option);
    }
    if (scenarios.some(s => s.id === 'ridge')) select.value = 'ridge';
    const apply = () => {
      const scenario = scenarios.find(s => s.id === select.value);
      state.scenario = scenario.id;
      $('description').textContent = scenario.description;
      $('sx').value = scenario.geometry.size[0];
      $('sy').value = scenario.geometry.size[1];
      $('sz').value = scenario.geometry.size[2];
      $('mx').value = scenario.geometry.min[0];
      $('my').value = scenario.geometry.min[1];
      $('mz').value = scenario.geometry.min[2];
      $('slice').value = Math.floor(scenario.geometry.size[2] / 2);
      evaluate();
    };
    select.addEventListener('change', apply);
    $('run').addEventListener('click', evaluate);
    $('parity').addEventListener('click', parity);
    $('bench').addEventListener('click', bench);
    $('axis').addEventListener('change', () => {
      const axis = +$('axis').value;
      $('slice').value = Math.floor(state.size[axis] / 2);
      render();
    });
    $('slice').addEventListener('input', render);
    apply();
  } catch (error) {
    $('health').textContent = 'server error: ' + error.message;
  }
}
start();
</script>
</body>
</html>
""";
}
