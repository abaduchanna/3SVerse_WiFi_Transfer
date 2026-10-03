# 3SVerse WiFi Transfer

**Two-way WiFi file transfer - phone → PC and PC → phone - over your own router. No internet, no cloud, no size limit — transfer 128 GB+ folders in one go.**

MIT licensed. Android app (APK) + Windows receiver (EXE).

## Why

Internet-based transfers (cloud, chat apps) are slow and often capped. This tool
uses your **local network only**: the phone streams files straight to the PC over
your TP-Link (or any) router at the maximum speed both devices support — typically
**20–80+ MB/s** on 5 GHz WiFi instead of 1–3 MB/s through the internet.

- **Two-way** — the phone sends folders to the PC; the PC dashboard ("Send to phone" card) queues files that the phone pulls into its Downloads folder.
- **Native app window** — the PC dashboard opens in a real app window (WebView2). Fallbacks: Edge/Chrome app window → default browser (force the old behaviour with `-browser`).
- **Auto-discovery** — start the PC server, open the app on the phone: PC address fills in automatically (UDP broadcast). Manual IP entry also supported.
- **Firewall auto-setup** — the exe adds the Windows inbound rules (TCP 8765 / UDP 8766) on first run; if it needs admin rights, Windows shows one UAC prompt. Skip with `-no-firewall`.
- **Whole-folder transfer** — pick a main folder (even Internal Storage root) with the system folder picker; the entire tree is transferred preserving structure.
- **Fast** — 3 parallel file streams + 1 MB buffers to saturate WiFi.
- **Uninterruptible** — Android foreground service + wake locks so hours-long transfers are not killed; per-file retry on errors.
- **Resume** — stop/pause/reconnect any time. Already-transferred files (same name + size) are skipped automatically.
- **Private** — data never leaves your router. The PC writes into one save folder you choose.

## Setup (once)

### PC (Windows)

1. Download `3SVerse-WiFi-Transfer-PC-v*.exe` from [Releases](../../releases/latest)
   - it is a **single file, no installer, no zip** (plain Go binary: clean profile
   for antivirus / SentinelOne - no self-extraction, no packer behavior).
2. Double-click `3SVerse-WiFi-Transfer-PC-v*.exe`. A console window opens showing
   the big **PC Address**, e.g. `192.168.1.5:8765`.
3. The dashboard opens in the **3SVerse WiFi Transfer app window**. Windows
   Firewall rules are added automatically (one UAC prompt the very first time -
   click **Yes**).
4. Phone→PC files arrive into `Downloads\3SVerse WiFi Transfer` (change: run
   `3SVerse-WiFi-Transfer-PC.exe -dir D:\MyFolder` from a cmd window).
5. To **send files to the phone**, use the *Send to phone* card on the dashboard:
   "Add files" or "Add folder" queues them; the phone app (PC → Phone card) pulls
   them into `Downloads/3SVerse WiFi Transfer`.

Flags: `-dir <path>` (save folder), `-browser` (open dashboard in a browser tab
instead of the app window), `-no-open` (do not open anything), `-no-firewall`
(skip the firewall auto-setup).

### Phone (Android 8.0+)

1. Download `3SVerse-WiFi-Transfer-Android-*.apk` from
   [Releases](../../releases/latest) and install it (allow "install unknown apps").
2. Connect the phone to the **same WiFi router** as the PC (important: not mobile data).
3. Open the app — it shows **"PC found: 192.168.x.x"** automatically.
4. Optional: tap **Test** to confirm the PC is reachable (shows free disk space).
5. **Phone → PC:** pick the main folder; the first row of the tree is the main
   folder itself - keep it ticked for everything, or untick it and tick individual
   folders/files (multiple selections allowed). Tap **Start Transfer**.
6. **PC → phone:** when the PC dashboard has queued files, the PC → Phone card
   shows the count - tap **Receive on Phone**.

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
| "PC found…" never appears | Both devices on the same router band? Some routers isolate WiFi clients (AP isolation) — disable it, or type the PC IP manually. |
| Test fails / "PC unreachable" | The exe adds the firewall rules itself on first run (one UAC prompt). If it could not: run it once as Administrator, or add inbound rules TCP 8765 + UDP 8766 for private networks in Windows Firewall. |
| Blank white window / "can't read and write to its data directory" | **Run the exe normally (double-click) — do NOT use "Run as administrator".** The firewall prompt already handles admin rights itself. If the native window still fails, the dashboard opens in your browser instead (or start it with `-browser`). |
| Transfer stops at night | Battery optimization killed the service — set battery to Unrestricted (see above). The next Start resumes where it left off. |
| Duplicate folders | If you renamed the PC save folder between runs, the manifest starts empty — point it back to the original folder to get resume. |

## How it works

```
Phone (APK)                                 PC (EXE)
────────────                                ─────────
SafTree walks the picked folder             Go net/http server on 0.0.0.0:8765
  (SAF / DocumentsContract, no AndroidX)    ├─ GET  /hello    → reachability, free space, outbox count
3 uploader threads                          ├─ GET  /manifest → received files+sizes (resume)
  HttpURLConnection PUT                     ├─ PUT  /file?path=rel → streams to .part, atomic rename
  fixed-length streaming, 1 MB buffer       ├─ POST /outbox?name=rel → queue for the phone (dashboard)
PC pulls queued files                       ├─ GET  /outbox/list | /outbox/file?name=rel | /outbox/clear
  → MediaStore Downloads (API 29+)          └─ POST /done     → summary
UDP 255.255.255.255:8766 ←─────────────────── broadcast "3SVERSE-XFER|ip|port|host|outboxN" every 2 s
```

## Build from source

- **APK**: push to GitHub — Actions builds it (Gradle 8.9, AGP 8.7.3, JDK 17), or run `gradle assembleDebug`.
- **EXE**: Actions builds it (pure Go cross-compile + goversioninfo resources), or run:
  `cd pc && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" .`

## Privacy & security

- Runs only on your LAN; no data leaves your network, no analytics, no permissions beyond storage picker + network.
- The PC accepts uploads only into its configured save folder; path traversal (`..`, absolute, drive paths) is rejected; Windows-illegal characters are sanitized.
- Anyone on the same WiFi can upload to an open server — use it on your home router. (Future: optional PIN pairing.)

## License

MIT — see [LICENSE](LICENSE).

Developed by [3SVerse](https://3sverse.com) © 2026
