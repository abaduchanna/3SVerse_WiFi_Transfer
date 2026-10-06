package com.threesverse.wifitransfer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal Storage Access Framework tree helper (no AndroidX).
 * - listChildren: one directory level (for the in-app browser)
 * - walk / walkSubtree: recursive listing of every regular file (for transfer)
 */
public final class SafTree {

    private SafTree() {
    }

    private static final String[] PROJ = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS
    };

    /** One file found inside the picked tree (transfer unit). */
    public static final class Entry {
        public final String relPath;
        public final Uri docUri;
        public final long size;

        Entry(String relPath, Uri docUri, long size) {
            this.relPath = relPath;
            this.docUri = docUri;
            this.size = size;
        }
    }

    /** One child shown in the browser (file OR folder). */
    public static final class Node {
        public final String docId;
        public final String name;
        public final String rel;
        public final boolean dir;
        public final long size;

        public Node(String docId, String name, String rel, boolean dir, long size) {
            this.docId = docId;
            this.name = name;
            this.rel = rel;
            this.dir = dir;
            this.size = size;
        }
    }

    /** Called periodically during the scan so the UI can show progress. */
    public interface ScanProgress {
        void onScan(int filesFound);
    }

    /**
     * System junk directories that never contain user media - skipping them
     * saves scan time, thousands of pointless requests (MIUI gallery
     * ".deleteRecord" churn is thousands of 0-byte records), and keeps the
     * PC log clean. Case-sensitive: a user folder called "Trash" still ships.
     */
    private static final Set<String> JUNK_DIRS = new HashSet<>(java.util.Arrays.asList(
            ".deleteRecord",          // MIUI gallery records
            ".thumbnails", ".thumbnail", // DCIM thumbnails
            ".trash", ".RecycleBin",   // file-manager recycle bins
            ".lost+found",             // fsck
            ".cache"                   // app cache residue
    ));

    private static boolean isJunkDir(String name) {
        return JUNK_DIRS.contains(name);
    }

    // ------------------------------------------------------------------
    // Browser: list one directory level
    // ------------------------------------------------------------------

    public static List<Node> listChildren(ContentResolver cr, Uri treeUri,
                                          String parentDocId, String parentRel) {
        List<Node> out = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, parentDocId);
        Cursor c = null;
        try {
            c = cr.query(childrenUri, PROJ, null, null, null);
            if (c == null) return out;
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0L : c.getLong(3);
                int flags = c.getInt(4);
                if (name == null || name.isEmpty()) continue;
                if ((flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
                    continue;
                }
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                String rel = (parentRel == null || parentRel.isEmpty())
                        ? name : parentRel + "/" + name;
                out.add(new Node(docId, name, rel, dir, size));
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        out.sort((a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    // ------------------------------------------------------------------
    // Transfer: recursive file listing
    // ------------------------------------------------------------------

    /** Walk the WHOLE picked tree. */
    public static void walk(ContentResolver cr, Uri treeUri, List<Entry> out,
                            ScanProgress cb) throws Exception {
        String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        walkSubtree(cr, treeUri, rootDocId, "", out, new HashSet<String>(), cb);
    }

    /**
     * Walk any subtree of the picked tree, starting at {@code startDocId}
     * with relative path base {@code startRel}. Entries already present in
     * {@code seen} (by relPath) are skipped and new ones are added to it.
     */
    public static void walkSubtree(ContentResolver cr, Uri treeUri,
                                   String startDocId, String startRel,
                                   List<Entry> out, Set<String> seen,
                                   ScanProgress cb) throws Exception {
        walkSubtree(cr, treeUri, startDocId, startRel, out, seen, cb, null, null);
    }

    /**
     * Same as above, but skips directories listed in {@code excludedDirs} and
     * files listed in {@code excludedFiles} (both keyed by relative path).
     * These exclusion lists are built by the browser when the user unticks
     * individual items inside an included folder (v1.4.9 selection model).
     */
    public static void walkSubtree(ContentResolver cr, Uri treeUri,
                                   String startDocId, String startRel,
                                   List<Entry> out, Set<String> seen,
                                   ScanProgress cb,
                                   Set<String> excludedDirs,
                                   Set<String> excludedFiles) throws Exception {
        ArrayDeque<String[]> stack = new ArrayDeque<>();
        // [0] = documentId, [1] = relative directory path
        stack.push(new String[]{startDocId, startRel == null ? "" : startRel});
        int reported = 0;

        while (!stack.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("scan cancelled");
            }
            String[] cur = stack.pop();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, cur[0]);
            Cursor c = null;
            try {
                c = cr.query(childrenUri, PROJ, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0L : c.getLong(3);
                    int flags = c.getInt(4);
                    if (name == null || name.isEmpty()) continue;
                    if ((flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
                        continue;
                    }
                    String childRel = cur[1].isEmpty() ? name : cur[1] + "/" + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (!isJunkDir(name)
                                && (excludedDirs == null || !excludedDirs.contains(childRel))) {
                            stack.push(new String[]{docId, childRel});
                        }
                    } else {
                        if (excludedFiles != null && excludedFiles.contains(childRel)) continue;
                        if (seen != null && !seen.add(childRel)) continue;
                        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        out.add(new Entry(childRel, docUri, size));
                        reported++;
                        if (cb != null && (reported % 100) == 0) {
                            cb.onScan(reported);
                        }
                    }
                }
            } finally {
                if (c != null) c.close();
            }
        }
    }
}
