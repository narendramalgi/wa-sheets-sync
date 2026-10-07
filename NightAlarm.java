package com.example.washeets;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

import java.util.Calendar;

/**
 * Two daily alarms:
 *  - 9 PM  "wake up": makes sure the WhatsApp listener is running and retries any unsent rows.
 *  - 3 AM  "check":   if no report was captured for the night, shows a reminder notification.
 * Also re-arms itself after the phone restarts or the app is updated.
 */
public class NightAlarm extends BroadcastReceiver {
    static final String ACTION_WAKE = "com.example.washeets.NIGHT_WAKE";
    static final String ACTION_CHECK = "com.example.washeets.NIGHT_CHECK";
    private static final long HOUR = 60L * 60L * 1000L;

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        Store s = new Store(c);
        if (ACTION_WAKE.equals(action)) {
            s.log("🌙 Started for tonight — waiting for the report from "
                    + hourLabel(s.fromHour()) + " onwards");
            NotificationListenerService.requestRebind(new ComponentName(c, WaListenerService.class));
            Uploader.flush(c);
        } else if (ACTION_CHECK.equals(action)) {
            String night = s.nightKey(System.currentTimeMillis() - HOUR);
            if (s.isDone(night)) {
                s.log("🌅 Finished for the night of " + Store.prettyNight(night));
            } else {
                s.log("⚠ No report captured for the night of " + Store.prettyNight(night));
                Notifier.show(c, 2, "No report captured last night",
                        "Nothing from " + s.groupsRaw().split("\n")[0].trim()
                                + " was saved. Open the app to add it manually.");
            }
            Uploader.flush(c);
        }
        schedule(c); // set up the next ones (also covers boot / app update)
    }

    /** (Re)arms both alarms for their next occurrence. Safe to call any time. */
    public static void schedule(Context c) {
        Store s = new Store(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        set(c, am, ACTION_WAKE, 1, nextAt(s.wakeHour()));
        set(c, am, ACTION_CHECK, 2, nextAt(s.untilHour()));
    }

    private static void set(Context c, AlarmManager am, String action, int code, long when) {
        Intent i = new Intent(c, NightAlarm.class).setAction(action);
        PendingIntent pi = PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // Inexact but still fires while the phone is dozing (usually within a few minutes).
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    private static long nextAt(int hour) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    static String hourLabel(int h) {
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + (h < 12 ? " AM" : " PM");
    }
}
