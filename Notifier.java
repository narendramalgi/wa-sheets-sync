package com.example.washeets;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Small status notifications: "report saved" and "no report captured". */
public final class Notifier {
    private static final String CHANNEL = "status";

    private Notifier() {}

    public static void show(Context c, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Report status", NotificationManager.IMPORTANCE_DEFAULT));

        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(id, n);
        } catch (SecurityException ignored) {
            // Notification permission not granted — the activity log still has the details.
        }
    }
}
