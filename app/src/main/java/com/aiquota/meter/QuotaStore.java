package com.aiquota.meter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class QuotaStore {
    private static final String PREF_NAME = "ai_quota_prefs";
    private static final String KEY_STATE = "quota_v3_state";
    private static final String KEY_SYNC_URL = "quota_sync_url";

    public static String getDefaultJson() {
        return "{"
                + "\"gemini\":{\"weekPct\":0,\"weekResetAt\":0,\"weekCycleHours\":168,\"weekStep\":1,"
                + "\"sessionPct\":0,\"sessionResetAt\":0,\"sessionCycleHours\":3,\"sessionStep\":2},"
                + "\"chatgpt\":{\"pct\":0,\"resetAt\":0,\"cycleHours\":3,\"step\":1.25},"
                + "\"grok\":{\"pct\":0,\"resetAt\":0,\"cycleHours\":2,\"step\":10},"
                + "\"manus\":{\"pct\":0,\"resetAt\":0,\"cycleHours\":24,\"step\":50}"
                + "}";
    }

    public static String getSyncUrl(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_SYNC_URL, "");
    }

    public static void saveSyncUrl(Context ctx, String url) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_SYNC_URL, url == null ? "" : url.trim()).apply();
    }

    public static void saveStateJson(Context ctx, String json) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_STATE, json).apply();
    }

    public static JSONObject getNormalizedState(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_STATE, null);
        JSONObject state;
        try {
            state = new JSONObject(raw != null ? raw : getDefaultJson());
            if (!state.has("gemini") || !state.has("chatgpt") || !state.has("grok") || !state.has("manus")) {
                state = new JSONObject(getDefaultJson());
            }
        } catch (Exception e) {
            try {
                state = new JSONObject(getDefaultJson());
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }

        long now = System.currentTimeMillis();
        boolean changed = false;
        try {
            JSONObject gm = state.getJSONObject("gemini");
            long gmWeekReset = gm.optLong("weekResetAt", 0L);
            if (gmWeekReset > 0 && now >= gmWeekReset) {
                gm.put("weekPct", 0);
                gm.put("weekResetAt", 0L);
                changed = true;
            }
            long gmSessReset = gm.optLong("sessionResetAt", 0L);
            if (gmSessReset > 0 && now >= gmSessReset) {
                gm.put("sessionPct", 0);
                gm.put("sessionResetAt", 0L);
                changed = true;
            }
            String[] keys = new String[]{"chatgpt", "grok", "manus"};
            for (String key : keys) {
                JSONObject item = state.getJSONObject(key);
                long resetAt = item.optLong("resetAt", 0L);
                if (resetAt > 0 && now >= resetAt) {
                    item.put("pct", 0);
                    item.put("resetAt", 0L);
                    changed = true;
                }
            }
        } catch (Exception ignored) {
        }

        if (changed || raw == null) {
            saveStateJson(ctx, state.toString());
        }
        return state;
    }

    public static boolean syncFromCloudBlocking(Context ctx) {
        String urlStr = getSyncUrl(ctx);
        if (urlStr == null || urlStr.isEmpty()) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
                JSONObject remote = new JSONObject(sb.toString());
                if (remote.has("gemini") && remote.has("manus")) {
                    saveStateJson(ctx, remote.toString());
                    return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    public static void pushToCloudAsync(final String json, final String urlStr) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlStr.trim());
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setInstanceFollowRedirects(true);
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();
                    conn.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    public static String formatPct(double pct) {
        if (Math.abs(pct - Math.rint(pct)) < 0.05) {
            return String.valueOf((int) Math.rint(pct));
        }
        return String.format(Locale.US, "%.1f", pct);
    }

    public static String formatCountdown(long resetAt, int cycleHours, String suffix) {
        long now = System.currentTimeMillis();
        if (resetAt <= 0 || resetAt <= now) {
            return "待機 · " + cycleHours + "h視窗";
        }
        long diffSecs = Math.max(0L, (resetAt - now) / 1000L);
        long days = diffSecs / 86400L;
        long hours = (diffSecs % 86400L) / 3600L;
        long mins = (diffSecs % 3600L) / 60L;

        if (days > 0) {
            return String.format(Locale.US, "%d天%02d時%02d分%s", days, hours, mins, suffix);
        }
        return String.format(Locale.US, "%02d時%02d分%s", hours, mins, suffix);
    }
}
