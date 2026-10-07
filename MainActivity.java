package com.example.washeets;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Single screen: permissions, settings, tonight's status, manual entry and the activity log. */
public class MainActivity extends Activity {

    private Store store;
    private TextView status, tonight, chats, log;
    private EditText url, secret, group, fromHour, untilHour, manual;

    private final SharedPreferences.OnSharedPreferenceChangeListener onChange =
            (prefs, key) -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        setTitle("MRP Accounts → Sheets");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // Tonight
        root.addView(header("Tonight"));
        tonight = text("");
        tonight.setTextSize(16);
        root.addView(tonight);

        // 1. Permissions
        root.addView(header("1. Permissions"));
        status = text("");
        root.addView(status);
        root.addView(button("Allow notification access", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        root.addView(button("Keep running in background (battery)", v -> askBattery()));

        // 2. Settings
        root.addView(header("2. Settings"));
        root.addView(label("Apps Script web app URL (ends in /exec)"));
        url = input(InputType.TYPE_TEXT_VARIATION_URI, 1);
        url.setText(store.url());
        root.addView(url);

        root.addView(label("Secret (same as SECRET in the script)"));
        secret = input(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 1);
        secret.setText(store.secret());
        root.addView(secret);

        root.addView(label("WhatsApp group (exact name)"));
        group = input(0, 1);
        group.setText(store.groupsRaw());
        root.addView(group);

        root.addView(label("Accept reports posted from (hour, 24h)  —  until (hour, 24h)"));
        LinearLayout hours = new LinearLayout(this);
        hours.setOrientation(LinearLayout.HORIZONTAL);
        fromHour = numberInput(store.fromHour());
        untilHour = numberInput(store.untilHour());
        TextView dash = text("  to  ");
        hours.addView(fromHour);
        hours.addView(dash);
        hours.addView(untilHour);
        root.addView(hours);
        root.addView(small("Default 22 to 3 = 10 PM to 3 AM. The app wakes up one hour earlier (9 PM)."));

        root.addView(button("Save", v -> {
            save();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            Uploader.flush(this);
        }));
        root.addView(button("Test connection to sheet", v -> {
            save();
            Uploader.testConnection(this);
            Toast.makeText(this, "Checking… see the log below", Toast.LENGTH_SHORT).show();
        }));

        // 3. Manual entry
        root.addView(header("3. Missed a night? Add it manually"));
        root.addView(small("Paste the report message here. It is saved with the current date & time."));
        manual = input(InputType.TYPE_TEXT_FLAG_MULTI_LINE, 6);
        manual.setHint("Pp- 6039\nZ- 975\nS- 795\nCash- 4670 - 800 = 300\nAcc G- 1088\nAcc P- 2233");
        root.addView(manual);
        root.addView(button("Add this report to the sheet", v -> addManual()));

        // 4. Status
        root.addView(header("4. Status"));
        root.addView(button("Retry sending now", v -> Uploader.flush(this)));
        root.addView(label("Chats seen recently (tap to use as the group name)"));
        chats = text("");
        chats.setTextColor(Color.rgb(25, 118, 210));
        root.addView(chats);

        root.addView(label("Activity log"));
        log = text("");
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        root.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.registerListener(onChange);
        NightAlarm.schedule(this);
        refresh();
        Uploader.flush(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        store.unregisterListener(onChange);
    }

    private void save() {
        store.saveSettings(url.getText().toString(), secret.getText().toString(),
                group.getText().toString(), parseHour(fromHour, Store.DEFAULT_FROM_HOUR),
                parseHour(untilHour, Store.DEFAULT_UNTIL_HOUR));
        NightAlarm.schedule(this);
    }

    private void addManual() {
        String text = manual.getText().toString();
        if (!Report.looksLikeReport(text)) {
            Toast.makeText(this, "That doesn't look like the report (needs at least 3 of Pp, Z, S, Cash, Acc G, Acc P)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Add this report?")
                .setMessage("It will be added as a new row with today's date & time.")
                .setPositiveButton("Add", (d, w) -> {
                    save();
                    Uploader.addManual(this, text);
                    manual.setText("");
                    Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        String night = store.nightKey(now);
        String window = NightAlarm.hourLabel(store.fromHour()) + " – " + NightAlarm.hourLabel(store.untilHour());
        String t;
        if (store.isDone(night)) {
            t = "✅ Report for " + Store.prettyNight(night) + " saved. Stopped until tomorrow "
                    + NightAlarm.hourLabel(store.wakeHour()) + ".";
        } else if (store.inWindow(now)) {
            t = "👀 Listening now for the report (" + window + ").";
        } else {
            t = "💤 Waiting. Starts at " + NightAlarm.hourLabel(store.wakeHour())
                    + ", accepts the report " + window + ".";
        }
        int q = store.queueSize();
        if (q > 0) t += "\n⏳ " + q + " waiting to be sent (no internet?)";
        tonight.setText(t);

        boolean access = hasNotificationAccess();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        status.setText((access ? "✅" : "❌") + " Notification access\n"
                + (battery ? "✅" : "⚠") + " Unrestricted battery\n"
                + (store.url().isEmpty() ? "❌ Apps Script URL not set" : "✅ Apps Script URL set") + "\n"
                + (store.secret().isEmpty() ? "❌ Secret not set" : "✅ Secret set"));

        List<String> recent = store.recentChats();
        chats.setText(recent.isEmpty() ? "(none yet — wait for any WhatsApp message)" : TextUtils.join("\n", recent));
        chats.setOnClickListener(recent.isEmpty() ? null : v -> pickChat(recent));

        List<String> lines = store.logLines();
        log.setText(lines.isEmpty() ? "(nothing yet)" : TextUtils.join("\n", lines));
    }

    private void pickChat(List<String> recent) {
        String[] items = recent.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Use this group")
                .setItems(items, (d, which) -> {
                    group.setText(items[which]);
                    Toast.makeText(this, "Set — tap Save", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        ComponentName me = new ComponentName(this, WaListenerService.class);
        return enabled != null && enabled.contains(me.flattenToString());
    }

    private void askBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private static int parseHour(EditText e, int fallback) {
        try {
            int h = Integer.parseInt(e.getText().toString().trim());
            return (h >= 0 && h <= 23) ? h : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // ---------- tiny view helpers ----------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView header(String s) {
        TextView t = text(s);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s);
        t.setTextSize(13);
        t.setPadding(0, dp(10), 0, dp(2));
        t.setTextColor(Color.DKGRAY);
        return t;
    }

    private TextView small(String s) {
        TextView t = text(s);
        t.setTextSize(12);
        t.setTextColor(Color.GRAY);
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        return t;
    }

    private EditText input(int variation, int lines) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        if (lines > 1) {
            e.setSingleLine(false);
            e.setMinLines(lines);
            e.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private EditText numberInput(int value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setEms(3);
        e.setText(String.valueOf(value));
        return e;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }
}
