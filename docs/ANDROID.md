# Android app

A native Android app that does what the web page does, but as a real webcam: it keeps running **with the app closed, other apps in front and the screen locked**, and does not depend on Chrome. It speaks the same hub protocol ([SPEC.md](SPEC.md)), so the Python hub, CLI, HTTP API and browser viewer work unchanged.

## What it does differently from the web app

| | Web app (PWA) | Android app |
|---|---|---|
| Keeps streaming with the screen off / app in background | No (browser suspends the page) | **Yes**: foreground service (camera + microphone types), wake lock, Wi-Fi lock, notification with a *Stop* action |
| Needs Chrome, a trusted certificate, or a Chrome flag | Yes | **No**: accepts the hub's certificate for private/LAN addresses, and plain `ws://` works too (`TLS` untick for a hub started with `--no-tls`) |
| Hub discovery | LAN scan (needs trusted cert) | LAN scan always works: probes the local /24 for hubs, filters by *Hub name*, re-scans when the hub's IP changes |
| Photos | `ImageCapture` / video frame | Camera2 JPEG at full sensor resolution (or the *Photo size* limit), cropped to the chosen aspect |
| Library | IndexedDB | Files in the app's storage (`Android/data/io.github.dnscribe.webcam/files/library`), same `lib_*` protocol, share/save-to-gallery/send-to-PC |

Everything the hub can do to the PWA it can do here: `set` (facing, deviceId, res, aspect, fps, audio, bitrate, codec, photoRes, streamFps/Width/Quality, snapQuality, zoom, torch, exposureCompensation, plus `orientation`), `snap`, `rec start|stop|send|discard`, `stream`, `get_state`, `lib_list|get|thumb|delete`, push to the hub, probe/scan, camera and hub names, token.

Not the same as the PWA: `codec: vp9` is ignored (H.264, or H.265 with `codec: hevc`); the aspect crop applies to photos and the preview stream, while a clip uses the closest aspect the camera can encode natively (no software crop); while a clip is recording, photos are cut from the stream frames at stream resolution.

## Build (plain `make`, no Gradle)

Needs a JDK 17+, `python3` and internet for the first build. From the repo root:

```bash
make sdk            # once: downloads the Android command-line tools + platform 34 + build-tools (to ~/Android/Sdk, or $ANDROID_HOME)
make apk            # -> build/android/webcam-<version>.apk   (signed with a local debug key, created on first use)
make install        # adb install -r -g   (grants camera / microphone / notification permissions)
make run            # starts the app;   make logs / stop / uninstall / devices
```
`ADB_FLAGS="-s SERIAL"` picks a device when several are attached. The version comes from `version.js` (`python tools/bump.py patch`), so the app and the PWA share one version number. Already have an SDK? `export ANDROID_HOME=…` (needs `platforms;android-34` and `build-tools;34.0.0`) and skip `make sdk`.

The pipeline is `aapt2 compile/link` → `javac` → `d8` → `zipalign` → `apksigner`, with OkHttp 3.12 and Okio as the only libraries (downloaded from Maven Central, checksum-verified, cached in `build/android/libs`).

### Tests that need no SDK and no phone
```bash
make check           # compile-check the whole app against the Android 14 framework classes
make test-protocol   # unit tests + the real Java protocol code against the real Python hub
```
`test-protocol` runs the app's actual `HubSession` / `LibraryStore` / `HubFinder` (everything under `android/src/…/core`) with a fake camera, against a real hub over `wss` and plain `ws`: connect, state, `set`, snapshot (0x02), preview frames (0x01), recording + `rec send` (0x03), library list/download (0x04)/delete, phone→PC push, HTTP API (`/files`, `/files.zip`, `/snapshot`), token rejection, probe/discovery, same-name replacement, and automatic reconnect after a hub restart. Needs `pip install websockets cryptography`.

### Build and install on another machine (the GIL box)
```bash
make deploy-gil                       # rsync the tree to printer@gil.home:webcam-build, then there: make apk install run
make deploy-gil REMOTE=user@host REMOTE_DIR=somewhere ADB_FLAGS="-s SERIAL"
```
or just `git pull` on that machine and run the commands above.

## Using it

1. Start the hub on the PC: `python python/webcam_hub.py` (open mode needs no token; use `--name` if you run several hubs).
2. Open the app on the phone and allow **Camera**, **Microphone** and **Notifications**. It starts the webcam service immediately and looks for a hub on the LAN by itself (or type the address under ⚙).
3. Name the phone under ⚙ (**Camera name**, e.g. `kitchen`) so Python can pick it: `hub.camera("kitchen")`.
4. Close the app or lock the screen: the notification stays and the camera keeps streaming. *Stop* in the notification or in ⚙ ends it.

**Keep it alive:** ⚙ → *Battery optimisation…* and allow “unrestricted” battery use. On Xiaomi/Huawei/Oppo/Samsung also enable *auto-start* / *run in background* for the app in the system settings, otherwise the vendor battery manager may kill the service.

## Layout
```
android/AndroidManifest.xml  res/                 manifest (foreground service types), strings, icon
android/src/io/github/dnscribe/webcam/
  CameraService.java         foreground service: camera + library + hub session, locks, notification, watchdog (re-discovers the hub)
  CameraController.java      Camera2: preview/YUV/JPEG/recorder surfaces with fallback ladder, zoom/torch/exposure, orientation, aspect crop
  MainActivity / SettingsActivity / LibraryActivity   plain-code UIs (no XML layouts, no AndroidX)
  Prefs.java AndroidThumbs.java
  core/                      pure Java (no Android imports): HubSession (the protocol), HubFinder, LibraryStore, Net, Tls, Aspect, Device
android/test/                Harness.java (fake phone), driver.py (scenarios), UnitTests.java
android/tools/fetch.py       checksummed downloader with retries
Makefile                     all build/test/install targets
```

## Status and known risks
Verified in CI-less development: the whole app compiles against the Android 14 framework API; the protocol/library/discovery/reconnect code passes the tests above against the real hub.

**Not yet verified on a device** (nothing here could be run on a phone or with the Android SDK at the time of writing): the APK packaging steps (`aapt2`/`d8`/`zipalign`/`apksigner`), Camera2 behaviour on real hardware (session fallbacks, orientation, preview transform, recording while previewing), vendor battery-manager behaviour, and Android 14 foreground-service-type enforcement. Expect to fix small things on first contact with a phone; `make logs` shows the app's errors, and the app also reports camera errors to the hub (`error` message).
