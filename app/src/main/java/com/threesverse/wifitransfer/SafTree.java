package com.threesverse.wifitransfer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Minimal Storage Access Framework tree walker (no AndroidX).
 * Recursively lists every regular file below a tree Uri picked with
 * ACTION_OPEN_DOCUMENT_TREE, preserving the relative path.
 */
public final class SafTree {

    private SafTree() {
    }

    /** One file found inside the picked tree. */
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

    /** Called periodically during the scan so the UI can show progress. */
    public interface ScanProgress {
        void onScan(int filesFound);
    }

    /**
     * Iteratively walks the whole tree (breadth-first). Returns nothing -
     * results are appended into {@code out}. Throws on transport errors.
     */
    public static void walk(ContentResolver cr, Uri treeUri, List<Entry> out,
                            ScanProgress cb) throws Exception {
        String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<String[]> stack = new ArrayDeque<>();
        // [0] = documentId, [1] = relative directory path ("" for root)
        stack.push(new String[]{rootDocId, ""});
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
                c = cr.query(childrenUri, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_FLAGS
                }, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0L : c.getLong(3);
                    int flags = c.getInt(4);
                    if (name == null || name.isEmpty()) continue;
                    if ((flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
                        continue; // cloud/virtual docs cannot be streamed reliably
                    }
                    String childRel = cur[1].isEmpty() ? name : cur[1] + "/" + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        stack.push(new String[]{docId, childRel});
                    } else {
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
