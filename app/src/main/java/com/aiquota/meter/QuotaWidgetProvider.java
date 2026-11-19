package com.aiquota.meter;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

public class QuotaWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.aiquota.meter.ACTION_REFRESH";

    private static final int COLOR_GREEN = 0xFF34D399;
    private static final int COLOR_AMBER = 0xFFFB923C;
    private static final int COLOR_RED   = 0xFFF87171;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            final PendingResult result = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        QuotaStore.syncFromCloudBlocking(context);
                        updateAllWidgets(context);
                    } finally {
                        result.finish();
                    }
                }
            }).start();
        }
    }

    public static void updateAllWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, QuotaWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        for (int id : ids) {
            updateAppWidget(context, mgr, id);
        }
    }

    private static int getColorForPct(double pct) {
        if (pct >= 90.0) return COLOR_RED;
        if (pct >= 75.0) return COLOR_AMBER;
        return COLOR_GREEN;
    }

    private static void setColoredProgress(RemoteViews views, int greenId, int amberId, int redId, double pct) {
        int clamped = (int) Math.max(0, Math.min(100, Math.round(pct)));
        int activeId = pct >= 90.0 ? redId : (pct >= 75.0 ? amberId : greenId);

        views.setViewVisibility(greenId, activeId == greenId ? View.VISIBLE : View.GONE);
        views.setViewVisibility(amberId, activeId == amberId ? View.VISIBLE : View.GONE);
        views.setViewVisibility(redId, activeId == redId ? View.VISIBLE : View.GONE);
        views.setProgressBar(activeId, 100, clamped, false);
    }

    public static void updateAppWidget(Context context, AppWidgetManager mgr, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quota);
        JSONObject state = QuotaStore.getNormalizedState(context);
        String syncUrl = QuotaStore.getSyncUrl(context);

        views.setTextViewText(R.id.tv_sync_status, (syncUrl != null && !syncUrl.isEmpty()) ? "雲端已連線" : "本機模式");

        try {
            // 1. Gemini PRO
            JSONObject gm = state.getJSONObject("gemini");
            double gmWeekPct = gm.optDouble("weekPct", 0.0);
            long gmWeekReset = gm.optLong("weekResetAt", 0L);
            int gmWeekCycle = gm.optInt("weekCycleHours", 168);

            double gmSessPct = gm.optDouble("sessionPct", 0.0);
            long gmSessReset = gm.optLong("sessionResetAt", 0L);
            int gmSessCycle = gm.optInt("sessionCycleHours", 3);

            views.setTextViewText(R.id.tv_gm_week, "週 " + QuotaStore.formatPct(gmWeekPct) + "% · " + QuotaStore.formatCountdown(gmWeekReset, gmWeekCycle, ""));
            views.setTextColor(R.id.tv_gm_week, getColorForPct(gmWeekPct));
            setColoredProgress(views, R.id.pb_gm_week_green, R.id.pb_gm_week_amber, R.id.pb_gm_week_red, gmWeekPct);

            views.setTextViewText(R.id.tv_gm_sess, "段 " + QuotaStore.formatPct(gmSessPct) + "% · " + QuotaStore.formatCountdown(gmSessReset, gmSessCycle, ""));
            setColoredProgress(views, R.id.pb_gm_sess_green, R.id.pb_gm_sess_amber, R.id.pb_gm_sess_red, gmSessPct);

            // 2. ChatGPT PLUS
            JSONObject cg = state.getJSONObject("chatgpt");
            double cgPct = cg.optDouble("pct", 0.0);
            double cgStep = cg.optDouble("step", 1.25);
            if (cgStep <= 0) cgStep = 1.25;
            int cgCount = (int) Math.round(cgPct / cgStep);
            int cgMax = (int) Math.round(100.0 / cgStep);

            views.setTextViewText(R.id.tv_cg_pct, "已用 " + QuotaStore.formatPct(cgPct) + "% (" + cgCount + "/" + cgMax + ")");
            views.setTextColor(R.id.tv_cg_pct, getColorForPct(cgPct));
            setColoredProgress(views, R.id.pb_cg_green, R.id.pb_cg_amber, R.id.pb_cg_red, cgPct);
            views.setTextViewText(R.id.tv_cg_reset, QuotaStore.formatCountdown(cg.optLong("resetAt", 0L), cg.optInt("cycleHours", 3), "重置"));

            // 3. Grok FREE
            JSONObject gk = state.getJSONObject("grok");
            double gkPct = gk.optDouble("pct", 0.0);
            double gkStep = gk.optDouble("step", 10.0);
            if (gkStep <= 0) gkStep = 10.0;
            int gkCount = (int) Math.round(gkPct / gkStep);
            int gkMax = (int) Math.round(100.0 / gkStep);

            views.setTextViewText(R.id.tv_gk_pct, "已用 " + QuotaStore.formatPct(gkPct) + "% (" + gkCount + "/" + gkMax + ")");
            views.setTextColor(R.id.tv_gk_pct, getColorForPct(gkPct));
            setColoredProgress(views, R.id.pb_gk_green, R.id.pb_gk_amber, R.id.pb_gk_red, gkPct);
            views.setTextViewText(R.id.tv_gk_reset, QuotaStore.formatCountdown(gk.optLong("resetAt", 0L), gk.optInt("cycleHours", 2), "重置"));

            // 4. Manus FREE
            JSONObject mn = state.getJSONObject("manus");
            double mnPct = mn.optDouble("pct", 0.0);
            double mnStep = mn.optDouble("step", 50.0);
            if (mnStep <= 0) mnStep = 50.0;
            int mnCount = (int) Math.round(mnPct / mnStep);
            int mnMax = (int) Math.round(100.0 / mnStep);

            views.setTextViewText(R.id.tv_mn_pct, "已用 " + QuotaStore.formatPct(mnPct) + "% (" + mnCount + "/" + mnMax + ")");
            views.setTextColor(R.id.tv_mn_pct, getColorForPct(mnPct));
            setColoredProgress(views, R.id.pb_mn_green, R.id.pb_mn_amber, R.id.pb_mn_red, mnPct);
            views.setTextViewText(R.id.tv_mn_reset, QuotaStore.formatCountdown(mn.optLong("resetAt", 0L), mn.optInt("cycleHours", 24), "重置"));

        } catch (Exception ignored) {
        }

        // Tap refresh button -> sync from cloud & refresh widget in background
        Intent refreshIntent = new Intent(context, QuotaWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        PendingIntent refreshPi = PendingIntent.getBroadcast(
                context, 1001, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_refresh, refreshPi);

        // Tap any card -> open MainActivity
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent launchPi = PendingIntent.getActivity(
                context, 1002, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.card_gemini, launchPi);
        views.setOnClickPendingIntent(R.id.card_chatgpt, launchPi);
        views.setOnClickPendingIntent(R.id.card_grok, launchPi);
        views.setOnClickPendingIntent(R.id.card_manus, launchPi);

        mgr.updateAppWidget(appWidgetId, views);
    }
}
