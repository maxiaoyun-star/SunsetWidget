package com.sunset.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SunsetWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.sunset.widget.ACTION_REFRESH";
    public static final String ACTION_TOGGLE_SOURCE = "com.sunset.widget.ACTION_TOGGLE_SOURCE";

    public static final String PREFS_NAME = "sunset_prefs";
    public static final String KEY_CITY = "city";
    public static final String KEY_SOURCE = "source";
    private static final String DEFAULT_CITY = "上海";
    private static final String DEFAULT_SOURCE = "EC";

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(1);

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            refreshWidget(context, appWidgetManager, id);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action) || ACTION_TOGGLE_SOURCE.equals(action)) {
            if (ACTION_TOGGLE_SOURCE.equals(action)) {
                toggleSource(context);
            }
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, SunsetWidgetProvider.class));
            for (int id : ids) {
                refreshWidget(context, mgr, id);
            }
        }
    }

    private void toggleSource(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cur = prefs.getString(KEY_SOURCE, DEFAULT_SOURCE);
        String next = "EC".equals(cur) ? "GFS" : "EC";
        prefs.edit().putString(KEY_SOURCE, next).apply();
    }

    /** 立即把小组件置为加载态并绑定点击，然后后台拉数据 */
    private void refreshWidget(Context context, AppWidgetManager mgr, int widgetId) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final String city = prefs.getString(KEY_CITY, DEFAULT_CITY);
        final String source = prefs.getString(KEY_SOURCE, DEFAULT_SOURCE);

        RemoteViews views = buildBaseViews(context, widgetId, source);
        views.setTextViewText(R.id.tv_footer, "加载中…");
        mgr.updateAppWidget(widgetId, views);

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                fetchAndRender(context, widgetId, city, source);
            }
        });
    }

    /** 后台线程：请求数据并刷新小组件 */
    private void fetchAndRender(Context context, int widgetId, String city, String source) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        RemoteViews views = buildBaseViews(context, widgetId, source);
        try {
            SunsetDataFetcher.DayData today = SunsetDataFetcher.fetchDay(city, source, true);
            SunsetDataFetcher.DayData tomorrow = SunsetDataFetcher.fetchDay(city, source, false);

            views.setTextViewText(R.id.tv_today_level, today.level);
            views.setTextViewText(R.id.tv_today_quality, "鲜艳度 " + today.quality);
            views.setTextViewText(R.id.tv_today_sunrise, "日出 " + today.sunrise);
            views.setTextViewText(R.id.tv_today_sunset, "日落 " + today.sunset);

            views.setTextViewText(R.id.tv_tomorrow_level, tomorrow.level);
            views.setTextViewText(R.id.tv_tomorrow_quality, "鲜艳度 " + tomorrow.quality);
            views.setTextViewText(R.id.tv_tomorrow_sunrise, "日出 " + tomorrow.sunrise);
            views.setTextViewText(R.id.tv_tomorrow_sunset, "日落 " + tomorrow.sunset);

            String footer = city + " · " + source + " · 更新 " + nowTime();
            views.setTextViewText(R.id.tv_footer, footer);
        } catch (Exception e) {
            views.setTextViewText(R.id.tv_today_level, "—");
            views.setTextViewText(R.id.tv_tomorrow_level, "—");
            views.setTextViewText(R.id.tv_footer, city + " · " + source + " · 获取失败，点刷新重试");
        }
        mgr.updateAppWidget(widgetId, views);
    }

    /** 创建视图并绑定所有点击事件（每次更新都要重新绑定，否则按钮失效） */
    private RemoteViews buildBaseViews(Context context, int widgetId, String source) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_sunset);

        views.setTextViewText(R.id.btn_source, source);

        views.setOnClickPendingIntent(R.id.btn_refresh,
                broadcastPendingIntent(context, ACTION_REFRESH, widgetId, 0));
        views.setOnClickPendingIntent(R.id.btn_source,
                broadcastPendingIntent(context, ACTION_TOGGLE_SOURCE, widgetId, 10000));
        views.setOnClickPendingIntent(R.id.widget_root, openConfigPendingIntent(context));

        return views;
    }

    private PendingIntent broadcastPendingIntent(Context context, String action, int widgetId, int offset) {
        Intent intent = new Intent(context, SunsetWidgetProvider.class);
        intent.setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, widgetId + offset, intent, flags);
    }

    private PendingIntent openConfigPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, 20000, intent, flags);
    }

    private String nowTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date());
    }
}
