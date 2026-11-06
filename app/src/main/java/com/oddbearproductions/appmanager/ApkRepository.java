package com.oddbearproductions.appmanager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import android.database.Cursor;

/** Uses SAF trees rather than broad file-system access; filenames do not establish APK identity. */
public final class ApkRepository {
    public static class Apk {
        public Uri uri;
        public String filename, pkg, label, version, error;
        public long versionCode, size, modified;
        public boolean valid() { return pkg != null && !pkg.isEmpty(); }
    }
    private final HashMap<String, Apk> cache = new HashMap<>();

    public static Uri tree(Context ctx, String pref) {
        String s = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(pref, "");
        return s.isEmpty() ? null : Uri.parse(s);
    }
    public static void setTree(Context ctx, String pref, Uri uri) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(pref, uri.toString()).apply();
    }
    public static Uri doc(Uri tree, String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
    }
    public static Uri rootDoc(Uri tree) {
        return doc(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    public List<Apk> scan(Context ctx, Uri folder) throws Exception {
        ArrayList<Apk> list = new ArrayList<>();
        if (folder == null) return list;
        Uri childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folder, DocumentsContract.getTreeDocumentId(folder));
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor cursor = ctx.getContentResolver().query(childUri, cols, null, null, null)) {
            if (cursor == null) throw new Exception("Folder provider did not return files");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (!InstallPolicy.isApkName(name)) continue;
                String id = cursor.getString(0);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) continue;
                long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0L : cursor.getLong(4);
                Uri uri = doc(folder, id);
                String key = uri + ":" + size + ":" + modified;
                Apk item = cache.get(key);
                if (item == null) {
                    item = inspect(ctx, uri, name, size);
                    item.modified = modified;
                    cache.put(key, item);
                }
                list.add(item);
            }
        }
        list.sort((a,b) -> Long.compare(b.modified, a.modified));
        if (cache.size() > 300) cache.clear();
        return list;
    }

    public Apk inspect(Context ctx, Uri uri, String displayName, long length) {
        Apk a = new Apk();
        a.uri = uri; a.filename = displayName; a.size = length;
        // PackageManager requires a local archive path, so cache temporarily,
        // parse, then remove that copy. No source file is deleted here.
        File copy = null;
        try {
            copy = File.createTempFile("apk-", ".apk", ctx.getCacheDir());
            try (InputStream src = ctx.getContentResolver().openInputStream(uri);
                 OutputStream dst = new FileOutputStream(copy)) {
                if (src == null) throw new IllegalStateException("Cannot open APK");
                byte[] bytes = new byte[65536]; int read;
                while ((read=src.read(bytes))!=-1) dst.write(bytes,0,read);
            }
            PackageManager pm = ctx.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = pm.getPackageArchiveInfo(copy.getAbsolutePath(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = pm.getPackageArchiveInfo(copy.getAbsolutePath(), 0);
            }
            if (info == null || info.packageName == null) throw new IllegalArgumentException("Invalid or unsupported APK");
            a.pkg = info.packageName;
            a.version = info.versionName == null ? "unknown" : info.versionName;
            a.versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            a.label = info.packageName.substring(info.packageName.lastIndexOf('.')+1);
            if (info.applicationInfo != null) {
                try {
                    info.applicationInfo.sourceDir = copy.getAbsolutePath();
                    info.applicationInfo.publicSourceDir = copy.getAbsolutePath();
                    CharSequence appLabel = info.applicationInfo.loadLabel(pm);
                    if (appLabel != null && appLabel.length() > 0) a.label = appLabel.toString();
                } catch (Exception ignored) { /* package label fallback is safe */ }
            }
        } catch (Exception ex) {
            a.error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        } finally {
            if (copy != null && copy.exists()) { // no permanent APK cache
                //noinspection ResultOfMethodCallIgnored
                copy.delete();
            }
        }
        return a;
    }

    /** Hash the current file contents to avoid deleting a replacement APK after the install. */
    public static String sha256(Context ctx, Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = ctx.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot reopen installer for verification");
            byte[] buf = new byte[65536];
            int read;
            while ((read = input.read(buf)) != -1) digest.update(buf, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    public static long installedCode(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception ex) { return -1; }
    }
    public static boolean installed(Context ctx, String pkg) { return installedCode(ctx, pkg) >= 0; }

    public static Uri importApk(Context ctx, Uri treeUri, Uri original, String suggestedName) throws Exception {
        if (treeUri == null) throw new IllegalStateException("Select an installer folder first");
        String name = InstallPolicy.safeName(suggestedName);
        if (!InstallPolicy.isApkName(name)) throw new IllegalArgumentException("Only .apk files are accepted");
        String unique = System.currentTimeMillis() + "_" + name;
        Uri dst = DocumentsContract.createDocument(ctx.getContentResolver(), rootDoc(treeUri),
                "application/vnd.android.package-archive", unique);
        if (dst == null) throw new IllegalStateException("Folder did not create APK");
        try (InputStream from = ctx.getContentResolver().openInputStream(original);
             OutputStream to = ctx.getContentResolver().openOutputStream(dst, "w")) {
            if (from == null || to == null) throw new IllegalStateException("Unable to open import streams");
            byte[] bytes = new byte[65536]; int n;
            while ((n=from.read(bytes))>=0) if(n>0) to.write(bytes,0,n);
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(ctx.getContentResolver(), dst); } catch (Exception ignored) {}
            throw e;
        }
        return dst;
    }
}
