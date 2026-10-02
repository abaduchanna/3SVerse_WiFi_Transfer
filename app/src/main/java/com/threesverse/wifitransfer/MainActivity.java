package com.threesverse.wifitransfer;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
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
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.CheckBox;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 3SVerse WiFi Transfer - phone side.
 *
 * Pick a main folder once (usually Internal Storage = whole phone), browse it
 * in-app: folders expandable, checkbox = include whole folder, individual
 * files selectable too. Transfer streams to the PC over local WiFi.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 41;
    private static final int REQ_NOTIF = 42;
    private static final String DISC_MAGIC = "3SVERSE-XFER";

    private boolean dark;
    private int bg, card, textMain, textSub, accentDark, line, dimText;

    private EditText ipInput;
    private TextView status;
    private TextView foundPc;
    private TextView rootLabel;
    private TextView selSummary;
    private LinearLayout browserBox;
    private ScrollView browserScroll;
    private Button startBtn;
    private Button pauseBtn;
    private Button stopBtn;
    private TextView phaseText;
    private TextView fileText;
    private TextView totalText;
    private ProgressBar fileBar;
    private ProgressBar totalBar;
    private TextView logText;

    // --- browser state ---
    private String rootUriStr, rootDocId, rootName;
    private final Map<String, List<SafTree.Node>> childrenCache = new HashMap<>();
    private final Map<String, SafTree.Node> nodeByDocId = new HashMap<>();
    private final Set<String> expanded = new HashSet<>();
    private final Set<String> checkedDirs = new HashSet<>();
    private final Set<String> checkedFiles = new HashSet<>();

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
        // 3SVerse Studio palette (same tokens as the PC app / 3sverse.com)
        bg = dark ? Color.rgb(7, 6, 11) : Color.rgb(245, 241, 230);
        card = dark ? Color.rgb(13, 12, 20) : Color.WHITE;
        textMain = dark ? Color.rgb(245, 241, 230) : Color.rgb(20, 18, 26);
        textSub = dark ? Color.rgb(155, 151, 179) : Color.rgb(107, 104, 128);
        accentDark = dark ? Color.rgb(110, 231, 239) : Color.rgb(14, 124, 140);
        line = dark ? Color.rgb(35, 33, 48) : Color.rgb(230, 228, 238);
        dimText = dark ? Color.rgb(94, 90, 117) : Color.rgb(160, 175, 168);
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

        // Stacked brand header (Studio pattern): logo / headline / sub-heading
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        logo.setMaxHeight(dp(56));
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp0.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(logo, lp0);
        root.addView(space(6));

        TextView title = label("3SVERSE WIFI TRANSFER", 16, true, textMain);
        title.setLetterSpacing(0.18f);
        TextView sub = label("PHONE → PC · LOCAL WIFI · NO CLOUD", 10, false, textSub);
        sub.setLetterSpacing(0.3f);
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpT.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(title, lpT);
        root.addView(sub, new LinearLayout.LayoutParams(lpT));
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
        row.addView(ipInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button testBtn = btnGhost("Test");
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

        // --- source / browser card ---
        LinearLayout c2 = cardBox();
        c2.addView(label("What to Send (Selection)", 14, true, textMain));
        c2.addView(space(6));

        rootLabel = label("Main folder: (not chosen yet)", 12, false, textSub);
        c2.addView(rootLabel);

        Button pickBtn = btn("Choose the Main Folder (Internal Storage)");
        pickBtn.setOnClickListener(v -> pickFolder());
        c2.addView(pickBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c2.addView(space(8));

        browserBox = new LinearLayout(this);
        browserBox.setOrientation(LinearLayout.VERTICAL);
        browserScroll = new ScrollView(this);
        browserScroll.addView(browserBox);
        GradientDrawable bg2 = new GradientDrawable();
        bg2.setColor(dark ? Color.rgb(14, 30, 24) : Color.rgb(245, 248, 247));
        bg2.setCornerRadius(dp(10));
        bg2.setStroke(dp(1), line);
        browserScroll.setBackground(bg2);
        browserScroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        c2.addView(browserScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        c2.addView(space(6));

        TextView hint = label("Folder checkbox = the whole folder (all sub-folders). "
                + "Tap a name to expand/collapse it. A file checkbox selects just that file.", 11, false, textSub);
        c2.addView(hint);
        c2.addView(space(8));

        selSummary = label("Selected: nothing", 12, true, accentDark);
        c2.addView(selSummary);
        c2.addView(space(8));

        startBtn = btn("Start Transfer");
        startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        startBtn.setOnClickListener(v -> startTransfer());
        c2.addView(startBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c2.addView(space(6));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        pauseBtn = btnGhost("Pause");
        pauseBtn.setOnClickListener(v -> startService(svc(TransferService.ACTION_PAUSE)));
        stopBtn = btnGhost("Stop");
        stopBtn.setOnClickListener(v -> startService(svc(TransferService.ACTION_STOP)));
        row2.addView(pauseBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p2.leftMargin = dp(8);
        row2.addView(stopBtn, p2);
        c2.addView(row2);
        root.addView(c2);
        root.addView(space(10));

        // --- progress card ---
        LinearLayout c3 = cardBox();
        c3.addView(label("Progress", 14, true, textMain));
        c3.addView(space(6));
        phaseText = label("No transfer running.", 13, false, textMain);
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        root.addView(c4);

        // Animated brand background (Studio): the 3sverse.com hero ring +
        // glossy orb behind the content, at the site's exact speeds
        // (ring 120s/turn + 16px/12s float, orb 140s/turn + 12px/13s float).
        FrameLayout bgLayer = new FrameLayout(this);
        bgLayer.setBackgroundColor(bg);

        ImageView ring = new ImageView(this);
        ring.setImageResource(R.drawable.bg_spiral);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(520), dp(540));
        rp.gravity = Gravity.TOP | Gravity.END;
        rp.setMarginEnd(dp(210));
        rp.topMargin = dp(30);
        ring.setLayoutParams(rp);
        ring.setAlpha(0.45f);
        RotateAnimation ringSpin = new RotateAnimation(0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        ringSpin.setDuration(120000L);
        ringSpin.setInterpolator(new LinearInterpolator());
        ringSpin.setRepeatCount(Animation.INFINITE);
        ring.startAnimation(ringSpin);
        TranslateAnimation ringFloat = new TranslateAnimation(0f, 0f, dp(16), -dp(16));
        ringFloat.setDuration(6000L);
        ringFloat.setRepeatMode(Animation.REVERSE);
        ringFloat.setRepeatCount(Animation.INFINITE);
        ring.startAnimation(ringFloat);
        bgLayer.addView(ring);

        ImageView orb = new ImageView(this);
        orb.setImageResource(R.drawable.bg_orb);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(dp(300), dp(300));
        op.gravity = Gravity.BOTTOM | Gravity.START;
        op.leftMargin = dp(60);
        op.bottomMargin = dp(70);
        orb.setLayoutParams(op);
        orb.setAlpha(0.4f);
        RotateAnimation orbSpin = new RotateAnimation(0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        orbSpin.setDuration(140000L);
        orbSpin.setInterpolator(new LinearInterpolator());
        orbSpin.setRepeatCount(Animation.INFINITE);
        orb.startAnimation(orbSpin);
        TranslateAnimation orbFloat = new TranslateAnimation(0f, 0f, dp(12), -dp(12));
        orbFloat.setDuration(6500L);
        orbFloat.setRepeatMode(Animation.REVERSE);
        orbFloat.setRepeatCount(Animation.INFINITE);
        orb.startAnimation(orbFloat);
        bgLayer.addView(orb);

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.addView(bgLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootFrame.addView(outer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootFrame);
    }

    // ------------------------------------------------------------------
    // Browser rendering
    // ------------------------------------------------------------------

    private List<SafTree.Node> childrenOf(String docId, String rel) {
        List<SafTree.Node> list = childrenCache.get(docId);
        if (list == null) {
            ContentResolver cr = getContentResolver();
            list = SafTree.listChildren(cr, Uri.parse(rootUriStr), docId, rel);
            childrenCache.put(docId, list);
            for (SafTree.Node n : list) {
                if (!nodeByDocId.containsKey(n.docId)) nodeByDocId.put(n.docId, n);
            }
        }
        return list;
    }

    private void renderBrowser() {
        browserBox.removeAllViews();
        if (rootUriStr == null) {
            TextView t = label("Choose the Main Folder first.", 12, false, textSub);
            t.setPadding(dp(6), dp(10), dp(6), dp(10));
            browserBox.addView(t);
            updateSummary();
            return;
        }
        renderRows(rootDocId, "", 0, false);
        updateSummary();
    }

    private void renderRows(String parentDocId, String parentRel, int depth, boolean parentChecked) {
        List<SafTree.Node> kids = childrenOf(parentDocId, parentRel);
        for (SafTree.Node n : kids) {
            addRow(n, depth, parentChecked);
            if (n.dir && expanded.contains(n.docId)) {
                renderRows(n.docId, n.rel, depth + 1,
                        parentChecked || checkedDirs.contains(n.docId));
            }
        }
    }

    private void addRow(final SafTree.Node n, int depth, boolean insideChecked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(depth * 14), dp(2), dp(2), dp(2));

        final boolean checked = n.dir
                ? checkedDirs.contains(n.docId)
                : checkedFiles.contains(n.docId);
        final boolean locked = insideChecked && !checked;

        CheckBox cb = new CheckBox(this);
        cb.setChecked(checked);
        cb.setEnabled(!locked);
        cb.setScaleX(0.9f);
        cb.setScaleY(0.9f);
        cb.setOnCheckedChangeListener((b, isChecked) -> {
            if (n.dir) {
                if (isChecked) checkedDirs.add(n.docId);
                else checkedDirs.remove(n.docId);
            } else {
                if (isChecked) checkedFiles.add(n.docId);
                else checkedFiles.remove(n.docId);
            }
            renderBrowser();
        });
        row.addView(cb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        String shown = n.dir
                ? (expanded.contains(n.docId) ? "▾ " : "▸ ") + n.name
                : n.name + "  (" + human(n.size) + ")";
        name.setText(shown);
        name.setTextSize(13);
        name.setTextColor(locked ? dimText : (checked ? accentDark : textMain));
        name.setTypeface(n.dir ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        name.setSingleLine(true);
        name.setPadding(dp(2), dp(8), dp(2), dp(8));
        name.setOnClickListener(v -> {
            if (!n.dir) return;
            if (expanded.contains(n.docId)) expanded.remove(n.docId);
            else {
                expanded.add(n.docId);
                childrenOf(n.docId, n.rel); // lazy load
            }
            renderBrowser();
        });
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        browserBox.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void updateSummary() {
        int nf = checkedFiles.size();
        int nd = checkedDirs.size();
        if (nf == 0 && nd == 0) {
            selSummary.setText("Selected: nothing");
            selSummary.setTextColor(textSub);
        } else {
            selSummary.setText("Selected: " + nd + " folder(s), " + nf + " file(s)"
                    + (checkedDirs.contains(rootDocId) ? "  •  ENTIRE STORAGE" : ""));
            selSummary.setTextColor(accentDark);
        }
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private ProgressBar bar() {
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(10000);
        p.getProgressDrawable().setColorFilter(accentDark, android.graphics.PorterDuff.Mode.SRC_IN);
        return p;
    }

    private Button btn(String s) {
        // Primary CTA: the Studio cyan -> magenta gradient with dark text
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.rgb(7, 6, 11));
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(110, 231, 239), Color.rgb(228, 75, 215)});
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    private Button btnGhost(String s) {
        // Secondary action: Studio ghost style (bordered, dim text)
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(dark ? Color.rgb(185, 181, 204) : Color.rgb(20, 18, 26));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), line);
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
                            foundPc.setText("PC found: " + ip + "  (" + host + ")");
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
            Toast.makeText(this, "Enter the PC IP first", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText("Status: testing…");
        new Thread(() -> {
            String res = hello(ip);
            runOnUiThread(() -> {
                if (res == null) {
                    status.setText("Status: PC unreachable. Same WiFi? Firewall allowed? Correct IP?");
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
            return human(o.optLong("free", 0));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void pickFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_PICK_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "Folder picker failed to open: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
            rootUriStr = tree.toString();
            rootDocId = DocumentsContract.getTreeDocumentId(tree);
            rootName = friendlyRoot(rootDocId);
            childrenCache.clear();
            nodeByDocId.clear();
            expanded.clear();
            checkedFiles.clear();
            checkedDirs.clear();
            checkedDirs.add(rootDocId); // default: whole picked folder
            SafTree.Node rootNode = new SafTree.Node(rootDocId, rootName, "", true, 0);
            nodeByDocId.put(rootDocId, rootNode);
            rootLabel.setText("Main folder: " + rootName + "  (entire transfer selected)");
            expanded.add(rootDocId);
            renderBrowser();
            log("Main folder: " + rootName);
        }
    }

    private String friendlyRoot(String docId) {
        if (docId == null) return "?";
        if (docId.equals("primary:")) return "Internal Storage";
        if (docId.startsWith("primary:")) return "Internal Storage/" + docId.substring(8);
        return docId.endsWith(":") ? docId.substring(0, docId.length() - 1) : docId;
    }

    private void startTransfer() {
        final String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter the PC IP first (or let the PC server run)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (rootUriStr == null) {
            Toast.makeText(this, "Choose the Main Folder first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkedDirs.isEmpty() && checkedFiles.isEmpty()) {
            Toast.makeText(this, "No folder/file selected", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> selDirs = new ArrayList<>();
        for (String docId : checkedDirs) {
            SafTree.Node n = nodeByDocId.get(docId);
            if (n != null) selDirs.add(docId + "\t" + n.rel);
        }
        List<String> selFiles = new ArrayList<>();
        for (String docId : checkedFiles) {
            SafTree.Node n = nodeByDocId.get(docId);
            if (n != null) selFiles.add(docId + "\t" + n.rel + "\t" + n.size);
        }
        Intent i = new Intent(this, TransferService.class);
        i.setAction(TransferService.ACTION_START);
        i.putExtra("treeUri", rootUriStr);
        i.putExtra("host", ip);
        i.putExtra("port", 8765);
        i.putExtra("selDirs", selDirs.toArray(new String[0]));
        i.putExtra("selFiles", selFiles.toArray(new String[0]));
        startService(i);
        log("Transfer start: " + ip + " • " + selDirs.size() + " folders, "
                + selFiles.size() + " files");
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
                phaseText.setText(s.message.isEmpty() ? "No transfer running." : s.message);
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
