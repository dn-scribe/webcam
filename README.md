# IP Webcam — phone camera on your LAN, driven from Python

Turn an Android phone into a LAN camera with **nothing but a web page**, and control it from a Python app, a browser, `curl` or the command line: live view, camera settings, snapshots, recording, and downloading what the phone has saved. Installable as a PWA and fully offline (no internet needed after the first load).

* **App (open on the phone):** https://dn-scribe.github.io/webcam/
* **Protocol & design details:** [docs/SPEC.md](docs/SPEC.md)

## How it works

```
 Python hub  (listens on a port, has a name)  <──── wss://  ────  Phone PWA (camera, connects out)
      │  Python API · CLI · HTTP API · browser viewer
```

A web page can't listen on a port, so the roles are inverted: the **hub** (`python/webcam_hub.py`) listens, and each phone connects *out* to it and becomes a named **camera**. TLS can't be dropped: the browser only allows camera access, and a page served over HTTPS may only open `wss://` sockets. Everything else is optional — **no token and no pairing by default**.

## Quick start

```bash
pip install -r python/requirements.txt
python python/webcam_hub.py
```

1. **Once per phone, make it trust the hub** (pick one):
   * **Install the hub's CA (recommended).** Download `https://<hub-ip>:8765/ca.crt` on the phone and install it (Android: *Settings → Security → Encryption & credentials → Install a certificate → CA certificate*). No certificate warnings ever, and the app can find the hub by itself. The CA is name-constrained to private IP ranges, and the hub re-issues its certificate automatically if its IP changes.
   * **Or accept the warning:** open `https://<hub-ip>:8765/` once and proceed. (No auto-discovery with this option: type the hub address.)
2. **Open the app** on the phone and install it (*Install app* in the App panel). It looks for the hub on the LAN by itself. Optionally name the phone (**Camera name**, e.g. `kitchen`).
3. **Use it** — browser viewer (`python python/webcam_hub.py --open`), CLI prompt, Python, or `curl` (below).

Without a CA the phone can still connect to a known address (type it, or use the link/QR the hub prints); only *scanning* needs the trusted certificate.

## Naming: each side selects the other by name

| Name of | Set in | Used for |
|---|---|---|
| **Hub** | `--name office` (default: host name), `Hub(name=…)` | The phone's **Hub name** field picks the hub among several on the LAN (exact / substring / glob, e.g. `lab*`) and re-finds it if its IP changes. Shown in the app status bar and viewer header; carried in the connect link (`&hubname=`) |
| **Camera** (phone) | **Camera name** in the app (default `cam-xxxx`) | Python/CLI/HTTP/viewer pick a camera by name (exact, case-insensitive, substring or glob). Duplicate names get `-2`, `-3`… |

## The phone app

* **Camera screen (default):** the live view takes all the space that is left and the immediate controls are docked around it — status and ⚙ along the top; **Flip · Snap · Record · Download** in a row below the view (a column on the right in landscape); a one-line strip with zoom / torch / exposure / focus next to them. **Pinch the view to zoom.** ⛶ toggles browser full screen. Everything else (connection, camera settings, library, app) is in the ⚙ settings sheet, which opens by itself on first run.
* **Floating layout (optional):** *⚙ → App → Controls → Floating* lets you drag the toolbar and quick panel anywhere over the video and rotate them; positions are remembered (*Reset floating layout*).
* **Preview fit:** *Fit* shows the whole frame; *Fill* crops the edges to use the full screen (preview only — snapshots and recordings are unaffected).
* **Camera settings:** camera device, resolution, frame rate, microphone, plus every control the device reports (zoom, torch, exposure, focus, white balance, ISO, brightness, contrast, saturation, sharpness).
* **Recording:** quality 1–10 Mbps and codec (H.264 mp4 or smaller VP9 webm). Chunks are written to the phone's IndexedDB while recording (crash-safe, not held in RAM; interrupted recordings are recovered on the next launch).
* **Library:** snapshots and clips are stored on the phone and managed together: thumbnails, filter (All / Clips / Photos), tap to preview, rename, download, share, delete, **Select** for bulk actions, and **→ PC** to push files to the hub.
* **Live stream:** optional JPEG preview stream to the hub (fps / width / quality).
* **Connection panel:** hub address & port, hub name, camera name, optional token, scan range, **Find hub on LAN**, **Test connection** (explains why a connection fails), auto-reconnect.
* **App panel:** version, install, *Update / clear cache*.

## The hub

### CLI
```
python python/webcam_hub.py [options]
```
| Option | Meaning |
|---|---|
| `--port N` | Listening port (default 8765) |
| `--name NAME` | Hub name shown to phones (default host name) |
| `--token T` / `--token auto` | Require a shared secret (default: none — open) |
| `--allow-public` | Also accept clients outside private/LAN ranges (default: LAN only) |
| `--open` | Open the browser viewer on this machine |
| `--out DIR` | Folder for downloaded/pushed files (default `webcam-media`) |
| `--camera NAME` | Camera to act on |
| `--list` / `--pull` | Non-interactive: print / download the phone's library, then exit (`--kind photo\|video`, `--delete-after`, `--wait SEC`, `--all-cameras --expect N` for every camera into its own sub-folder) |
| `--cert F --key F` | Use your own certificate instead of the generated CA (e.g. from `mkcert`) |
| `--serve-app [DIR]` | Also serve the PWA from the hub (camera works; no offline install because of the certificate) |
| `--no-tls` | Plain `ws://` — only for an `http://` page such as `--serve-app` with a flag-enabled browser; rarely useful |
| `--quiet` | No banner/QR (scripts) |

Interactive prompt: `cams`, `use <camera>`, `state`, `snap [quality]`, `rec start|stop`, `stream on|off [fps]`, `set key=value …` (e.g. `res=1920x1080 fps=30 facing=user zoom=2 torch=true bitrate=low codec=vp9`), `frame`, `ls [photo|video]`, `get <#|name|latest|all>`, `pull`, `rm <#|name>`, `quit`.

### Python API
```python
from webcam_hub import Hub
hub = Hub(port=8765, name="office").start()
hub.wait_for_phone()                       # or wait_for_phone(camera="kitchen")

hub.cameras()                              # [{'name': 'kitchen', 'ip': ..., 'video': ..., 'recording': False}]
cam = hub.camera("kitchen")                # a view: every call below also works as cam.snapshot() etc.
hub.use("kitchen")                         # or set a default; or pass camera="gar*" to any call

hub.set(res="1920x1080", torch=True)       # any setting the app shows
jpeg = hub.snapshot()                      # bytes
hub.stream(True, fps=10); frame = hub.latest_frame(wait=2)       # preview JPEG
hub.rec_start(); ...; data, name = hub.rec_stop_and_fetch()

hub.library(kind=None)                     # photos/clips stored on the phone, newest first
hub.download("latest", "out_dir"); hub.download_all("out_dir", kind="video", delete_after=False)
hub.delete(items)                          # no confirmation!
hub.on_frame = lambda jpeg, camera: ...    # callbacks: on_frame, on_file(path, meta), on_camera(name, connected)
```
With several cameras connected, a call without `camera=`/`use()` raises `LookupError` listing the names.

### HTTP API (curl or any language)
```bash
curl -k "https://IP:8765/cameras"                              # connected cameras
curl -k "https://IP:8765/files?camera=kitchen"                 # JSON library listing
curl -k "https://IP:8765/files/photo/<id>" -o a.jpg            # one file (add &download=1 for save-as)
curl -k "https://IP:8765/files/latest?kind=video" -o clip.mp4
curl -k "https://IP:8765/files.zip?kind=photo" -o photos.zip   # or ?items=photo:ID,video:ID
curl -k "https://IP:8765/snapshot?quality=0.9" -o now.jpg      # fresh photo (also saved on the phone)
```
`?camera=NAME` selects a camera (default: the only one, or `use()`). With `--token`, add `?token=T` or `Authorization: Bearer T`. Downloads are size-verified and written via a `.part` file; `download_all` skips files already present and never overwrites a different file with the same name. (`-k` is only needed until the OS trusts the hub CA.)

## Browser viewer (mirror on the PC)

`python python/webcam_hub.py --open`, or open `https://localhost:8765/view` (`?token=T` if you use a token). It mirrors the phone: live view, Snap, Record, *Get last recording*, every camera/recording/stream setting (kept in sync both ways with the phone), a **Library** tab (thumbnails, per-file download, select → ZIP, *All as ZIP*, preview, delete) and a camera picker when several are connected (`/view?camera=kitchen` preselects). Keys: Space = snap, R = record. The phone's preview stream is switched on automatically while someone watches. From Python: `hub.open_viewer()`.

## Getting photos and clips to the PC

| Way | How |
|---|---|
| Browser viewer | Library tab |
| Phone app | Library → item or **Select** → **→ PC** (saved in the hub's `--out` folder) |
| CLI | `--pull --out photos --kind photo [--delete-after]`, or `ls` / `get` / `pull` / `rm` at the prompt |
| Python | `hub.library()`, `hub.download(...)`, `hub.download_all(...)` |
| HTTP | `/files`, `/files/<kind>/<id>`, `/files/latest`, `/files.zip` |

## Security

* The hub is **open by default**: anyone on your LAN can view the camera and fetch its files. On shared networks use `--token auto` (phones then need the token: the field in the app, or the link/QR the hub prints).
* Clients outside private ranges (RFC 1918, link-local, 100.64/10, loopback) are rejected unless `--allow-public`.
* The generated CA lives in `~/.webcam-hub/` (`ca.key` is private; keep it there). It can only vouch for private IPs and `localhost`.
* Don't expose the hub port to the internet.

## Versioning, offline, deploy

* The version lives in `version.js`; `python tools/bump.py patch|minor|major`, then push to `main`.
* CI (`.github/workflows/pages.yml`) deploys to GitHub Pages and stamps a build id, so every deploy refreshes installed apps (new service-worker cache, old caches deleted, page reloads). *App → Update / clear cache* forces it. Pages setup: *Settings → Pages → Source: GitHub Actions*.
* After the first load the app works with no internet.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Status flashes *connecting → offline*, "code 1006" in a few ms | The phone doesn't trust the hub certificate (or wrong IP/port). Use **Test connection**: it says whether the host is unreachable (firewall / other network) or refused. Install the CA or open `https://<hub-ip>:8765/` and accept once. The hub prints each request and TLS failure in its console |
| *Find hub on LAN* finds nothing | The phone must trust the hub cert (CA install); same Wi-Fi (no guest/AP isolation); the hub's firewall must allow the port; type a **Scan range** if the app can't work out the network |
| Several hubs, wrong one chosen | Set the app's **Hub name** (or start hubs with distinct `--name`s) |
| `LookupError: several cameras connected` | Pass `camera="name"` or call `hub.use("name")` |
| Update not showing on the phone | *App → Update / clear cache* (needs the page reachable once) |
| Certificate rejected after the hub's IP changed | Restart the hub: it issues a new leaf certificate for the new IP; the installed CA stays valid |

## Limits

* The phone can't be the server (browser limitation) and TLS can't be removed (camera access requires HTTPS).
* A clip opened in the viewer, a ZIP, or an HTTP download is first fetched from the phone into the hub's memory; very large clips use that much RAM there (the Python API and `--pull` write straight to disk).
* Video is JPEG-per-frame for live view (simple, robust, bandwidth-heavy at high fps); recordings use the phone's H.264/VP9 encoder.
* Android may throttle the page when the screen is off; the app holds a screen wake lock while open.
* Camera controls depend on what the browser/device exposes. Not tested on iOS.

## Repository layout

```
index.html style.css app.js          PWA: UI, camera, library, hub client, LAN scan
sw.js manifest.webmanifest icons/    offline + install
version.js                           single source of the app version (+ CI build id)
python/webcam_hub.py                 hub: WSS server, CA, Python API, CLI, HTTP API
python/viewer.html                   browser viewer served by the hub
python/requirements.txt              websockets, cryptography, (qrcode)
tools/bump.py  tools/make_icons.py   version bump, icon generator
.github/workflows/pages.yml          deploy to GitHub Pages
docs/SPEC.md                         protocol and design
```
