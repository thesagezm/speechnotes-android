#!/usr/bin/env python3
"""A LocalSend v2.2 SENDER used to verify the Android BookDrop receiver.

NOT app code — a verification probe. It pushes a file to a running
Speechnotes receiver (Waydroid or a phone on the same network) exactly the
way the official LocalSend client does, so the receiver's large-file path,
its access-code gate and its landing can be exercised from a host.

Usage:
    python3 research/localsend-probe.py <host> <port> <file> [access-code]

Example (Waydroid, IP from `waydroid status`):
    python3 research/localsend-probe.py <host> <port> <file> [access-code]
    python3 research/localsend-probe.py <host> 53317 /tmp/big.m4b 1844

Exit code 0 = the receiver answered 200 for the upload.
"""

import hashlib
import json
import os
import socket
import sys
import time
import urllib.parse
import uuid

if len(sys.argv) < 4:
    sys.stderr.write("usage: localsend-probe.py <host> <port> <file> [access-code]\n")
    raise SystemExit(2)

HOST = sys.argv[1]
PORT = int(sys.argv[2])
FILE_PATH = sys.argv[3]
PIN = sys.argv[4] if len(sys.argv) > 4 else None

DEVICE = {
    "alias": "Host Probe",
    "version": "2.2",
    "deviceModel": "host",
    "deviceType": "desktop",
    "fingerprint": "host-probe-fingerprint",
    "port": PORT,
    "protocol": "http",
    "download": False,
}


def http(method, path, body=None, headers=None, body_path=None):
    conn = socket.create_connection((HOST, PORT), timeout=30)
    conn.settimeout(600)
    payload = body or b""
    length = len(payload)
    if body_path is not None:
        length = os.path.getsize(body_path)
    head = f"{method} {path} HTTP/1.1\r\nHost: {HOST}\r\nContent-Length: {length}\r\nConnection: close\r\n"
    for key, value in (headers or {}).items():
        head += f"{key}: {value}\r\n"
    head += "\r\n"
    conn.sendall(head.encode())
    if body_path is not None:
        with open(body_path, "rb") as handle:
            while True:
                chunk = handle.read(1 << 20)
                if not chunk:
                    break
                conn.sendall(chunk)
    elif payload:
        conn.sendall(payload)
    chunks = []
    while True:
        data = conn.recv(65536)
        if not data:
            break
        chunks.append(data)
    conn.close()
    raw = b"".join(chunks)
    head_bytes, _, body_bytes = raw.partition(b"\r\n\r\n")
    status = int(head_bytes.split(b" ")[1])
    return status, body_bytes


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    size = os.path.getsize(FILE_PATH)
    name = os.path.basename(FILE_PATH)
    file_id = str(uuid.uuid4())

    status, _ = http("POST", "/api/localsend/v2/register", json.dumps(DEVICE).encode(),
                     {"Content-Type": "application/json"})
    print(f"register -> {status}")

    query = f"?pin={urllib.parse.quote(PIN)}" if PIN else ""
    files = {
        file_id: {
            "id": file_id,
            "fileName": name,
            "size": size,
            "fileType": "application/octet-stream",
            "sha256": sha256_of(FILE_PATH),
            "preview": None,
            "metadata": None,
        }
    }
    prepare = {"info": DEVICE, "pin": PIN, "files": files}
    started = time.time()
    status, body = http("POST", f"/api/localsend/v2/prepare-upload{query}",
                        json.dumps(prepare).encode(), {"Content-Type": "application/json"})
    print(f"prepare-upload -> {status} ({time.time() - started:.1f}s)")
    if status != 200:
        print(body.decode(errors="replace")[:400])
        return 1
    prepared = json.loads(body)
    token = prepared["files"][file_id]
    session = prepared["sessionId"]

    upload_query = urllib.parse.urlencode(
        {"sessionId": session, "fileId": file_id, "token": token, **({"pin": PIN} if PIN else {})}
    )
    started = time.time()
    status, body = http("POST", f"/api/localsend/v2/upload?{upload_query}", body_path=FILE_PATH,
                        headers={"Content-Type": "application/octet-stream"})
    print(f"upload -> {status} ({size / 1e6:.1f} MB in {time.time() - started:.1f}s)")
    if status != 200:
        print(body.decode(errors="replace")[:400])
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
