# TODO, risks and the first debug session

Status: the native Android app compiles against the Android 14 API and its protocol/library/discovery/reconnect code passes `make test-protocol`
against the real hub. **Nothing has run on a phone yet** (the sessions so far had no SDK and no route to the phone). This file is the plan for the
first on-device session.

## 1. First on-device session (playbook)

Run on the machine the phone is attached to (`adb devices` must list it). Paste any failing output back into the session.

| # | Do | Expect | If not |
|---|---|---|---|
| 1 | `make phone-info` | model, Android version/SDK, lock state, camera list | note the Android version: it decides which background rules apply |
| 2 | `make sdk` (once) then `make apk` | `build/android/webcam-<version>.apk` | the **packaging pipeline was never run**: `aapt2`/`d8`/`zipalign`/`apksigner` errors are likely to be small flag or path fixes. d8 may warn about OkHttp's optional classes (conscrypt, bouncycastle…): warnings are fine, errors are not |
| 3 | `make dev` (= apk + install + provision + run) | app opens, permission prompts already granted, notification "Webcam running" | `make logs`; `make provision-status` |
| 4 | `python python/webcam_hub.py --open` on the PC | phone appears as a camera, status bar shows the hub, viewer shows live video | check same Wi-Fi/subnet; try *Find hub on the LAN* in ⚙; `--no-tls` + untick TLS to rule out TLS |
| 5 | In the viewer: Snap, Record/Stop/Get, change resolution/aspect, zoom, torch, switch camera | photo downloads; clip plays; settings reflect | `make logs` for Camera2 errors (the app also sends them to the hub as `error`) |
| 6 | **Orientation:** hold portrait, landscape, upside-down; try ⚙ → Orientation | photos/clips/stream upright | the sign convention in `CameraController.needRotation()` is derived from the Camera2 docs, not tested: if rotated 90°/180°, flip it there |
| 7 | **Preview** on the phone is upright and undistorted in portrait and landscape | | `MainActivity.configureTransform` is the Google sample logic, untested here |
| 8 | **Lock-screen test:** `make lock`, wait 2 min, then from the PC `snapshot()` / viewer live view | still streaming, snapshot works | check the notification is still there, `make provision-status`, vendor battery settings |
| 9 | **Background test:** press Home, open other apps, repeat step 8 | same | |
| 10 | **Kill test:** `make kill` (or swipe the app away), then `make provision-status` | service survives or is restarted | expected to be the weak spot: see 2.1 |
| 11 | **Recovery:** `make watch` in another terminal, then `make stop` | the watcher wakes the phone and restarts the app; camera back in the viewer | `isForeground=true` text in `dumpsys` is an assumption; adjust the grep in the Makefile |
| 12 | Leave it plugged in for a few hours | still connected | heat/battery/Wi-Fi sleep: note when and how it died |

Useful while debugging: `make shot` (phone screenshot), `make logs`, `make wifi-adb PHONE_IP=…` (adb over Wi-Fi), `make lock` / `make wake`.

## 2. Known risks (unverified)

1. **Camera after the process is killed (Android 12+).** A service restarted by the system in the background cannot open the camera. Mitigations in place: the activity can be started from the PC over the lock screen (`showWhenLocked`/`turnScreenOn`), `make watch`. Not built: hub-side watchdog, "tap to restore" notification with a full-screen intent, overlay-permission start. Also no auto-start after reboot (not allowed for camera services).
2. **Vendor battery managers** (Xiaomi, Huawei, Oppo, Samsung…) can kill the service regardless of `provision`; needs per-vendor settings by hand.
3. **Camera2 session combinations.** The session tries preview+YUV+JPEG(+recorder) and falls back (drop preview → drop JPEG → drop YUV). On weak (LEGACY-level) devices recording may lose live-stream/photos; the fallbacks themselves are untested.
4. **Photos while recording** come from stream frames (stream resolution), not full-size JPEGs.
5. **Aspect crop for clips:** clips use the nearest natively recordable aspect, they are not software-cropped like photos and the stream (the PWA does crop clips).
6. **Foreground-service types on Android 14+:** the app declares `camera|microphone` and starts with `camera` only when the microphone permission is missing; first-run permission ordering is untested.
7. **TLS:** the app accepts any hub certificate on private addresses (no pinning). Fine for a LAN with a token; document before exposing elsewhere.
8. `make sdk` (command-line-tools download URL/version) and `deploy-gil` were never run.

## 3. Planned work (rough priority)

- [ ] Get the first device session done (section 1) and fix what it finds.
- [ ] Hub-side **adb watchdog**: `webcam_hub.py --adb SERIAL`: if a named camera is missing for N seconds, run `adb shell input keyevent KEYCODE_WAKEUP` + `am start`. (Today: `make watch`.)
- [ ] Recovery without a PC: full-screen-intent notification / overlay start, so the phone can bring its own camera back.
- [ ] Intent extras for remote control (`am start -n … --es action stop|start`, `--es hub 192.168…`) so provisioning can configure the app without touching it.
- [ ] Hub: `/stream.mjpg` and `/frame.jpg` so OpenCV/VLC/Home Assistant/Frigate can consume a camera directly; optional RTSP/WebRTC.
- [ ] Hub: mount into an existing ASGI/aiohttp server on one port instead of owning its own port.
- [ ] Release signing + `make release`; versioned APK on GitHub releases.
- [ ] In-app: pinch-to-zoom, tap-to-focus, thumbnails grid, hub error/status screen, battery/thermal readout sent in `state`.
- [ ] Crash/ANR reporting to the hub (`error` message) and a ring buffer of recent logs the hub can fetch (`get_log`).
- [ ] Headless experiment: run the same Camera2 + protocol code as an `app_process` shell-user process (scrcpy-style), no APK/foreground-service rules. Android 14 may block shell-user camera access.
- [ ] PWA: the same `watch`-style hints (it cannot run in the background; keep as the convenient/no-install option).

## 4. Housekeeping
- [ ] Keep `docs/SPEC.md` in sync when the protocol changes; add a protocol test for any new message.
- [ ] CI: run `make check` and `make test-protocol` in GitHub Actions (needs only JDK + python).
