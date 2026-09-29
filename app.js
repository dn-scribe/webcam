'use strict';
/* IP Webcam PWA — camera, controls, snapshots, recording, and the WebSocket link to the Python hub.
   Protocol: see docs/SPEC.md */
(() => {
const PROTO = 1;
const $ = (s) => document.querySelector(s);
const video = $('#video');

// ---------- settings ----------
const DEFAULTS = {
  facing: 'environment', deviceId: '', res: '1280x720', fps: 30, audio: false,
  streamOn: false, streamFps: 10, streamWidth: 640, streamQuality: 0.6, snapQuality: 0.92,
  adv: {},
  hub: { host: '', port: 8765, token: '', tls: true, auto: true, name: 'phone' },
};
const CAMERA_KEYS = ['facing', 'deviceId', 'res', 'fps', 'audio'];
const STREAM_KEYS = ['streamFps', 'streamWidth', 'streamQuality', 'snapQuality'];
let S = load();
function load() {
  try { const j = JSON.parse(localStorage.getItem('webcam.settings') || '{}');
    return { ...DEFAULTS, ...j, adv: { ...(j.adv || {}) }, hub: { ...DEFAULTS.hub, ...(j.hub || {}) } };
  } catch { return structuredClone(DEFAULTS); }
}
function save() { try { localStorage.setItem('webcam.settings', JSON.stringify(S)); } catch {} }

// ---------- ui helpers ----------
let toastTimer;
function toast(msg, ms = 3000) {
  const t = $('#toast'); t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), ms);
}
const stamp = () => new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
function download(blob, name) {
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name;
  document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 30000);
}

// ---------- camera ----------
let stream = null, track = null, caps = {}, devices = [];

async function startCamera() {
  stopCamera();
  const [w, h] = S.res.split('x').map(Number);
  const v = { width: { ideal: w }, height: { ideal: h }, frameRate: { ideal: Number(S.fps) } };
  if (S.deviceId) v.deviceId = { exact: S.deviceId }; else v.facingMode = { ideal: S.facing };
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: v, audio: !!S.audio });
  } catch (e) {
    if (S.audio) { S.audio = false; toast('Microphone unavailable, continuing without'); return startCamera(); }
    toast('Camera error: ' + e.name); throw e;
  }
  track = stream.getVideoTracks()[0];
  video.srcObject = stream;
  await video.play().catch(() => {});
  caps = track.getCapabilities ? track.getCapabilities() : {};
  devices = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'videoinput');
  for (const [k, val] of Object.entries(S.adv)) await applyAdv(k, val, true);
  buildUI();
  updateCamInfo();
  requestWake();
  sendState();
}
function stopCamera() {
  if (stream) stream.getTracks().forEach((t) => t.stop());
  stream = track = null;
}
function updateCamInfo() {
  const st = track ? track.getSettings() : {};
  $('#camInfo').textContent = st.width ? `${st.width}×${st.height} @${Math.round(st.frameRate || 0)}` : '';
}
async function applyAdv(key, value, quiet) {
  if (!track) return;
  try { await track.applyConstraints({ advanced: [{ [key]: value }] }); S.adv[key] = value; }
  catch (e) { if (!quiet) toast(`${key}: not supported`); }
}

// Apply a partial settings object (from UI or hub). Returns error string or null.
async function applySettings(p) {
  let restart = false;
  if (p.width && p.height) p = { ...p, res: `${p.width}x${p.height}` };
  const cam = CAMERA_KEYS.filter((k) => k in p && p[k] !== S[k]);
  if (cam.length) {
    if (recorder) return 'cannot change camera while recording';
    if ('facing' in p && !('deviceId' in p)) S.deviceId = '';
    cam.forEach((k) => (S[k] = p[k])); restart = true;
  }
  for (const k of STREAM_KEYS) if (k in p) S[k] = Number(p[k]);
  if ('streamOn' in p) S.streamOn = !!p.streamOn;
  if (restart) await startCamera();
  for (const [k, v] of Object.entries(p)) {
    if (CAMERA_KEYS.includes(k) || STREAM_KEYS.includes(k) || ['width', 'height', 'streamOn', 'res'].includes(k)) continue;
    if (k in caps) await applyAdv(k, v);
  }
  save(); syncUI(); updateCamInfo(); streamLoop(); sendState();
  return null;
}

// ---------- dynamic device controls ----------
const ADV_KEYS = ['zoom', 'torch', 'exposureMode', 'exposureCompensation', 'exposureTime', 'focusMode',
  'focusDistance', 'whiteBalanceMode', 'colorTemperature', 'iso', 'brightness', 'contrast', 'saturation', 'sharpness'];
function buildUI() {
  const dev = $('#selDevice'); dev.innerHTML = '';
  devices.forEach((d, i) => dev.add(new Option(d.label || `Camera ${i + 1}`, d.deviceId)));
  const cur = track && track.getSettings().deviceId; if (cur) dev.value = cur;
  const box = $('#advControls'); box.innerHTML = '';
  let n = 0;
  for (const k of ADV_KEYS) {
    const c = caps[k]; if (c === undefined) continue;
    const cur = (track.getSettings() || {})[k];
    const lab = document.createElement('label'); let inp;
    if (typeof c === 'boolean' || (Array.isArray(c) && c.every((x) => typeof x === 'boolean'))) {
      lab.className = 'row'; inp = Object.assign(document.createElement('input'), { type: 'checkbox', checked: !!(S.adv[k] ?? cur) });
      inp.onchange = () => applySettings({ [k]: inp.checked });
      lab.append(inp, k);
    } else if (Array.isArray(c)) {
      inp = document.createElement('select'); c.forEach((o) => inp.add(new Option(o, o))); inp.value = S.adv[k] ?? cur ?? c[0];
      inp.onchange = () => applySettings({ [k]: inp.value }); lab.append(k, inp);
    } else if (typeof c === 'object' && 'min' in c) {
      inp = Object.assign(document.createElement('input'), { type: 'range', min: c.min, max: c.max, step: c.step || (c.max - c.min) / 100 });
      inp.value = S.adv[k] ?? cur ?? c.min;
      const val = Object.assign(document.createElement('span'), { className: 'val', textContent: ' ' + Number(inp.value).toFixed(2) });
      inp.oninput = () => (val.textContent = ' ' + Number(inp.value).toFixed(2));
      inp.onchange = () => applySettings({ [k]: Number(inp.value) });
      lab.append(k, val, inp);
    } else continue;
    box.append(lab); n++;
  }
  if (!n) box.innerHTML = '<p class="hint">This camera/browser exposes no extra controls.</p>';
}
function syncUI() {
  $('#selRes').value = S.res; $('#selFps').value = String(S.fps); $('#chkAudio').checked = S.audio;
  $('#chkStream').checked = S.streamOn; $('#stFps').value = S.streamFps; $('#stWidth').value = S.streamWidth;
  $('#stQual').value = S.streamQuality; $('#snQual').value = S.snapQuality;
}

// ---------- frames / snapshots ----------
const scratch = document.createElement('canvas');
function grab(maxW, quality) {
  const vw = video.videoWidth, vh = video.videoHeight;
  if (!vw) return Promise.reject(new Error('no video'));
  const sc = maxW && vw > maxW ? maxW / vw : 1;
  scratch.width = Math.round(vw * sc); scratch.height = Math.round(vh * sc);
  scratch.getContext('2d').drawImage(video, 0, 0, scratch.width, scratch.height);
  return new Promise((res, rej) => scratch.toBlob((b) => (b ? res({ blob: b, w: scratch.width, h: scratch.height }) : rej(new Error('encode failed'))), 'image/jpeg', quality));
}
const gallery = [];
async function snap(quality, id) {
  const { blob, w, h } = await grab(0, quality ?? S.snapQuality);
  const name = `snap-${stamp()}.jpg`, url = URL.createObjectURL(blob);
  gallery.unshift({ url, name }); if (gallery.length > 24) URL.revokeObjectURL(gallery.pop().url);
  renderGallery(); flash();
  if (id != null && wsOpen()) {
    sendJSON({ t: 'snap_meta', id, w, h, size: blob.size, mime: 'image/jpeg' });
    const head = new Uint8Array(5); head[0] = 2; new DataView(head.buffer).setUint32(1, id);
    ws.send(new Blob([head, blob]));
  }
  return { blob, name };
}
function flash() { video.style.opacity = 0.3; setTimeout(() => (video.style.opacity = 1), 90); }
function renderGallery() {
  const g = $('#gallery'); g.innerHTML = '';
  gallery.forEach(({ url, name }) => {
    const a = Object.assign(document.createElement('a'), { href: url, download: name });
    a.append(Object.assign(document.createElement('img'), { src: url })); g.append(a);
  });
  $('#galCount').textContent = gallery.length ? `(${gallery.length})` : '';
}

// ---------- streaming ----------
let streamTimer, inflight = false;
function streamLoop() {
  clearTimeout(streamTimer);
  if (!S.streamOn || !wsOpen()) return;
  const period = 1000 / Math.max(1, S.streamFps);
  if (!inflight && ws.bufferedAmount < 512 * 1024 && video.readyState >= 2) {
    inflight = true;
    grab(S.streamWidth, S.streamQuality)
      .then(({ blob }) => { if (wsOpen()) ws.send(new Blob([new Uint8Array([1]), blob])); })
      .catch(() => {}).finally(() => (inflight = false));
  }
  streamTimer = setTimeout(streamLoop, period);
}

// ---------- recording ----------
let recorder = null, recChunks = [], recSize = 0, recStart = 0, recTick, lastRec = null, recSeq = 0;
function pickMime() {
  const c = ['video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'];
  return c.find((m) => window.MediaRecorder && MediaRecorder.isTypeSupported(m)) || '';
}
function startRec() {
  if (recorder || !stream) return 'not ready';
  if (!window.MediaRecorder) return 'MediaRecorder unsupported';
  const mime = pickMime();
  recChunks = []; recSize = 0;
  recorder = new MediaRecorder(stream, mime ? { mimeType: mime, videoBitsPerSecond: 8e6 } : {});
  recorder.ondataavailable = (e) => { if (e.data.size) { recChunks.push(e.data); recSize += e.data.size; } };
  recorder.onstop = () => {
    clearInterval(recTick);
    const type = recorder.mimeType || mime || 'video/webm';
    const ext = type.includes('mp4') ? 'mp4' : 'webm';
    lastRec = { blob: new Blob(recChunks, { type }), type, name: `rec-${stamp()}.${ext}`, id: ++recSeq, secs: (Date.now() - recStart) / 1000 };
    recChunks = []; recorder = null; recUI(); reportRec(); toast(`Recorded ${(lastRec.blob.size / 1e6).toFixed(1)} MB`);
  };
  recorder.start(1000); recStart = Date.now();
  recTick = setInterval(() => { recUI(); reportRec(); }, 1000);
  recUI(); reportRec();
  return null;
}
function stopRec() { if (recorder && recorder.state !== 'inactive') recorder.stop(); }
function recUI() {
  const on = !!recorder;
  $('#btnRec').classList.toggle('rec', on);
  $('#btnRec').innerHTML = on ? '⏹<small>Stop</small>' : '⏺<small>Record</small>';
  $('#recBadge').hidden = !on;
  const s = Math.floor((Date.now() - recStart) / 1000); $('#recTime').textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  $('#btnDl').disabled = !lastRec || on;
  ['selDevice', 'selRes', 'selFps', 'chkAudio', 'btnFlip'].forEach((i) => ($('#' + i).disabled = on));
}
function reportRec() {
  sendJSON({ t: 'rec_status', ...recStatus() });
}
const recStatus = () => ({ state: recorder ? 'recording' : 'idle', elapsed: recorder ? (Date.now() - recStart) / 1000 : 0, size: recSize, last: lastRec && { id: lastRec.id, size: lastRec.blob.size, name: lastRec.name } });
async function sendRecording() {
  if (!lastRec) throw new Error('no recording');
  const CH = 256 * 1024; let idx = 0;
  for (let off = 0; off < lastRec.blob.size; off += CH, idx++) {
    while (wsOpen() && ws.bufferedAmount > 2 * CH) await new Promise((r) => setTimeout(r, 20));
    if (!wsOpen()) throw new Error('disconnected');
    const head = new Uint8Array(9); head[0] = 3;
    const dv = new DataView(head.buffer); dv.setUint32(1, lastRec.id); dv.setUint32(5, idx);
    ws.send(new Blob([head, lastRec.blob.slice(off, off + CH)]));
  }
  sendJSON({ t: 'rec_file_end', id: lastRec.id, size: lastRec.blob.size, mime: lastRec.type, chunks: idx, name: lastRec.name });
}

// ---------- hub connection ----------
let ws = null, reconnectTimer, wantConn = false, backoff = 1000;
const wsOpen = () => ws && ws.readyState === 1;
const sendJSON = (o) => { if (wsOpen()) ws.send(JSON.stringify(o)); };
function setConn(state, text) {
  $('#connDot').className = 'dot ' + state; $('#connText').textContent = text;
  try { if (state === 'on') localStorage.setItem('webcam.wasConnected', '1'); } catch {}
}
function hubUrl(scheme) {
  const h = S.hub; return `${scheme}://${h.host}:${h.port}`;
}
function connect() {
  clearTimeout(reconnectTimer);
  if (ws) { ws.onclose = null; try { ws.close(); } catch {} }
  const h = S.hub;
  if (!h.host) { $('#connHint').textContent = 'Enter the hub address.'; return; }
  wantConn = true;
  const tls = h.tls || location.protocol === 'https:';
  if (location.protocol === 'https:' && !h.tls) { $('#connHint').textContent = 'HTTPS pages can only use wss:// — TLS forced on.'; $('#hubTls').checked = h.tls = true; }
  const url = hubUrl(tls ? 'wss' : 'ws') + '/ws';
  setConn('wait', 'connecting…');
  $('#certLink').href = `https://${h.host}:${h.port}/`; $('#certLink').hidden = !tls;
  try { ws = new WebSocket(url); } catch (e) { setConn('off', 'bad address'); return; }
  let opened = false;
  ws.binaryType = 'blob';
  ws.onopen = () => { opened = true; sendJSON({ t: 'hello', token: h.token, name: h.name || 'phone', app: 'webcam-pwa', proto: PROTO, version: self.APP_VERSION }); };
  ws.onmessage = onMessage;
  ws.onerror = () => {};
  ws.onclose = (e) => {
    clearTimeout(streamTimer); setConn('off', 'offline'); $('#btnConnect').textContent = wantConn && h.auto ? 'Disconnect' : 'Connect'; ws = null;
    if (e.code === 4401) { $('#connHint').textContent = 'Hub rejected the token.'; wantConn = false; return; }
    if (!opened) $('#connHint').innerHTML = 'Could not connect. Same Wi-Fi? Hub running? With the self-signed certificate, tap “Trust hub certificate” once and accept the warning, then come back.';
    if (wantConn && h.auto) { reconnectTimer = setTimeout(connect, backoff); backoff = Math.min(backoff * 1.6, 10000); }
  };
}
function disconnect() { try { localStorage.removeItem('webcam.wasConnected'); } catch {} wantConn = false; clearTimeout(reconnectTimer); if (ws) { ws.onclose = null; ws.close(); ws = null; } setConn('off', 'offline'); }

async function onMessage(ev) {
  if (typeof ev.data !== 'string') return;
  let m; try { m = JSON.parse(ev.data); } catch { return; }
  try {
    switch (m.t) {
      case 'welcome': backoff = 1000; setConn('on', 'hub ' + S.hub.host); $('#connHint').textContent = ''; $('#pConn').open = false; sendState(); reportRec(); streamLoop(); break;
      case 'ping': sendJSON({ t: 'pong' }); break;
      case 'get_state': sendState(); break;
      case 'set': { const e = await applySettings(m.settings || {}); if (e) sendJSON({ t: 'error', msg: e }); break; }
      case 'stream': {
        const p = { streamOn: !!m.on }; if (m.fps) p.streamFps = m.fps; if (m.width) p.streamWidth = m.width; if (m.quality) p.streamQuality = m.quality;
        await applySettings(p); break;
      }
      case 'snap': await snap(m.quality, m.id); break;
      case 'rec': {
        let e = null;
        if (m.action === 'start') e = startRec();
        else if (m.action === 'stop') stopRec();
        else if (m.action === 'send') await sendRecording();
        else if (m.action === 'discard') { lastRec = null; recUI(); }
        if (e) sendJSON({ t: 'error', id: m.id, msg: e });
        break;
      }
    }
  } catch (e) { sendJSON({ t: 'error', id: m.id, msg: String(e.message || e) }); }
}
function sendState() {
  if (!wsOpen()) return;
  const st = track ? track.getSettings() : {};
  sendJSON({
    t: 'state', version: self.APP_VERSION, build: self.APP_BUILD,
    settings: { facing: S.facing, deviceId: S.deviceId, res: S.res, fps: S.fps, audio: S.audio, streamFps: S.streamFps, streamWidth: S.streamWidth, streamQuality: S.streamQuality, snapQuality: S.snapQuality, ...S.adv },
    caps, devices: devices.map((d) => ({ id: d.deviceId, label: d.label })),
    video: { w: st.width, h: st.height, fps: st.frameRate }, recording: recStatus(), streaming: S.streamOn,
  });
}

// ---------- wake lock ----------
let wake = null;
async function requestWake() { try { if (navigator.wakeLock && !wake && document.visibilityState === 'visible') { wake = await navigator.wakeLock.request('screen'); wake.onrelease = () => (wake = null); } } catch {} }
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { requestWake(); reg && reg.update().catch(() => {}); } });

// ---------- service worker / versions / install ----------
let reg = null, deferredInstall = null;
async function clearAllAndReload() {
  try { for (const k of await caches.keys()) await caches.delete(k); } catch {}
  try { for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister(); } catch {}
  location.reload();
}
async function initSW() {
  const cur = `${self.APP_VERSION}+${self.APP_BUILD}`;
  $('#verText').textContent = 'v' + cur;
  let prev = null; try { prev = localStorage.getItem('webcam.ver'); localStorage.setItem('webcam.ver', cur); } catch {}
  if (prev && prev !== cur) toast(`Updated ${prev} → ${cur}`, 5000);
  if (!('serviceWorker' in navigator)) return;
  const hadController = !!navigator.serviceWorker.controller;
  let reloaded = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => { if (hadController && !reloaded && !recorder) { reloaded = true; location.reload(); } });
  try { reg = await navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }); reg.update().catch(() => {}); } catch (e) { console.warn('SW', e); }
}
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferredInstall = e; $('#btnInstall').hidden = false; });
window.addEventListener('appinstalled', () => { $('#btnInstall').hidden = true; toast('Installed'); });

// ---------- wire up ----------
function readHash() {
  if (!location.hash.startsWith('#')) return false;
  const p = new URLSearchParams(location.hash.slice(1));
  if (!p.get('hub')) return false;
  const [host, port] = p.get('hub').split(':');
  Object.assign(S.hub, { host, port: Number(port) || 8765 });
  if (p.has('token')) S.hub.token = p.get('token');
  if (p.has('tls')) S.hub.tls = p.get('tls') !== '0';
  if (p.has('name')) S.hub.name = p.get('name');
  history.replaceState(null, '', location.pathname + location.search);
  save(); return true;
}
function fillHub() {
  const h = S.hub; $('#hubHost').value = h.host; $('#hubPort').value = h.port; $('#hubToken').value = h.token;
  $('#hubTls').checked = h.tls; $('#hubAuto').checked = h.auto; $('#devName').value = h.name;
}
function readHub() {
  Object.assign(S.hub, { host: $('#hubHost').value.trim(), port: Number($('#hubPort').value) || 8765, token: $('#hubToken').value.trim(),
    tls: $('#hubTls').checked, auto: $('#hubAuto').checked, name: $('#devName').value.trim() || 'phone' });
  save();
}

$('#btnSnap').onclick = () => snap().then(({ name }) => toast('Saved ' + name)).catch((e) => toast(e.message));
$('#btnRec').onclick = () => { if (recorder) stopRec(); else { const e = startRec(); if (e) toast(e); } };
$('#btnDl').onclick = () => lastRec && download(lastRec.blob, lastRec.name);
$('#btnFlip').onclick = () => applySettings({ facing: S.facing === 'user' ? 'environment' : 'user', deviceId: '' });
$('#selDevice').onchange = (e) => applySettings({ deviceId: e.target.value });
$('#selRes').onchange = (e) => applySettings({ res: e.target.value });
$('#selFps').onchange = (e) => applySettings({ fps: Number(e.target.value) });
$('#chkAudio').onchange = (e) => applySettings({ audio: e.target.checked });
$('#chkStream').onchange = (e) => applySettings({ streamOn: e.target.checked });
['stFps:streamFps', 'stWidth:streamWidth', 'stQual:streamQuality', 'snQual:snapQuality'].forEach((s) => {
  const [id, key] = s.split(':'); $('#' + id).onchange = (e) => applySettings({ [key]: Number(e.target.value) });
});
$('#btnConnect').onclick = () => { readHub(); if (wantConn && ws) { disconnect(); $('#btnConnect').textContent = 'Connect'; } else { backoff = 1000; connect(); $('#btnConnect').textContent = 'Disconnect'; } };
$('#btnUpdate').onclick = () => { toast('Clearing cache…'); clearAllAndReload(); };
$('#btnInstall').onclick = async () => { if (deferredInstall) { deferredInstall.prompt(); deferredInstall = null; } };

(async function init() {
  const fromHash = readHash();
  fillHub(); syncUI(); recUI(); await initSW();
  try { await startCamera(); } catch {}
  if (fromHash || (S.hub.host && S.hub.auto && localStorage.getItem('webcam.wasConnected') === '1')) { $('#btnConnect').textContent = 'Disconnect'; connect(); }
  window.webcam = { S, applySettings, snap, connect, disconnect }; // debugging / tests
})();
})();
