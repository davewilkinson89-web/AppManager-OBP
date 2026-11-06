package com.oddbearproductions.appmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.net.Uri;

/** Background approval resumes only when the user taps the notification. */
public final class NotificationHelper {
    private static final String CHANNEL = "apk_installations";
    private NotificationHelper() {}
    public static void show(Context ctx, String id, String heading, String detail, Intent approval) {
        try {
            NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL,"APK installation results",NotificationManager.IMPORTANCE_HIGH));
            // The package installer supplies a trusted confirmation intent. Directly
            // wrap that intent in an immutable PendingIntent, rather than forwarding
            // an arbitrary Intent extra through our exported MainActivity.
            Intent open;
            if (approval != null) {
                open = approval;
            } else {
                open = new Intent(ctx, MainActivity.class);
                open.setAction("com.obp.appmanager.OPEN_LOG");
                open.setData(Uri.parse("obpmanager://notice/" + id));
                open.putExtra("attempt_id", id);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx,id.hashCode(),open,flags);
            Notification n = new Notification.Builder(ctx,CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(heading).setContentText(detail)
                    .setStyle(new Notification.BigTextStyle().bigText(detail))
                    .setContentIntent(pi).setAutoCancel(true).build();
            nm.notify(id.hashCode(),n);
        } catch (SecurityException ignored) {
            // Notification permission may be denied: pending record is still in Logs.
        }
    }
}
