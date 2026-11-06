package com.oddbearproductions.appmanager;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Durable, append-only-ish installation attempt history (last 250 entries). */
public final class HistoryStore {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "attempts.json";
    private HistoryStore() {}

    private static JSONArray load(Context context) {
        try {
            AtomicFile file = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
            return new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
        } catch (Exception ignored) { return new JSONArray(); }
    }
    private static void save(Context context, JSONArray items) {
        AtomicFile atomic = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(items.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (Exception ex) {
            if (out != null) atomic.failWrite(out);
        }
    }
    public static String create(Context c, String packageName, String label, String version,
                                long versionCode, String filename, Uri source, String operation) {
        synchronized (LOCK) {
            String id = UUID.randomUUID().toString();
            JSONObject item = new JSONObject();
            try {
                item.put("id", id); item.put("package", packageName);
                item.put("app", label); item.put("version", version);
                item.put("code", versionCode); item.put("apk", filename);
                item.put("uri", source.toString()); item.put("operation", operation);
                item.put("status", "Preparing"); item.put("created", System.currentTimeMillis());
                item.put("error", ""); item.put("pendingLog", false);
            } catch (Exception ignored) {}
            JSONArray old = load(c), latest = new JSONArray();
            latest.put(item);
            for (int i=0; i<Math.min(old.length(),249); i++) latest.put(old.optJSONObject(i));
            save(c, latest);
            return id;
        }
    }
    public static JSONObject get(Context c, String id) {
        synchronized (LOCK) {
            JSONArray arr = load(c);
            for (int i=0; i<arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null && id.equals(item.optString("id"))) {
                    try { return new JSONObject(item.toString()); } catch (Exception ignored) {}
                }
            }
            return null;
        }
    }
    public static JSONArray all(Context c) {
        synchronized (LOCK) { return load(c); }
    }
    public static void update(Context c, String id, String status, String message,
                              String sha, Boolean pendingLog) {
        synchronized (LOCK) {
            JSONArray arr = load(c);
            for (int i=0; i<arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null && id.equals(item.optString("id"))) {
                    try {
                        if (status != null) item.put("status", status);
                        if (message != null) item.put("error", message);
                        if (sha != null) item.put("sha256", sha);
                        if (pendingLog != null) item.put("pendingLog", pendingLog);
                        item.put("updated", System.currentTimeMillis());
                    } catch (Exception ignored) {}
                    save(c, arr); return;
                }
            }
        }
    }
    public static String text(JSONObject log) {
        if (log == null) return "No log available";
        StringBuilder s = new StringBuilder();
        s.append("OBP App Manager - Installation Log\n");
        s.append("Timestamp (epoch ms): ").append(log.optLong("created")).append('\n');
        for (String k : new String[]{"id","app","package","version","code","apk","operation","status","error","sha256"}) {
            if (log.has(k)) s.append(k).append(": ").append(log.optString(k)).append('\n');
        }
        s.append("Android version: ").append(android.os.Build.VERSION.RELEASE).append('\n');
        s.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n');
        s.append("Note: No user files or credentials are included in this diagnostic.\n");
        return s.toString();
    }
}
