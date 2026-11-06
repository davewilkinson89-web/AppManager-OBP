package com.oddbearproductions.appmanager;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SAF folder output: user may choose a cloud DocumentsProvider, including Drive if offered. */
public final class FailureLogSync {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private FailureLogSync() {}
    public static void flushAsync(Context context) {
        Context app = context.getApplicationContext();
        EXEC.execute(() -> flush(app));
    }
    static void flush(Context ctx) {
        Uri folder = ApkRepository.tree(ctx,"logs_tree");
        if (folder == null) return; // local records retained, copy still works
        JSONArray all = HistoryStore.all(ctx);
        for (int i=all.length()-1; i>=0; i--) {
            JSONObject log = all.optJSONObject(i);
            if (log == null || !log.optBoolean("pendingLog")) continue;
            String id = log.optString("id");
            String date = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK)
                    .format(new Date(log.optLong("created")));
            String pkg = InstallPolicy.safeName(log.optString("package"));
            String filename = "OBP-Install-Failure-" + date + "-" + pkg + "-" + id.substring(0,8) + ".txt";
            Uri created = null;
            try {
                created = DocumentsContract.createDocument(ctx.getContentResolver(),
                        ApkRepository.rootDoc(folder),"text/plain",filename);
                if (created == null) throw new IllegalStateException("Provider refused log file");
                try (OutputStream out = ctx.getContentResolver().openOutputStream(created,"w")) {
                    if (out == null) throw new IllegalStateException("Provider refused output stream");
                    out.write(HistoryStore.text(log).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                HistoryStore.update(ctx,id,null,null,null,false);
            } catch (Exception e) {
                if (created != null) try {
                    DocumentsContract.deleteDocument(ctx.getContentResolver(),created);
                } catch (Exception ignored) {}
                // leave pending and retry next time; don't recursively log failed logging
                break;
            }
        }
    }
}
