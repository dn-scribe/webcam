# Webcam — specification & design

An all-JavaScript IP webcam: a static page (GitHub Pages) that installs as an Android PWA, turns the phone
camera into a LAN camera, and is driven from a Python hub (Python API, CLI, HTTP API, browser viewer).
This document describes the current behaviour (app/hub v0.6). User-facing instructions are in the [README](../README.md).

## 1. Architecture

```
 Hub (python/webcam_hub.py)  ── listens on --port, has a name ──┐
   ├─ WSS  /ws   phones (cameras)  ◄───────────  Phone PWA (connects out, has a camera name)
   ├─ WSS  /ui   browser viewers   ◄───────────  python/viewer.html (served by the hub at /view)
   └─ HTTPS      /files /files.zip /snapshot /cameras /ca.crt / /view   (+ optional PWA files)
```

**Why the phone connects out.** A web page cannot open a listening socket, so the phone cannot be a server.
The hub listens; each phone connects outbound and becomes a *camera*. One socket carries control, snapshots,
recordings, library transfers and the preview stream.

**Why TLS.** `getUserMedia` and service workers need a secure context, so the page is served over HTTPS
(GitHub Pages), and an HTTPS page may only open `wss://` sockets. TLS therefore cannot be removed.
Everything else is optional: the token is off by default, and no pairing step is required.

### 1.1 Certificates
* The hub creates a **local CA** once (`~/.webcam-hub/ca.crt|ca.key`, RSA-2048, 10 years). It is
  *name-constrained* (critical `NameConstraints`) to `10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10, 127/8` and `localhost`,
  so it cannot vouch for real websites even if its key leaks.
* A **leaf certificate** for the hub's current LAN IP (+ `127.0.0.1`, `localhost`) is signed by the CA and cached per IP
  (`leaf-<ip>.crt|key`); a new one is issued automatically when the IP changes. Leaf validity is 825 days.
* The CA is downloadable at `https://<hub>/ca.crt`. Installing it on the phone once removes all warnings and enables
  scanning (§6). Without it the user can accept the browser's certificate warning once instead.
* `--cert/--key` replaces the whole scheme with your own certificate.

### 1.2 Trust model
* Default **open**: no token. Clients outside private ranges (RFC 1918, link-local, 100.64/10, loopback; IPv4-mapped IPv6 included)
  are rejected (HTTP 403, WebSocket close 4403) unless `allow_public`.
* With a token (`--token T|auto`), the phone sends it in `hello`; viewers and HTTP callers send `?token=` or `Authorization: Bearer`.
  Wrong/missing: WebSocket close 4401, HTTP 401.

## 2. Features

| Area | Feature |
|---|---|
| Camera | front/back/device, resolution, fps, microphone; every capability the browser reports for the track, rendered dynamically (zoom, torch, exposure mode/compensation/time, focus mode/distance, white balance/temperature, ISO, brightness, contrast, saturation, sharpness) |
| UI | camera screen: video maximal, controls docked around it (CSS grid: top bar, view, quick strip, action row; action column on the right in landscape); settings sheet; pinch-to-zoom; optional floating draggable layout; preview Fit/Fill |
| Snapshots | full-sensor JPEG through `ImageCapture.takePhoto()` (`photoRes`: `max`\|`12mp`\|`8mp`\|`2mp`\|`video`), falling back to the video frame at JPEG quality `snapQuality`; saved to the phone library; hub may request one |
| Recording | MediaRecorder (H.264 mp4 if supported, else VP9/VP8 webm); bitrate 1/2.5/5/10 Mbps; chunks persisted to IndexedDB, crash recovery |
| Library | photos + clips in one list: thumbnails, preview, rename, share, download, delete, multi-select, push to hub |
| Live view | optional JPEG-per-frame preview stream with back-pressure (frames dropped, never queued) |
| Hub access | Python API, CLI, HTTP API, browser viewer (live view, controls, library, camera picker) |
| Multi-camera | several phones at once, selected by name (exact / case-insensitive / substring / glob) |
| Discovery | the phone scans its /24 for hubs; hub and camera both have names; hub chosen by name |
| Offline/versioning | manifest + service worker precache; single-source version; CI build stamp; clear-cache button |

## 3. Wire protocol

Phone → hub: WebSocket `/ws` (the phone's first message must arrive within 10 s). JSON text frames carry a `t` field;
binary frames start with a type byte. Protocol number `proto = 1` (additive changes only so far; unknown `t` values are ignored).

### 3.1 Connection
1. Phone sends **`hello`** `{t, token, name, app:"webcam-pwa", proto, version}` — or **`probe`** `{t}` (discovery, §6).
2. Hub replies **`welcome`** `{t, proto, name, hub}` (`name` = camera name after de-duplication, `hub` = hub name),
   or closes: `4401` bad token, `4403` not on the LAN, `4000` replaced (same device reconnected).
3. Camera names are sanitised (≤ 40 chars). If another *device* (different IP) already uses the name the hub appends `-2`, `-3`…;
   the same IP reconnecting replaces its old connection.

### 3.2 Phone → hub (JSON)
| `t` | Fields | Meaning |
|---|---|---|
| `hello`, `probe` | see 3.1 | |
| `state` | `version, build, settings, caps, devices[{id,label}], video{w,h,fps}, recording, streaming` | after welcome and after every change |
| `rec_status` | `state: idle\|recording, elapsed, size, last{id,size,name}` | on change, 1 Hz while recording |
| `snap_meta` | `id, w, h, size, mime` | precedes binary `0x02` |
| `rec_file_end` | `id, size, mime, chunks, name` | after all `0x03` chunks |
| `lib` | `req?, items[]` | library listing (reply to `lib_list`; also sent unprompted when the library changes) |
| `lib_thumb` | `kind, id, data` (data-URL or null) | reply to `lib_thumb` |
| `lib_file_end` | `req, push, kind, id, name, mime, size, chunks` | after all `0x04` chunks |
| `error` | `id?`/`req?`, `msg` | a command failed |
| `pong` | | reply to `ping` |

Library item: `{kind:"photo"\|"video", id, name, type, size, ts (ms), secs?, w?, h?}`. Photo ids are `ts*100+seq`; video ids are Unix seconds.

### 3.3 Hub → phone (JSON)
| `t` | Fields |
|---|---|
| `welcome` | `proto, name, hub` |
| `hub` (probe reply) | `proto, name, tokenRequired, cameras` — hub then closes |
| `set` | `settings: {facing, deviceId, res:"1280x720"\|"max" (or width,height), fps, audio, photoRes, bitrate:"low\|medium\|high\|max", codec:"auto\|h264\|vp9", streamFps, streamWidth, streamQuality, snapQuality, <any device capability: zoom, torch, …>}`. Camera-level keys restart the camera (refused while recording) |
| `stream` | `on, fps?, width?, quality?` |
| `snap` | `id, quality?` (0–1) → `snap_meta` + binary `0x02` |
| `rec` | `action: start\|stop\|send\|discard`, `id?` (for `send`/`discard`) |
| `lib_list` | `req` |
| `lib_get` | `req, kind, id` → binary `0x04` chunks + `lib_file_end` |
| `lib_thumb` | `kind, id` |
| `lib_delete` | `items:[{kind,id}]` (no confirmation) |
| `get_state`, `ping` | |

### 3.4 Binary (phone → hub)
| Byte 0 | Layout | Relayed to viewers |
|---|---|---|
| `0x01` | preview frame `[01][JPEG…]` | yes (dropped for a busy viewer) |
| `0x02` | snapshot `[02][id u32 BE][JPEG…]` | yes |
| `0x03` | recording chunk `[03][id u32 BE][index u32 BE][bytes…]` | yes |
| `0x04` | library file chunk `[04][req u32 BE][index u32 BE][bytes…]` (256 KiB chunks, sent with back-pressure) | no (hub only) |

A `0x04` stream is a download if the hub has a pending `lib_get` for `(camera, req)`; otherwise it is a **push** from the phone
(`lib_file_end.push = true`), stored under `save_dir` (name collisions get `-<req>`). Every transfer is verified against `size`.

### 3.5 Viewer socket `/ui?token=&camera=`
Hub → viewer: `cameras {hub, items:[{name,ip}], selected}`, `phone {connected, name}`, then every JSON message and binary `0x01–0x03`
of the viewer's selected camera, unchanged. Viewer → hub: `select {camera}` and the commands `set, stream, snap, rec, get_state, lib_list, lib_thumb, lib_delete`
(forwarded to the selected camera). A viewer with no selection follows the hub's default camera, or the only camera; the page auto-selects the first.
Viewer snapshot ids start at `0x40000000` so they never clash with the Python API's. The hub switches a camera's preview stream on when the first
viewer watches it and off when the last leaves.

### 3.6 HTTP
All camera routes accept `?camera=NAME` (default: `use()` camera, or the only one; ambiguity → 404 with the names).

| Route | Result |
|---|---|
| `GET /` | status page (also the place to accept the certificate once), links to `/ca.crt` and `/view` |
| `GET /ca.crt` | the hub CA (PEM) |
| `GET /view` | browser viewer |
| `GET /cameras` | `{cameras:[{name,ip,app,since,video,streaming,recording}]}` |
| `GET /files[?kind=]` | `{camera, items:[… + url]}` |
| `GET /files/<kind>/<id>`, `/files/latest[?kind=]` | one file; `&download=1` → `Content-Disposition: attachment` |
| `GET /files.zip[?kind=\|?items=photo:ID,video:ID]` | stored (uncompressed) ZIP; duplicate names get `-<id>` |
| `GET /snapshot[?quality=]` | fresh JPEG (also saved on the phone) |
| other | PWA files if `--serve-app`, else 404 |

Status codes: 401 token, 403 not LAN, 404 no match/ambiguous camera/item, 502 error, 503 no camera, 504 phone timeout.

## 4. Phone app internals

* **Settings** persist in `localStorage` (`webcam.settings`); camera constraints are applied with `applyConstraints({advanced:[…]})`, one key at a time so unsupported ones fail alone.
* **Storage** (IndexedDB `webcam`, v2): `chunks` (key `[rec, idx]`, recording pieces), `recs` (clip metadata + thumbnail), `snaps` (photo metadata + JPEG blob). Orphaned chunks (no `recs` row) are turned back into clips on launch.
* **Library notifications:** the app sends `lib` whenever the set of items or names changes; thumbnails are generated on request and cached for the session.
* **Preview stream:** a canvas grabs the video at `streamFps`, scaled to `streamWidth`, JPEG `streamQuality`; skipped while the socket's `bufferedAmount` > 512 KiB.
* **Reconnect:** exponential back-off 1 → 10 s; after 3 failed attempts with auto-reconnect on, it scans for the hub again (the IP may have changed).
* **Camera resolution:** requested strictly (`exact` width/height, then the swapped orientation) and only then as a soft `ideal`, because Android treats `ideal` as a hint and often returns 640×480; a toast reports a delivered size more than 15% below the request.
* **Wake lock** requested while the camera runs.

## 5. Offline and versioning

* `sw.js` precaches the app shell (`cache: 'reload'` so HTTP caches never feed it stale files), serves cache-first, and registers with `updateViaCache:'none'` so `version.js`/`sw.js` changes are always seen.
* Cache name = `webcam-<APP_VERSION>-<APP_BUILD>`; `activate` deletes every other cache; the page reloads once on `controllerchange` (not while recording).
* `version.js` is the single source of the version (`tools/bump.py`); CI replaces `APP_BUILD` with the commit SHA on each deploy, so installed apps refresh even without a bump.
* *App → Update / clear cache* deletes all caches and service-worker registrations and reloads.

## 6. Discovery

1. **Probe:** a phone connects to `wss://<ip>:<port>/ws` and sends `{t:"probe"}`; a hub answers `{t:"hub", name, tokenRequired, cameras}` and closes. Anything else (no answer, bad TLS, refused) counts as "no hub".
2. **Where to look:** the last saved host first; else the phone's /24 — its address comes from WebRTC ICE candidates (real addresses are exposed because camera permission is granted) — or the user-typed *scan range*. 254 hosts per prefix, 48 concurrent probes, 2.5 s timeout each.
3. **Which hub:** results are filtered by the *Hub name* setting (exact / case-insensitive / substring / glob). One match → connect; several → the user picks; none → the app lists the hubs it did find.
4. **When:** at start-up if no hub is saved, on *Find hub on LAN*, and automatically after 3 failed reconnects.
5. A probe only succeeds if the phone trusts the hub's certificate, which is why the one-time CA install is recommended.

## 7. Hub internals

* Threaded facade over one asyncio loop; every public method is safe from any thread. Per-camera state: connection, last `state`, last preview frame, library cache.
* **Camera selection:** `camera=None` → `use()` camera → the only camera → `LookupError` listing names. `hub.camera(name)` returns a view that injects `camera=` into every call.
* Pending requests are futures keyed by `("snap"|"rec"|"lib"|"file", id)`; file transfers use a per-`(camera, req)` sink that writes to memory or to a `.part` file renamed on success.
* `process_request` is asynchronous so HTTP routes can wait on the phone; slow viewers never block the phone (frames dropped, other messages bounded by a 5 s send timeout).

## 8. Known limits

* The phone cannot be the server; TLS cannot be removed (§1).
* Open by default (see Trust model). Do not expose the port to the internet.
* HTTP downloads, ZIPs and viewer previews are buffered in hub memory first; the Python API and `--pull` stream to disk.
* Live view is JPEG-per-frame (simple, robust, bandwidth-heavy); recordings use the phone's encoder.
* Scanning needs the phone to trust the hub certificate and a network without client isolation; /24 only (or typed ranges).
* Android may throttle a page whose screen is off; a wake lock is held while open.
* Camera controls depend on the browser/device. Developed against Chromium/Android; iOS untested.
