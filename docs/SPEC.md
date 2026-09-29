# Webcam — specification & design

An all-JavaScript IP webcam: a static page (GitHub Pages) that installs as an Android PWA,
turns the phone camera into a LAN camera, and is driven from a Python app.

## 1. The one hard constraint

A web page **cannot open a listening socket**, so the phone cannot be an HTTP/RTSP server
("share address, set port" on the phone is impossible in pure JS). The design therefore
inverts the connection:

```
 Python app (Hub, listens on --port)  <──wss──  Phone PWA (connects out to hub address:port)
```

* The **Hub** (`python/webcam_hub.py`) listens on the port you choose and prints its
  LAN address, a connect URL and a QR code ("share address").
* The phone opens that URL (or types address/port/token) and connects **outbound** over
  WebSocket. Both are on the same LAN; no internet needed once the app is installed.
* Everything else (camera control, snapshots, recording) rides over that one socket.

### Secure-context / TLS
`getUserMedia` and service workers need HTTPS, so the page comes from
`https://dn-scribe.github.io/webcam/`. An HTTPS page may only open `wss://` sockets, so the Hub
serves **TLS with a self-signed certificate** (auto-generated, SAN = LAN IP). One-time step on
the phone: open `https://<hub-ip>:<port>/` in Chrome and accept the certificate warning.
The app shows this link when a connection fails. Bring your own cert with `--cert/--key`
(e.g. from `mkcert`) to avoid the warning entirely.

## 2. Features

| Area | Feature |
|---|---|
| Camera settings | front/back/device pick, resolution presets, fps, mic on/off; plus every capability the browser reports for the track, rendered dynamically: zoom, torch, exposure mode/compensation/time, focus mode/distance, white balance/temperature, ISO, brightness, contrast, saturation, sharpness |
| Single frames | Snap button (local gallery + download); Hub can request a snapshot at full resolution / chosen JPEG quality |
| Recording | Start / Stop / Download on the phone (MediaRecorder, mp4 if supported else webm); Hub can start, stop and pull the file |
| Live view | Optional MJPEG-style preview stream: JPEG frames over the socket, configurable fps / width / quality, with back-pressure (drops frames rather than lagging) |
| Sharing | Hub prints `https://…/#hub=IP:PORT&token=T` + QR; hash auto-fills and auto-connects |
| Install/offline | Web manifest, service worker precache, works with no internet |
| Versioning | Single source `version.js`; `tools/bump.py` bumps it; CI stamps a build id; SW cache name = version+build; old caches deleted on activate; in-app "Update / clear cache" |

## 3. Wire protocol (v1)

WebSocket, path `/ws`. Text frames = JSON with a `t` field. Binary frames start with a type byte.

### Phone → Hub (JSON)
| `t` | Fields | Meaning |
|---|---|---|
| `hello` | `token, name, app, proto` | first message; hub replies `welcome` or closes with 4401 |
| `state` | `settings, caps, devices, video{w,h,fps}, recording, streaming, version` | sent after hello, and after every change |
| `snap_meta` | `id, w, h, size, mime` | precedes binary type `0x02` |
| `rec_status` | `state: idle\|recording, elapsed, size` | on change / 1 Hz while recording |
| `rec_file_end` | `id, size, mime, chunks, name` | after all `0x03` chunks |
| `error` | `id?, msg` | command failed |
| `pong` | | reply to `ping` |

### Hub → Phone (JSON)
| `t` | Fields |
|---|---|
| `welcome` | `proto` |
| `set` | `settings: {facing, deviceId, res:"1280x720", fps, audio, zoom, torch, …, streamFps, streamWidth, streamQuality}` |
| `stream` | `on, fps?, width?, quality?` |
| `snap` | `id, quality?` (0–1) |
| `rec` | `action: start\|stop\|send\|discard`, `id?` (for `send`) |
| `get_state`, `ping` | |

### Binary (phone → hub)
| Byte 0 | Layout |
|---|---|
| `0x01` | preview frame: `[01][JPEG…]` |
| `0x02` | snapshot: `[02][id u32 BE][JPEG…]` |
| `0x03` | recording chunk: `[03][id u32 BE][index u32 BE][bytes…]` |

## 4. Components

```
index.html  style.css  app.js     PWA UI + camera + socket client
sw.js  manifest.webmanifest  version.js  icons/   offline + install + versioning
python/webcam_hub.py              Hub: WSS server, sync API + CLI
tools/bump.py  tools/make_icons.py
.github/workflows/pages.yml       deploy to GitHub Pages, stamps build id
```

### Python API (sketch)
```python
from webcam_hub import Hub
hub = Hub(port=8765); hub.start()          # prints URL + QR
hub.wait_for_phone()
hub.set(res="1920x1080", torch=True)
jpeg = hub.snapshot()                       # bytes
hub.stream(True, fps=10)                    # hub.latest_frame() -> bytes
hub.rec_start(); ...; mp4 = hub.rec_stop_and_fetch()
```

## 5. Browser viewer (`/view`, WS `/ui`)
The hub serves `python/viewer.html` at `/view?token=T`. It opens `wss://host/ui?token=T`. The hub forwards every phone→hub message (JSON and binary, unchanged) to all viewers and forwards viewer commands `set|stream|snap|rec|get_state` to the phone. Extra hub→viewer message: `{t:"phone", connected, name}`. Viewer snapshot ids start at `0x40000000` so they never clash with the Python API's. Preview frames are dropped for a viewer that is still busy, so a slow browser never stalls the phone. The hub turns the phone's stream on when the first viewer joins and off when the last leaves.

## 6. Known limits
* Recording is buffered in memory on the phone (fine for minutes, not hours).
* Android may throttle the page when the screen is off; the app holds a screen wake lock.
* Manual camera controls depend on the browser/device exposing them.
* Video is JPEG-per-frame, not H.264 — simple and robust, bandwidth-heavy at high fps.
