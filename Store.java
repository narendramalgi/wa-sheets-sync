package com.example.washeets;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Settings, night window, outgoing queue, duplicate filter and activity log (SharedPreferences). */
public final class Store {
    public static final String DEFAULT_GROUP = "ACCOUNTS - MRP VENTURES";
    public static final int DEFAULT_FROM_HOUR = 22; // accept messages posted from 10 PM…
    public static final int DEFAULT_UNTIL_HOUR = 3;  // …until 3 AM

    private static final String PREFS = "wa_sheets";
    private static final int MAX_SEEN_IDS = 1000;
    private static final int MAX_LOG = 80;
    private static final int MAX_CHATS = 30;
    private static final Object LOCK = new Object();

    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- settings ----------
    public String url() { return p.getString("url", "").trim(); }
    public String secret() { return p.getString("secret", "").trim(); }
    public String groupsRaw() { return p.getString("groups", DEFAULT_GROUP); }
    public int fromHour() { return p.getInt("from_hour", DEFAULT_FROM_HOUR); }
    public int untilHour() { return p.getInt("until_hour", DEFAULT_UNTIL_HOUR); }

    /** The app "wakes up" one hour before reports are accepted (9 PM by default). */
    public int wakeHour() { return (fromHour() + 23) % 24; }

    public void saveSettings(String url, String secret, String groups, int fromHour, int untilHour) {
        p.edit().putString("url", url.trim()).putString("secret", secret.trim())
                .putString("groups", groups)
                .putInt("from_hour", clampHour(fromHour)).putInt("until_hour", clampHour(untilHour))
                .apply();
    }

    private static int clampHour(int h) { return Math.max(0, Math.min(23, h)); }

    /** Selected group names, lower-cased and trimmed. */
    public Set<String> groups() {
        Set<String> out = new LinkedHashSet<>();
        for (String line : groupsRaw().split("\n")) {
            String g = normalize(line);
            if (!g.isEmpty()) out.add(g);
        }
        return out;
    }

    public static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ---------- night window ----------
    /** True if a message with this timestamp was posted inside the accepted window (10 PM – 3 AM). */
    public boolean inWindow(long time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        int h = c.get(Calendar.HOUR_OF_DAY);
        int from = fromHour(), until = untilHour();
        if (from == until) return true;
        return from < until ? (h >= from && h < until) : (h >= from || h < until);
    }

    /** The calendar date a night belongs to: 1 AM on the 9th counts as the night of the 8th. */
    public String nightKey(long time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        if (fromHour() > untilHour() && c.get(Calendar.HOUR_OF_DAY) < untilHour()) {
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    public static String prettyNight(String key) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key);
            return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return key;
        }
    }

    public boolean isDone(String nightKey) { return nightKey.equals(p.getString("done_night", "")); }

    public void markDone(String nightKey) { p.edit().putString("done_night", nightKey).apply(); }

    public String lastDoneNight() { return p.getString("done_night", ""); }

    // ---------- duplicate filter ----------
    /** Returns true the first time an id is seen, false afterwards. */
    public boolean markNew(String id) {
        synchronized (LOCK) {
            List<String> ids = readList("seen_ids");
            if (ids.contains(id)) return false;
            ids.add(id);
            while (ids.size() > MAX_SEEN_IDS) ids.remove(0);
            writeList("seen_ids", ids);
            return true;
        }
    }

    // ---------- outgoing queue ----------
    public void enqueue(JSONObject msg) {
        synchronized (LOCK) {
            JSONArray q = readArray("queue");
            q.put(msg);
            p.edit().putString("queue", q.toString()).apply();
        }
    }

    public JSONArray peekQueue(int max) {
        synchronized (LOCK) {
            JSONArray q = readArray("queue");
            JSONArray out = new JSONArray();
            for (int i = 0; i < q.length() && i < max; i++) out.put(q.opt(i));
            return out;
        }
    }

    /** Removes the first n items (the ones that were just sent). */
    public void dropFromQueue(int n) {
        synchronized (LOCK) {
            JSONArray q = readArray("queue");
            JSONArray rest = new JSONArray();
            for (int i = n; i < q.length(); i++) rest.put(q.opt(i));
            p.edit().putString("queue", rest.toString()).apply();
        }
    }

    public int queueSize() {
        synchronized (LOCK) { return readArray("queue").length(); }
    }

    // ---------- activity log & recently seen chats ----------
    public void log(String line) {
        synchronized (LOCK) {
            List<String> l = readList("log");
            String ts = new SimpleDateFormat("dd MMM hh:mm a", Locale.getDefault()).format(new Date());
            l.add(0, ts + "  " + line);
            while (l.size() > MAX_LOG) l.remove(l.size() - 1);
            writeList("log", l);
        }
    }

    public List<String> logLines() {
        synchronized (LOCK) { return readList("log"); }
    }

    public void sawChat(String name) {
        if (name == null || name.trim().isEmpty()) return;
        synchronized (LOCK) {
            List<String> l = readList("chats");
            if (!l.isEmpty() && l.get(0).equals(name)) return;
            l.remove(name);
            l.add(0, name);
            while (l.size() > MAX_CHATS) l.remove(l.size() - 1);
            writeList("chats", l);
        }
    }

    public List<String> recentChats() {
        synchronized (LOCK) { return readList("chats"); }
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        p.registerOnSharedPreferenceChangeListener(l);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        p.unregisterOnSharedPreferenceChangeListener(l);
    }

    // ---------- helpers ----------
    private JSONArray readArray(String key) {
        try {
            return new JSONArray(p.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private List<String> readList(String key) {
        JSONArray a = readArray(key);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private void writeList(String key, List<String> l) {
        p.edit().putString(key, new JSONArray(l).toString()).apply();
    }
}
