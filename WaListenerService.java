package com.example.washeets;

import android.app.Notification;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

/**
 * Reads WhatsApp notifications and picks out the nightly accounts report:
 *  1. only from the selected group (ACCOUNTS - MRP VENTURES),
 *  2. only if posted between 10 PM and 3 AM,
 *  3. only if it looks like the report (Pp / Z / S / Cash / Acc G / Acc P lines),
 *  4. only the first one each night — after that it stops until the next night.
 */
public class WaListenerService extends NotificationListenerService {

    private static final String WA = "com.whatsapp";
    private static final String WA_BUSINESS = "com.whatsapp.w4b";

    @Override
    public void onListenerConnected() {
        new Store(this).log("🔌 Listener connected");
        NightAlarm.schedule(this);
        Uploader.flush(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (!WA.equals(pkg) && !WA_BUSINESS.equals(pkg)) return;
        try {
            handle(sbn.getNotification());
        } catch (Exception e) {
            new Store(this).log("⚠ Could not read a notification: " + e.getMessage());
        }
    }

    private void handle(Notification n) throws Exception {
        Bundle ex = n.extras;
        if (ex == null) return;
        Store store = new Store(this);

        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        CharSequence convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);

        if (msgs != null && msgs.length > 0) {
            // Modern "messaging style" notification: one entry per message.
            String chat = cleanChatName(convTitle != null ? convTitle : title, convTitle == null);
            if (chat.isEmpty()) return;
            store.sawChat(chat);
            if (!store.groups().contains(Store.normalize(chat))) return;
            for (Parcelable p : msgs) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                consider(store, chat, senderOf(b),
                        b.getLong("time", System.currentTimeMillis()), b.getCharSequence("text"));
            }
            return;
        }

        // Fallback for simple notifications: title "Group: Sender", text "message".
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null || text == null) return;
        String t = title.toString();
        String chat;
        String sender = "";
        int colon = t.indexOf(": ");
        if (colon > 0) {
            chat = cleanChatName(t.substring(0, colon), false);
            sender = t.substring(colon + 2).trim();
        } else {
            chat = cleanChatName(t, false);
        }
        store.sawChat(chat);
        if (!store.groups().contains(Store.normalize(chat))) return;
        consider(store, chat, sender, n.when > 0 ? n.when : System.currentTimeMillis(), text);
    }

    private void consider(Store store, String chat, String sender, long time, CharSequence text)
            throws Exception {
        if (text == null) return;
        String body = text.toString().trim();
        if (body.isEmpty()) return;

        // WhatsApp re-posts earlier messages in each notification; handle each message once.
        String id = Integer.toHexString((chat + "|" + sender + "|" + time + "|" + body).hashCode())
                + "-" + time;
        if (!store.markNew(id)) return;

        if (!store.inWindow(time)) return;              // posted before 10 PM (or after 3 AM)
        if (!Report.looksLikeReport(body)) return;      // ordinary chat in the group

        String night = store.nightKey(time);
        if (store.isDone(night)) {
            store.log("↩ Another report-like message from " + sender
                    + " ignored — tonight's report is already saved");
            return;
        }

        JSONObject m = new JSONObject();
        m.put("id", id);
        m.put("group", chat);
        m.put("sender", sender);
        m.put("time", time);                            // when it was posted in WhatsApp
        m.put("captured", System.currentTimeMillis());  // phone date & time → first column
        m.put("text", body);
        m.put("report", true);
        store.enqueue(m);
        store.markDone(night);
        store.log("📥 Captured report for " + Store.prettyNight(night)
                + (sender.isEmpty() ? "" : " (from " + sender + ")") + " — done for tonight");
        Uploader.flush(this);
    }

    private static String senderOf(Bundle b) {
        CharSequence s = b.getCharSequence("sender");
        if (s != null) return s.toString().trim();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Object person = b.get("sender_person");
            if (person instanceof Person) {
                CharSequence name = ((Person) person).getName();
                if (name != null) return name.toString().trim();
            }
        }
        return "";
    }

    /** Turns "ACCOUNTS - MRP VENTURES (3 messages)" or "Group: Ravi" into the group name. */
    static String cleanChatName(CharSequence raw, boolean mayContainSender) {
        if (raw == null) return "";
        String s = raw.toString().trim();
        if (mayContainSender) {
            int colon = s.indexOf(": ");
            if (colon > 0) s = s.substring(0, colon);
        }
        s = s.replaceAll("\\s*\\(\\d+[^)]*\\)\\s*$", "");      // drop "(3 messages)"
        s = s.replace('‎', ' ').replace('‏', ' ');    // invisible direction marks
        return s.trim();
    }
}
