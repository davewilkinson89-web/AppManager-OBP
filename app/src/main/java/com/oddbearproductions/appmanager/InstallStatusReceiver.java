package com.oddbearproductions.appmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** System install result: logs failure, verifies package, and only then deletes managed APK. */
public final class InstallStatusReceiver extends BroadcastReceiver {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    @Override public void onReceive(Context ctx, Intent intent) {
        String id = intent.getStringExtra("attempt_id");
        if (id == null && intent.getData() != null) id = intent.getData().getLastPathSegment();
        if (id == null) return;
        final String attemptId = id;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        String error = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            HistoryStore.update(ctx,id,"Awaiting Android confirmation",null,null,null);
            Intent approval = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class)
                    : (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (approval == null) {
                HistoryStore.update(ctx,id,"Failed","Android did not provide a confirmation activity",null,true);
                FailureLogSync.flushAsync(ctx);
                return;
            }
            if (!MainActivity.tryLaunchApproval(approval)) {
                NotificationHelper.show(ctx,id,"Approve APK installation",
                        "Open to confirm the installation in Android",approval);
            }
            return;
        }
        final PendingResult result = goAsync();
        final String detail = error;
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try { process(app,attemptId,status,detail); }
            finally { result.finish(); }
        });
    }
    private void process(Context ctx, String id, int status, String detail) {
        JSONObject entry = HistoryStore.get(ctx,id);
        if (entry == null) return;
        String pkg = entry.optString("package");
        if (status == PackageInstaller.STATUS_SUCCESS) {
            long actual = ApkRepository.installedCode(ctx,pkg);
            long expected = entry.optLong("code",-1);
            if (InstallPolicy.mayDeleteInstaller(true,actual >= 0,actual == expected)) {
                // Export the icon from the newly installed package; do not retain its APK.
                if (ApkRepository.tree(ctx,"icons_tree") != null) {
                    try { IconExporter.exportOne(ctx,pkg); }
                    catch(Exception ignored) { /* Icon export is optional and never blocks installation */ }
                }
                boolean deleted = false;
                String issue = "";
                try {
                    Uri uri = Uri.parse(entry.optString("uri"));
                    String installedSourceHash = entry.optString("sha256");
                    // Re-check file after the installer finishes: a separate app or user
                    // may have replaced the document at the selected URI meanwhile.
                    if (installedSourceHash.isEmpty() ||
                            !installedSourceHash.equalsIgnoreCase(ApkRepository.sha256(ctx,uri))) {
                        issue = "Source APK changed since installation; cleanup skipped";
                    } else {
                        deleted = DocumentsContract.deleteDocument(ctx.getContentResolver(),uri);
                        if (!deleted) issue = "Document provider refused to delete installed APK";
                    }
                } catch (Exception ex) { issue = "APK cleanup skipped: " + ex.getMessage(); }
                HistoryStore.update(ctx,id,deleted ? "Installed and cleaned" : "Installed - cleanup skipped",
                        issue,null,!deleted);
                if (!deleted) FailureLogSync.flushAsync(ctx);
                NotificationHelper.show(ctx,id,deleted ? "APK installed" : "Installed - cleanup needed",
                        deleted ? pkg + " installed; installer deleted" : issue,null);
            } else {
                HistoryStore.update(ctx,id,"Failed","Install reported success but package/version verification failed",null,true);
                FailureLogSync.flushAsync(ctx);
                NotificationHelper.show(ctx,id,"APK verification failed",pkg + " was not verified",null);
            }
        } else {
            String statusName = name(status);
            String message = statusName + (detail == null || detail.isEmpty() ? "" : ": " + detail);
            boolean canReinstall = InstallPolicy.classifyDetailed(status,detail,
                    ApkRepository.installed(ctx,pkg),entry.optLong("code",-1),
                    ApkRepository.installedCode(ctx,pkg)) == InstallPolicy.FailureAction.OFFER_REINSTALL;
            HistoryStore.update(ctx,id,canReinstall ? "Conflict - reinstall available"
                    : status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Cancelled" : "Failed",
                    message,null,status != PackageInstaller.STATUS_FAILURE_ABORTED);
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) FailureLogSync.flushAsync(ctx);
            NotificationHelper.show(ctx,id,"APK installation " + (status == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelled" : "failed"),message,null);
        }
        MainActivity.statusChanged(id,status);
    }
    static String name(int code) {
        switch (code) {
            case PackageInstaller.STATUS_SUCCESS: return "Success";
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "User cancelled / aborted";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "Blocked by Android / policy";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "Package/signature conflict";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "Incompatible APK/device";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "Invalid APK";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "Insufficient storage";
            default: return "Installer error code " + code;
        }
    }
}
