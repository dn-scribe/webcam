#!/usr/bin/env python3
"""Protocol test: the Java harness (real HubSession/LibraryStore, fake camera) against the real Python hub.
usage: driver.py CLASSES_DIR JAVA_CLASSPATH   (run by `make test-protocol`)"""
import io, json, os, shutil, ssl, subprocess, sys, tempfile, time, urllib.request, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
from webcam_hub import Hub

CLASSES, CP = sys.argv[1], sys.argv[2]
ok = True
def check(label, cond, extra=""):
    global ok
    ok = ok and bool(cond)
    print(("PASS " if cond else "FAIL ") + label + (f"  [{extra}]" if extra and not cond else ""))

def phone(port, tls, name, token, work):
    p = subprocess.Popen(["java", "-cp", f"{CLASSES}:{CP}", "Harness", "run", "127.0.0.1", str(port), str(tls).lower(), name, token, str(work)],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    return p
def run_probe(port, tls):
    r = subprocess.run(["java", "-cp", f"{CLASSES}:{CP}", "Harness", "probe", "127.0.0.1", str(port), str(tls).lower()], capture_output=True, text=True, timeout=30)
    return [l for l in r.stdout.splitlines() if l.startswith("PROBE")][-1]

def scenario(tls, port):
    tag = "wss" if tls else "ws"
    tmp = Path(tempfile.mkdtemp(prefix="wc-test-"))
    got = []
    hub = Hub(port=port, tls=tls, name="testhub", quiet=True, save_dir=tmp / "pushed"); hub.on_file = lambda p, m: got.append((p, m)); hub.start()
    ph = phone(port, tls, "kitchen", "", tmp / "phone")
    try:
        check(f"[{tag}] camera connects", hub.wait_for_phone(15, camera="kitchen"))
        check(f"[{tag}] camera listed with name", [c["name"] for c in hub.cameras()] == ["kitchen"])
        st = hub.get_state()
        check(f"[{tag}] state has caps/settings/video", "zoom" in st.get("caps", {}) and st.get("settings", {}).get("res") == "1280x720" and st.get("video", {}).get("w") == 1280, json.dumps(st)[:200])
        hub.set(res="1920x1080", zoom=2.5); time.sleep(0.6)
        check(f"[{tag}] set() reaches the device and state reflects it", hub.get_state().get("settings", {}).get("zoom") == 2.5)
        jpg = hub.snapshot()
        check(f"[{tag}] snapshot() returns a JPEG", jpg[:2] == b"\xff\xd8" and len(jpg) > 500, len(jpg))
        hub.stream(True, fps=10); fr = hub.latest_frame(wait=4)
        check(f"[{tag}] preview stream frames arrive (0x01)", fr and fr[:2] == b"\xff\xd8")
        hub.stream(False)
        # clock sync, marks and recording metadata (the printer integration)
        ph.stdin.write("skew 5000\n"); ph.stdin.flush(); time.sleep(0.4)
        ck = hub.clock_sync(6)
        check(f"[{tag}] clock_sync finds the phone's clock offset (+5 s)", abs(ck["offset_ms"] - 5000) < max(30, ck["rtt_ms"]) and ck["rtt_ms"] < 500, ck)
        ack0 = hub.mark("before any recording")
        check(f"[{tag}] a mark with no recording running is acknowledged as not recorded", ack0["recording"] is False, ack0)
        hub.rec_start(label="run0204 test"); time.sleep(0.8)
        t_before = time.time()
        a1 = hub.mark("layer 1"); time.sleep(0.3); a2 = hub.mark("layer 2")
        check(f"[{tag}] marks during a recording are kept (rec_ms grows)", a1["recording"] and a2["recording"] and a2["rec_ms"] > a1["rec_ms"] > 0, (a1, a2))
        time.sleep(0.3)
        data, name = hub.rec_stop_and_fetch()
        check(f"[{tag}] recording stop + rec send (0x03)", len(data) == 700_000 and name.endswith(".mp4"), (len(data), name))
        check(f"[{tag}] the label is in the clip's file name", "run0204_test" in name, name)
        meta = hub.rec_meta()
        check(f"[{tag}] rec_meta: label, clocks at the first frame, marks in order", meta["label"] == "run0204 test" and meta["started"] and
              [m["text"] for m in meta["marks"]] == ["layer 1", "layer 2"] and meta["marks"][0]["rec_ms"] < meta["marks"][1]["rec_ms"] and meta["duration_ms"] > 1000, meta)
        check(f"[{tag}] start_wall_ms is on the phone's (skewed) clock", abs(meta["start_wall_ms"] / 1000.0 - (t_before - 0.8 + 5.0)) < 1.5, (meta["start_wall_ms"], t_before))
        ph.stdin.write("skew 0\n"); ph.stdin.flush()
        lib = hub.library()
        kinds = sorted(i["kind"] for i in lib)
        check(f"[{tag}] library listing (photo + video)", kinds == ["photo", "video"], kinds)
        p = hub.download("latest", tmp / "dl", kind="photo")
        check(f"[{tag}] library download (0x04) equals the snapshot", p.read_bytes() == jpg)
        files = hub.download_all(tmp / "all")
        check(f"[{tag}] download_all", len(files) == 2 and {f.suffix for f in files} == {".jpg", ".mp4"})
        # HTTP API through the hub
        ctx = ssl._create_unverified_context()
        base = f"{'https' if tls else 'http'}://127.0.0.1:{port}"
        j = json.loads(urllib.request.urlopen(base + "/files", context=ctx).read())
        check(f"[{tag}] GET /files", len(j["items"]) == 2 and j["camera"] == "kitchen")
        z = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(base + "/files.zip", context=ctx).read()))
        check(f"[{tag}] GET /files.zip", len(z.namelist()) == 2 and z.testzip() is None)
        check(f"[{tag}] GET /snapshot", urllib.request.urlopen(base + "/snapshot", context=ctx).read()[:2] == b"\xff\xd8")
        # thumbnails (viewer path): ask the phone directly through the hub socket
        # push from the phone ("-> PC")
        ph.stdin.write("push\n"); ph.stdin.flush(); t0 = time.time()
        while not got and time.time() - t0 < 10: time.sleep(0.1)
        check(f"[{tag}] phone -> PC push stored by the hub", got and got[0][0].exists() and got[0][1].get("camera") == "kitchen", got)
        n0 = len(hub.library()); hub.delete(hub.library()[0]); time.sleep(0.5)
        check(f"[{tag}] delete from the hub", len(hub.library()) == n0 - 1)
        ctx0 = ssl._create_unverified_context()
        base0 = f"{'https' if tls else 'http'}://127.0.0.1:{port}"
        jclock = json.loads(urllib.request.urlopen(base0 + "/clock?n=3", context=ctx0).read())
        jmeta = json.loads(urllib.request.urlopen(base0 + "/rec_meta", context=ctx0).read())
        urllib.request.urlopen(base0 + "/rec?action=start&label=http1", context=ctx0).read(); time.sleep(0.7)
        jmark = json.loads(urllib.request.urlopen(base0 + "/mark?text=from+http", context=ctx0).read())
        urllib.request.urlopen(base0 + "/rec?action=stop", context=ctx0).read(); time.sleep(1.0)
        check(f"[{tag}] HTTP /clock, /rec_meta, /rec, /mark", "offset_ms" in jclock and jmeta["label"] == "run0204 test" and jmark["recording"] is True and
              hub.library("video")[0]["name"].find("http1") > 0, (jclock, jmeta, jmark))
        bad = hub.cameras()
        # discovery probe
        check(f"[{tag}] HubFinder.probe sees the hub name", run_probe(port, tls) == "PROBE testhub|false|1", run_probe(port, tls))
    finally:
        ph.stdin.write("quit\n"); ph.stdin.flush()
        try: ph.wait(5)
        except Exception: ph.kill()
        shutil.rmtree(tmp, ignore_errors=True)

def token_scenario(port):
    tmp = Path(tempfile.mkdtemp(prefix="wc-test-"))
    hub = Hub(port=port, token="s3cret", name="secure", quiet=True); hub.start()
    bad = phone(port, True, "nope", "wrong", tmp / "a"); time.sleep(3)
    check("[token] wrong token is rejected", not hub.cameras())
    bad.stdin.write("quit\n"); bad.stdin.flush(); bad.wait(5)
    good = phone(port, True, "yes", "s3cret", tmp / "b")
    check("[token] right token is accepted", hub.wait_for_phone(10, camera="yes"))
    check("[token] probe reports tokenRequired", run_probe(port, True) == "PROBE secure|true|1", run_probe(port, True))
    good.stdin.write("quit\n"); good.stdin.flush(); good.wait(5)
    shutil.rmtree(tmp, ignore_errors=True)

def multi_scenario(port):
    tmp = Path(tempfile.mkdtemp(prefix="wc-test-"))
    hub = Hub(port=port, name="multi", quiet=True); hub.start()
    a = phone(port, True, "cam", "", tmp / "a"); b = phone(port, True, "cam", "", tmp / "b")
    time.sleep(4)
    names = sorted(c["name"] for c in hub.cameras())
    check("[multi] same-name cameras on one IP replace each other (same device)", names == ["cam"], names)
    for p in (a, b):
        p.stdin.write("quit\n"); p.stdin.flush()
        try: p.wait(5)
        except Exception: p.kill()
    shutil.rmtree(tmp, ignore_errors=True)

def reconnect_scenario(port):
    """The hub restarts (or the Wi-Fi drops): the phone must find its way back by itself."""
    tmp = Path(tempfile.mkdtemp(prefix="wc-test-"))
    code = f"import sys,time; sys.path.insert(0,{str(ROOT / 'python')!r}); from webcam_hub import Hub; Hub(port={port}, name='flaky', quiet=True).start(); time.sleep(600)"
    hub = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2.5)
    ph = phone(port, True, "survivor", "", tmp / "p")
    welcomes = []
    import threading
    threading.Thread(target=lambda: [welcomes.append(l) for l in ph.stdout if l.startswith("WELCOME")], daemon=True).start()
    t0 = time.time()
    while not welcomes and time.time() - t0 < 15: time.sleep(0.2)
    check("[reconnect] first connection", len(welcomes) == 1)
    hub.kill(); hub.wait(); time.sleep(2.5)
    hub = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    t0 = time.time()
    while len(welcomes) < 2 and time.time() - t0 < 40: time.sleep(0.3)
    check("[reconnect] phone reconnects by itself after the hub restarts", len(welcomes) >= 2, f"{len(welcomes)} welcomes after {time.time()-t0:.0f}s")
    ph.stdin.write("quit\n"); ph.stdin.flush()
    try: ph.wait(5)
    except Exception: ph.kill()
    hub.kill(); shutil.rmtree(tmp, ignore_errors=True)

scenario(True, 18765)
scenario(False, 18766)
token_scenario(18767)
multi_scenario(18768)
reconnect_scenario(18769)
print("ALL PASSED" if ok else "SOME TESTS FAILED")
sys.exit(0 if ok else 1)
