# IP Webcam (PWA + Python hub)

Turn an Android phone into a LAN camera, driven from a Python app. Pure-JS page, installable, works offline.

**App:** https://dn-scribe.github.io/webcam/  ·  **Spec:** [docs/SPEC.md](docs/SPEC.md)

## Why the phone connects to Python (not the other way round)
A web page cannot listen on a port. So the Python **hub** listens on the port you choose and prints its address + QR; the phone connects out to it over `wss://` on your LAN.

## Quick start
```bash
pip install websockets cryptography qrcode
python python/webcam_hub.py --port 8765
```
1. On the phone (same Wi-Fi), open the `https://<ip>:<port>/` link the hub prints and accept the self-signed cert warning **once**.
2. Open the app link / scan the QR (or open https://dn-scribe.github.io/webcam/ and type address, port, token). Menu → **Install app** / *Add to Home screen*.
3. Use the CLI (`snap`, `rec start`, `rec stop`, `stream on 10`, `set res=1920x1080 torch=true`) or the API:

```python
from webcam_hub import Hub
hub = Hub(port=8765).start(); hub.wait_for_phone()
hub.set(res="1920x1080"); open("a.jpg","wb").write(hub.snapshot())
hub.stream(True, fps=10); frame = hub.latest_frame(wait=2)   # JPEG bytes
hub.rec_start(); ...; data, name = hub.rec_stop_and_fetch()
```
Use `--cert/--key` (e.g. mkcert) for a trusted cert; `--serve-app` makes the hub serve the app itself (camera works, but no offline install because of the self-signed cert).

## Browser viewer (mirror on the PC)
The hub serves a page that mirrors the phone: `python python/webcam_hub.py --open` (or open `https://localhost:8765/view?token=<token>`, printed at start-up). It shows the live view, Snap / Record / Get-recording (downloads in the browser), and every camera, recording and stream setting, kept in sync both ways with the phone. The phone's preview stream is switched on automatically while a viewer is open. Keys: Space = snap, R = record. From Python: `hub.open_viewer()`.

## Phone UI
Draggable, rotatable control toolbar and quick camera panel (zoom/torch/exposure/focus) with a ⛶ full-screen view; positions are remembered (App → Reset control layout). Snap · Record/Stop · Download last recording · Flip camera · resolution/fps/mic · every zoom/torch/exposure/focus/white-balance control the device exposes · optional preview stream · snapshot gallery.

## Versioning & offline
* Version lives in `version.js`. `python tools/bump.py patch|minor|major` then push to `main`.
* CI deploys to Pages and stamps a build id, so every deploy refreshes installed apps: new service-worker cache, old caches deleted, page reloads. In-app **Update / clear cache** forces it.
* After the first load the app runs with no internet.

## GitHub Pages
Repo → Settings → Pages → Source: **GitHub Actions**. (`.github/workflows/pages.yml`)

## Library (clips + photos)
Snapshots are now saved in the phone's app storage just like recordings. The **Library** panel lists clips and photos together with thumbnails: filter (All / Clips / Photos), tap to preview, rename, download, share or delete, or tap **Select** for bulk download/share/delete.

## Recordings & compression
Recording chunks are written to the phone's IndexedDB while recording (crash-safe, not held in RAM; interrupted recordings are recovered on next launch). Quality/bitrate (1–10 Mbps) and codec (H.264 mp4 or smaller VP9 webm) are selectable in the app or via the hub (`set bitrate=low codec=vp9`). ⬇ downloads, ↗ shares to Files/Drive, 🗑 deletes.
