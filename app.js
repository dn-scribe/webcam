'use strict';
/* IP Webcam PWA — camera, controls, snapshots, recording, and the WebSocket link to the Python hub.
   Protocol: see docs/SPEC.md */
(() => {
const PROTO = 1;
const $ = (s) => document.querySelector(s);
const video = $('#video');

// ---------- settings ----------
const DEFAULTS = {
  facing: 'environment', deviceId: '', res: '1280x720', fps: 30, audio: false, bitrate: 'medium', codec: 'auto',
  streamOn: false, streamFps: 10, streamWidth: 640, streamQuality: 0.6, snapQuality: 0.92,
  adv: {}, ui: {},
  hub: { host: '', port: 8765, token: '', tls: true, auto: true, name: 'phone' },
};
const CAMERA_KEYS = ['facing', 'deviceId', 'res', 'fps', 'audio'];
const STREAM_KEYS = ['streamFps', 'streamWidth', 'streamQuality', 'snapQuality'];
const REC_KEYS = ['bitrate', 'codec'];
let S = load();
function load() {
  try { const j = JSON.parse(localStorage.getItem('webcam.settings') || '{}');
    return { ...DEFAULTS, ...j, adv: { ...(j.adv || {}) }, ui: { ...(j.ui || {}) }, hub: { ...DEFAULTS.hub, ...(j.hub || {}) } };
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
  for (const k of REC_KEYS) if (k in p) S[k] = String(p[k]);
  if ('streamOn' in p) S.streamOn = !!p.streamOn;
  if (restart) await startCamera();
  for (const [k, v] of Object.entries(p)) {
    if (CAMERA_KEYS.includes(k) || STREAM_KEYS.includes(k) || REC_KEYS.includes(k) || ['width', 'height', 'streamOn', 'res'].includes(k)) continue;
    if (k in caps) await applyAdv(k, v);
  }
  save(); syncUI(); updateCamInfo(); streamLoop(); sendState();
  if (!restart && track) buildUI();   // keep the two copies of each control in sync
  return null;
}

// ---------- dynamic device controls ----------
const ADV_KEYS = ['zoom', 'torch', 'exposureMode', 'exposureCompensation', 'exposureTime', 'focusMode',
  'focusDistance', 'whiteBalanceMode', 'colorTemperature', 'iso', 'brightness', 'contrast', 'saturation', 'sharpness'];
const QUICK_KEYS = ['zoom', 'torch', 'exposureCompensation', 'focusDistance'];
function mkControl(k) {
  const c = caps[k]; if (c === undefined) return null;
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
  } else return null;
  return lab;
}
function buildUI() {
  const dev = $('#selDevice'); dev.innerHTML = '';
  devices.forEach((d, i) => dev.add(new Option(d.label || `Camera ${i + 1}`, d.deviceId)));
  const curDev = track && track.getSettings().deviceId; if (curDev) dev.value = curDev;
  const box = $('#advControls'), quick = $('#quickBody'); box.innerHTML = ''; quick.innerHTML = '';
  let n = 0;
  if (track) for (const k of ADV_KEYS) {
    const el = mkControl(k); if (!el) continue;
    box.append(el); n++;
    if (QUICK_KEYS.includes(k)) quick.append(mkControl(k));
  }
  if (!n) box.innerHTML = '<p class="hint">This camera/browser exposes no extra controls.</p>';
  $('#quick').hidden = !quick.children.length; placeAll();
}
// ---------- movable controls ----------
const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);
const WIDGETS = { bar: { el: '#actions', def: { x: 0.5, y: 0.97, vert: false } }, quick: { el: '#quick', def: { x: 0.02, y: 0.45, vert: false } } };
function place(name) {
  const w = WIDGETS[name], el = $(w.el), host = el.offsetParent; if (!host || el.hidden) return;
  const p = { ...w.def, ...(S.ui[name] || {}) };
  el.classList.toggle('vert', !!p.vert);
  const W = Math.max(0, host.clientWidth - el.offsetWidth), H = Math.max(0, host.clientHeight - el.offsetHeight);
  el.style.left = clamp(p.x * W, 0, W) + 'px'; el.style.top = clamp(p.y * H, 0, H) + 'px';
}
function placeAll() { Object.keys(WIDGETS).forEach(place); }
function initWidgets() {
  for (const [name, w] of Object.entries(WIDGETS)) {
    const el = $(w.el), grip = el.querySelector('.grip'), rot = el.querySelector('.rot');
    grip.onpointerdown = (e) => {
      e.preventDefault(); grip.setPointerCapture(e.pointerId);
      const host = el.offsetParent, hr = host.getBoundingClientRect(), r = el.getBoundingClientRect(), dx = e.clientX - r.left, dy = e.clientY - r.top;
      const W = host.clientWidth - el.offsetWidth, H = host.clientHeight - el.offsetHeight;
      const move = (m) => { el.style.left = clamp(m.clientX - hr.left - dx, 0, W) + 'px'; el.style.top = clamp(m.clientY - hr.top - dy, 0, H) + 'px'; };
      const up = () => {
        grip.removeEventListener('pointermove', move); grip.removeEventListener('pointerup', up);
        S.ui[name] = { ...(S.ui[name] || w.def), x: W > 0 ? parseFloat(el.style.left) / W : 0, y: H > 0 ? parseFloat(el.style.top) / H : 0 }; save();
      };
      grip.addEventListener('pointermove', move); grip.addEventListener('pointerup', up);
    };
    rot.onclick = () => { S.ui[name] = { ...w.def, ...(S.ui[name] || {}), vert: !(S.ui[name] || w.def).vert }; save(); place(name); };
  }
  addEventListener('resize', placeAll);
  $('#btnFull').onclick = toggleFull;
  $('#btnResetLayout').onclick = () => { S.ui = {}; save(); placeAll(); toast('Layout reset'); };
  placeAll();
}
function toggleFull() {
  const on = document.body.classList.toggle('full');
  if (on) document.documentElement.requestFullscreen?.().catch(() => {}); else if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  setTimeout(placeAll, 50);
}
function syncUI() {
  $('#selRes').value = S.res; $('#selFps').value = String(S.fps); $('#selBitrate').value = S.bitrate; $('#selCodec').value = S.codec; $('#chkAudio').checked = S.audio;
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
let snaps = [], snapSeq = 0;   // persisted photos, newest first
async function snap(quality, id) {
  const { blob, w, h } = await grab(0, quality ?? S.snapQuality);
  const ts = Date.now(), item = { id: ts * 100 + (snapSeq++ % 100), kind: 'photo', name: `snap-${stamp()}.jpg`, type: 'image/jpeg', size: blob.size, ts, w, h, blob };
  snaps.unshift(item); idb.put('snaps', item).catch(() => {}); renderLib(); storageInfo(); flash();
  if (id != null && wsOpen()) {
    sendJSON({ t: 'snap_meta', id, w, h, size: blob.size, mime: 'image/jpeg' });
    const head = new Uint8Array(5); head[0] = 2; new DataView(head.buffer).setUint32(1, id);
    ws.send(new Blob([head, blob]));
  }
  return { blob, name: item.name };
}
function flash() { video.style.opacity = 0.3; setTimeout(() => (video.style.opacity = 1), 90); }

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

// ---------- recording (chunks persisted to IndexedDB, not RAM) ----------
const BITRATES = { low: 1e6, medium: 2.5e6, high: 5e6, max: 10e6 };
let recorder = null, recSize = 0, recStart = 0, recTick, lastRec = null, recId = 0, recWrites = [], recMem = [];
let recs = [];   // finished recordings' metadata, newest first

const idb = (() => {
  let dbp;
  const open = () => dbp || (dbp = new Promise((res, rej) => {
    const r = indexedDB.open('webcam', 2);
    r.onupgradeneeded = () => { const db = r.result, has = (n) => db.objectStoreNames.contains(n);
      if (!has('chunks')) db.createObjectStore('chunks', { keyPath: ['rec', 'idx'] });
      if (!has('recs')) db.createObjectStore('recs', { keyPath: 'id' });
      if (!has('snaps')) db.createObjectStore('snaps', { keyPath: 'id' }); };
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  }));
  const run = async (store, mode, fn) => { const db = await open(); return new Promise((res, rej) => {
    const tx = db.transaction(store, mode), st = tx.objectStore(store), out = fn(st);
    tx.oncomplete = () => res(out && 'result' in out ? out.result : undefined); tx.onerror = () => rej(tx.error); }); };
  return {
    put: (store, v) => run(store, 'readwrite', (st) => st.put(v)),
    all: (store) => run(store, 'readonly', (st) => st.getAll()),
    chunks: (rec) => run('chunks', 'readonly', (st) => st.getAll(IDBKeyRange.bound([rec, 0], [rec, Infinity]))),
    delSnap: (id) => run('snaps', 'readwrite', (st) => st.delete(id)),
    del: async (rec) => { await run('chunks', 'readwrite', (st) => st.delete(IDBKeyRange.bound([rec, 0], [rec, Infinity]))); await run('recs', 'readwrite', (st) => st.delete(rec)); },
  };
})();
async function recBlob(meta) {
  const parts = (await idb.chunks(meta.id)).map((c) => c.blob);
  return new Blob(parts, { type: meta.type });
}
function pickMime() {
  const h264 = ['video/mp4;codecs=avc1', 'video/mp4'], vp9 = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'];
  const order = S.codec === 'vp9' ? [...vp9, ...h264] : S.codec === 'h264' ? [...h264, ...vp9] : [...h264, ...vp9];
  return order.find((m) => window.MediaRecorder && MediaRecorder.isTypeSupported(m)) || '';
}
function startRec() {
  if (recorder || !stream) return 'not ready';
  if (!window.MediaRecorder) return 'MediaRecorder unsupported';
  const mime = pickMime();
  recSize = 0; recWrites = []; recMem = []; recId = Math.floor(Date.now() / 1000);
  const id = recId; let idx = 0;
  recorder = new MediaRecorder(stream, { ...(mime && { mimeType: mime }), videoBitsPerSecond: BITRATES[S.bitrate] || BITRATES.medium, audioBitsPerSecond: 96000 });
  recorder.ondataavailable = (e) => {
    if (!e.data.size) return;
    recSize += e.data.size; const i = idx++;
    recWrites.push(idb.put('chunks', { rec: id, idx: i, blob: e.data }).catch(() => recMem.push({ rec: id, idx: i, blob: e.data })));
  };
  recorder.onstop = async () => {
    clearInterval(recTick);
    const type = recorder.mimeType || mime || 'video/webm', started = recStart;
    recorder = null; recUI();
    await Promise.all(recWrites);
    let thumb = null; try { thumb = (await grab(240, 0.6)).blob; } catch {}
    const meta = { id, kind: 'video', thumb, type, name: `rec-${stamp()}.${type.includes('mp4') ? 'mp4' : 'webm'}`, size: recSize, secs: (Date.now() - started) / 1000, ts: Date.now() };
    if (recMem.length) { meta.mem = new Blob(recMem.sort((a, b) => a.idx - b.idx).map((c) => c.blob), { type }); }  // IndexedDB failed: keep in RAM
    else await idb.put('recs', meta).catch(() => {});
    recs.unshift(meta); lastRec = meta; recUI(); renderLib(); reportRec(); toast(`Recorded ${(meta.size / 1e6).toFixed(1)} MB`); storageInfo();
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
  ['selDevice', 'selRes', 'selFps', 'chkAudio', 'btnFlip', 'selBitrate', 'selCodec'].forEach((i) => ($('#' + i).disabled = on));
}
function reportRec() { sendJSON({ t: 'rec_status', ...recStatus() }); }
const recStatus = () => ({ state: recorder ? 'recording' : 'idle', elapsed: recorder ? (Date.now() - recStart) / 1000 : 0, size: recSize, last: lastRec && { id: lastRec.id, size: lastRec.size, name: lastRec.name } });
const blobOf = (meta) => (meta.mem ? Promise.resolve(meta.mem) : recBlob(meta));
async function sendRecording(id) {
  const meta = recs.find((r) => r.id === id) || lastRec;
  if (!meta) throw new Error('no recording');
  const blob = await blobOf(meta), CH = 256 * 1024; let idx = 0;
  for (let off = 0; off < blob.size; off += CH, idx++) {
    while (wsOpen() && ws.bufferedAmount > 2 * CH) await new Promise((r) => setTimeout(r, 20));
    if (!wsOpen()) throw new Error('disconnected');
    const head = new Uint8Array(9); head[0] = 3;
    const dv = new DataView(head.buffer); dv.setUint32(1, meta.id); dv.setUint32(5, idx);
    ws.send(new Blob([head, blob.slice(off, off + CH)]));
  }
  sendJSON({ t: 'rec_file_end', id: meta.id, size: blob.size, mime: meta.type, chunks: idx, name: meta.name });
}
// ---------- library (videos + photos) ----------
const itemKey = (i) => `${i.kind}:${i.id}`;
const allItems = () => [...recs, ...snaps].sort((a, b) => b.ts - a.ts);
const blobOfItem = (i) => (i.kind === 'photo' ? Promise.resolve(i.blob) : blobOf(i));
let libFilter = 'all', selMode = false; const sel = new Set(), thumbUrls = new Map();
function thumbUrl(i) {
  const k = itemKey(i); if (thumbUrls.has(k)) return thumbUrls.get(k);
  const src = i.kind === 'photo' ? i.blob : i.thumb, u = src ? URL.createObjectURL(src) : null;
  thumbUrls.set(k, u); return u;
}
function forgetItem(i) { const u = thumbUrls.get(itemKey(i)); if (u) URL.revokeObjectURL(u); thumbUrls.delete(itemKey(i)); sel.delete(itemKey(i)); }
const fmtSize = (n) => (n > 1e6 ? (n / 1e6).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1e3)) + ' KB');
const fmtDur = (s) => `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')}`;
const guard = (fn) => (...a) => fn(...a).catch((e) => e.name !== 'AbortError' && toast(String(e.message || e)));

async function shareItems(items) {
  const files = await Promise.all(items.map(async (i) => new File([await blobOfItem(i)], i.name, { type: i.type })));
  if (navigator.canShare && navigator.canShare({ files })) await navigator.share({ files, title: files.length === 1 ? files[0].name : `${files.length} files` });
  else toast('Sharing files is not supported here — use Download');
}
async function downloadItems(items) { for (const i of items) { download(await blobOfItem(i), i.name); if (items.length > 1) await new Promise((r) => setTimeout(r, 400)); } }
async function deleteItems(items, ask = true) {
  if (!items.length || (ask && !confirm(`Delete ${items.length === 1 ? items[0].name : items.length + ' items'}? This cannot be undone.`))) return;
  for (const i of items) {
    if (i.kind === 'photo') { await idb.delSnap(i.id).catch(() => {}); snaps = snaps.filter((x) => x !== i); }
    else { await idb.del(i.id).catch(() => {}); recs = recs.filter((x) => x !== i); if (lastRec === i) lastRec = recs[0] || null; }
    forgetItem(i);
  }
  renderLib(); recUI(); storageInfo();
}
async function renameItem(i) {
  const dot = i.name.lastIndexOf('.'), ext = dot > 0 ? i.name.slice(dot) : '', base = dot > 0 ? i.name.slice(0, dot) : i.name;
  const n = prompt('Rename', base); if (n === null) return;
  const clean = n.trim().replace(/[\\/:*?"<>|]/g, '_'); if (!clean) return;
  i.name = clean + ext;
  if (i.kind === 'photo') await idb.put('snaps', i); else { const { mem, ...m } = i; await idb.put('recs', m); }
  renderLib();
}
function renderLib() {
  const items = allItems(), shown = items.filter((i) => libFilter === 'all' || (libFilter === 'video') === (i.kind === 'video'));
  $('#libCount').textContent = items.length ? `(${recs.length} 🎞 · ${snaps.length} 📷)` : '';
  document.querySelectorAll('#libFilter button').forEach((b) => b.classList.toggle('on', b.dataset.f === libFilter));
  $('#libSelect').textContent = selMode ? 'Done' : 'Select';
  $('#libActions').hidden = !selMode; $('#libSelN').textContent = `${sel.size} selected`;
  ['#libDl', '#libShare', '#libDel'].forEach((b) => ($(b).disabled = !sel.size));
  const grid = $('#libGrid'); grid.innerHTML = '';
  if (!shown.length) grid.innerHTML = '<p class="hint">Nothing here yet — take a snapshot or record a clip.</p>';
  for (const i of shown) {
    const card = document.createElement('div'); card.className = 'card' + (sel.has(itemKey(i)) ? ' sel' : '');
    const u = thumbUrl(i);
    card.innerHTML = (u ? `<img src="${u}" alt="">` : '<div class="noimg">🎞</div>') +
      (i.kind === 'video' ? `<span class="tag">▶ ${fmtDur(i.secs || 0)}</span>` : '') + (selMode ? `<span class="chk">${sel.has(itemKey(i)) ? '✔' : ''}</span>` : '') +
      `<div class="cap"></div>`;
    card.querySelector('.cap').textContent = `${i.name.replace(/^(snap|rec)-/, '')} · ${fmtSize(i.size)}`;
    card.onclick = () => { if (selMode) { sel.has(itemKey(i)) ? sel.delete(itemKey(i)) : sel.add(itemKey(i)); renderLib(); } else openPreview(i); };
    grid.append(card);
  }
}
let pvUrl = null;
async function openPreview(i) {
  const dlg = $('#preview'), body = $('#pvBody'); body.innerHTML = '';
  const blob = await blobOfItem(i); pvUrl = URL.createObjectURL(blob);
  const el = document.createElement(i.kind === 'photo' ? 'img' : 'video'); el.src = pvUrl;
  if (i.kind === 'video') Object.assign(el, { controls: true, autoplay: false, playsInline: true });
  body.append(el); $('#pvName').textContent = `${i.name} · ${fmtSize(i.size)}`;
  $('#pvDl').onclick = guard(() => downloadItems([i])); $('#pvShare').onclick = guard(() => shareItems([i]));
  $('#pvRename').onclick = guard(async () => { await renameItem(i); $('#pvName').textContent = `${i.name} · ${fmtSize(i.size)}`; });
  $('#pvDel').onclick = guard(async () => { await deleteItems([i]); if (!allItems().includes(i)) dlg.close(); });
  dlg.showModal();
}
$('#preview').addEventListener('close', () => { if (pvUrl) URL.revokeObjectURL(pvUrl); pvUrl = null; $('#pvBody').innerHTML = ''; });
$('#pvClose').onclick = () => $('#preview').close();
document.querySelectorAll('#libFilter button').forEach((b) => (b.onclick = () => { libFilter = b.dataset.f; renderLib(); }));
$('#libSelect').onclick = () => { selMode = !selMode; sel.clear(); renderLib(); };
$('#libAll').onclick = () => { const shown = [...document.querySelectorAll('#libGrid .card')].length; const items = allItems().filter((i) => libFilter === 'all' || (libFilter === 'video') === (i.kind === 'video')); items.length && sel.size === items.length ? sel.clear() : items.forEach((i) => sel.add(itemKey(i))); renderLib(); };
const selected = () => allItems().filter((i) => sel.has(itemKey(i)));
$('#libDl').onclick = guard(() => downloadItems(selected()));
$('#libShare').onclick = guard(() => shareItems(selected()));
$('#libDel').onclick = guard(() => deleteItems(selected()));

async function storageInfo() {
  try {
    const e = await navigator.storage.estimate(); const persisted = navigator.storage.persisted ? await navigator.storage.persisted() : false;
    const mine = [...recs, ...snaps].reduce((a, r) => a + r.size, 0);
    $('#storInfo').textContent = `Phone storage: library uses ${(mine / 1e6).toFixed(0)} MB · app quota ${(e.usage / 1e6).toFixed(0)} / ${(e.quota / 1e9).toFixed(1)} GB${persisted ? ' · protected' : ''}`;
  } catch {}
}
async function loadRecs() {
  try {
    if (navigator.storage && navigator.storage.persist) navigator.storage.persist();
    const metas = await idb.all('recs'), have = new Set(metas.map((m) => m.id));
    // recover recordings interrupted by a crash/kill: chunks exist but no metadata
    const orphan = new Map();
    for (const c of await idb.all('chunks')) if (!have.has(c.rec)) { const o = orphan.get(c.rec) || { size: 0, type: c.blob.type }; o.size += c.blob.size; orphan.set(c.rec, o); }
    for (const [id, o] of orphan) {
      const type = o.type || 'video/webm', m = { id, kind: 'video', type, size: o.size, secs: 0, ts: id * 1000, name: `recovered-${id}.${type.includes('mp4') ? 'mp4' : 'webm'}` };
      await idb.put('recs', m); metas.push(m);
    }
    metas.forEach((m) => (m.kind = 'video'));
    recs = metas.sort((a, b) => b.ts - a.ts); lastRec = recs[0] || null;
    snaps = (await idb.all('snaps')).sort((a, b) => b.ts - a.ts);
  } catch {}
  renderLib(); recUI(); storageInfo();
}

// ---------- hub connection ----------
let ws = null, reconnectTimer, wantConn = false, backoff = 1000, failures = 0;
const wsOpen = () => ws && ws.readyState === 1;
const sendJSON = (o) => { if (wsOpen()) ws.send(JSON.stringify(o)); };
function setConn(state, text) {
  $('#connDot').className = 'dot ' + state; $('#connText').textContent = text;
  try { if (state === 'on') localStorage.setItem('webcam.wasConnected', '1'); } catch {}
}
function hubUrl(scheme) {
  const h = S.hub; return `${scheme}://${h.host}:${h.port}`;
}
async function probe() {
  const h = S.hub, ctl = new AbortController(), t = setTimeout(() => ctl.abort(), 5000);
  try { await fetch(`https://${h.host}:${h.port}/`, { mode: 'no-cors', cache: 'no-store', signal: ctl.signal }); return 'ok'; }
  catch { return ctl.signal.aborted ? 'timeout' : 'fail'; } finally { clearTimeout(t); }
}
async function diagnose() {
  const h = S.hub; if (!h.host) return 'Enter the hub address first.';
  $('#connHint').textContent = 'Testing…';
  const r = await probe(), where = `${h.host}:${h.port}`;
  const msg = {
    ok: `✔ ${where} is reachable and its certificate is trusted, yet the socket failed. Check the token matches the hub, and that the hub is the current webcam_hub.py.`,
    timeout: `✖ No answer from ${where} within 5 s. Wrong IP? Phone on a different Wi-Fi/guest network? PC firewall blocking Python (allow it on private networks)?`,
    fail: `✖ ${where} refused or its certificate isn't trusted. Tap “Trust hub certificate”: if the page doesn't load → hub not running / wrong port / network. If it shows “Webcam hub OK” after the warning → accept it, come back and retry.`,
  }[r];
  $('#connHint').textContent = msg; return msg;
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
  const t0 = performance.now();
  ws.onclose = (e) => {
    const dt = Math.round(performance.now() - t0), was = opened;
    clearTimeout(streamTimer); setConn('off', 'offline'); $('#btnConnect').textContent = wantConn && h.auto ? 'Disconnect' : 'Connect'; ws = null;
    if (e.code === 4401) { $('#connHint').textContent = 'Hub rejected the token.'; wantConn = false; return; }
    if (!was && !failures++) diagnose();          // first failure of a run: explain why
    if (wantConn && h.auto) {
      const wait = backoff; reconnectTimer = setTimeout(connect, wait); backoff = Math.min(backoff * 1.6, 10000);
      setConn('off', `offline · attempt ${failures} failed (${dt} ms, code ${e.code}) · retry ${Math.round(wait / 1000)}s`);
    } else setConn('off', `offline (code ${e.code})`);
  };
}
function disconnect() { try { localStorage.removeItem('webcam.wasConnected'); } catch {} wantConn = false; clearTimeout(reconnectTimer); if (ws) { ws.onclose = null; ws.close(); ws = null; } setConn('off', 'offline'); }

async function onMessage(ev) {
  if (typeof ev.data !== 'string') return;
  let m; try { m = JSON.parse(ev.data); } catch { return; }
  try {
    switch (m.t) {
      case 'welcome': backoff = 1000; failures = 0; setConn('on', 'hub ' + S.hub.host); $('#connHint').textContent = ''; $('#pConn').open = false; sendState(); reportRec(); streamLoop(); break;
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
        else if (m.action === 'send') await sendRecording(m.id);
        else if (m.action === 'discard') { const r = recs.find((x) => x.id === m.id) || lastRec; if (r) await deleteItems([r], false); }
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
    settings: { facing: S.facing, deviceId: S.deviceId, res: S.res, fps: S.fps, audio: S.audio, bitrate: S.bitrate, codec: S.codec, streamFps: S.streamFps, streamWidth: S.streamWidth, streamQuality: S.streamQuality, snapQuality: S.snapQuality, ...S.adv },
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
$('#btnDl').onclick = async () => lastRec && download(await blobOf(lastRec), lastRec.name);
$('#selBitrate').onchange = (e) => applySettings({ bitrate: e.target.value });
$('#selCodec').onchange = (e) => applySettings({ codec: e.target.value });
$('#btnFlip').onclick = () => applySettings({ facing: S.facing === 'user' ? 'environment' : 'user', deviceId: '' });
$('#selDevice').onchange = (e) => applySettings({ deviceId: e.target.value });
$('#selRes').onchange = (e) => applySettings({ res: e.target.value });
$('#selFps').onchange = (e) => applySettings({ fps: Number(e.target.value) });
$('#chkAudio').onchange = (e) => applySettings({ audio: e.target.checked });
$('#chkStream').onchange = (e) => applySettings({ streamOn: e.target.checked });
['stFps:streamFps', 'stWidth:streamWidth', 'stQual:streamQuality', 'snQual:snapQuality'].forEach((s) => {
  const [id, key] = s.split(':'); $('#' + id).onchange = (e) => applySettings({ [key]: Number(e.target.value) });
});
$('#btnConnect').onclick = () => { readHub(); if (wantConn && ws) { disconnect(); $('#btnConnect').textContent = 'Connect'; } else { backoff = 1000; failures = 0; connect(); $('#btnConnect').textContent = 'Disconnect'; } };
$('#btnTest').onclick = () => { readHub(); diagnose(); };
$('#btnUpdate').onclick = () => { toast('Clearing cache…'); clearAllAndReload(); };
$('#btnInstall').onclick = async () => { if (deferredInstall) { deferredInstall.prompt(); deferredInstall = null; } };

(async function init() {
  const fromHash = readHash();
  fillHub(); syncUI(); recUI(); initWidgets(); loadRecs(); await initSW();
  try { await startCamera(); } catch {}
  if (fromHash || (S.hub.host && S.hub.auto && localStorage.getItem('webcam.wasConnected') === '1')) { $('#btnConnect').textContent = 'Disconnect'; connect(); }
  window.webcam = { S, applySettings, snap, connect, disconnect }; // debugging / tests
})();
})();
