package com.oddbearproductions.appmanager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Writes one complete APK to a system PackageInstaller session. OS owns the final approval. */
public final class Installer {
    private Installer() {}
    public static void install(Context ctx, ApkRepository.Apk apk, String operation) throws Exception {
        if (!apk.valid()) throw new IllegalArgumentException("Cannot install an unrecognized APK");
        String id = HistoryStore.create(ctx, apk.pkg, apk.label, apk.version,
                apk.versionCode, apk.filename, apk.uri, operation);
        installRecorded(ctx, apk, id);
    }
    public static void installRecorded(Context ctx, ApkRepository.Apk apk, String id) throws Exception {
        if (!apk.valid()) throw new IllegalArgumentException("Invalid APK");
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(apk.pkg);
        if (apk.size > 0) params.setSize(apk.size);
        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = pi.openSession(sessionId);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = ctx.getContentResolver().openInputStream(apk.uri);
                 OutputStream out = session.openWrite("base.apk", 0, apk.size > 0 ? apk.size : -1)) {
                if (in == null) throw new IllegalArgumentException("APK source is unavailable");
                byte[] chunk = new byte[65536]; int n;
                while ((n=in.read(chunk)) != -1) {
                    out.write(chunk,0,n); sha.update(chunk,0,n);
                }
                session.fsync(out);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format("%02x", b & 0xff));
            HistoryStore.update(ctx,id,"Waiting for Android approval",null,hex.toString(),null);
            Intent callback = new Intent(ctx, InstallStatusReceiver.class);
            callback.setData(Uri.parse("obpmanager://attempt/" + id));
            callback.putExtra("attempt_id",id);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent receiver = PendingIntent.getBroadcast(ctx, sessionId, callback, flags);
            session.commit(receiver.getIntentSender());
        } catch (Exception e) {
            try { pi.abandonSession(sessionId); } catch (Exception ignored) {}
            HistoryStore.update(ctx, id, "Failed", "Could not prepare install: " + e.getMessage(), null, true);
            FailureLogSync.flushAsync(ctx);
            throw e;
        } finally { if (session != null) session.close(); }
    }
}
