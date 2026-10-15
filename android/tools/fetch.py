#!/usr/bin/env python3
"""fetch.py URL DEST [SHA256]  - download with retries (Maven Central rate-limits with 429) and verify a checksum."""
import hashlib, os, sys, time, urllib.error, urllib.request

url, dest = sys.argv[1], sys.argv[2]
want = sys.argv[3].lower() if len(sys.argv) > 3 else None

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

if os.path.exists(dest) and (want is None or sha(dest) == want):
    sys.exit(0)
os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
tmp = dest + ".part"
for attempt in range(1, 9):
    try:
        print(f"fetch {url} (try {attempt})", flush=True)
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "webcam-build"}), timeout=120) as r, open(tmp, "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                f.write(b)
        if want and sha(tmp) != want:
            raise IOError(f"checksum mismatch for {url}: got {sha(tmp)}, want {want}")
        os.replace(tmp, dest)
        sys.exit(0)
    except (urllib.error.URLError, IOError, OSError) as e:
        print(f"  failed: {e}", file=sys.stderr)
        time.sleep(min(5 * attempt, 30))
sys.exit(f"could not download {url}")
