#!/usr/bin/env python3
"""Webcam Hub — the Python side of the IP Webcam PWA.

The phone (browser) cannot listen on a port, so the hub listens and the phone connects
out to it over WSS on your LAN.  See docs/SPEC.md for the protocol.

Library use:
    from webcam_hub import Hub
    hub = Hub(port=8765); hub.start(); hub.wait_for_phone()
    hub.set(res="1920x1080", torch=True)
    jpeg = hub.snapshot()
    hub.stream(True, fps=10); frame = hub.latest_frame()
    hub.rec_start(); ...; data, name = hub.rec_stop_and_fetch()

Library (photos/clips stored on the phone):
    hub.library(); hub.download("latest", "out_dir"); hub.download_all("out_dir", kind="video"); hub.delete(items)
    HTTP:  curl -k "https://IP:PORT/files?token=T"   /files/photo/<id>   /files/latest?kind=video   /files.zip   /snapshot
CLI:  python webcam_hub.py --port 8765        (type `help` at the prompt)
      python webcam_hub.py --pull --out photos --kind photo      (download everything, then exit)

Requires: pip install websockets cryptography   (optional: qrcode)
"""
from __future__ import annotations

import argparse
import asyncio
import datetime
import http
import ipaddress
import json
import mimetypes
import io
import re
import secrets
import socket
import ssl
import struct
import sys
import webbrowser
import zipfile
from urllib.parse import parse_qs, urlparse
import threading
from pathlib import Path

from websockets.asyncio.server import serve
from websockets.datastructures import Headers
from websockets.http11 import Response

PROTO = 1
APP_URL = "https://dn-scribe.github.io/webcam/"
CERT_DIR = Path.home() / ".webcam-hub"


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packets sent; just picks the outbound interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def ensure_cert(ip: str) -> tuple[Path, Path]:
    """Create (once per LAN IP) a self-signed cert valid for that IP, localhost and 127.0.0.1."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    CERT_DIR.mkdir(exist_ok=True)
    crt, key = CERT_DIR / f"hub-{ip}.crt", CERT_DIR / f"hub-{ip}.key"
    if crt.exists() and key.exists():
        return crt, key
    k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "webcam-hub")])
    now = datetime.datetime.now(datetime.timezone.utc)
    san = x509.SubjectAlternativeName(
        [x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
         x509.IPAddress(ipaddress.ip_address(ip))])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(k.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=825)).add_extension(san, critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(k, hashes.SHA256()))
    crt.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key.write_bytes(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                                    serialization.NoEncryption()))
    key.chmod(0o600)
    return crt, key


class _Sink:
    """Collects the chunks of one file transfer, in memory or straight to disk."""

    def __init__(self, dest: Path | None = None):
        self.dest, self.n, self.mem = dest, 0, io.BytesIO() if dest is None else None
        self.f = None
        if dest is not None:
            dest.parent.mkdir(parents=True, exist_ok=True)
            self.tmp = dest.with_name(dest.name + ".part")
            self.f = open(self.tmp, "wb")

    def write(self, b: bytes):
        self.n += len(b)
        (self.f or self.mem).write(b)

    def finish(self, size: int):
        if self.f:
            self.f.close()
        if self.n != size:
            self.abort()
            raise RuntimeError(f"transfer incomplete ({self.n}/{size} bytes)")
        if self.f:
            self.tmp.replace(self.dest)
            return self.dest
        return self.mem.getvalue()

    def abort(self):
        if self.f:
            self.f.close()
            self.tmp.unlink(missing_ok=True)


def _safe_name(name: str) -> str:
    return re.sub(r'[\\/:*?"<>|]', "_", name) or "file"


class Hub:
    """Threaded facade around an asyncio WebSocket server; safe to call from any thread."""

    def __init__(self, port=8765, host="0.0.0.0", token=None, tls=True, cert=None, key=None,
                 app_dir=None, app_url=APP_URL, quiet=False, save_dir="webcam-media"):
        self.port, self.host, self.tls = port, host, tls
        self.token = token if token is not None else secrets.token_urlsafe(6)
        self.ip = lan_ip()
        self.cert, self.key = cert, key
        self.app_dir = Path(app_dir).resolve() if app_dir else None
        self.app_url, self.quiet = app_url, quiet
        self.state: dict = {}            # last `state` message from the phone
        self.on_frame = None             # callback(jpeg_bytes)
        self._frame = None
        self._frame_evt = threading.Event()
        self._phone_evt = threading.Event()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._ws = None
        self._next_id = 1
        self._waiters: dict = {}         # ("snap"|"rec", id) -> asyncio.Future
        self._rec_chunks: dict = {}      # id -> {idx: bytes}
        self._ready = threading.Event()
        self.save_dir = Path(save_dir)   # where files the phone pushes ("→ PC") are stored
        self.on_file = None              # callback(path: Path, meta: dict) for pushed files
        self.library_cache: list = []    # last library listing from the phone
        self._sinks: dict = {}           # req -> _Sink for in-flight file transfers
        self._viewers: set = set()       # browser viewers connected to /ui
        self._busy: set = set()          # viewers still sending a preview frame (frames are dropped for them)
        self._auto_on = False            # hub switched the phone's stream on because a viewer is watching

    # ---------- lifecycle ----------
    @property
    def scheme(self):
        return "wss" if self.tls else "ws"

    @property
    def connect_url(self):
        base = self.app_url if self.app_url.endswith("/") else self.app_url + "/"
        return f"{base}#hub={self.ip}:{self.port}&token={self.token}&tls={int(self.tls)}"

    @property
    def trust_url(self):
        return f"{'https' if self.tls else 'http'}://{self.ip}:{self.port}/"

    @property
    def viewer_url(self):
        return f"{'https' if self.tls else 'http'}://localhost:{self.port}/view?token={self.token}"

    def open_viewer(self):
        """Open the browser viewer (mirrors the phone: live view + all controls) on this machine."""
        webbrowser.open(self.viewer_url)

    def start(self):
        threading.Thread(target=lambda: asyncio.run(self._main()), daemon=True, name="webcam-hub").start()
        self._ready.wait(10)
        if not self.quiet:
            self.print_banner()
        return self

    def print_banner(self):
        print(f"\nWebcam hub listening on {self.scheme}://{self.ip}:{self.port}   token: {self.token}")
        print(f"  1) On the phone (once, self-signed cert): open {self.trust_url} and accept the warning")
        print(f"  2) Open the app: {self.connect_url}")
        print(f"  3) Browser viewer (this PC): {self.viewer_url}\n")
        try:
            import qrcode
            q = qrcode.QRCode(border=1)
            q.add_data(self.connect_url)
            q.print_ascii(invert=True)
        except ImportError:
            print("  (pip install qrcode  to show a QR code here)")

    async def _main(self):
        self._loop = asyncio.get_running_loop()
        if not self.quiet:  # surfaces TLS handshake failures (e.g. phone rejecting the certificate)
            import logging
            logging.basicConfig(level=logging.WARNING, format="[hub %(levelname)s] %(message)s")
            logging.getLogger("websockets.server").setLevel(logging.INFO)
        ctx = None
        if self.tls:
            crt, key = (self.cert, self.key) if self.cert else ensure_cert(self.ip)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(crt, key)
        async with serve(self._handler, self.host, self.port, ssl=ctx, max_size=64 * 1024 * 1024,
                         process_request=self._http, ping_interval=15, ping_timeout=20):
            self._ready.set()
            await asyncio.Future()

    async def _http(self, connection, request):
        """Plain HTTP(S) on the same port: status page, viewer, app files and the /files API."""
        if not self.quiet:
            print(f"[hub] {connection.remote_address[0]} -> {request.path.split('?')[0]}")
        url = urlparse(request.path)
        path, q = url.path, parse_qs(url.query)
        if path in ("/ws", "/ui"):
            return None
        if path in ("/files", "/files.zip", "/snapshot") or path.startswith("/files/"):
            return await self._api(path, q, request)
        if self.app_dir:
            f = (self.app_dir / (path.lstrip("/") or "index.html")).resolve()
            if f.is_file() and self.app_dir in f.parents:
                ctype = mimetypes.guess_type(f.name)[0] or "application/octet-stream"
                return Response(200, "OK", Headers([("Content-Type", ctype), ("Cache-Control", "no-cache")]), f.read_bytes())
        if path == "/favicon.ico":
            return Response(204, "No Content", Headers(), b"")
        if path == "/view":
            page = (Path(__file__).parent / "viewer.html").read_bytes()
            return Response(200, "OK", Headers([("Content-Type", "text/html; charset=utf-8"), ("Cache-Control", "no-cache")]), page)
        if path == "/":
            body = f"<h1>Webcam hub OK</h1><p>Certificate accepted. Return to the app.</p><p><a href='/view?token={self.token}'>Open browser viewer</a></p><p>{self.connect_url}</p>".encode()
            return Response(200, "OK", Headers([("Content-Type", "text/html")]), body)
        return Response(404, "Not Found", Headers(), b"not found")

    def _reply(self, status, body=b"", ctype="text/plain", name=None, inline=True):
        h = [("Content-Type", ctype), ("Cache-Control", "no-store")]
        if name:
            h.append(("Content-Disposition", f'{"inline" if inline else "attachment"}; filename="{_safe_name(name)}"'))
        if isinstance(body, str):
            body = body.encode()
        return Response(status, http.HTTPStatus(status).phrase, Headers(h), body)

    async def _api(self, path, q, request):
        """HTTP API (token via ?token= or 'Authorization: Bearer'):
             GET /files[?kind=photo|video]               JSON listing (newest first)
             GET /files/<photo|video>/<id>[?download=1]   one file;  /files/latest?kind=photo
             GET /files.zip[?kind=..|items=photo:ID,video:ID]   several files as a ZIP
             GET /snapshot[?quality=0.9&download=1]       take a fresh JPEG now"""
        auth = request.headers.get("Authorization", "")
        tok = (q.get("token") or [""])[0] or (auth[7:] if auth.startswith("Bearer ") else "")
        if tok != self.token:
            return self._reply(401, "bad or missing token")
        if self._ws is None:
            return self._reply(503, "phone not connected")
        inline = (q.get("download") or ["0"])[0] != "1"
        kind = (q.get("kind") or [None])[0]
        try:
            if path == "/snapshot":
                quality = float(q["quality"][0]) if "quality" in q else None
                jpg = await self._asnap(quality)
                return self._reply(200, jpg, "image/jpeg", f"snap-{datetime.datetime.now():%Y%m%d-%H%M%S}.jpg", inline)
            items = await self._alib()
            if path == "/files":
                body = [dict(i, url=f"/files/{i['kind']}/{i['id']}") for i in items if not kind or i["kind"] == kind]
                return self._reply(200, json.dumps({"items": body}), "application/json")
            if path == "/files.zip":
                if "items" in q:
                    want = {tuple(x.split(":")) for x in q["items"][0].split(",") if ":" in x}
                    pick = [i for i in items if (i["kind"], str(i["id"])) in want]
                else:
                    pick = [i for i in items if not kind or i["kind"] == kind]
                if not pick:
                    return self._reply(404, "nothing to zip")
                buf = io.BytesIO()
                with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
                    seen = set()
                    for i in pick:
                        _, data = await self._afile(i)
                        n = _safe_name(i["name"])
                        if n in seen:
                            n = f"{Path(n).stem}-{i['id']}{Path(n).suffix}"
                        seen.add(n)
                        z.writestr(n, data)
                return self._reply(200, buf.getvalue(), "application/zip", f"webcam-{datetime.datetime.now():%Y%m%d-%H%M%S}.zip", False)
            parts = path.split("/")  # ['', 'files', 'latest'] or ['', 'files', kind, id]
            if len(parts) == 3 and parts[2] == "latest":
                item = next((i for i in items if not kind or i["kind"] == kind), None)
            elif len(parts) == 4:
                item = next((i for i in items if i["kind"] == parts[2] and str(i["id"]) == parts[3]), None)
            else:
                item = None
            if item is None:
                return self._reply(404, "not found")
            name, data = await self._afile(item)
            return self._reply(200, data, item.get("type") or "application/octet-stream", name, inline)
        except asyncio.TimeoutError:
            return self._reply(504, "phone did not answer in time")
        except Exception as e:
            return self._reply(502, f"error: {e}")

    # ---------- socket handling ----------
    async def _handler(self, ws):
        if ws.request.path.split("?")[0] == "/ui":
            return await self._viewer(ws)
        try:
            hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
        except Exception:
            return
        if hello.get("t") != "hello" or hello.get("token") != self.token:
            await ws.close(4401, "bad token")
            return
        if self._ws is not None:
            try:
                await self._ws.close(4000, "replaced")
            except Exception:
                pass
        self._ws = ws
        self.state = {"name": hello.get("name")}
        await ws.send(json.dumps({"t": "welcome", "proto": PROTO}))
        self._phone_evt.set()
        self._auto_on = False
        await self._bcast(json.dumps({"t": "phone", "connected": True, "name": hello.get("name")}))
        await self._auto_stream()
        if not self.quiet:
            print(f"[hub] phone connected: {hello.get('name')} (app v{hello.get('version')})")
        try:
            async for msg in ws:
                if isinstance(msg, bytes):
                    self._binary(msg)
                    if self._viewers and msg[:1] in (b"\x01", b"\x02", b"\x03"):   # file transfers (0x04) stay hub-only
                        await self._bcast(msg, frame=msg[:1] == b"\x01")
                else:
                    m = json.loads(msg)
                    self._json(m)
                    if self._viewers:
                        await self._bcast(msg)
        except Exception:
            pass
        finally:
            if self._ws is ws:
                self._ws = None
                self._phone_evt.clear()
                if not self.quiet:
                    print("[hub] phone disconnected")
                await self._bcast(json.dumps({"t": "phone", "connected": False}))

    # ---------- browser viewer ----------
    VIEWER_CMDS = {"set", "stream", "snap", "rec", "get_state", "lib_list", "lib_thumb", "lib_delete"}

    async def _bcast(self, data, frame=False):
        for v in list(self._viewers):
            if frame:      # never let a slow viewer stall the phone: drop frames while it is still busy
                if v in self._busy:
                    continue
                self._busy.add(v)
                asyncio.create_task(self._vsend(v, data, True))
            else:
                await self._vsend(v, data, False)

    async def _vsend(self, v, data, frame):
        try:
            await asyncio.wait_for(v.send(data), 5)
        except Exception:
            pass
        finally:
            if frame:
                self._busy.discard(v)

    async def _auto_stream(self):
        """Turn the phone's preview stream on while someone is watching, off when the last viewer leaves."""
        if self._ws is None:
            return
        if self._viewers and not self._auto_on:
            self._auto_on = True
            await self._send({"t": "stream", "on": True, "fps": 10, "width": 960, "quality": 0.6})
        elif not self._viewers and self._auto_on:
            self._auto_on = False
            await self._send({"t": "stream", "on": False})

    async def _viewer(self, ws):
        if parse_qs(urlparse(ws.request.path).query).get("token", [""])[0] != self.token:
            await ws.close(4401, "bad token")
            return
        self._viewers.add(ws)
        try:
            await ws.send(json.dumps({"t": "phone", "connected": self.connected, "name": self.state.get("name")}))
            if "caps" in self.state:
                await ws.send(json.dumps({"t": "state", **self.state}))
            await self._auto_stream()
            async for msg in ws:
                if isinstance(msg, str) and self._ws is not None:
                    try:
                        if json.loads(msg).get("t") in self.VIEWER_CMDS:
                            await self._ws.send(msg)
                    except ValueError:
                        pass
        except Exception:
            pass
        finally:
            self._viewers.discard(ws)
            self._busy.discard(ws)
            await self._auto_stream()

    def _binary(self, b: bytes):
        kind = b[0]
        if kind == 1:
            self._frame = b[1:]
            self._frame_evt.set()
            if self.on_frame:
                self.on_frame(self._frame)
        elif kind == 2:
            (i,) = struct.unpack(">I", b[1:5])
            self._resolve(("snap", i), b[5:])
        elif kind == 3:
            i, idx = struct.unpack(">II", b[1:9])
            self._rec_chunks.setdefault(i, {})[idx] = b[9:]
        elif kind == 4:   # library file chunk: either answers a lib_get (sink exists) or is pushed by the phone
            req, _idx = struct.unpack(">II", b[1:9])
            sink = self._sinks.get(req)
            if sink is None:
                sink = self._sinks[req] = _Sink()
                sink.pushed = True
            sink.write(b[9:])

    def _json(self, m: dict):
        t = m.get("t")
        if t == "state":
            self.state.update(m)
        elif t == "rec_status":
            self.state["recording"] = m
        elif t == "rec_file_end":
            parts = self._rec_chunks.pop(m["id"], {})
            data = b"".join(parts[i] for i in range(m["chunks"]) if i in parts)
            self._resolve(("rec", m["id"]), (data, m["name"]) if len(data) == m["size"] else RuntimeError("recording incomplete"))
        elif t == "lib":
            self.library_cache = m["items"]
            if m.get("req") is not None:
                self._resolve(("lib", m["req"]), m["items"])
        elif t == "lib_file_end":
            self._file_end(m)
        elif t == "error" and m.get("req") is not None:
            self._resolve(("file", m["req"]), RuntimeError(m.get("msg", "phone error")))
            self._resolve(("lib", m["req"]), RuntimeError(m.get("msg", "phone error")))
        elif t == "error":
            if not self.quiet:
                print("[phone error]", m.get("msg"))
            for k, f in list(self._waiters.items()):
                if not f.done():
                    f.set_exception(RuntimeError(m.get("msg", "phone error")))
                    self._waiters.pop(k, None)

    def _file_end(self, m: dict):
        req = m["req"]
        if m.get("push"):   # phone pushed a file ("→ PC"): store it
            sink = self._sinks.pop(req, None)
            if sink is None:
                return
            try:
                data = sink.finish(m["size"])
            except RuntimeError as e:
                print("[hub] push failed:", e)
                return
            self.save_dir.mkdir(parents=True, exist_ok=True)
            dest = self.save_dir / _safe_name(m["name"])
            if dest.exists():
                dest = dest.with_name(f"{dest.stem}-{req}{dest.suffix}")
            dest.write_bytes(data)
            if not self.quiet:
                print(f"[hub] received {dest} ({len(data)} bytes)")
            if self.on_file:
                self.on_file(dest, m)
        else:
            self._resolve(("file", req), m)

    def _resolve(self, key, value):
        f = self._waiters.pop(key, None)
        if f and not f.done():
            f.set_exception(value) if isinstance(value, Exception) else f.set_result(value)

    # ---------- async building blocks (run on the hub loop) ----------
    def _rid(self):
        i, self._next_id = self._next_id, self._next_id + 1
        return i

    async def _alib(self, timeout=15):
        req = self._rid()
        fut = self._waiters[("lib", req)] = self._loop.create_future()
        await self._send({"t": "lib_list", "req": req})
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._waiters.pop(("lib", req), None)

    async def _asnap(self, quality=None, timeout=20):
        i = self._rid()
        fut = self._waiters[("snap", i)] = self._loop.create_future()
        await self._send({"t": "snap", "id": i, **({"quality": quality} if quality else {})})
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._waiters.pop(("snap", i), None)

    async def _afile(self, item, dest_dir=None, timeout=900):
        """Fetch one library item from the phone -> (name, bytes) or, with dest_dir, (name, Path)."""
        req = self._rid()
        sink = self._sinks[req] = _Sink(Path(dest_dir) / _safe_name(item["name"]) if dest_dir else None)
        fut = self._waiters[("file", req)] = self._loop.create_future()
        try:
            await self._send({"t": "lib_get", "req": req, "kind": item["kind"], "id": item["id"]})
            meta = await asyncio.wait_for(fut, timeout)
            return meta["name"], sink.finish(meta["size"])
        except BaseException:
            sink.abort()
            raise
        finally:
            self._sinks.pop(req, None)
            self._waiters.pop(("file", req), None)

    # ---------- sync API ----------
    def _run(self, coro, timeout=30):
        return asyncio.run_coroutine_threadsafe(coro, self._loop).result(timeout)

    async def _send(self, obj):
        if self._ws is None:
            raise ConnectionError("phone not connected")
        await self._ws.send(json.dumps(obj))

    def wait_for_phone(self, timeout=None) -> bool:
        return self._phone_evt.wait(timeout)

    @property
    def connected(self):
        return self._phone_evt.is_set()

    def set(self, **settings):
        """e.g. set(res="1920x1080", fps=30, facing="user", zoom=2, torch=True, streamFps=15)"""
        self._run(self._send({"t": "set", "settings": settings}))

    def get_state(self, wait=0.5):
        self._run(self._send({"t": "get_state"}))
        import time
        time.sleep(wait)
        return self.state

    def stream(self, on=True, fps=None, width=None, quality=None):
        m = {"t": "stream", "on": on}
        m.update({k: v for k, v in dict(fps=fps, width=width, quality=quality).items() if v})
        self._run(self._send(m))

    def latest_frame(self, wait=None):
        """Newest preview JPEG (bytes) or None. wait=seconds blocks for a *new* frame."""
        if wait:
            self._frame_evt.clear()
            self._frame_evt.wait(wait)
        return self._frame

    def snapshot(self, quality=None, timeout=20) -> bytes:
        async def go():
            i, self._next_id = self._next_id, self._next_id + 1
            fut = self._waiters[("snap", i)] = self._loop.create_future()
            await self._send({"t": "snap", "id": i, **({"quality": quality} if quality else {})})
            return await asyncio.wait_for(fut, timeout)
        return self._run(go(), timeout + 2)

    # ---- library on the phone: list / download / delete ----
    def library(self, kind=None, timeout=15):
        """List photos and clips stored on the phone, newest first.
        Each item: {kind: 'photo'|'video', id, name, type, size, ts (ms), secs, w, h}."""
        items = self._run(self._alib(timeout), timeout + 2)
        return [i for i in items if not kind or i["kind"] == kind]

    def find(self, ref, kind=None, items=None):
        """Resolve a reference to a library item: item dict, 'latest', numeric id, or file name."""
        if isinstance(ref, dict):
            return ref
        pool = [i for i in (items if items is not None else self.library(kind)) if not kind or i["kind"] == kind]
        if str(ref) == "latest":
            hit = pool[0] if pool else None
        else:
            hit = next((i for i in pool if str(i["id"]) == str(ref) or i["name"] == str(ref)), None)
        if hit is None:
            raise KeyError(f"no library item matches {ref!r}")
        return hit

    def download(self, ref="latest", dest=None, kind=None, timeout=900):
        """Download one photo/clip from the phone.
        dest=None -> returns (bytes, name);  dest=<dir> -> saves <dir>/<name> and returns the Path."""
        item = self.find(ref, kind)
        name, out = self._run(self._afile(item, dest), timeout + 2)
        return (out, name) if dest is None else out

    def download_all(self, dest, kind=None, skip_existing=True, delete_after=False, progress=None):
        """Save every (or every `kind`) item to the folder `dest`. Returns the list of Paths written.
        skip_existing skips files already there with the same size; delete_after removes them from the phone
        once saved (only items that were verified complete)."""
        dest, saved, done, used = Path(dest), [], [], set()
        for item in self.library(kind):
            name = _safe_name(item["name"])
            target = dest / name
            if name in used or (target.exists() and target.stat().st_size != item["size"]):
                name = f"{Path(name).stem}-{item['id']}{Path(name).suffix}"   # same name, different file: keep both
                target = dest / name
            used.add(name)
            if skip_existing and target.exists() and target.stat().st_size == item["size"]:
                done.append(item)
                continue
            path = self._run(self._afile(dict(item, name=name), dest), 902)[1]
            saved.append(path)
            done.append(item)
            if progress:
                progress(item, path)
        if delete_after and done:
            self.delete(done)
        return saved

    def delete(self, refs):
        """Delete items from the phone's library (no confirmation!). refs: items / ids / names."""
        items = self.library()
        picked = [self.find(r, items=items) for r in (refs if isinstance(refs, (list, tuple)) else [refs])]
        self._run(self._send({"t": "lib_delete", "items": [{"kind": i["kind"], "id": i["id"]} for i in picked]}))
        return len(picked)

    def rec_start(self):
        self._run(self._send({"t": "rec", "action": "start"}))

    def rec_stop(self):
        self._run(self._send({"t": "rec", "action": "stop"}))

    def rec_fetch(self, timeout=600):
        """Pull the last finished recording from the phone -> (bytes, filename)."""
        async def go():
            for _ in range(100):  # wait for the phone to finish finalising the file
                last = (self.state.get("recording") or {}).get("last")
                if last and (self.state["recording"].get("state") == "idle"):
                    break
                await asyncio.sleep(0.1)
            else:
                raise RuntimeError("no finished recording")
            i = self.state["recording"]["last"]["id"]
            fut = self._waiters[("rec", i)] = self._loop.create_future()
            await self._send({"t": "rec", "action": "send", "id": i})
            return await asyncio.wait_for(fut, timeout)
        return self._run(go(), timeout + 2)

    def rec_stop_and_fetch(self):
        self.rec_stop()
        import time
        time.sleep(0.5)
        return self.rec_fetch()


# ---------- CLI ----------
def cli():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--token", help="shared secret (default: random per run)")
    ap.add_argument("--no-tls", action="store_true", help="plain ws:// (only usable from an http:// page, e.g. --serve-app)")
    ap.add_argument("--cert"); ap.add_argument("--key")
    ap.add_argument("--serve-app", nargs="?", const=str(Path(__file__).resolve().parent.parent),
                    help="also serve the PWA files from this dir (default: repo root)")
    ap.add_argument("--open", action="store_true", help="open the browser viewer on this machine")
    ap.add_argument("--out", default="webcam-media", help="folder for downloaded/pushed files (default: webcam-media)")
    ap.add_argument("--list", action="store_true", help="wait for the phone, print its library, exit")
    ap.add_argument("--pull", action="store_true", help="wait for the phone, download its whole library into --out, exit")
    ap.add_argument("--kind", choices=["photo", "video"], help="limit --list/--pull to photos or clips")
    ap.add_argument("--delete-after", action="store_true", help="with --pull: remove files from the phone once saved")
    ap.add_argument("--wait", type=float, default=120, help="seconds to wait for the phone with --list/--pull")
    ap.add_argument("--quiet", action="store_true", help="no banner/QR (for scripts)")
    a = ap.parse_args()

    hub = Hub(port=a.port, token=a.token, tls=not a.no_tls, cert=a.cert, key=a.key, app_dir=a.serve_app,
              quiet=a.quiet, save_dir=a.out)
    if a.serve_app:
        hub.app_url = f"{hub.trust_url}"
    hub.start()
    if a.open:
        hub.open_viewer()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    def show(items):
        print(f"{'#':>3}  {'kind':5}  {'size':>9}  {'time':16}  name")
        for n, i in enumerate(items, 1):
            when = datetime.datetime.fromtimestamp(i["ts"] / 1000).strftime("%Y-%m-%d %H:%M")
            extra = f" ({i['secs']:.0f}s)" if i["kind"] == "video" and i.get("secs") else ""
            print(f"{n:>3}  {i['kind']:5}  {i['size'] / 1e6:7.2f}MB  {when}  {i['name']}{extra}")
        print(f"{len(items)} item(s)")

    if a.list or a.pull:   # non-interactive mode for scripts
        if not hub.wait_for_phone(a.wait):
            sys.exit("phone did not connect")
        if a.list:
            show(hub.library(a.kind))
        else:
            files = hub.download_all(out, kind=a.kind, delete_after=a.delete_after, progress=lambda i, p: print("saved", p))
            print(f"{len(files)} new file(s) in {out}")
        return 0
    help_txt = ("commands: state | snap [quality] | rec start | rec stop | stream on|off [fps] | "
                "set key=value ... (res=1280x720 fps=30 facing=user zoom=2 torch=true) | frame | "
                "ls [photo|video] | get <#|name|latest|all> [photo|video] | pull [photo|video] | rm <#|name> ... | quit")
    listing: list = []
    print(help_txt)
    ts = lambda: datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    conv = lambda v: {"true": True, "false": False}.get(v.lower(), v) if not v.replace(".", "", 1).lstrip("-").isdigit() else (float(v) if "." in v else int(v))
    while True:
        try:
            line = input("hub> ").strip().split()
        except (EOFError, KeyboardInterrupt):
            break
        if not line:
            continue
        try:
            c = line[0]
            if c in ("quit", "exit"):
                break
            elif c == "help":
                print(help_txt)
            elif c == "state":
                print(json.dumps(hub.get_state(), indent=1)[:4000])
            elif c == "snap":
                data = hub.snapshot(float(line[1]) if len(line) > 1 else None)
                p = out / f"snap-{ts()}.jpg"; p.write_bytes(data); print("saved", p, len(data), "bytes")
            elif c == "rec" and line[1:] == ["start"]:
                hub.rec_start(); print("recording…")
            elif c == "rec" and line[1:] == ["stop"]:
                data, name = hub.rec_stop_and_fetch()
                p = out / name; p.write_bytes(data); print("saved", p, len(data), "bytes")
            elif c == "stream":
                hub.stream(line[1] == "on", int(line[2]) if len(line) > 2 else None)
            elif c == "frame":
                f = hub.latest_frame(wait=3)
                if f:
                    p = out / f"frame-{ts()}.jpg"; p.write_bytes(f); print("saved", p)
                else:
                    print("no frame (stream on first)")
            elif c == "ls":
                listing = hub.library(line[1] if len(line) > 1 else None)
                show(listing)
            elif c in ("get", "pull"):
                kind = line[2] if c == "get" and len(line) > 2 else (line[1] if c == "pull" and len(line) > 1 else None)
                ref = line[1] if c == "get" and len(line) > 1 else "all"
                if c == "pull" or ref == "all":
                    for f in hub.download_all(out, kind=kind, progress=lambda i, p: print("saved", p)):
                        pass
                    print("done ->", out)
                else:
                    if ref.isdigit() and 1 <= int(ref) <= len(listing):
                        ref = listing[int(ref) - 1]       # number from the last `ls`
                    print("saved", hub.download(ref, out, kind=kind))
            elif c == "rm":
                refs = [listing[int(r) - 1] if r.isdigit() and 1 <= int(r) <= len(listing) else r for r in line[1:]]
                if refs and input(f"delete {len(refs)} item(s) from the phone? [y/N] ").lower() == "y":
                    print("deleted", hub.delete(refs))
            elif c == "set":
                hub.set(**{k: conv(v) for k, v in (x.split("=", 1) for x in line[1:])})
            else:
                print(help_txt)
        except Exception as e:  # keep the prompt alive
            print("error:", e)


if __name__ == "__main__":
    sys.exit(cli())
