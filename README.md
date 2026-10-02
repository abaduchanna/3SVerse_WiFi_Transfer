# 3SVerse WiFi Transfer

**Phone → PC file transfer over your own WiFi router. No internet, no cloud, no size limit — transfer 128 GB+ folders in one go.**

MIT licensed. Android app (APK) + Windows receiver (EXE).

## Why

Internet-based transfers (cloud, chat apps) are slow and often capped. This tool
uses your **local network only**: the phone streams files straight to the PC over
your TP-Link (or any) router at the maximum speed both devices support — typically
**20–80+ MB/s** on 5 GHz WiFi instead of 1–3 MB/s through the internet.

- **Auto-discovery** — start the PC server, open the app on the phone: PC address fills in automatically (UDP broadcast). Manual IP entry also supported.
- **Whole-folder transfer** — pick a main folder (even Internal Storage root) with the system folder picker; the entire tree is transferred preserving structure.
- **Fast** — 3 parallel file streams + 1 MB buffers to saturate WiFi.
- **Uninterruptible** — Android foreground service + wake locks so hours-long transfers are not killed; per-file retry on errors.
- **Resume** — stop/pause/reconnect any time. Already-transferred files (same name + size) are skipped automatically.
- **Private** — data never leaves your router. The PC writes into one save folder you choose.

## Setup (once)

### PC (Windows)

1. Download `3SVerse-WiFi-Transfer-PC-*-standalone.zip` from
   [Releases](../../releases/latest), extract anywhere.
2. Run `pc_server.exe`.
3. When **Windows Firewall** asks (first run), tick **Private networks** and click
   **Allow access**.
4. Click **START Server**. The green bar shows the PC address, e.g. `192.168.1.5:8765`.
   Choose the save folder (default: `Downloads\3SVerse WiFi Transfer`).

### Phone (Android 8.0+)

1. Download `3SVerse-WiFi-Transfer-Android-*.apk` from
   [Releases](../../releases/latest) and install it (allow "install unknown apps").
2. Connect the phone to the **same WiFi router** as the PC (important: not mobile data).
3. Open the app — it shows **"PC mil gaya: 192.168.x.x"** automatically.
4. Optional: tap **Test** to confirm the PC is reachable (shows free disk space).
5. Tap **Select Folder & Start Transfer**, choose the main folder, done.

The screen stays on the progress (files, GB, speed, ETA). You can minimize the app —
the notification keeps the progress.

## Pause / Resume / Stop

- **Pause** — in-flight files finish, nothing new starts. **Resume** continues.
- **Stop** — transfer halts. Starting a new transfer to the same PC **skips everything already received** (name + size match) and continues with the rest.
- If the WiFi drops mid-transfer, the app retries each file automatically; worst case stop → start again and it resumes.

## Maximum speed tips

- Use the router's **5 GHz** network if available (2.4 GHz tops out ~10–20 MB/s; 5 GHz can do 40–80+ MB/s).
- Keep the phone in the same room as the router for the transfer.
- Windows Defender real-time scanning of thousands of small files adds overhead — the folder is a normal user folder, keep it that way (do not exclude folders you don't own).
- Phone storage speed matters: internal storage (UFS) is fast; a cheap SD card will bottleneck.
- Android battery optimization: if your phone (Xiaomi/Oppo/Samsung etc.) kills the app, allow **Unrestricted battery** for "3SVerse WiFi Transfer" in Settings → Battery.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| "PC mil gaya…" never appears | Both devices on the same router band? Some routers isolate WiFi clients (AP isolation) — disable it, or type the PC IP manually (green bar on PC). |
| Test fails | Windows Firewall blocked it. Re-run `pc_server.exe` as administrator once, or allow inbound TCP 8765 + UDP 8766 for private networks. |
| Transfer stops at night | Battery optimization killed the service — set battery to Unrestricted (see above). The next Start resumes where it left off. |
| Duplicate folders | If you renamed the PC save folder between runs, the manifest starts empty — point it back to the original folder to get resume. |

## How it works

```
Phone (APK)                                PC (EXE)
────────────                               ─────────
SafTree walks the picked folder            ThreadingHTTPServer on 0.0.0.0:8765
  (SAF / DocumentsContract, no AndroidX)   ├─ GET  /hello    → reachability + free space
3 uploader threads                         ├─ GET  /manifest → received files+sizes (resume)
  HttpURLConnection PUT                     ├─ PUT  /file?path=rel → streams to .part, atomic rename
  fixed-length streaming, 1 MB buffer       └─ POST /done     → summary
UDP 255.255.255.255:8766 ←────────────────── broadcast "3SVERSE-XFER|ip|port|host" every 2 s
```

## Build from source

- **APK**: push to GitHub — Actions builds it (Gradle 8.9, AGP 8.7.3, JDK 17), or run `gradle assembleDebug`.
- **EXE**: Actions builds it (Nuitka standalone, tk-inter plugin), or run:
  `python -m nuitka --standalone --enable-plugin=tk-inter --windows-console-mode=disable pc/pc_server.py`

## Privacy & security

- Runs only on your LAN; no data leaves your network, no analytics, no permissions beyond storage picker + network.
- The PC accepts uploads only into its configured save folder; path traversal (`..`, absolute, drive paths) is rejected; Windows-illegal characters are sanitized.
- Anyone on the same WiFi can upload to an open server — use it on your home router. (Future: optional PIN pairing.)

## License

MIT — see [LICENSE](LICENSE).

Developed by [3SVerse](https://3sverse.com) © 2026
