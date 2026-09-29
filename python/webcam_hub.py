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

CLI:  python webcam_hub.py --port 8765        (type `help` at the prompt)

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
import secrets
import socket
import ssl
import struct
import sys
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


class Hub:
    """Threaded facade around an asyncio WebSocket server; safe to call from any thread."""

    def __init__(self, port=8765, host="0.0.0.0", token=None, tls=True, cert=None, key=None,
                 app_dir=None, app_url=APP_URL, quiet=False):
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

    def start(self):
        threading.Thread(target=lambda: asyncio.run(self._main()), daemon=True, name="webcam-hub").start()
        self._ready.wait(10)
        if not self.quiet:
            self.print_banner()
        return self

    def print_banner(self):
        print(f"\nWebcam hub listening on {self.scheme}://{self.ip}:{self.port}   token: {self.token}")
        print(f"  1) On the phone (once, self-signed cert): open {self.trust_url} and accept the warning")
        print(f"  2) Open the app: {self.connect_url}\n")
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

    def _http(self, connection, request):
        """Plain HTTP(S) on the same port: /  -> status page (for cert trust); other paths -> app files."""
        if not self.quiet:
            print(f"[hub] {connection.remote_address[0]} -> {request.path.split('?')[0]}")
        if request.path.split("?")[0] == "/ws":
            return None
        path = request.path.split("?")[0]
        if self.app_dir:
            f = (self.app_dir / (path.lstrip("/") or "index.html")).resolve()
            if f.is_file() and self.app_dir in f.parents:
                ctype = mimetypes.guess_type(f.name)[0] or "application/octet-stream"
                return Response(200, "OK", Headers([("Content-Type", ctype), ("Cache-Control", "no-cache")]), f.read_bytes())
        if path == "/":
            body = f"<h1>Webcam hub OK</h1><p>Certificate accepted. Return to the app.</p><p>{self.connect_url}</p>".encode()
            return Response(200, "OK", Headers([("Content-Type", "text/html")]), body)
        return Response(404, "Not Found", Headers(), b"not found")

    # ---------- socket handling ----------
    async def _handler(self, ws):
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
        if not self.quiet:
            print(f"[hub] phone connected: {hello.get('name')} (app v{hello.get('version')})")
        try:
            async for msg in ws:
                if isinstance(msg, bytes):
                    self._binary(msg)
                else:
                    self._json(json.loads(msg))
        except Exception:
            pass
        finally:
            if self._ws is ws:
                self._ws = None
                self._phone_evt.clear()
                if not self.quiet:
                    print("[hub] phone disconnected")

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
        elif t == "error":
            if not self.quiet:
                print("[phone error]", m.get("msg"))
            for k, f in list(self._waiters.items()):
                if not f.done():
                    f.set_exception(RuntimeError(m.get("msg", "phone error")))
                    self._waiters.pop(k, None)

    def _resolve(self, key, value):
        f = self._waiters.pop(key, None)
        if f and not f.done():
            f.set_exception(value) if isinstance(value, Exception) else f.set_result(value)

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
    ap.add_argument("--out", default=".", help="where snap/rec files are saved")
    a = ap.parse_args()

    hub = Hub(port=a.port, token=a.token, tls=not a.no_tls, cert=a.cert, key=a.key, app_dir=a.serve_app)
    if a.serve_app:
        hub.app_url = f"{hub.trust_url}"
    hub.start()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    help_txt = ("commands: state | snap [quality] | rec start | rec stop | stream on|off [fps] | "
                "set key=value ... (res=1280x720 fps=30 facing=user zoom=2 torch=true) | frame | quit")
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
            elif c == "set":
                hub.set(**{k: conv(v) for k, v in (x.split("=", 1) for x in line[1:])})
            else:
                print(help_txt)
        except Exception as e:  # keep the prompt alive
            print("error:", e)


if __name__ == "__main__":
    sys.exit(cli())
