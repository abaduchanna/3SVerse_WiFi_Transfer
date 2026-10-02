package com.threesverse.wifitransfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 3SVerse WiFi Transfer - phone side.
 * PC receiver broadcasts its address over UDP (port 8766); this app hears it
 * and auto-fills. User picks a folder (SAF), app streams everything to the
 * PC over local WiFi - no internet involved.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 41;
    private static final int REQ_NOTIF = 42;
    private static final String DISC_MAGIC = "3SVERSE-XFER";

    private boolean dark;
    private int bg, card, textMain, textSub, accent, accentDark, line;

    private EditText ipInput;
    private TextView status;
    private TextView phaseText;
    private TextView fileText;
    private TextView totalText;
    private ProgressBar fileBar;
    private ProgressBar totalBar;
    private TextView logText;
    private Button startBtn;
    private Button pauseBtn;
    private Button stopBtn;
    private TextView foundPc;

    private Thread discoThread;
    private volatile DatagramSocket discoSocket;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Deque<String> logLines = new ArrayDeque<>();
    private volatile boolean uiRunning = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.AppThemeDark : R.style.AppThemeLight);
        super.onCreate(savedInstanceState);
        colors();
        buildUi();
        startDiscovery();
        pollLoop();
        requestNotificationsIfNeeded();
    }

    private void colors() {
        bg = dark ? Color.rgb(16, 32, 26) : Color.rgb(242, 247, 245);
        card = dark ? Color.rgb(23, 45, 37) : Color.WHITE;
        textMain = dark ? Color.rgb(231, 243, 238) : Color.rgb(11, 31, 23);
        textSub = dark ? Color.rgb(157, 184, 173) : Color.rgb(91, 107, 100);
        accent = Color.rgb(14, 159, 110);
        accentDark = dark ? Color.rgb(52, 211, 153) : Color.rgb(11, 122, 85);
        line = dark ? Color.rgb(40, 70, 58) : Color.rgb(220, 232, 227);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadius(dp(14));
        box.setBackground(g);
        return box;
    }

    private TextView label(String s, int size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(bg);

        TextView title = label("3SVerse WiFi Transfer", 22, true, textMain);
        TextView sub = label("Phone → PC • local WiFi • internet nahi lagta", 12, false, textSub);
        root.addView(title);
        root.addView(sub);
        root.addView(space(10));

        // --- connection card ---
        LinearLayout c1 = cardBox();
        c1.addView(label("PC Connection", 14, true, textMain));
        c1.addView(space(6));
        foundPc = label("PC dhoond rahe hain… (dono same WiFi par hon)", 12, false, textSub);
        c1.addView(foundPc);
        c1.addView(space(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        ipInput = new EditText(this);
        ipInput.setHint("PC IP jaise 192.168.1.5");
        ipInput.setSingleLine(true);
        ipInput.setTextSize(14);
        ipInput.setTextColor(textMain);
        ipInput.setHintTextColor(textSub);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(dark ? Color.rgb(14, 30, 24) : Color.rgb(245, 248, 247));
        eg.setCornerRadius(dp(10));
        eg.setStroke(dp(1), line);
        ipInput.setBackground(eg);
        ipInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(ipInput, ipLp);

        Button testBtn = btn("Test");
        testBtn.setOnClickListener(v -> testPc());
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.leftMargin = dp(8);
        row.addView(testBtn, tLp);
        c1.addView(row);

        c1.addView(space(8));
        status = label("Status: PC ka wait…", 12, false, textSub);
        c1.addView(status);
        root.addView(c1);
        root.addView(space(10));

        // --- action card ---
        LinearLayout c2 = cardBox();
        startBtn = btn("Select Folder & Start Transfer");
        startBtn.setOnClickListener(v -> pickFolder());
        startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        c2.addView(startBtn);
        c2.addView(space(8));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        pauseBtn = btn("Pause");
        pauseBtn.setOnClickListener(v ->
                startService(svc(TransferService.ACTION_PAUSE)));
        stopBtn = btn("Stop");
        stopBtn.setOnClickListener(v ->
                startService(svc(TransferService.ACTION_STOP)));
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p2.leftMargin = dp(8);
        row2.addView(pauseBtn, p1);
        row2.addView(stopBtn, p2);
        c2.addView(row2);
        root.addView(c2);
        root.addView(space(10));

        // --- progress card ---
        LinearLayout c3 = cardBox();
        c3.addView(label("Progress", 14, true, textMain));
        c3.addView(space(6));
        phaseText = label("Koi transfer chal nahi raha.", 13, false, textMain);
        c3.addView(phaseText);
        c3.addView(space(6));
        fileText = label("", 12, false, textSub);
        c3.addView(fileText);
        fileBar = bar();
        c3.addView(fileBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        c3.addView(space(8));
        totalText = label("", 12, false, textSub);
        c3.addView(totalText);
        totalBar = bar();
        c3.addView(totalBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        root.addView(c3);
        root.addView(space(10));

        // --- log card ---
        LinearLayout c4 = cardBox();
        c4.addView(label("Log", 14, true, textMain));
        c4.addView(space(6));
        logText = label("", 11, false, textSub);
        logText.setTypeface(Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(this);
        sv.addView(logText);
        c4.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        root.addView(c4);

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);
    }

    private ProgressBar bar() {
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(10000);
        p.getProgressDrawable().setColorFilter(accentDark, android.graphics.PorterDuff.Mode.SRC_IN);
        return p;
    }

    private Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(accentDark);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Intent svc(String action) {
        Intent i = new Intent(this, TransferService.class);
        i.setAction(action);
        return i;
    }

    // ------------------------------------------------------------------
    // UDP discovery: PC broadcasts "3SVERSE-XFER|ip|port|hostname"
    // ------------------------------------------------------------------

    private void startDiscovery() {
        discoThread = new Thread(() -> {
            try {
                discoSocket = new DatagramSocket(null);
                discoSocket.setReuseAddress(true);
                discoSocket.bind(new InetSocketAddress(8766));
                discoSocket.setBroadcast(true);
                byte[] buf = new byte[512];
                while (!discoThread.isInterrupted() && uiRunning) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        discoSocket.receive(pkt);
                    } catch (Exception e) {
                        break;
                    }
                    String s = new String(pkt.getData(), 0, pkt.getLength(),
                            StandardCharsets.UTF_8).trim();
                    if (!s.startsWith(DISC_MAGIC + "|")) continue;
                    String[] parts = s.split("\\|");
                    if (parts.length < 4) continue;
                    final String ip = parts[1];
                    final String host = parts[3];
                    runOnUiThread(() -> {
                        if (foundPc != null) {
                            foundPc.setText("PC mil gaya: " + ip + "  (" + host + ")");
                            foundPc.setTextColor(accentDark);
                        }
                        if (ipInput != null && ipInput.getText().toString().trim().isEmpty()) {
                            ipInput.setText(ip);
                        }
                    });
                }
            } catch (Exception ignore) {
            }
        }, "discovery");
        discoThread.start();
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void testPc() {
        final String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Pehle PC ka IP likhein", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText("Status: test ho raha hai…");
        new Thread(() -> {
            String res = hello(ip);
            runOnUiThread(() -> {
                if (res == null) {
                    status.setText("Status: PC se raabta FAIL. Same WiFi? Firewall 'Allow'? IP sahi?");
                    status.setTextColor(Color.rgb(220, 60, 60));
                } else {
                    status.setText("Status: PC READY (" + res + " free space)");
                    status.setTextColor(accentDark);
                }
            });
        }, "hello").start();
    }

    private String hello(String ip) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + ip + ":8765/hello");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            byte[] b = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, StandardCharsets.UTF_8));
            in.close();
            JSONObject o = new JSONObject(sb.toString());
            if (!o.optBoolean("ok")) return null;
            long free = o.optLong("free", 0);
            return human(free);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
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

    private void pickFolder() {
        final String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Pehle PC ka IP likhein (ya PC chal kar aane dein)", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_PICK_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "Folder picker nahi khula: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignore) {
            }
            String ip = ipInput.getText().toString().trim();
            Intent i = new Intent(this, TransferService.class);
            i.setAction(TransferService.ACTION_START);
            i.putExtra("treeUri", tree.toString());
            i.putExtra("host", ip);
            i.putExtra("port", 8765);
            startService(i);
            log("Transfer start: " + ip);
        }
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    // ------------------------------------------------------------------
    // Progress polling
    // ------------------------------------------------------------------

    private void log(String s) {
        logLines.addLast("[" + java.time.LocalTime.now().withNano(0) + "] " + s);
        while (logLines.size() > 40) logLines.removeFirst();
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) sb.append(l).append('\n');
        logText.setText(sb.toString());
    }

    private void pollLoop() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!uiRunning) return;
                TransferService.State s = TransferService.STATE;
                phaseText.setText(s.message.isEmpty() ? "Koi transfer chal nahi raha." : s.message);
                switch (s.phase) {
                    case TransferService.State.TRANSFERRING:
                        fileText.setText("File: " + s.currentFile
                                + "  (" + human(s.currentBytes) + " / " + human(s.currentSize) + ")");
                        fileBar.setProgress(s.currentSize > 0
                                ? (int) (10000.0 * s.currentBytes / s.currentSize) : 0);
                        totalText.setText("Files: " + s.filesDone + " / " + s.filesTotal
                                + "  •  " + human(s.bytesDone) + " / " + human(s.bytesTotal)
                                + "  •  " + human(s.bytesPerSec) + "/s"
                                + "  •  ETA " + eta(s));
                        totalBar.setProgress(s.bytesTotal > 0
                                ? (int) (10000.0 * s.bytesDone / s.bytesTotal) : 0);
                        break;
                    case TransferService.State.SCANNING:
                        fileText.setText("");
                        fileBar.setProgress(0);
                        totalText.setText("");
                        totalBar.setProgress(0);
                        break;
                    case TransferService.State.DONE:
                        totalBar.setProgress(10000);
                        fileBar.setProgress(10000);
                        break;
                    default:
                        break;
                }
                startBtn.setEnabled(s.phase != TransferService.State.TRANSFERRING
                        && s.phase != TransferService.State.SCANNING);
                ui.postDelayed(this, 500);
            }
        }, 500);
    }

    private String eta(TransferService.State s) {
        if (s.bytesPerSec <= 0) return "…";
        long sec = (long) ((s.bytesTotal - s.bytesDone) / (double) s.bytesPerSec);
        if (sec < 0) sec = 0;
        long h = sec / 3600, m = (sec % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        return m + "m " + (sec % 60) + "s";
    }

    @Override
    protected void onDestroy() {
        uiRunning = false;
        ui.removeCallbacksAndMessages(null);
        if (discoSocket != null && !discoSocket.isClosed()) discoSocket.close();
        if (discoThread != null) discoThread.interrupt();
        super.onDestroy();
    }
}
