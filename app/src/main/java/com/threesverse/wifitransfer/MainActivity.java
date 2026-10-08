package com.threesverse.wifitransfer;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
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
    private static final int REQ_WRITE_LEGACY = 43;
    private static final String DISC_MAGIC = "3SVERSE-XFER";
    private static final String RX_SUBDIR = "3SVerse WiFi Transfer";

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
    // v1.4.9 exclusion model: relative paths excluded from an INCLUDED parent
    // (unticking a file/folder inside a ticked folder excludes just that item
    // instead of blocking the checkbox - fixes single-file selection).
    private final Set<String> excludedDirs = new HashSet<>();
    private final Set<String> excludedFiles = new HashSet<>();

    private Thread discoThread;
    private volatile DatagramSocket discoSocket;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Deque<String> logLines = new ArrayDeque<>();
    private volatile boolean uiRunning = true;

    // --- PC -> phone receive state ---
    private TextView rxStatus;
    private Button receiveBtn;
    private TextView rxProgress;
    private volatile boolean receiving = false;
    private long lastOutboxPoll = 0;
    private boolean legacyWritePending = false;

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
        // Wide monogram wordmark (transparent, ~1.9:1) - the square tile read
        // as a narrow little box in the header.
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_header);
        logo.setAdjustViewBounds(true);
        // License Studio header parity (user order 2026-10-06): logo 168dp wide
        // (tight 654x155 wordmark), 8dp gaps, sub 9.5sp/.3em #8b87a0, 20dp below.
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(
                dp(168), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp0.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(logo, lp0);
        root.addView(space(8));

        TextView title = label("3SVERSE WIFI TRANSFER", 16, true, textMain);
        title.setLetterSpacing(0.18f);
        title.setGravity(Gravity.CENTER);
        TextView sub = label("PHONE → PC · LOCAL WIFI · NO CLOUD", 10, false, textSub);
        sub.setTextSize(9.5f);
        sub.setTextColor(dark ? Color.rgb(139, 135, 160) : textSub);
        sub.setLetterSpacing(0.3f);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpT.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(title, lpT);
        root.addView(space(8));
        root.addView(sub, new LinearLayout.LayoutParams(lpT));
        root.addView(space(20));

        // --- connection card ---
        LinearLayout c1 = cardBox();
        c1.addView(label("PC Connection", 14, true, textMain));
        c1.addView(space(6));
        foundPc = label("Looking for the PC… (both devices on the same WiFi)", 12, false, textSub);
        c1.addView(foundPc);
        c1.addView(space(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        ipInput = new EditText(this);
        ipInput.setHint("PC IP like 192.168.1.5");
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
        status = label("Status: waiting for the PC…", 12, false, textSub);
        c1.addView(status);
        root.addView(c1);
        root.addView(space(10));

        // --- PC -> phone receive card (two-way transfer) ---
        LinearLayout c1b = cardBox();
        c1b.addView(label("PC → Phone", 14, true, textMain));
        c1b.addView(space(6));
        rxStatus = label("Queue on the PC: (looking…)", 12, false, textSub);
        c1b.addView(rxStatus);
        c1b.addView(space(8));
        receiveBtn = btn("Receive on Phone");
        receiveBtn.setEnabled(false);
        receiveBtn.setOnClickListener(v -> startReceive());
        c1b.addView(receiveBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c1b.addView(space(6));
        rxProgress = label("Files land in Downloads/" + RX_SUBDIR + ".", 12, false, textSub);
        c1b.addView(rxProgress);
        root.addView(c1b);
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
                + "Tap a name to expand/collapse it. A file checkbox selects just that file. "
                + "The first row is the Main Folder itself - untick it to pick individual "
                + "folders and files instead.", 11, false, textSub);
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

        // Fixed brand footer, the 3SVerse standard used by ALL four
        // surfaces (xfer APK + xfer PC dashboard + Studio exe + Studio
        // app): 32dp strip, bg #0d0c14, developed-by CENTER + app
        // version RIGHT, 1dp top border (user: "xfer ka footer bhi
        // standardize kardo"). It remains visible at the bottom while
        // the transfer controls scroll independently above it.
        FrameLayout footer = new FrameLayout(this);
        footer.setBackgroundColor(Color.rgb(13, 12, 20));
        TextView dev = label("DEVELOPED BY WWW.3SVERSE.COM", 9, true,
                dark ? Color.rgb(199, 203, 224) : Color.rgb(245, 241, 230));
        dev.setGravity(Gravity.CENTER);
        dev.setLetterSpacing(0.12f);
        footer.addView(dev, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        String vname;
        try {
            vname = "v" + getPackageManager().getPackageInfo(
                    getPackageName(), 0).versionName;
        } catch (Exception e) {
            vname = "";
        }
        TextView ver = label(vname, 8, true,
                dark ? Color.rgb(139, 135, 160) : Color.rgb(107, 104, 128));
        ver.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ver.setLetterSpacing(0.08f);
        ver.setPadding(0, 0, dp(10), 0);
        footer.addView(ver, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        // Animated brand background (Studio): the 3sverse.com hero ring +
        // glossy orb behind the content. License Studio dashboard geometry
        // (user order 2026-10-06: use the large LS spiral/orb, the smaller
        // xfer ones are gone) - ring 120s/turn + 16px/12s float, orb 140s/turn + 12px/13s.
        FrameLayout bgLayer = new FrameLayout(this);
        bgLayer.setBackgroundColor(bg);

        ImageView ring = new ImageView(this);
        ring.setImageResource(R.drawable.bg_spiral);
        // LS dashboard geometry (android assets/public #bgart): ring = 88vw
        // (capped at 560px), 38vw off the right edge, 8vw above the top edge;
        // spiral.webp aspect (900x932) keeps the height. Opacity .68.
        android.util.DisplayMetrics dmp = getResources().getDisplayMetrics();
        int sw = dmp.widthPixels, sh = dmp.heightPixels;
        int ringW = (int) Math.min(sw * 0.88f, dp(560));
        int ringH = (int) (ringW * 932.0 / 900.0);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ringW, ringH);
        rp.gravity = Gravity.TOP | Gravity.END;
        rp.setMarginEnd((int) (-sw * 0.38f));
        rp.topMargin = (int) (-sw * 0.08f);
        ring.setLayoutParams(rp);
        ring.setAlpha(0.9f);
        // Spin + float TOGETHER. (View.startAnimation() holds ONE animation -
        // the second call replaced the first, so the ring only bobbed and
        // never spun like the website. Property animators compose properly.)
        ObjectAnimator ringSpin = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f);
        ringSpin.setDuration(120000L);
        ringSpin.setInterpolator(new LinearInterpolator());
        ringSpin.setRepeatCount(ObjectAnimator.INFINITE);
        ObjectAnimator ringFloat = ObjectAnimator.ofFloat(ring, View.TRANSLATION_Y, -dp(16), dp(16));
        ringFloat.setDuration(12000L);
        ringFloat.setInterpolator(new AccelerateDecelerateInterpolator());
        ringFloat.setRepeatCount(ObjectAnimator.INFINITE);
        ringFloat.setRepeatMode(ObjectAnimator.REVERSE);
        ringSpin.start();
        ringFloat.start();
        bgLayer.addView(ring);

        ImageView orb = new ImageView(this);
        orb.setImageResource(R.drawable.bg_orb);
        // orb = 70vw (capped at 420px), 13vw off the left edge and 12vw
        // below the bottom edge - the exact LS composition. Opacity .56.
        int orbS = (int) Math.min(sw * 0.70f, dp(420));
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(orbS, orbS);
        op.gravity = Gravity.BOTTOM | Gravity.START;
        op.leftMargin = (int) (-sw * 0.13f);
        op.bottomMargin = (int) (-sw * 0.12f);
        orb.setLayoutParams(op);
        orb.setAlpha(0.95f);
        // Same fix as the ring: spin + float as two property animators.
        ObjectAnimator orbSpin = ObjectAnimator.ofFloat(orb, View.ROTATION, 0f, 360f);
        orbSpin.setDuration(140000L);
        orbSpin.setInterpolator(new LinearInterpolator());
        orbSpin.setRepeatCount(ObjectAnimator.INFINITE);
        ObjectAnimator orbFloat = ObjectAnimator.ofFloat(orb, View.TRANSLATION_Y, -dp(12), dp(12));
        orbFloat.setDuration(13000L);
        orbFloat.setInterpolator(new AccelerateDecelerateInterpolator());
        orbFloat.setRepeatCount(ObjectAnimator.INFINITE);
        orbFloat.setRepeatMode(ObjectAnimator.REVERSE);
        orbSpin.start();
        orbFloat.start();
        bgLayer.addView(orb);

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.addView(bgLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams outerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        outerParams.bottomMargin = dp(32);
        rootFrame.addView(outer, outerParams);
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32), Gravity.BOTTOM);
        rootFrame.addView(footer, footerParams);
        // 1dp top border on the footer strip (footer standard)
        View footerLine = new View(this);
        footerLine.setBackgroundColor(line);
        FrameLayout.LayoutParams footerLineParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.BOTTOM);
        footerLineParams.bottomMargin = dp(32);
        rootFrame.addView(footerLine, footerLineParams);
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
        // Keep the user's scroll position across rebuilds (toggling a checkbox
        // or expanding a deep folder used to snap the list back to the top).
        final int scrollY = browserScroll != null ? browserScroll.getScrollY() : 0;
        browserBox.removeAllViews();
        if (rootUriStr == null) {
            TextView t = label("Choose the Main Folder first.", 12, false, textSub);
            t.setPadding(dp(6), dp(10), dp(6), dp(10));
            browserBox.addView(t);
            updateSummary();
            return;
        }
        // The Main Folder itself gets its own checkbox row so "entire storage"
        // can be unticked - otherwise every child row stays locked and only
        // one (implicit) selection is possible.
        SafTree.Node rootNode = nodeByDocId.get(rootDocId);
        if (rootNode != null) {
            addRow(rootNode, 0, false);
        }
        renderRows(rootDocId, "", 0, checkedDirs.contains(rootDocId));
        browserBox.post(() -> {
            if (browserScroll != null) browserScroll.scrollTo(0, scrollY);
        });
        updateSummary();
    }

    private void renderRows(String parentDocId, String parentRel, int depth, boolean parentIncluded) {
        List<SafTree.Node> kids = childrenOf(parentDocId, parentRel);
        for (SafTree.Node n : kids) {
            addRow(n, depth, parentIncluded);
            if (n.dir && expanded.contains(n.docId)) {
                // Effective inclusion of this folder decides whether its own
                // children are implicit (excludable) or explicit selections.
                boolean selfIncluded = parentIncluded
                        ? !excludedDirs.contains(n.rel)
                        : checkedDirs.contains(n.docId);
                renderRows(n.docId, n.rel, depth + 1, selfIncluded);
            }
        }
    }

    private void addRow(final SafTree.Node n, int depth, boolean insideChecked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(depth * 14), dp(2), dp(2), dp(2));

        // v1.4.9 inclusion/exclusion model: items inside an included folder
        // show as ticked and stay ENABLED - unticking excludes just that item
        // from the parent's transfer. When the parent is not included, the
        // checkbox is a normal explicit selection. Single files inside a
        // folder are therefore always selectable.
        final boolean checked = n.dir
                ? (insideChecked
                        ? !excludedDirs.contains(n.rel)
                        : checkedDirs.contains(n.docId))
                : (insideChecked
                        ? !excludedFiles.contains(n.rel)
                        : checkedFiles.contains(n.docId));

        CheckBox cb = new CheckBox(this);
        cb.setChecked(checked);
        cb.setEnabled(true);
        cb.setScaleX(0.9f);
        cb.setScaleY(0.9f);
        cb.setOnCheckedChangeListener((b, isChecked) -> {
            if (insideChecked) {
                // Parent included: the checkbox excludes/includes this one item.
                if (isChecked) {
                    if (n.dir) excludedDirs.remove(n.rel);
                    else excludedFiles.remove(n.rel);
                } else {
                    if (n.dir) {
                        excludedDirs.add(n.rel);
                        checkedDirs.remove(n.docId);
                    } else {
                        excludedFiles.add(n.rel);
                        checkedFiles.remove(n.docId);
                    }
                }
            } else {
                if (n.dir) {
                    if (isChecked) {
                        checkedDirs.add(n.docId);
                        excludedDirs.remove(n.rel);
                    } else {
                        checkedDirs.remove(n.docId);
                    }
                } else {
                    if (isChecked) {
                        checkedFiles.add(n.docId);
                        excludedFiles.remove(n.rel);
                    } else {
                        checkedFiles.remove(n.docId);
                    }
                }
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
        name.setTextColor(checked ? accentDark : textMain);
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
        int nf = 0;
        for (String docId : checkedFiles) {
            SafTree.Node n = nodeByDocId.get(docId);
            if (n != null && !excludedFiles.contains(n.rel)) nf++;
        }
        int nd = checkedDirs.size();
        int nx = excludedFiles.size() + excludedDirs.size();
        if (nf == 0 && nd == 0) {
            selSummary.setText(nx > 0
                    ? "Selected: " + nx + " item(s) excluded"
                    : "Selected: nothing");
            selSummary.setTextColor(textSub);
        } else {
            selSummary.setText("Selected: " + nd + " folder(s), " + nf + " file(s)"
                    + (nx > 0 ? "  •  " + nx + " excluded" : "")
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
        // Primary CTA: the Studio cyan -> magenta gradient with dark text.
        // Pressed = brighter gradient, the touch twin of the PC dashboard
        // hover (filter: brightness(1.1)) - "button/gradient/hover
        // standardize karo".
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
        GradientDrawable gPress = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(122, 236, 241), Color.rgb(231, 97, 220)});
        gPress.setCornerRadius(dp(12));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, gPress);
        sl.addState(new int[]{}, g);
        b.setBackground(sl);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    private Button btnGhost(String s) {
        // Secondary action: Studio ghost style (bordered, dim text).
        // Pressed = cyan border + cyan text + faint cyan wash, the touch
        // twin of the PC ghost hover (border-color + color -> cyan).
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        int rest = dark ? Color.rgb(185, 181, 204) : Color.rgb(20, 18, 26);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), line);
        GradientDrawable gPress = new GradientDrawable();
        gPress.setColor(Color.argb(26, 110, 231, 239));
        gPress.setCornerRadius(dp(12));
        gPress.setStroke(dp(1), Color.rgb(110, 231, 239));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, gPress);
        sl.addState(new int[]{}, g);
        b.setBackground(sl);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_pressed}, {}},
                new int[]{Color.rgb(110, 231, 239), rest}));
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
            JSONObject o = helloFull(ip);
            runOnUiThread(() -> {
                if (o == null) {
                    status.setText("Status: PC unreachable - run the PC app (v1.3+) on the "
                            + "same WiFi and click 'Yes' on the Windows firewall prompt.");
                    status.setTextColor(Color.rgb(220, 60, 60));
                } else if (o.optString("version", "").isEmpty()) {
                    status.setText("Status: PC app is outdated - download the new PC exe "
                            + "(v1.3+) from GitHub and run it on the PC.");
                    status.setTextColor(Color.rgb(220, 60, 60));
                } else {
                    status.setText("Status: PC READY (v" + o.optString("version")
                            + " \u00b7 " + human(o.optLong("free", 0)) + " free)");
                    status.setTextColor(accentDark);
                }
            });
        }, "hello").start();
    }

    private JSONObject helloFull(String ip) {
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
            return o;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // PC -> phone receive (pull model: the phone stays the HTTP client)
    // ------------------------------------------------------------------

    private void pollOutboxTick() {
        if (receiving || rxStatus == null) return;
        long now = System.currentTimeMillis();
        if (now - lastOutboxPoll < 3500) return;
        lastOutboxPoll = now;
        final String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) return;
        new Thread(() -> {
            JSONObject o = helloFull(ip);
            runOnUiThread(() -> {
                if (o == null) {
                    rxStatus.setText("PC unreachable - run the PC app (v1.3+) on the PC "
                            + "and allow the firewall prompt.");
                    rxStatus.setTextColor(textSub);
                    receiveBtn.setEnabled(false);
                } else if (o.optString("version", "").isEmpty()) {
                    rxStatus.setText("PC app is outdated - download the new PC exe (v1.3+) "
                            + "from GitHub and run it on the PC.");
                    rxStatus.setTextColor(textSub);
                    receiveBtn.setEnabled(false);
                } else {
                    int nFiles = o.optInt("outbox", 0);
                    long nBytes = o.optLong("outboxBytes", 0);
                    if (nFiles > 0) {
                        rxStatus.setText("PC has " + nFiles + " file(s) (" + human(nBytes)
                                + ") waiting for this phone.");
                        rxStatus.setTextColor(accentDark);
                    } else {
                        rxStatus.setText("PC is ready. Use the 'Send to phone' card on the PC dashboard.");
                        rxStatus.setTextColor(textSub);
                    }
                    receiveBtn.setEnabled(nFiles > 0 && !receiving);
                }
            });
        }, "outbox-poll").start();
    }

    private void startReceive() {
        final String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter the PC IP first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (receiving) return;
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            legacyWritePending = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_LEGACY);
            return;
        }
        doReceive(ip);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_LEGACY && legacyWritePending) {
            legacyWritePending = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doReceive(ipInput.getText().toString().trim());
            } else {
                Toast.makeText(this, "Storage permission is needed to save the files",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doReceive(String ip) {
        receiving = true;
        receiveBtn.setEnabled(false);
        rxProgress.setText("Connecting…");
        new Thread(() -> {
            int ok = 0, failed = 0;
            try {
                JSONObject list = getJson("http://" + ip + ":8765/outbox/list");
                JSONArray files = list == null ? null : list.optJSONArray("files");
                if (files == null) throw new Exception("Could not read the PC queue");
                int total = files.length();
                for (int i = 0; i < total; i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    String name = f.optString("name", "file_" + i);
                    final int idx = i + 1, idxTotal = total;
                    runOnUiThread(() -> rxProgress.setText("Receiving " + idx + "/" + idxTotal + ": " + name));
                    try {
                        downloadToDownloads(ip, name);
                        ok++;
                    } catch (Exception e) {
                        failed++;
                    }
                }
                final int okF = ok, failF = failed;
                runOnUiThread(() -> {
                    rxProgress.setText(okF + " file(s) received"
                            + (failF > 0 ? ", " + failF + " failed" : "")
                            + "  →  Downloads/" + RX_SUBDIR);
                    log("PC → phone: " + okF + " received"
                            + (failF > 0 ? ", " + failF + " failed" : ""));
                    receiving = false;
                    receiveBtn.setEnabled(true);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    rxProgress.setText("Receive failed: " + msg);
                    receiving = false;
                    receiveBtn.setEnabled(true);
                });
            }
        }, "receive").start();
    }

    private JSONObject getJson(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            in.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void downloadToDownloads(String ip, String name) throws Exception {
        String enc = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
        URL u = new URL("http://" + ip + ":8765/outbox/file?name=" + enc);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    saveViaMediaStore(name, in);
                } else {
                    saveLegacy(name, in);
                }
            } finally {
                try { in.close(); } catch (Exception ignore) { }
            }
        } finally {
            c.disconnect();
        }
    }

    private void saveViaMediaStore(String name, InputStream in) throws Exception {
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeFileName(name));
        cv.put(MediaStore.MediaColumns.MIME_TYPE, guessMime(name));
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + RX_SUBDIR);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("Could not create the download entry");
        OutputStream out = null;
        try {
            out = cr.openOutputStream(uri);
            if (out == null) throw new Exception("Could not open the download stream");
            copyStream(in, out);
            out.close();
            out = null;
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } catch (Exception e) {
            cr.delete(uri, null, null); // no half files
            throw e;
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignore) { }
            }
        }
    }

    private void saveLegacy(String name, InputStream in) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), RX_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Could not create " + dir);
        }
        File dst = new File(dir, sanitizeFileName(name));
        int k = 1;
        while (dst.exists()) {
            String base = sanitizeFileName(name);
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            String ext = dot > 0 ? base.substring(dot) : "";
            dst = new File(dir, stem + " (" + k + ")" + ext);
            k++;
        }
        OutputStream out = new java.io.FileOutputStream(dst);
        try {
            copyStream(in, out);
        } finally {
            out.close();
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String sanitizeFileName(String name) {
        String flat = name.replace('\\', '_').replace('/', '_').replace(':', '_');
        for (String ch : new String[]{"<", ">", "\"", "|", "?", "*"}) {
            flat = flat.replace(ch, "_");
        }
        return flat.trim().isEmpty() ? "file" : flat.trim();
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase();
        String[][] map = {
            {".jpg", "image/jpeg"}, {".jpeg", "image/jpeg"}, {".png", "image/png"},
            {".gif", "image/gif"}, {".webp", "image/webp"}, {".mp4", "video/mp4"},
            {".mp3", "audio/mpeg"}, {".wav", "audio/wav"}, {".pdf", "application/pdf"},
            {".zip", "application/zip"}, {".txt", "text/plain"}, {".csv", "text/csv"},
            {".apk", "application/vnd.android.package-archive"},
            {".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
            {".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
        };
        for (String[] m : map) {
            if (lower.endsWith(m[0])) return m[1];
        }
        return "application/octet-stream";
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
            excludedFiles.clear();
            excludedDirs.clear();
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
            if (n == null || excludedFiles.contains(n.rel)) continue;
            selFiles.add(docId + "\t" + n.rel + "\t" + n.size);
        }
        Intent i = new Intent(this, TransferService.class);
        i.setAction(TransferService.ACTION_START);
        i.putExtra("treeUri", rootUriStr);
        i.putExtra("host", ip);
        i.putExtra("port", 8765);
        i.putExtra("selDirs", selDirs.toArray(new String[0]));
        i.putExtra("selFiles", selFiles.toArray(new String[0]));
        i.putExtra("exclDirs", excludedDirs.toArray(new String[0]));
        i.putExtra("exclFiles", excludedFiles.toArray(new String[0]));
        // Clear any stale error/progress from a previous run BEFORE starting the
        // service, so the Progress card never shows an old failure (e.g. a
        // leftover "Could not reach PC address null:0") for the new attempt.
        TransferService.STATE.reset();
        TransferService.STATE.phase = TransferService.State.SCANNING;
        TransferService.STATE.message = "Connecting to the PC…";
        log("Transfer start: " + ip + " • " + selDirs.size() + " folders, "
                + selFiles.size() + " files");
        try {
            startService(i);
        } catch (Exception ex) {
            TransferService.STATE.phase = TransferService.State.ERROR;
            TransferService.STATE.error = "Could not start the transfer service ("
                    + ex.getClass().getSimpleName() + "). Reopen the app and try again.";
            TransferService.STATE.message = "Error: " + TransferService.STATE.error;
            log("Error: " + TransferService.STATE.error);
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
                pollOutboxTick();
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
