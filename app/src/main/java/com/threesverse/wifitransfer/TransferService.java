package com.threesverse.wifitransfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground service that streams a picked SAF folder to the PC receiver
 * over the local WiFi network.
 *
 * Speed: 3 parallel file streams + 1 MB buffers (multiple TCP flows fill
 * WiFi much better than one). Reliability: foreground service + wake locks
 * keep Android from killing long (128 GB class) transfers, per-file retry,
 * and a size-based manifest lets a new run skip everything already on the PC.
 */
public class TransferService extends Service {

    public static final String ACTION_START = "com.threesverse.wifitransfer.START";
    public static final String ACTION_PAUSE = "com.threesverse.wifitransfer.PAUSE";
    public static final String ACTION_RESUME = "com.threesverse.wifitransfer.RESUME";
    public static final String ACTION_STOP = "com.threesverse.wifitransfer.STOP";

    private static final String CHANNEL_ID = "transfer";
    private static final int NOTIF_ID = 1001;
    private static final int WORKERS = 3;
    private static final int RETRIES = 3;
    private static final int BUF = 1 << 20; // 1 MB

    /** Live transfer state polled by MainActivity (500 ms). */
    public static final class State {
        public static final int IDLE = 0, SCANNING = 1, TRANSFERRING = 2,
                PAUSED = 3, DONE = 4, ERROR = 5;
        public volatile int phase = IDLE;
        public volatile String message = "";
        public volatile String currentFile = "";
        public volatile long currentBytes = 0, currentSize = 0;
        public volatile int filesTotal = 0, filesDone = 0, filesSkipped = 0, filesFailed = 0;
        public volatile long bytesTotal = 0, bytesDone = 0, bytesPerSec = 0;
        public volatile String error = "";
        public final void reset() {
            phase = IDLE; message = ""; currentFile = ""; currentBytes = 0; currentSize = 0;
            filesTotal = 0; filesDone = 0; filesSkipped = 0; filesFailed = 0;
            bytesTotal = 0; bytesDone = 0; bytesPerSec = 0; error = "";
        }
    }

    public static final State STATE = new State();

    private ContentResolver cr;
    private String host;
    private int port;
    private UriHolder holder;
    private String[] intentDirs;
    private String[] intentFiles;

    private final ArrayDeque<SafTree.Entry> queue = new ArrayDeque<>();
    private final Map<String, Long> manifest = new HashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong inflightBytes = new AtomicLong();
    private ExecutorService pool;
    private Thread manager;

    private volatile boolean paused = false;
    private volatile boolean cancelled = false;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager notifMgr;

    /** Keeps (treeUri, host, port) so RESUME after pause needs no extras re-check. */
    private static final class UriHolder {
        String treeUri; String host; int port;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "WiFi Transfer", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Progress of the folder transfer to your PC");
        notifMgr.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_STOP;
        if (ACTION_START.equals(action)) {
            startAsForeground("Preparing transfer…");
            acquireLocks();
            cr = getContentResolver();
            cancelled = false;
            paused = false;
            holder = new UriHolder();
            holder.treeUri = intent.getStringExtra("treeUri");
            holder.host = intent.getStringExtra("host");
            holder.port = intent.getIntExtra("port", 8765);
            intentDirs = intent.getStringArrayExtra("selDirs");
            intentFiles = intent.getStringArrayExtra("selFiles");
            STATE.reset();
            // Guard: a broken intent (missing host/port) must never surface as
            // a confusing "Could not reach PC address null:0" network error.
            if (holder.host == null || holder.host.trim().isEmpty() || holder.port <= 0) {
                STATE.phase = State.ERROR;
                STATE.error = "Internal error: PC address was not passed to the transfer. "
                        + "Please fully close the app, reopen it, and press Start Transfer again.";
                STATE.message = "Error: " + STATE.error;
                notify("Transfer error");
                stopForegroundService();
                return START_NOT_STICKY;
            }
            startManager();
        } else if (ACTION_PAUSE.equals(action)) {
            paused = true;
            STATE.phase = State.PAUSED;
            STATE.message = "Paused - tap Resume to continue.";
            notify("Paused - tap Resume to continue");
        } else if (ACTION_RESUME.equals(action)) {
            paused = false;
            STATE.phase = State.TRANSFERRING;
            notify("Resuming transfer…");
        } else if (ACTION_STOP.equals(action)) {
            cancelled = true;
            paused = false;
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground(String text) {
        Notification n = buildNotification(text, 0, 0);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "3SVerse:WifiXfer");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "3SVerse:WifiXferLock");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    private Notification buildNotification(String text, long done, long total) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("3SVerse WiFi Transfer")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (total > 0) {
            b.setProgress((int) Math.min(total, Integer.MAX_VALUE),
                    (int) Math.min(done, Integer.MAX_VALUE), false);
        }
        return b.build();
    }

    private void notify(String text) {
        notifMgr.notify(NOTIF_ID, buildNotification(text, STATE.bytesDone, STATE.bytesTotal));
    }

    private void notifyProgress() {
        String text = STATE.filesDone + "/" + STATE.filesTotal + " files • "
                + human(STATE.bytesDone) + " / " + human(STATE.bytesTotal)
                + " • " + human(STATE.bytesPerSec) + "/s";
        notifMgr.notify(NOTIF_ID, buildNotification(text, STATE.bytesDone, STATE.bytesTotal));
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        for (String u : new String[]{"KB", "MB", "GB", "TB"}) {
            v /= 1024.0;
            if (v < 1024) return String.format(java.util.Locale.US, "%.1f %s", v, u);
        }
        return String.format(java.util.Locale.US, "%.1f PB", v / 1024.0);
    }

    // ------------------------------------------------------------------
    // Manager: scan -> manifest -> dispatch -> wait -> done
    // ------------------------------------------------------------------

    private void startManager() {
        manager = new Thread(this::runManager, "xfer-manager");
        manager.start();
    }

    private void runManager() {
        try {
            // Phase 1: hello (PC reachable?)
            STATE.phase = State.SCANNING;
            STATE.message = "Connecting to the PC…";
            JSONObject hello = httpGetJson("/hello");
            if (hello == null || !hello.optBoolean("ok")) {
                throw new Exception("PC unreachable at " + holder.host + ":" + holder.port
                        + ". Run the PC app (v1.3 or newer) on the same WiFi and click "
                        + "'Yes' on the Windows firewall prompt, then press Test first.");
            }

            // Phase 2: build file list from the user's selection
            STATE.message = "Scanning folders…";
            List<SafTree.Entry> all = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            final int[] lastScan = {0};
            SafTree.ScanProgress cb = count -> {
                lastScan[0] = count;
                STATE.message = "Scanning folders… " + count + " files found";
            };
            Uri tree = Uri.parse(holder.treeUri);
            String[] selDirs = intentDirs;
            if (selDirs != null) {
                for (String d : selDirs) {
                    if (cancelled) return;
                    String[] p = d.split("\t", 2);
                    if (p.length < 2) continue;
                    SafTree.walkSubtree(cr, tree, p[0], p[1], all, seen, cb);
                }
            }
            String[] selFiles = intentFiles;
            if (selFiles != null) {
                for (String f : selFiles) {
                    String[] p = f.split("\t");
                    if (p.length < 3) continue;
                    if (!seen.add(p[1])) continue;
                    try {
                        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, p[0]);
                        all.add(new SafTree.Entry(p[1], docUri, Long.parseLong(p[2])));
                    } catch (Exception ignore) {
                    }
                }
            }
            STATE.filesTotal = all.size();
            long total = 0;
            for (SafTree.Entry e : all) total += e.size;
            STATE.bytesTotal = total;
            STATE.message = all.size() + " files • " + human(total);
            if (all.isEmpty()) {
                STATE.phase = State.DONE;
                STATE.message = "No files found (empty folder).";
                stopForegroundService();
                return;
            }

            // Phase 3: manifest - resume support (skip what PC already has)
            Map<String, Long> pc = fetchManifest();
            synchronized (manifest) {
                manifest.clear();
                if (pc != null) manifest.putAll(pc);
            }

            // Phase 4: queue + dispatch
            synchronized (queue) {
                queue.clear();
                queue.addAll(all);
            }
            STATE.phase = State.TRANSFERRING;
            pool = Executors.newFixedThreadPool(WORKERS);
            dispatchLoop();

            if (cancelled) {
                STATE.phase = State.IDLE;
                STATE.message = "Transfer stopped. Press Start again to resume the remaining files.";
            } else {
                STATE.phase = State.DONE;
                STATE.message = "Done! " + STATE.filesDone + " files sent, "
                        + STATE.filesSkipped + " were already on the PC, "
                        + STATE.filesFailed + " failed.";
                try {
                    JSONObject done = new JSONObject();
                    done.put("files", STATE.filesDone);
                    done.put("bytes", STATE.bytesDone);
                    httpPostJson("/done", done.toString());
                } catch (Exception ignore) {
                }
                notify("Transfer complete");
            }
        } catch (Exception e) {
            STATE.phase = State.ERROR;
            STATE.error = e.getMessage() == null ? e.toString() : e.getMessage();
            STATE.message = "Error: " + STATE.error;
            notify("Transfer error");
        } finally {
            shutdownPool();
            stopForegroundService();
        }
    }

    private void dispatchLoop() throws InterruptedException {
        long lastNotif = 0;
        long lastSpeedT = System.currentTimeMillis();
        long lastSpeedB = 0;

        while (true) {
            if (cancelled) return;
            if (paused) {
                Thread.sleep(300);
                lastSpeedT = System.currentTimeMillis();
                lastSpeedB = currentBytesApprox();
                continue;
            }
            SafTree.Entry e;
            synchronized (queue) {
                e = queue.pollFirst();
            }
            if (e == null) {
                if (inFlight.get() == 0) break; // everything dispatched + finished
                Thread.sleep(150);
                tickSpeed(lastSpeedT, lastSpeedB);
                continue;
            }

            boolean have = false;
            synchronized (manifest) {
                Long sz = manifest.get(e.relPath);
                have = sz != null && sz == e.size;
            }
            if (have) {
                STATE.filesSkipped++;
                STATE.filesDone++; // count as processed
                STATE.bytesDone += e.size;
                continue;
            }

            inFlight.incrementAndGet();
            inflightBytes.set(0);
            STATE.currentFile = e.relPath;
            STATE.currentSize = e.size;
            STATE.currentBytes = 0;
            final SafTree.Entry entry = e;
            pool.execute(() -> {
                try {
                    uploadWithRetry(entry);
                } finally {
                    inFlight.decrementAndGet();
                }
            });

            long now = System.currentTimeMillis();
            if (now - lastNotif > 600) {
                lastNotif = now;
                tickSpeed(lastSpeedT, lastSpeedB);
                notifyProgress();
            }
        }
        // drain tail (last in-flight files)
        while (inFlight.get() > 0 && !cancelled) {
            Thread.sleep(150);
            tickSpeed(lastSpeedT, lastSpeedB);
            notifyProgress();
        }
    }

    private long currentBytesApprox() {
        return STATE.bytesDone + inflightBytes.get();
    }

    private void tickSpeed(long lastSpeedT, long lastSpeedB) {
        long now = System.currentTimeMillis();
        long dt = now - lastSpeedT;
        if (dt >= 800) {
            long db = currentBytesApprox() - lastSpeedB;
            STATE.bytesPerSec = (long) (db * 1000.0 / dt);
        }
    }

    private void shutdownPool() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void stopForegroundService() {
        releaseLocks();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Upload with retries
    // ------------------------------------------------------------------

    private void uploadWithRetry(SafTree.Entry e) {
        Exception last = null;
        for (int attempt = 1; attempt <= RETRIES && !cancelled; attempt++) {
            try {
                uploadOnce(e);
                STATE.filesDone++;
                STATE.bytesDone += e.size;
                synchronized (manifest) {
                    manifest.put(e.relPath, e.size);
                }
                return;
            } catch (Exception ex) {
                last = ex;
                try {
                    Thread.sleep(1200L * attempt);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
        if (!cancelled) {
            STATE.filesFailed++;
            STATE.message = "Fail: " + e.relPath
                    + (last != null ? " (" + last.getMessage() + ")" : "");
        }
    }

    private void uploadOnce(SafTree.Entry e) throws Exception {
        String q = URLEncoder.encode(e.relPath, StandardCharsets.UTF_8.name());
        URL u = new URL("http://" + holder.host + ":" + holder.port + "/file?path=" + q);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(e.size);
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            InputStream in = cr.openInputStream(e.docUri);
            if (in == null) throw new Exception("Could not open file: " + e.relPath);
            long sent = 0;
            try (OutputStream out = c.getOutputStream()) {
                byte[] buf = new byte[BUF];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sent += n;
                    inflightBytes.set(sent);
                    STATE.currentBytes = sent;
                }
            } finally {
                try { in.close(); } catch (Exception ignore) { }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            // consume response so the connection returns to the pool
            InputStream rs = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (rs != null) {
                byte[] sink = new byte[4096];
                while (rs.read(sink) > 0) { /* drain */ }
                rs.close();
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Tiny HTTP client helpers
    // ------------------------------------------------------------------

    private JSONObject httpGetJson(String path) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + holder.host + ":" + holder.port + path);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            in.close();
            return new JSONObject(sb.toString());
        } catch (Exception ex) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private Map<String, Long> fetchManifest() {
        JSONObject m = httpGetJson("/manifest");
        if (m == null || !m.optBoolean("ok")) return null;
        Map<String, Long> out = new HashMap<>();
        JSONObject files = m.optJSONObject("files");
        if (files != null) {
            java.util.Iterator<String> it = files.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, files.optLong(k, -1));
            }
        }
        return out;
    }

    private void httpPostJson(String path, String body) throws Exception {
        URL u = new URL("http://" + holder.host + ":" + holder.port + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(b);
            }
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        releaseLocks();
        shutdownPool();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
