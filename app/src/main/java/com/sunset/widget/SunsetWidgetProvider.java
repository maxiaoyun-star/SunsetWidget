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
    public static final String KEY_CITY_LIST = "city_list";
    public static final String KEY_CURRENT_CITY = "current_city";
    public static final String KEY_SOURCE = "source";
    public static final String DEFAULT_CITY = "长春";
    public static final String DEFAULT_SOURCE = "GFS";

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
        final String city = prefs.getString(KEY_CURRENT_CITY, DEFAULT_CITY);
        final String source = prefs.getString(KEY_SOURCE, DEFAULT_SOURCE);

        RemoteViews views = buildBaseViews(context, widgetId, source);
        views.setTextViewText(R.id.tv_footer, city + " 加载中…");
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

        SunsetDataFetcher.DayData today = SunsetDataFetcher.fetchDay(city, source, true);
        String effectiveSource = source;
        // 只有"请求成功但这个数据源没有这座城市的数据"才值得换另一个数据源；
        // 请求本身失败时换数据源没有意义，只会白白多等一轮。
        if (!today.hasData && today.error.isEmpty()) {
            String other = otherSource(source);
            SunsetDataFetcher.DayData alt = SunsetDataFetcher.fetchDay(city, other, true);
            if (alt.hasData) {
                today = alt;
                effectiveSource = other;
            }
        }
        SunsetDataFetcher.DayData tomorrow = SunsetDataFetcher.fetchDay(city, effectiveSource, false);

        views.setTextViewText(R.id.tv_today_level, today.level);
        views.setTextViewText(R.id.tv_today_quality, today.hasData ? "鲜艳度 " + today.quality : "鲜艳度 —");
        views.setTextViewText(R.id.tv_today_sunrise, "日出 " + today.sunrise);
        views.setTextViewText(R.id.tv_today_sunset, "日落 " + today.sunset);

        views.setTextViewText(R.id.tv_tomorrow_level, tomorrow.level);
        views.setTextViewText(R.id.tv_tomorrow_quality, tomorrow.hasData ? "鲜艳度 " + tomorrow.quality : "鲜艳度 —");
        views.setTextViewText(R.id.tv_tomorrow_sunrise, "日出 " + tomorrow.sunrise);
        views.setTextViewText(R.id.tv_tomorrow_sunset, "日落 " + tomorrow.sunset);

        views.setTextViewText(R.id.btn_source, effectiveSource);
        views.setTextViewText(R.id.tv_footer, footerText(city, today, tomorrow, effectiveSource));

        mgr.updateAppWidget(widgetId, views);
    }

    /** 底部状态行。取数失败时把原因写出来，否则用户只能看到"待更新"，无从判断。 */
    private String footerText(String city, SunsetDataFetcher.DayData today,
                              SunsetDataFetcher.DayData tomorrow, String source) {
        // display_city_name 形如 "吉林省-长春"，底部只留城市本身，省得挤成两行
        String cityDisplay = today.cityDisplay;
        int dash = cityDisplay.lastIndexOf('-');
        if (dash >= 0 && dash + 1 < cityDisplay.length()) {
            cityDisplay = cityDisplay.substring(dash + 1);
        }
        String cityName = cityDisplay.isEmpty() ? city : cityDisplay;
        String error = !today.error.isEmpty() ? today.error : tomorrow.error;
        String status;
        if (!error.isEmpty()) {
            status = "取数失败 " + error;
        } else if (!today.hasData) {
            status = "暂无预报";
        } else {
            status = "更新 " + nowTime();
        }
        return cityName + " · " + source + " · " + status;
    }

    private String otherSource(String source) {
        return "EC".equals(source) ? "GFS" : "EC";
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
