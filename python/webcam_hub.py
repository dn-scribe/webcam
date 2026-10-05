#!/usr/bin/env python3
"""Webcam Hub — the Python side of the IP Webcam PWA.

A web page cannot listen on a port, so the hub listens and the phones connect out to it
over WSS on your LAN (TLS is needed because the browser only allows camera access, and
secure-page -> socket connections, over HTTPS).  Several phones can connect at once; each
is a *camera* with a name (set in the app) that you select by name from Python.
Open by default: no token, no pairing; only private-LAN clients are accepted.
See docs/SPEC.md for the protocol.

Library use:
    from webcam_hub import Hub
    hub = Hub(port=8765).start(); hub.wait_for_phone()
    hub.set(res="1920x1080", torch=True)
    jpeg = hub.snapshot()
    hub.stream(True, fps=10); frame = hub.latest_frame()
    hub.rec_start(); ...; data, name = hub.rec_stop_and_fetch()

Several cameras (matched by name: exact, case-insensitive, or substring / glob):
    hub.cameras()                         # [{'name': 'kitchen', 'ip': ...}, ...]
    kitchen = hub.camera("kitchen")       # a view that routes every call to that camera
    kitchen.snapshot(); hub.snapshot(camera="gar*"); hub.use("kitchen")   # default for later calls

Library (photos/clips stored on the phone):
    hub.library(); hub.download("latest", "out_dir"); hub.download_all("out_dir", kind="video"); hub.delete(items)
    HTTP:  curl -k "https://IP:PORT/files"   /files/photo/<id>   /files/latest?kind=video   /files.zip   /snapshot
           (add ?camera=NAME to pick a camera, /cameras lists them; ?token=T if you started with --token)
CLI:  python webcam_hub.py --port 8765        (type `help` at the prompt)
      python webcam_hub.py --pull --out photos --kind photo [--camera kitchen]   (download, then exit)

Requires: pip install websockets cryptography   (optional: qrcode)
"""
from __future__ import annotations

import argparse
import asyncio
import datetime
import fnmatch
import functools
import http
import io
import ipaddress
import json
import mimetypes
import re
import secrets
import socket
import ssl
import struct
import sys
import threading
import time
import webbrowser
import zipfile
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

from websockets.asyncio.server import serve
from websockets.datastructures import Headers
from websockets.http11 import Response

PROTO = 1
APP_URL = "https://dn-scribe.github.io/webcam/"
CERT_DIR = Path.home() / ".webcam-hub"
PRIVATE_NETS = [ipaddress.ip_network(n) for n in
                ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10", "127.0.0.0/8")]


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packets sent; just picks the outbound interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


# ---------------------------------------------------------------- certificates
def ensure_cert(ip: str) -> tuple[Path, Path, Path]:
    """Local CA (created once, install it on the phone once) + a leaf cert for the current LAN IP.
    The leaf is re-issued automatically whenever the IP changes; the CA is name-constrained to
    private addresses so it cannot vouch for real websites. Returns (leaf_crt, leaf_key, ca_crt)."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    CERT_DIR.mkdir(exist_ok=True)
    ca_crt, ca_key = CERT_DIR / "ca.crt", CERT_DIR / "ca.key"
    now = datetime.datetime.now(datetime.timezone.utc)
    pem = lambda k: k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                                    serialization.NoEncryption())
    if not (ca_crt.exists() and ca_key.exists()):
        k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Webcam Hub local CA"),
                          x509.NameAttribute(NameOID.ORGANIZATION_NAME, "webcam-hub")])
        permitted = [x509.IPAddress(n) for n in PRIVATE_NETS] + [x509.DNSName("localhost")]
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(k.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
                .not_valid_after(now + datetime.timedelta(days=3650))
                .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
                .add_extension(x509.KeyUsage(digital_signature=True, key_cert_sign=True, crl_sign=True,
                                             content_commitment=False, key_encipherment=False, data_encipherment=False,
                                             key_agreement=False, encipher_only=False, decipher_only=False), critical=True)
                .add_extension(x509.NameConstraints(permitted_subtrees=permitted, excluded_subtrees=None), critical=True)
                .add_extension(x509.SubjectKeyIdentifier.from_public_key(k.public_key()), critical=False)
                .sign(k, hashes.SHA256()))
        ca_crt.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        ca_key.write_bytes(pem(k))
        ca_key.chmod(0o600)
    ca_cert = x509.load_pem_x509_certificate(ca_crt.read_bytes())
    ca_priv = serialization.load_pem_private_key(ca_key.read_bytes(), None)

    crt, key = CERT_DIR / f"leaf-{ip}.crt", CERT_DIR / f"leaf-{ip}.key"
    if crt.exists() and key.exists():
        return crt, key, ca_crt
    k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    san = x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                                       x509.IPAddress(ipaddress.ip_address(ip))])
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "webcam-hub")]))
            .issuer_name(ca_cert.subject).public_key(k.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=825))
            .add_extension(san, critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=True, key_encipherment=True, content_commitment=False,
                                         data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                         crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(k.public_key()), critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_priv.public_key()), critical=False)
            .sign(ca_priv, hashes.SHA256()))
    crt.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key.write_bytes(pem(k))
    key.chmod(0o600)
    return crt, key, ca_crt


# ---------------------------------------------------------------- helpers
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


def _private(addr) -> bool:
    try:
        ip = ipaddress.ip_address(addr[0] if isinstance(addr, tuple) else addr)
        ip = ip.ipv4_mapped or ip if ip.version == 6 else ip
        return ip.is_loopback or ip.is_link_local or any(ip in n for n in PRIVATE_NETS) or ip.is_private
    except Exception:
        return False


class _Cam:
    """One connected phone."""

    def __init__(self, name, ws, ip, hello):
        self.name, self.ws, self.ip, self.hello = name, ws, ip, hello
        self.state: dict = {"name": name}
        self.frame = None
        self.frame_evt = threading.Event()
        self.library: list = []
        self.auto_on = False     # hub switched the preview stream on because a viewer is watching
        self.since = time.time()

    def info(self):
        rec = self.state.get("recording") or {}
        return {"name": self.name, "ip": self.ip, "app": self.hello.get("version"), "since": self.since,
                "video": self.state.get("video"), "streaming": self.state.get("streaming"),
                "recording": rec.get("state") == "recording"}


_CAM_METHODS = {"set", "get_state", "stream", "latest_frame", "snapshot", "rec_start", "rec_stop", "rec_fetch",
                "rec_stop_and_fetch", "library", "find", "download", "download_all", "delete", "wait_for_phone"}


class CameraView:
    """hub.camera("kitchen") -> object whose methods all target that camera."""

    def __init__(self, hub, name):
        self._hub, self.name = hub, name

    def __getattr__(self, attr):
        if attr in _CAM_METHODS:
            return functools.partial(getattr(self._hub, attr), camera=self.name)
        raise AttributeError(attr)

    def __repr__(self):
        return f"<CameraView {self.name!r}>"


class Hub:
    """Threaded facade around an asyncio WebSocket server; safe to call from any thread."""

    VIEWER_CMDS = {"set", "stream", "snap", "rec", "get_state", "lib_list", "lib_thumb", "lib_delete"}

    def __init__(self, port=8765, host="0.0.0.0", token="", tls=True, cert=None, key=None,
                 app_dir=None, app_url=APP_URL, quiet=False, save_dir="webcam-media", allow_public=False, name=None):
        """name: how this hub identifies itself to phones and viewers (default: this machine's host name).
        token: "" = open (default) | "auto" = random | any string = required of phones/viewers/HTTP API.
        allow_public=False rejects clients that are not on a private/loopback address."""
        self.port, self.host, self.tls = port, host, tls
        self.token = secrets.token_urlsafe(6) if token == "auto" else (token or "")
        self.allow_public = allow_public
        self.name = (name or socket.gethostname() or "hub")[:40]
        self.ip = lan_ip()
        self.cert, self.key, self.ca_path = cert, key, None
        self.app_dir = Path(app_dir).resolve() if app_dir else None
        self.app_url, self.quiet = app_url, quiet
        self.save_dir = Path(save_dir)   # where files the phone pushes ("→ PC") are stored
        self.on_frame = None             # callback(jpeg_bytes, camera_name)
        self.on_file = None              # callback(path: Path, meta: dict) for pushed files
        self.on_camera = None            # callback(name: str, connected: bool)
        self._cams: dict[str, _Cam] = {}
        self._cur: str | None = None     # camera chosen with use()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._next_id = 1
        self._waiters: dict = {}         # ("snap"|"rec"|"lib"|"file", id) -> asyncio.Future
        self._rec_chunks: dict = {}      # (camera, id) -> {idx: bytes}
        self._sinks: dict = {}           # (camera, req) -> _Sink for in-flight file transfers
        self._ready = threading.Event()
        self._viewers: dict = {}         # browser viewer websocket -> selected camera name (or None)
        self._busy: set = set()          # viewers still sending a preview frame (frames are dropped for them)

    # ---------- lifecycle ----------
    @property
    def scheme(self):
        return "wss" if self.tls else "ws"

    @property
    def connect_url(self):
        base = self.app_url if self.app_url.endswith("/") else self.app_url + "/"
        tok = f"&token={self.token}" if self.token else ""
        return f"{base}#hub={self.ip}:{self.port}&hubname={quote(self.name)}{tok}&tls={int(self.tls)}"

    @property
    def trust_url(self):
        return f"{'https' if self.tls else 'http'}://{self.ip}:{self.port}/"

    @property
    def ca_url(self):
        return f"https://{self.ip}:{self.port}/ca.crt"

    @property
    def viewer_url(self):
        tok = f"?token={self.token}" if self.token else ""
        return f"{'https' if self.tls else 'http'}://localhost:{self.port}/view{tok}"

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
        print(f"\nWebcam hub \"{self.name}\" on {self.scheme}://{self.ip}:{self.port}   "
              f"{'token: ' + self.token if self.token else 'OPEN: no token, LAN clients only'}")
        print(f"  Phone: open {self.app_url}, put \"{self.name}\" in its 'Hub name' field (optional) — it finds this hub on the LAN by itself,")
        print(f"         or tap through {self.connect_url}")
        if not self.tls:
            print(f"  PLAIN HTTP mode (no certificates). Chrome only allows the camera on a secure origin, so on EACH phone, once:")
            print(f"    chrome://flags/#unsafely-treat-insecure-origin-as-secure  → add  http://{self.ip}:{self.port}  → Enabled → Relaunch")
            print(f"    then open http://{self.ip}:{self.port}/ . (If the PC/router changes this IP, update the flag, or give the PC a fixed IP.)")
        if self.tls and self.ca_path:
            print(f"  First time only, to silence the certificate warning and allow auto-discovery,")
            print(f"  install the hub's CA on the phone: download {self.ca_url}, then")
            print(f"  Settings → Security → Encryption & credentials → Install a certificate → CA certificate.")
            print(f"  (Quick alternative: open {self.trust_url} once and accept the warning.)")
        print(f"  Browser viewer (this PC): {self.viewer_url}\n")
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
            if self.cert:
                crt, key = self.cert, self.key
            else:
                crt, key, self.ca_path = ensure_cert(self.ip)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(crt, key)
        async with serve(self._handler, self.host, self.port, ssl=ctx, max_size=64 * 1024 * 1024,
                         process_request=self._http, ping_interval=15, ping_timeout=20):
            self._ready.set()
            await asyncio.Future()

    # ---------- camera registry ----------
    def cameras(self, match=None):
        """Connected cameras: [{'name','ip','app','since','video','streaming','recording'}]."""
        return [c.info() for c in list(self._cams.values()) if not match or self._matches(c.name, match)]

    @staticmethod
    def _matches(name, pattern):
        n, p = name.lower(), pattern.lower()
        return n == p or fnmatch.fnmatch(n, p if any(ch in p for ch in "*?[") else f"*{p}*")

    def _find_cam(self, pattern):
        cams = self._cams
        if pattern in cams:
            return cams[pattern]
        exact = [c for n, c in cams.items() if n.lower() == pattern.lower()]
        if len(exact) == 1:
            return exact[0]
        hits = [c for n, c in cams.items() if self._matches(n, pattern)]
        names = ", ".join(cams) or "none"
        if not hits:
            raise LookupError(f"no camera matches {pattern!r} (connected: {names})")
        if len(hits) > 1:
            raise LookupError(f"{pattern!r} is ambiguous: {', '.join(c.name for c in hits)}")
        return hits[0]

    def _pick(self, camera=None) -> _Cam:
        if camera is not None:
            return self._find_cam(camera)
        if self._cur in self._cams:
            return self._cams[self._cur]
        if len(self._cams) == 1:
            return next(iter(self._cams.values()))
        if not self._cams:
            raise ConnectionError("no camera connected")
        raise LookupError(f"several cameras connected ({', '.join(self._cams)}): pass camera=NAME or call hub.use(NAME)")

    def use(self, name):
        """Make `name` (exact / substring / glob) the default camera for calls that don't pass camera=."""
        self._cur = self._find_cam(name).name
        return self._cur

    def camera(self, name) -> CameraView:
        return CameraView(self, self._find_cam(name).name)

    def wait_for_phone(self, timeout=None, camera=None) -> bool:
        """Block until a camera (any, or one matching `camera`) is connected."""
        end = None if timeout is None else time.time() + timeout
        while True:
            if (any(self._matches(n, camera) for n in list(self._cams)) if camera else self._cams):
                return True
            if end is not None and time.time() > end:
                return False
            time.sleep(0.1)

    @property
    def connected(self):
        return bool(self._cams)

    # ---------- HTTP(S) ----------
    def _tok_ok(self, tok) -> bool:
        return not self.token or tok == self.token

    def _peer_ok(self, addr) -> bool:
        return self.allow_public or _private(addr)

    async def _http(self, connection, request):
        """Plain HTTP(S) on the same port: status page, CA download, viewer, app files and the /files API."""
        peer = connection.remote_address
        if not self._peer_ok(peer):
            return Response(403, "Forbidden", Headers(), b"LAN clients only")
        if not self.quiet:
            print(f"[hub] {peer[0]} -> {request.path.split('?')[0]}")
        url = urlparse(request.path)
        path, q = url.path, parse_qs(url.query)
        if path in ("/ws", "/ui"):
            return None
        if path in ("/files", "/files.zip", "/snapshot", "/cameras") or path.startswith("/files/"):
            return await self._api(path, q, request)
        if path == "/ca.crt" and self.ca_path:
            return self._reply(200, self.ca_path.read_bytes(), "application/x-x509-ca-cert", "webcam-hub-ca.crt", False)
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
            tq = f"?token={self.token}" if self.token else ""
            ca = f"<p><a href='/ca.crt'>Download the hub CA certificate</a> (install once to remove this warning)</p>" if self.ca_path else ""
            body = (f"<h1>Webcam hub OK</h1><p>Certificate accepted. Return to the app.</p>{ca}"
                    f"<p><a href='/view{tq}'>Open browser viewer</a></p><p>{self.connect_url}</p>").encode()
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
        """HTTP API (token via ?token= or 'Authorization: Bearer' only if the hub was started with one):
             GET /cameras                                 JSON list of connected cameras
             GET /files[?kind=photo|video]                JSON listing (newest first)
             GET /files/<photo|video>/<id>[?download=1]   one file;  /files/latest?kind=photo
             GET /files.zip[?kind=..|items=photo:ID,video:ID]   several files as a ZIP
             GET /snapshot[?quality=0.9&download=1]       take a fresh JPEG now
           All camera routes accept ?camera=NAME (exact / substring / glob)."""
        auth = request.headers.get("Authorization", "")
        tok = (q.get("token") or [""])[0] or (auth[7:] if auth.startswith("Bearer ") else "")
        if not self._tok_ok(tok):
            return self._reply(401, "bad or missing token")
        if path == "/cameras":
            return self._reply(200, json.dumps({"cameras": self.cameras()}), "application/json")
        try:
            cam = self._pick((q.get("camera") or [None])[0])
        except LookupError as e:
            return self._reply(404, str(e))
        except ConnectionError:
            return self._reply(503, "no camera connected")
        inline = (q.get("download") or ["0"])[0] != "1"
        kind = (q.get("kind") or [None])[0]
        try:
            if path == "/snapshot":
                quality = float(q["quality"][0]) if "quality" in q else None
                jpg = await self._asnap(cam, quality)
                return self._reply(200, jpg, "image/jpeg", f"snap-{datetime.datetime.now():%Y%m%d-%H%M%S}.jpg", inline)
            items = await self._alib(cam)
            if path == "/files":
                body = [dict(i, url=f"/files/{i['kind']}/{i['id']}", camera=cam.name) for i in items if not kind or i["kind"] == kind]
                return self._reply(200, json.dumps({"camera": cam.name, "items": body}), "application/json")
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
                        _, data = await self._afile(cam, i)
                        n = _safe_name(i["name"])
                        if n in seen:
                            n = f"{Path(n).stem}-{i['id']}{Path(n).suffix}"
                        seen.add(n)
                        z.writestr(n, data)
                return self._reply(200, buf.getvalue(), "application/zip", f"{cam.name}-{datetime.datetime.now():%Y%m%d-%H%M%S}.zip", False)
            parts = path.split("/")  # ['', 'files', 'latest'] or ['', 'files', kind, id]
            if len(parts) == 3 and parts[2] == "latest":
                item = next((i for i in items if not kind or i["kind"] == kind), None)
            elif len(parts) == 4:
                item = next((i for i in items if i["kind"] == parts[2] and str(i["id"]) == parts[3]), None)
            else:
                item = None
            if item is None:
                return self._reply(404, "not found")
            name, data = await self._afile(cam, item)
            return self._reply(200, data, item.get("type") or "application/octet-stream", name, inline)
        except asyncio.TimeoutError:
            return self._reply(504, "phone did not answer in time")
        except Exception as e:
            return self._reply(502, f"error: {e}")

    # ---------- socket handling ----------
    async def _handler(self, ws):
        if not self._peer_ok(ws.remote_address):
            await ws.close(4403, "LAN clients only")
            return
        if ws.request.path.split("?")[0] == "/ui":
            return await self._viewer(ws)
        try:
            hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
        except Exception:
            return
        if hello.get("t") == "probe":   # LAN scan from the phone: "is there a hub here?"
            await ws.send(json.dumps({"t": "hub", "proto": PROTO, "name": self.name, "tokenRequired": bool(self.token), "cameras": len(self._cams)}))
            return
        if hello.get("t") != "hello" or not self._tok_ok(hello.get("token")):
            await ws.close(4401, "bad token")
            return
        cam = await self._register(ws, hello)
        await ws.send(json.dumps({"t": "welcome", "proto": PROTO, "name": cam.name, "hub": self.name}))
        if not self.quiet:
            print(f"[hub] camera connected: {cam.name} @ {cam.ip} (app v{hello.get('version')})")
        if self.on_camera:
            self.on_camera(cam.name, True)
        await self._send_cameras()
        await self._viewers_sync(cam)
        await self._auto_all()
        try:
            async for msg in ws:
                if isinstance(msg, bytes):
                    self._binary(cam, msg)
                    if self._viewers and msg[:1] in (b"\x01", b"\x02", b"\x03"):   # file transfers (0x04) stay hub-only
                        await self._bcast(cam, msg, frame=msg[:1] == b"\x01")
                else:
                    m = json.loads(msg)
                    self._json(cam, m)
                    if self._viewers:
                        await self._bcast(cam, msg)
        except Exception:
            pass
        finally:
            if self._cams.get(cam.name) is cam:
                del self._cams[cam.name]
                if not self.quiet:
                    print(f"[hub] camera disconnected: {cam.name}")
                if self.on_camera:
                    self.on_camera(cam.name, False)
                await self._send_cameras()
                await self._viewers_sync(cam)

    async def _register(self, ws, hello) -> _Cam:
        base = re.sub(r"[^\w .@-]", "_", str(hello.get("name") or "camera"))[:40].strip() or "camera"
        ip = ws.remote_address[0]
        name, n = base, 1
        while name in self._cams and self._cams[name].ip != ip:    # same name from another device -> suffix
            n += 1
            name = f"{base}-{n}"
        old = self._cams.get(name)
        if old is not None:                                         # same device reconnecting -> replace
            try:
                await old.ws.close(4000, "replaced")
            except Exception:
                pass
        cam = self._cams[name] = _Cam(name, ws, ip, hello)
        return cam

    # ---------- browser viewers ----------
    def _vcam(self, v):
        sel = self._viewers.get(v)
        if sel in self._cams:
            return self._cams[sel]
        if sel is None:
            if self._cur in self._cams:
                return self._cams[self._cur]
            if len(self._cams) == 1:
                return next(iter(self._cams.values()))
        return None

    async def _bcast(self, cam, data, frame=False):
        for v in [v for v in list(self._viewers) if self._vcam(v) is cam]:
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

    async def _send_cameras(self):
        for v in list(self._viewers):
            cam = self._vcam(v)
            await self._vsend(v, json.dumps({"t": "cameras", "hub": self.name, "items": [{"name": c.name, "ip": c.ip} for c in self._cams.values()],
                                             "selected": cam.name if cam else None}), False)

    async def _viewer_sync(self, v):
        cam = self._vcam(v)
        await self._vsend(v, json.dumps({"t": "phone", "connected": cam is not None, "name": cam.name if cam else None}), False)
        if cam and "caps" in cam.state:
            await self._vsend(v, json.dumps({"t": "state", **cam.state}), False)

    async def _viewers_sync(self, cam):
        for v in [v for v in list(self._viewers) if self._vcam(v) in (cam, None)]:
            await self._viewer_sync(v)

    async def _auto_all(self):
        """Phone preview stream: on while someone is watching that camera, off when the last viewer leaves."""
        watched = {self._vcam(v) for v in self._viewers}
        for cam in list(self._cams.values()):
            if cam in watched and not cam.auto_on:
                cam.auto_on = True
                await self._csend(cam, {"t": "stream", "on": True, "fps": 10, "width": 960, "quality": 0.6})
            elif cam not in watched and cam.auto_on:
                cam.auto_on = False
                await self._csend(cam, {"t": "stream", "on": False})

    async def _viewer(self, ws):
        q = parse_qs(urlparse(ws.request.path).query)
        if not self._tok_ok(q.get("token", [""])[0]):
            await ws.close(4401, "bad token")
            return
        sel = q.get("camera", [None])[0]
        try:
            sel = self._find_cam(sel).name if sel else None
        except LookupError:
            sel = sel or None
        self._viewers[ws] = sel
        try:
            await self._send_cameras()
            await self._viewer_sync(ws)
            await self._auto_all()
            async for msg in ws:
                if not isinstance(msg, str):
                    continue
                try:
                    m = json.loads(msg)
                except ValueError:
                    continue
                if m.get("t") == "select":
                    try:
                        self._viewers[ws] = self._find_cam(m.get("camera", "")).name
                    except LookupError:
                        continue
                    await self._send_cameras()
                    await self._viewer_sync(ws)
                    await self._auto_all()
                elif m.get("t") in self.VIEWER_CMDS:
                    cam = self._vcam(ws)
                    if cam:
                        await cam.ws.send(msg)
        except Exception:
            pass
        finally:
            self._viewers.pop(ws, None)
            self._busy.discard(ws)
            await self._auto_all()

    # ---------- phone -> hub messages ----------
    def _binary(self, cam: _Cam, b: bytes):
        kind = b[0]
        if kind == 1:
            cam.frame = b[1:]
            cam.frame_evt.set()
            if self.on_frame:
                self.on_frame(cam.frame, cam.name)
        elif kind == 2:
            (i,) = struct.unpack(">I", b[1:5])
            self._resolve(("snap", i), b[5:])
        elif kind == 3:
            i, idx = struct.unpack(">II", b[1:9])
            self._rec_chunks.setdefault((cam.name, i), {})[idx] = b[9:]
        elif kind == 4:   # library file chunk: either answers a lib_get (sink exists) or is pushed by the phone
            req, _idx = struct.unpack(">II", b[1:9])
            sink = self._sinks.get((cam.name, req))
            if sink is None:
                sink = self._sinks[(cam.name, req)] = _Sink()
            sink.write(b[9:])

    def _json(self, cam: _Cam, m: dict):
        t = m.get("t")
        if t == "state":
            cam.state.update(m)
        elif t == "rec_status":
            cam.state["recording"] = m
        elif t == "rec_file_end":
            parts = self._rec_chunks.pop((cam.name, m["id"]), {})
            data = b"".join(parts[i] for i in range(m["chunks"]) if i in parts)
            self._resolve(("rec", m["id"]), (data, m["name"]) if len(data) == m["size"] else RuntimeError("recording incomplete"))
        elif t == "lib":
            cam.library = m["items"]
            if m.get("req") is not None:
                self._resolve(("lib", m["req"]), m["items"])
        elif t == "lib_file_end":
            self._file_end(cam, m)
        elif t == "error" and m.get("req") is not None:
            self._resolve(("file", m["req"]), RuntimeError(m.get("msg", "phone error")))
            self._resolve(("lib", m["req"]), RuntimeError(m.get("msg", "phone error")))
        elif t == "error":
            if not self.quiet:
                print(f"[{cam.name} error]", m.get("msg"))
            for k, f in list(self._waiters.items()):
                if not f.done():
                    f.set_exception(RuntimeError(m.get("msg", "phone error")))
                    self._waiters.pop(k, None)

    def _file_end(self, cam: _Cam, m: dict):
        req = m["req"]
        if m.get("push"):   # phone pushed a file ("→ PC"): store it
            sink = self._sinks.pop((cam.name, req), None)
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
                print(f"[hub] received {dest} ({len(data)} bytes) from {cam.name}")
            if self.on_file:
                self.on_file(dest, {**m, "camera": cam.name})
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

    async def _csend(self, cam: _Cam, obj):
        await cam.ws.send(json.dumps(obj))

    async def _alib(self, cam, timeout=15):
        req = self._rid()
        fut = self._waiters[("lib", req)] = self._loop.create_future()
        await self._csend(cam, {"t": "lib_list", "req": req})
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._waiters.pop(("lib", req), None)

    async def _asnap(self, cam, quality=None, timeout=20):
        i = self._rid()
        fut = self._waiters[("snap", i)] = self._loop.create_future()
        await self._csend(cam, {"t": "snap", "id": i, **({"quality": quality} if quality else {})})
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._waiters.pop(("snap", i), None)

    async def _afile(self, cam, item, dest_dir=None, timeout=900):
        """Fetch one library item from the phone -> (name, bytes) or, with dest_dir, (name, Path)."""
        req = self._rid()
        sink = self._sinks[(cam.name, req)] = _Sink(Path(dest_dir) / _safe_name(item["name"]) if dest_dir else None)
        fut = self._waiters[("file", req)] = self._loop.create_future()
        try:
            await self._csend(cam, {"t": "lib_get", "req": req, "kind": item["kind"], "id": item["id"]})
            meta = await asyncio.wait_for(fut, timeout)
            return meta["name"], sink.finish(meta["size"])
        except BaseException:
            sink.abort()
            raise
        finally:
            self._sinks.pop((cam.name, req), None)
            self._waiters.pop(("file", req), None)

    # ---------- sync API (every call takes camera=None -> default camera) ----------
    def _run(self, coro, timeout=30):
        return asyncio.run_coroutine_threadsafe(coro, self._loop).result(timeout)

    def set(self, camera=None, **settings):
        """e.g. set(res="1920x1080", fps=30, facing="user", zoom=2, torch=True, streamFps=15)"""
        cam = self._pick(camera)
        self._run(self._csend(cam, {"t": "set", "settings": settings}))

    def get_state(self, wait=0.5, camera=None):
        cam = self._pick(camera)
        self._run(self._csend(cam, {"t": "get_state"}))
        time.sleep(wait)
        return cam.state

    def stream(self, on=True, fps=None, width=None, quality=None, camera=None):
        cam = self._pick(camera)
        m = {"t": "stream", "on": on}
        m.update({k: v for k, v in dict(fps=fps, width=width, quality=quality).items() if v})
        self._run(self._csend(cam, m))

    def latest_frame(self, wait=None, camera=None):
        """Newest preview JPEG (bytes) or None. wait=seconds blocks for a *new* frame."""
        cam = self._pick(camera)
        if wait:
            cam.frame_evt.clear()
            cam.frame_evt.wait(wait)
        return cam.frame

    def snapshot(self, quality=None, timeout=20, camera=None) -> bytes:
        cam = self._pick(camera)
        return self._run(self._asnap(cam, quality, timeout), timeout + 2)

    # ---- library on the phone: list / download / delete ----
    def library(self, kind=None, timeout=15, camera=None):
        """List photos and clips stored on the phone, newest first.
        Each item: {kind: 'photo'|'video', id, name, type, size, ts (ms), secs, w, h}."""
        cam = self._pick(camera)
        items = self._run(self._alib(cam, timeout), timeout + 2)
        return [i for i in items if not kind or i["kind"] == kind]

    def find(self, ref, kind=None, items=None, camera=None):
        """Resolve a reference to a library item: item dict, 'latest', numeric id, or file name."""
        if isinstance(ref, dict):
            return ref
        pool = [i for i in (items if items is not None else self.library(kind, camera=camera)) if not kind or i["kind"] == kind]
        if str(ref) == "latest":
            hit = pool[0] if pool else None
        else:
            hit = next((i for i in pool if str(i["id"]) == str(ref) or i["name"] == str(ref)), None)
        if hit is None:
            raise KeyError(f"no library item matches {ref!r}")
        return hit

    def download(self, ref="latest", dest=None, kind=None, timeout=900, camera=None):
        """Download one photo/clip from the phone.
        dest=None -> returns (bytes, name);  dest=<dir> -> saves <dir>/<name> and returns the Path."""
        cam = self._pick(camera)
        item = self.find(ref, kind, camera=cam.name)
        name, out = self._run(self._afile(cam, item, dest), timeout + 2)
        return (out, name) if dest is None else out

    def download_all(self, dest, kind=None, skip_existing=True, delete_after=False, progress=None, camera=None):
        """Save every (or every `kind`) item to the folder `dest`. Returns the list of Paths written.
        skip_existing skips files already there with the same size; delete_after removes them from the phone
        once saved (only items that were verified complete)."""
        cam = self._pick(camera)
        dest, saved, done, used = Path(dest), [], [], set()
        for item in self.library(kind, camera=cam.name):
            name = _safe_name(item["name"])
            target = dest / name
            if name in used or (target.exists() and target.stat().st_size != item["size"]):
                name = f"{Path(name).stem}-{item['id']}{Path(name).suffix}"   # same name, different file: keep both
                target = dest / name
            used.add(name)
            if skip_existing and target.exists() and target.stat().st_size == item["size"]:
                done.append(item)
                continue
            path = self._run(self._afile(cam, dict(item, name=name), dest), 902)[1]
            saved.append(path)
            done.append(item)
            if progress:
                progress(item, path)
        if delete_after and done:
            self.delete(done, camera=cam.name)
        return saved

    def delete(self, refs, camera=None):
        """Delete items from the phone's library (no confirmation!). refs: items / ids / names."""
        cam = self._pick(camera)
        items = self.library(camera=cam.name)
        picked = [self.find(r, items=items) for r in (refs if isinstance(refs, (list, tuple)) else [refs])]
        self._run(self._csend(cam, {"t": "lib_delete", "items": [{"kind": i["kind"], "id": i["id"]} for i in picked]}))
        return len(picked)

    # ---- recording ----
    def rec_start(self, camera=None):
        self._run(self._csend(self._pick(camera), {"t": "rec", "action": "start"}))

    def rec_stop(self, camera=None):
        self._run(self._csend(self._pick(camera), {"t": "rec", "action": "stop"}))

    def rec_fetch(self, timeout=600, camera=None):
        """Pull the last finished recording from the phone -> (bytes, filename)."""
        cam = self._pick(camera)

        async def go():
            for _ in range(100):  # wait for the phone to finish finalising the file
                rec = cam.state.get("recording") or {}
                if rec.get("last") and rec.get("state") == "idle":
                    break
                await asyncio.sleep(0.1)
            else:
                raise RuntimeError("no finished recording")
            i = cam.state["recording"]["last"]["id"]
            fut = self._waiters[("rec", i)] = self._loop.create_future()
            await self._csend(cam, {"t": "rec", "action": "send", "id": i})
            return await asyncio.wait_for(fut, timeout)
        return self._run(go(), timeout + 2)

    def rec_stop_and_fetch(self, camera=None):
        self.rec_stop(camera=camera)
        time.sleep(0.5)
        return self.rec_fetch(camera=camera)


# ---------------------------------------------------------------- CLI
def cli():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--name", help="name of this hub, shown to phones (they can pick a hub by it). Default: host name")
    ap.add_argument("--token", default="", help="require this shared secret ('auto' = random). Default: open, no token")
    ap.add_argument("--allow-public", action="store_true", help="also accept clients outside private/LAN address ranges")
    ap.add_argument("--no-tls", action="store_true", help="plain HTTP/ws:// with no certificates; implies --serve-app (see README: needs a Chrome flag on the phone)")
    ap.add_argument("--cert"); ap.add_argument("--key")
    ap.add_argument("--serve-app", nargs="?", const=str(Path(__file__).resolve().parent.parent),
                    help="also serve the PWA files from this dir (default: repo root)")
    ap.add_argument("--open", action="store_true", help="open the browser viewer on this machine")
    ap.add_argument("--out", default="webcam-media", help="folder for downloaded/pushed files (default: webcam-media)")
    ap.add_argument("--camera", help="camera name to act on (exact / substring / glob)")
    ap.add_argument("--all-cameras", action="store_true", help="with --list/--pull: every connected camera (own sub-folder each)")
    ap.add_argument("--expect", type=int, default=1, help="with --all-cameras: wait until this many cameras are connected")
    ap.add_argument("--list", action="store_true", help="wait for the phone, print its library, exit")
    ap.add_argument("--pull", action="store_true", help="wait for the phone, download its whole library into --out, exit")
    ap.add_argument("--kind", choices=["photo", "video"], help="limit --list/--pull to photos or clips")
    ap.add_argument("--delete-after", action="store_true", help="with --pull: remove files from the phone once saved")
    ap.add_argument("--wait", type=float, default=120, help="seconds to wait for the phone with --list/--pull")
    ap.add_argument("--quiet", action="store_true", help="no banner/QR (for scripts)")
    a = ap.parse_args()
    if a.no_tls and a.serve_app is None:     # an https:// page can't open ws://, so plain-HTTP mode must serve the app itself
        a.serve_app = str(Path(__file__).resolve().parent.parent)

    hub = Hub(port=a.port, token=a.token, tls=not a.no_tls, cert=a.cert, key=a.key, app_dir=a.serve_app,
              quiet=a.quiet, save_dir=a.out, allow_public=a.allow_public, name=a.name)
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
        deadline = time.time() + a.wait
        if a.all_cameras:
            while len(hub.cameras()) < a.expect and time.time() < deadline:
                time.sleep(0.2)
            targets = [c["name"] for c in hub.cameras()]
        else:
            if not hub.wait_for_phone(a.wait, camera=a.camera):
                sys.exit("camera did not connect")
            targets = [hub._pick(a.camera).name]
        if not targets:
            sys.exit("no camera connected")
        for name in targets:
            folder = out / _safe_name(name) if a.all_cameras else out
            if a.list:
                print(f"== {name}")
                show(hub.library(a.kind, camera=name))
            else:
                files = hub.download_all(folder, kind=a.kind, delete_after=a.delete_after, camera=name,
                                         progress=lambda i, p: print("saved", p))
                print(f"{name}: {len(files)} new file(s) in {folder}")
        return 0

    help_txt = ("commands: cams | use <camera> | state | snap [quality] | rec start | rec stop | stream on|off [fps] | "
                "set key=value ... (res=1280x720 fps=30 facing=user zoom=2 torch=true) | frame | "
                "ls [photo|video] | get <#|name|latest|all> [photo|video] | pull [photo|video] | rm <#|name> ... | quit")
    print(help_txt)
    listing: list = []
    ts = lambda: datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    conv = lambda v: {"true": True, "false": False}.get(v.lower(), v) if not v.replace(".", "", 1).lstrip("-").isdigit() else (float(v) if "." in v else int(v))
    if a.camera:
        try:
            hub.wait_for_phone(30, camera=a.camera)
            print("using", hub.use(a.camera))
        except LookupError as e:
            print(e)
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
            elif c == "cams":
                for cam in hub.cameras():
                    v = cam.get("video") or {}
                    print(f"  {cam['name']:20} {cam['ip']:15} {v.get('w', '?')}x{v.get('h', '?')}"
                          f"{'  [REC]' if cam['recording'] else ''}{'  <- current' if cam['name'] == hub._cur else ''}")
                print(f"{len(hub.cameras())} camera(s)")
            elif c == "use":
                print("using", hub.use(" ".join(line[1:])))
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
