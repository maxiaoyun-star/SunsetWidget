package com.sunset.widget;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SunsetDataFetcher {

    private static final String BASE_URL = "https://sunsetbot.top/detailed/";

    public static class DayData {
        public boolean hasData = false;
        public String level = "待更新";
        public String quality = "—";
        public String sunrise = "—:—";
        public String sunset = "—:—";
        public String cityDisplay = "";
    }

    /**
     * 拉取某一天的火烧云预测数据。
     *
     * @param city   城市简称，如 "长春"
     * @param source 数据源，EC 或 GFS
     * @param today  true 为今天，false 为明天
     */
    public static DayData fetchDay(String city, String source, boolean today) throws Exception {
        String sunsetEvent = today ? "set_1" : "set_2";
        String sunriseEvent = today ? "rise_1" : "rise_2";

        DayData data = new DayData();

        // 日落是火烧云预测的核心，网络失败则向上抛异常
        JSONObject sunset = fetchEvent(city, source, sunsetEvent);
        data.cityDisplay = sunset.optString("display_city_name", "");
        String qualityRaw = sunset.optString("tb_quality", "");
        String[] q = parseQuality(qualityRaw);
        data.quality = q[0];
        data.level = q[1];
        data.sunset = parseTime(sunset.optString("tb_event_time", ""));
        data.hasData = !data.level.isEmpty() && !"—".equals(data.level) && !"待更新".equals(data.level);

        // 日出时间单独获取，失败不影响日落预测
        try {
            JSONObject sunrise = fetchEvent(city, source, sunriseEvent);
            data.sunrise = parseTime(sunrise.optString("tb_event_time", ""));
        } catch (Exception ignored) {
        }

        return data;
    }

    private static JSONObject fetchEvent(String city, String source, String event) throws Exception {
        String queryId = String.valueOf(System.currentTimeMillis() % 10000000);
        StringBuilder sb = new StringBuilder(BASE_URL);
        sb.append("?query_id=").append(queryId);
        sb.append("&intend=select_city");
        sb.append("&query_city=").append(URLEncoder.encode(city, "UTF-8"));
        sb.append("&model=").append(source);
        sb.append("&event_date=None");
        sb.append("&event=").append(event);
        sb.append("&times=None");

        HttpURLConnection conn = null;
        try {
            URL url = new URL(sb.toString());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SunsetWidget/1.0");
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + code);
            }

            String body = readStream(conn.getInputStream());
            return new JSONObject(body);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    /** 解析 "0.022（微烧）" -> {"0.022", "微烧"} */
    private static String[] parseQuality(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new String[]{"—", "待更新"};
        }
        int s = raw.indexOf('（');
        int e = raw.indexOf('）');
        if (s >= 0 && e > s) {
            return new String[]{raw.substring(0, s), raw.substring(s + 1, e)};
        }
        s = raw.indexOf('(');
        e = raw.indexOf(')');
        if (s >= 0 && e > s) {
            return new String[]{raw.substring(0, s), raw.substring(s + 1, e)};
        }
        // 无括号(如 "-" 或纯数值):等级视为待更新
        return new String[]{raw, "待更新"};
    }

    /** 解析 "2026-09-16 18:03:52" -> "18:03" */
    private static String parseTime(String raw) {
        if (raw == null || raw.length() < 16) {
            return "—:—";
        }
        return raw.substring(11, 16);
    }
}
