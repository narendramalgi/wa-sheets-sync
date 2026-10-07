package com.example.washeets;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends queued reports to the Google Apps Script web app, one request at a time. */
public final class Uploader {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final int BATCH = 10;

    private Uploader() {}

    public static void flush(Context ctx) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> flushNow(app));
    }

    /** Checks the URL and secret without adding anything to the sheet. */
    public static void testConnection(Context ctx) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            Store s = new Store(app);
            try {
                JSONObject m = new JSONObject();
                m.put("id", "test-" + System.currentTimeMillis());
                m.put("test", true);
                JSONArray arr = new JSONArray();
                arr.put(m);
                JSONObject r = post(s, arr);
                s.log("✅ Connected to the sheet. Take Home Cash till date: " + rupees(r.optDouble("totalCash", 0)));
            } catch (Exception e) {
                s.log("❌ Connection test failed: " + e.getMessage());
            }
        });
    }

    /** Queues a report typed or pasted into the app (for a night the phone missed). */
    public static void addManual(Context ctx, String text) {
        Store s = new Store(ctx);
        try {
            long now = System.currentTimeMillis();
            JSONObject m = new JSONObject();
            m.put("id", "manual-" + now);
            m.put("group", "Manual entry");
            m.put("sender", "");
            m.put("time", now);
            m.put("captured", now);
            m.put("text", text.trim());
            m.put("report", true);
            s.enqueue(m);
            s.log("✍ Manual report queued");
        } catch (Exception e) {
            s.log("❌ Could not queue manual report: " + e.getMessage());
        }
        flush(ctx);
    }

    private static void flushNow(Context app) {
        Store s = new Store(app);
        if (s.url().isEmpty()) {
            if (s.queueSize() > 0) s.log("⏸ Not sent yet: add your Apps Script URL in Settings");
            return;
        }
        while (true) {
            JSONArray batch = s.peekQueue(BATCH);
            if (batch.length() == 0) return;
            try {
                JSONObject r = post(s, batch);
                s.dropFromQueue(batch.length());
                int added = r.optInt("added");
                int skipped = r.optInt("skipped");
                if (added > 0) {
                    String cash = r.isNull("cash") ? "—" : rupees(r.optDouble("cash"));
                    String total = rupees(r.optDouble("totalCash", 0));
                    s.log("✅ Saved to sheet. Cash " + cash + " · Till date " + total);
                    Notifier.show(app, 1, "Report saved to Google Sheet",
                            "Take Home Cash: " + cash + "\nTake Home Cash till date: " + total);
                }
                if (skipped > 0) {
                    s.log("⚠ Sheet skipped " + skipped + " message(s) — already saved, or not in the report format");
                }
            } catch (Exception e) {
                s.log("⚠ Send failed, will retry: " + e.getMessage());
                return;
            }
        }
    }

    private static JSONObject post(Store s, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("secret", s.secret());
        body.put("messages", messages);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(s.url()).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true); // Apps Script answers via a redirect
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        int code = c.getResponseCode();
        String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);

        JSONObject r;
        try {
            r = new JSONObject(text);
        } catch (Exception e) {
            throw new Exception("Unexpected reply — check the URL ends in /exec and access is 'Anyone'");
        }
        if (!r.optBoolean("ok")) throw new Exception(r.optString("error", "server error"));
        return r;
    }

    static String rupees(double v) {
        NumberFormat f = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        f.setMaximumFractionDigits(2);
        return "₹" + f.format(v);
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }
}
