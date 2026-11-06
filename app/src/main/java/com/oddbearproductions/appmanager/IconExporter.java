package com.oddbearproductions.appmanager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import java.io.OutputStream;
import java.util.Collection;

/** Exports actual installed-app icons as PNGs through a user-approved SAF directory. */
public final class IconExporter {
    private IconExporter() {}

    /** This is a best-effort asset export; an icon error never blocks APK installation. */
    public static void exportOne(Context context, String packageName) throws Exception {
        Uri tree = ApkRepository.tree(context, "icons_tree");
        if (tree == null) return;
        PackageManager pm = context.getPackageManager();
        Drawable drawable = pm.getApplicationIcon(packageName).mutate();
        int size = 192;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            String filename = InstallPolicy.safeName(packageName) + ".png";
            Uri destination = findOrCreate(context, tree, filename);
            try (OutputStream out = context.getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    throw new IllegalStateException("Icon PNG could not be written");
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static Uri findOrCreate(Context context, Uri tree, String name) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = context.getContentResolver().query(children, columns, null, null, null)) {
            if (c == null) throw new IllegalStateException("Icon directory cannot be read");
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) return ApkRepository.doc(tree, c.getString(0));
            }
        }
        Uri created = DocumentsContract.createDocument(context.getContentResolver(),
                ApkRepository.rootDoc(tree), "image/png", name);
        if (created == null) throw new IllegalStateException("Icon directory cannot create a file");
        return created;
    }

    public static int exportAll(Context context, Collection<String> packages) {
        int success = 0;
        for (String pkg : packages) {
            try { exportOne(context, pkg); success++; }
            catch (Exception ignored) { /* surfaced by caller as exported count */ }
        }
        return success;
    }
}
