# IP Webcam (PWA + Python hub)

Turn an Android phone into a LAN camera, driven from a Python app. Pure-JS page, installable, works offline.

**App:** https://dn-scribe.github.io/webcam/  ·  **Spec:** [docs/SPEC.md](docs/SPEC.md)

## Why the phone connects to Python (not the other way round)
A web page cannot listen on a port, so the Python **hub** listens and the phone connects out to it over `wss://` on your LAN. **TLS can't be dropped**: the browser only allows camera access, and a page served over HTTPS may only open `wss://` sockets. What *is* optional is everything else: **no token and no pairing by default**.

## Quick start (out of the box)
```bash
pip install websockets cryptography qrcode
python python/webcam_hub.py
```
1. **Once per phone** – make the phone trust the hub (pick one):
   * *Best:* download `https://<hub-ip>:8765/ca.crt` and install it (Android: Settings → Security → Encryption & credentials → Install a certificate → CA certificate). No more warnings, ever, and the phone can auto-discover the hub. The CA is name-constrained to private addresses, and the hub re-issues its certificate automatically when its IP changes.
   * *Quick:* open `https://<hub-ip>:8765/` and accept the warning.
2. Open https://dn-scribe.github.io/webcam/ (install it as an app). It **looks for the hub on the LAN by itself** (button *Find hub on LAN*; re-scans automatically if the hub's IP changes). Set a **camera name** (e.g. `kitchen`) in the Connection panel.
3. Use the viewer, CLI or API.

**Security defaults:** the hub is open (no token) but only accepts clients on private/LAN addresses (`--allow-public` to lift). Anyone on your LAN can then see the camera and fetch its files, so on shared networks start with `--token auto` (or `--token SECRET`); phones then need the token (field in the app, or it is in the connect link/QR).

### Naming the hub (identity in the other direction)
Give the hub a name — `python python/webcam_hub.py --name office` (default: the machine's host name; `Hub(name="office")` in Python). Phones learn it when they scan, and the app has a **Hub name** field (exact, substring or glob, e.g. `office` or `lab*`): with several hubs on the LAN it connects to the matching one, and if the hub's IP changes it re-finds it by name. The name is also in the connect link/QR (`&hubname=`) and shown in the app's status bar and the viewer's header. Cameras (phones) are named in the app, hubs on the command line, and each side selects the other by name.

### Several cameras
Each phone is a camera with the name you gave it (duplicates get `-2`, `-3`). Pick by name — exact, case-insensitive, substring or glob:
```python
hub.cameras()                       # [{'name': 'kitchen', 'ip': ...}, ...]
hub.camera("kitchen").snapshot()    # a view that routes every call to that camera
hub.snapshot(camera="gar*"); hub.use("kitchen")     # or set a default
```
CLI: `cams`, `use kitchen`, `--camera kitchen`, `--all-cameras --expect 3 --pull` (own sub-folder each). HTTP: `/cameras`, and `?camera=NAME` on every route. The viewer gets a camera picker when more than one is connected (`/view?camera=kitchen` preselects).

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

## Downloading photos & clips to the PC
Everything the phone has saved (see Library below) can be pulled to the PC four ways. The hub saves into `webcam-media/` by default (`--out DIR`).

| Way | How |
|---|---|
| **Browser viewer** | `Library` tab: thumbnails, per-file ⬇, select several → ZIP, *All as ZIP*, preview, delete |
| **Phone app** | Library → tap an item (or **Select**) → **→ PC** pushes it to the hub's folder |
| **Command line** | `python python/webcam_hub.py --pull --out photos [--kind photo\|video] [--delete-after]` downloads everything and exits (`--list` just lists). Interactive prompt: `ls`, `get <#\|name\|latest\|all>`, `pull`, `rm <#>` |
| **Python API** | `hub.library()`, `hub.download("latest", "out_dir")`, `hub.download_all("out_dir", kind="video", delete_after=False)`, `hub.delete(items)`; pushed files fire `hub.on_file(path, meta)` |
| **HTTP** (curl, any language) | `curl -k "https://IP:PORT/files?camera=kitchen"` list · `/files/photo/<id>` · `/files/latest?kind=video` · `/files.zip[?kind=photo]` · `/snapshot` (fresh JPEG). Add `&download=1` for a save-as header. If the hub was started with `--token`, add `?token=T` (or `Authorization: Bearer T`) |

Downloads are verified (size check) and written via a `.part` file; `download_all` skips files already present and never overwrites different files that share a name.

## Library (clips + photos)
Snapshots are now saved in the phone's app storage just like recordings. The **Library** panel lists clips and photos together with thumbnails: filter (All / Clips / Photos), tap to preview, rename, download, share or delete, or tap **Select** for bulk download/share/delete.

## Recordings & compression
Recording chunks are written to the phone's IndexedDB while recording (crash-safe, not held in RAM; interrupted recordings are recovered on next launch). Quality/bitrate (1–10 Mbps) and codec (H.264 mp4 or smaller VP9 webm) are selectable in the app or via the hub (`set bitrate=low codec=vp9`). ⬇ downloads, ↗ shares to Files/Drive, 🗑 deletes.
