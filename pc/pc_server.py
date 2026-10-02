#!/usr/bin/env python3
"""3SVerse WiFi Transfer - PC receiver.

Runs a small HTTP server on your PC (port 8765). The 3SVerse WiFi Transfer
Android app connects over the LOCAL WiFi network and streams a picked folder
(into this PC) - no internet, no cloud, full WiFi speed.

Endpoints (used by the phone app, not by humans):
  GET  /hello                 -> {"ok":true,"app":...,"free":<bytes>}
  GET  /manifest              -> {"ok":true,"files":{"rel/path":size,...}}
  PUT  /file?path=<rel>       -> streams body to <save>/<rel> (atomic .part)
  POST /done                  -> {"ok":true}

It also broadcasts "3SVERSE-XFER|<ip>|<port>|<hostname>" on UDP 8766 every
2 seconds so the phone app can auto-discover this PC.
"""

import json
import os
import queue
import re
import socket
import threading
import time
import tkinter as tk
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from tkinter import filedialog
from tkinter.scrolledtext import ScrolledText
from urllib.parse import parse_qs, unquote, urlparse

PORT = 8765
DISC_PORT = 8766
DISC_MAGIC = "3SVERSE-XFER"
VERSION = "1.0.0"

WIN_RESERVED = {"CON", "PRN", "AUX", "NUL",
                "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
                "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"}


def sanitize_rel(rel: str) -> str:
    """Validate + clean a relative path coming from the phone (Windows-safe)."""
    if not rel:
        raise ValueError("empty path")
    raw = rel.replace("\\", "/")
    if raw.startswith(("/", "~")) or re.match(r"^[A-Za-z]:", raw):
        raise ValueError("absolute path forbidden")
    rel = raw.strip("/")
    if not rel:
        raise ValueError("empty path")
    parts = []
    for seg in rel.split("/"):
        if seg in ("", ".", ".."):
            raise ValueError("bad path segment")
        for ch in '<>:"|?*':
            seg = seg.replace(ch, "_")
        stem = seg.split(".")[0].upper()
        if stem in WIN_RESERVED:
            seg = "_" + seg
        seg = seg.rstrip(" .") or "_"
        parts.append(seg)
    return os.path.join(*parts)


def lan_ip() -> str:
    """Best-effort LAN IP (UDP trick, no packets actually sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


class Receiver:
    """Shared state between the HTTP handler threads and the GUI."""

    def __init__(self, root_dir: str, logq: queue.Queue):
        self.root = root_dir
        self.logq = logq
        self.lock = threading.Lock()
        self.manifest = {}
        self.files_done = 0
        self.bytes_done = 0
        self.speed = 0.0
        self._speed_t = time.time()
        self._speed_b = 0
        self.rescan()

    def rescan(self) -> None:
        m = {}
        base = self.root
        for dirpath, _dirs, files in os.walk(base):
            for name in files:
                if name.endswith(".part"):
                    continue
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, base)
                try:
                    m[rel.replace("\\", "/")] = os.path.getsize(full)
                except OSError:
                    pass
        with self.lock:
            self.manifest = m

    def log(self, msg: str) -> None:
        self.logq.put(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")

    def tick_speed(self) -> None:
        now = time.time()
        with self.lock:
            dt = now - self._speed_t
            if dt >= 1.0:
                db = self.bytes_done - self._speed_b
                self.speed = db / dt
                self._speed_t = now
                self._speed_b = self.bytes_done


class Handler(BaseHTTPRequestHandler):
    receiver: Receiver = None  # injected by serve()
    protocol_version = "HTTP/1.1"

    # -- helpers -------------------------------------------------------
    def _json(self, obj: dict, code: int = 200) -> None:
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # silence default stderr spam
        pass

    # -- routes --------------------------------------------------------
    def do_GET(self):
        r = self.receiver
        path = urlparse(self.path).path
        if path == "/hello":
            free = shutil_disk_free(r.root)
            self._json({"ok": True, "app": "3SVerse WiFi Transfer",
                        "version": VERSION, "free": free})
        elif path == "/manifest":
            with r.lock:
                files = dict(r.manifest)
            self._json({"ok": True, "files": files})
        else:
            self._json({"ok": False, "error": "unknown route"}, 404)

    def do_PUT(self):
        r = self.receiver
        try:
            qs = parse_qs(urlparse(self.path).query)
            rel = sanitize_rel(unquote(qs.get("path", [""])[0]))
        except Exception:
            self._json({"ok": False, "error": "bad path"}, 400)
            return
        length = int(self.headers.get("Content-Length", "0") or 0)
        final = os.path.join(r.root, rel)
        tmp = final + ".part"
        try:
            os.makedirs(os.path.dirname(final), exist_ok=True)
            n = 0
            with open(tmp, "wb") as f:
                remaining = length
                while remaining > 0:
                    chunk = self.rfile.read(min(1 << 20, remaining))
                    if not chunk:
                        raise ConnectionError("client stopped mid-file")
                    f.write(chunk)
                    remaining -= len(chunk)
                    n += len(chunk)
            os.replace(tmp, final)  # atomic on same volume
            with r.lock:
                r.manifest[rel.replace("\\", "/")] = n
                r.files_done += 1
                r.bytes_done += n
            self._json({"ok": True, "size": n})
        except Exception as e:
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except OSError:
                pass
            try:
                self._json({"ok": False, "error": str(e)}, 500)
            except Exception:
                pass

    def do_POST(self):
        r = self.receiver
        path = urlparse(self.path).path
        if path == "/done":
            with r.lock:
                files, byts = r.files_done, r.bytes_done
            self._json({"ok": True, "files": files, "bytes": byts})
            r.log(f"Phone reported done: {files} files, {byts / (1 << 30):.2f} GB")
        else:
            self._json({"ok": False, "error": "unknown route"}, 404)


def shutil_disk_free(path: str) -> int:
    import shutil
    try:
        return shutil.disk_usage(path).free
    except Exception:
        return 0


def broadcaster(stop_evt: threading.Event, logq: queue.Queue) -> None:
    """UDP broadcast so the phone auto-discovers this PC."""
    ip = lan_ip()
    host = socket.gethostname()
    payload = f"{DISC_MAGIC}|{ip}|{PORT}|{host}".encode("utf-8")
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    while not stop_evt.is_set():
        for target in ("255.255.255.255",):
            try:
                s.sendto(payload, (target, DISC_PORT))
            except Exception:
                pass
        stop_evt.wait(2.0)
    s.close()


class ServerThread(threading.Thread):
    def __init__(self, root_dir: str, logq: queue.Queue):
        super().__init__(daemon=True)
        self.receiver = Receiver(root_dir, logq)
        self.httpd = None
        self.stop_evt = threading.Event()
        self.bc = None

    def run(self):
        Handler.receiver = self.receiver
        self.httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
        self.httpd.daemon_threads = True
        try:
            self.httpd.serve_forever(poll_interval=0.2)
        except Exception:
            pass

    def start_all(self):
        self.start()
        self.bc = threading.Thread(target=broadcaster,
                                   args=(self.stop_evt, self.receiver.logq), daemon=True)
        self.bc.start()

    def shutdown(self):
        self.stop_evt.set()
        try:
            self.httpd.shutdown()
            self.httpd.server_close()
        except Exception:
            pass


# ----------------------------------------------------------------------
# GUI
# ----------------------------------------------------------------------

class App:
    def __init__(self, tk_root: tk.Tk):
        self.tk = tk_root
        tk_root.title("3SVerse WiFi Transfer")
        tk_root.geometry("640x520")
        tk_root.minsize(560, 460)

        home = os.path.join(os.path.expanduser("~"), "Downloads",
                            "3SVerse WiFi Transfer")
        self.folder_var = tk.StringVar(value=home)
        self.addr_var = tk.StringVar(value="…")
        self.stat_var = tk.StringVar(value="Server: STOPPED")
        self.logq: queue.Queue = queue.Queue()
        self.srv: ServerThread = None
        self.ui_speed_t = time.time()
        self.ui_speed_b = 0

        pad = {"padx": 12, "pady": 6}

        tk.Label(tk_root, text="3SVerse WiFi Transfer",
                 font=("Segoe UI", 16, "bold")).pack(anchor="w", padx=12, pady=(12, 0))
        tk.Label(tk_root, text="Phone app se same WiFi par folder bhejein — direct, fast, resume-able.",
                 fg="#5b6b64").pack(anchor="w", padx=12)

        addr = tk.Frame(tk_root, bg="#0e9f6e")
        addr.pack(fill="x", padx=12, pady=10)
        tk.Label(addr, text="PC Address:  ", font=("Segoe UI", 13, "bold"),
                 bg="#0e9f6e", fg="white").pack(side="left", padx=10, pady=10)
        tk.Label(addr, textvariable=self.addr_var, font=("Consolas", 13, "bold"),
                 bg="#0e9f6e", fg="white").pack(side="left")

        frow = tk.Frame(tk_root)
        frow.pack(fill="x", padx=12, **pad)
        tk.Label(frow, text="Save folder:").pack(side="left")
        tk.Entry(frow, textvariable=self.folder_var).pack(
            side="left", fill="x", expand=True, padx=8)
        tk.Button(frow, text="Browse…", command=self.browse).pack(side="left")

        brow = tk.Frame(tk_root)
        brow.pack(fill="x", padx=12, **pad)
        self.start_btn = tk.Button(brow, text="START Server", font=("Segoe UI", 11, "bold"),
                                   bg="#0e9f6e", fg="white", command=self.start)
        self.start_btn.pack(side="left")
        self.stop_btn = tk.Button(brow, text="STOP", font=("Segoe UI", 11, "bold"),
                                  bg="#c0392b", fg="white", command=self.stop,
                                  state="disabled")
        self.stop_btn.pack(side="left", padx=8)
        tk.Label(brow, textvariable=self.stat_var,
                 font=("Segoe UI", 10, "bold")).pack(side="left", padx=8)

        self.speed_var = tk.StringVar(value="Received: 0 files • 0.0 GB • 0.0 MB/s")
        tk.Label(tk_root, textvariable=self.speed_var,
                 font=("Segoe UI", 11, "bold"), fg="#0b7a55").pack(anchor="w", padx=12)

        tk.Label(tk_root, text="Log", font=("Segoe UI", 10, "bold")).pack(
            anchor="w", padx=12, pady=(8, 0))
        self.logbox = ScrolledText(tk_root, height=14, font=("Consolas", 9),
                                   state="disabled", bg="#0f1f19", fg="#9fe8c8")
        self.logbox.pack(fill="both", expand=True, padx=12, pady=(2, 12))

        self.tk.after(300, self.poll)
        self.log("Ready. START dabayen — agar Windows Firewall pooche to 'Allow access' karein.")

    def browse(self):
        if self.srv:
            self.log("Folder tabdeel karna ho to pehle STOP karein.")
            return
        d = filedialog.askdirectory()
        if d:
            self.folder_var.set(d)

    def start(self):
        folder = self.folder_var.get().strip()
        if not folder:
            self.log("Save folder choose karein.")
            return
        try:
            os.makedirs(folder, exist_ok=True)
        except Exception as e:
            self.log(f"Folder ban nahi saka: {e}")
            return
        self.srv = ServerThread(folder, self.logq)
        self.srv.start_all()
        self.addr_var.set(f"{lan_ip()}:{PORT}")
        self.stat_var.set("Server: RUNNING")
        self.start_btn.config(state="disabled")
        self.stop_btn.config(state="normal")
        self.log(f"Server chal raha hai: http://{lan_ip()}:{PORT}")
        self.log(f"Save folder: {folder}")
        self.log(f"Phone app same WiFi par kholein — PC khud detect ho jayega.")

    def stop(self):
        if self.srv:
            self.srv.shutdown()
            self.srv = None
        self.stat_var.set("Server: STOPPED")
        self.start_btn.config(state="normal")
        self.stop_btn.config(state="disabled")
        self.log("Server band ho gaya.")

    def poll(self):
        try:
            while True:
                line = self.logq.get_nowait()
                self.logbox.config(state="normal")
                self.logbox.insert("end", line + "\n")
                self.logbox.see("end")
                self.logbox.config(state="disabled")
        except queue.Empty:
            pass
        if self.srv:
            self.srv.receiver.tick_speed()
            with self.srv.receiver.lock:
                files = self.srv.receiver.files_done
                byts = self.srv.receiver.bytes_done
                speed = self.srv.receiver.speed
            self.speed_var.set(
                f"Received: {files} files • {byts / (1 << 30):.2f} GB • {speed / (1 << 20):.1f} MB/s")
            # per-file activity heartbeat
            if files > 0 and files % 25 == 0:
                pass
        self.tk.after(300, self.poll)

    def log(self, msg):
        self.logq.put(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")


def main():
    root = tk.Tk()
    try:
        # keep window taskbar icon default; brand via title
        root.iconbitmap(default=None)
    except Exception:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
