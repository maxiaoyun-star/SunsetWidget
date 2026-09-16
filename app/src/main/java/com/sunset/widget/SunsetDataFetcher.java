package com.sunset.widget;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SSLException;

public class SunsetDataFetcher {

    private static final String BASE_URL = "https://sunsetbot.top/detailed/";
    private static final String SITE_URL = "https://sunsetbot.top/";
    // 网站前面挂了 CDN，请求头对齐网站自身的 jQuery AJAX，免得被当成爬虫拦掉
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;

    public static class DayData {
        public boolean hasData = false;
        public String level = "待更新";
        public String quality = "—";
        public String sunriseLevel = "待更新";
        public String sunriseQuality = "—";
        public String sunrise = "—:—";
        public String sunset = "—:—";
        public String cityDisplay = "";
        /** 请求失败时的简短原因；为空表示请求本身是成功的。 */
        public String error = "";
    }

    /**
     * 拉取某一天的火烧云预测数据。
     *
     * <p>网络问题不抛异常，而是把原因写进 {@link DayData#error}，这样可以和"该时次还没有
     * 预报"区分开：前者是取数失败，后者是数据源确实没生成。
     *
     * @param city   城市简称，如 "长春"
     * @param source 数据源，EC 或 GFS
     * @param today  true 为今天，false 为明天
     */
    public static DayData fetchDay(String city, String source, boolean today) {
        String sunsetEvent = today ? "set_1" : "set_2";
        String sunriseEvent = today ? "rise_1" : "rise_2";

        DayData data = new DayData();

        JSONObject sunset;
        try {
            sunset = fetchEvent(city, source, sunsetEvent);
        } catch (Exception e) {
            data.error = describe(e);
            return data;
        }

        data.cityDisplay = sunset.optString("display_city_name", "");
        String[] q = parseQuality(sunset.optString("tb_quality", ""));
        data.quality = q[0];
        data.level = q[1];
        data.sunset = parseTime(sunset.optString("tb_event_time", ""));
        data.hasData = !"—".equals(data.level) && !"待更新".equals(data.level);

        // 日出时间与火烧云预测单独获取，失败不影响日落预测
        try {
            JSONObject sunrise = fetchEvent(city, source, sunriseEvent);
            data.sunrise = parseTime(sunrise.optString("tb_event_time", ""));
            String[] sq = parseQuality(sunrise.optString("tb_quality", ""));
            data.sunriseQuality = sq[0];
            data.sunriseLevel = sq[1];
        } catch (Exception ignored) {
        }

        return data;
    }

    /** 源站偶发 525，单次请求失败就重试几次。 */
    private static JSONObject fetchEvent(String city, String source, String event) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return fetchEventOnce(city, source, event);
            } catch (Exception e) {
                last = e;
                if (attempt < ATTEMPTS) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last;
    }

    private static JSONObject fetchEventOnce(String city, String source, String event) throws Exception {
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
            conn = (HttpURLConnection) new URL(sb.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Referer", SITE_URL);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + code);
            }

            String body = readBody(conn);
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                throw new Exception("NOT_JSON");
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 读取响应体。服务器可能返回 gzip 压缩内容（CDN 会根据 Accept-Encoding 决定），
     * 系统没有自动解压时按魔数手动解压，否则 JSON 解析必然失败。
     */
    private static String readBody(HttpURLConnection conn) throws Exception {
        byte[] bytes = readAll(conn.getInputStream());
        if (bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes));
            try {
                bytes = readAll(gz);
            } finally {
                gz.close();
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            is.close();
        }
        return out.toByteArray();
    }

    /** 把异常翻译成小组件底部能放下的短原因，尽量精确到类型，便于定位。 */
    private static String describe(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return "超时";
        }
        if (e instanceof UnknownHostException) {
            return "域名解析失败";
        }
        if (e instanceof ConnectException) {
            return "连接失败";
        }
        if (e instanceof SSLException) {
            return "SSL握手失败";
        }
        if (e instanceof SecurityException) {
            return "权限不足";
        }
        String msg = e.getMessage();
        if (msg != null) {
            if (msg.startsWith("HTTP ")) {
                return msg;
            }
            if ("NOT_JSON".equals(msg)) {
                return "返回数据异常";
            }
            String low = msg.toLowerCase(Locale.US);
            if (low.contains("reset")) {
                return "连接被重置";
            }
            if (low.contains("cleartext")) {
                return "明文被禁止";
            }
            if (low.contains("unreachable") || low.contains("no route")) {
                return "网络不可达";
            }
        }
        return e.getClass().getSimpleName();
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
