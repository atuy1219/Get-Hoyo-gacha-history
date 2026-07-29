package com.atuy.hoyogachahistory;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GachaApi {
    private static final int ITEMS_PER_PAGE = 20;
    private static final long INITIAL_COOLDOWN_MS = 4_000L;
    private static final long REQUEST_INTERVAL_MS = 900L;
    private static final long[] RATE_LIMIT_BACKOFF_MS = {10_000L, 20_000L, 40_000L};

    private static final String WEBVIEW_USER_AGENT =
            "Mozilla/5.0 (iPad; CPU OS 18_7 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148";

    private static long lastRequestStartedAt;

    private GachaApi() {
    }

    public static int fetchAll(GameConfig game, String capturedUrl, HistoryDb historyDb) throws Exception {
        String prefix = normalizePrefix(game, capturedUrl);
        int imported = 0;

        // The game's WebView requests the first history page immediately after the URL is
        // logged. Waiting here prevents this app from racing that request and triggering -110.
        SystemClock.sleep(INITIAL_COOLDOWN_MS);

        for (int type : game.gachaTypes) {
            String endpoint = prefix;
            if (game == GameConfig.STAR_RAIL && (type == 21 || type == 22)) {
                endpoint = prefix.replace("/getGachaLog", "/getLdGachaLog");
            }

            String queryType = Integer.toString(type);
            String latestStoredId = historyDb.latestId(game.key, queryType);
            String endId = "0";

            for (int page = 0; page < 200; page++) {
                String requestUrl = endpoint +
                        "&" + game.typeParameter + "=" + type +
                        "&end_id=" + endId;

                JSONObject response = getJsonWithRateLimitRetry(requestUrl);
                int retcode = response.optInt("retcode", -1);
                if (retcode != 0) {
                    throw new IllegalStateException(
                            "HoYoverse API error " + retcode + ": " + response.optString("message", "unknown")
                    );
                }

                JSONObject data = response.optJSONObject("data");
                JSONArray list = data == null ? null : data.optJSONArray("list");
                if (list == null || list.length() == 0) {
                    break;
                }

                JSONArray newItems = new JSONArray();
                boolean reachedStoredHistory = false;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String id = item.optString("id");
                    if (latestStoredId != null && latestStoredId.equals(id)) {
                        reachedStoredHistory = true;
                        break;
                    }
                    if (!id.isEmpty()) {
                        newItems.put(item);
                    }
                }

                if (newItems.length() > 0) {
                    imported += historyDb.upsertPage(game.key, queryType, newItems);
                }

                if (reachedStoredHistory || list.length() < ITEMS_PER_PAGE) {
                    break;
                }

                endId = list.getJSONObject(list.length() - 1).optString("id", "0");
                if ("0".equals(endId) || endId.isEmpty()) {
                    break;
                }
            }
        }
        return imported;
    }

    static String normalizePrefix(GameConfig game, String input) {
        String url = cleanCapturedUrl(input);
        Map<String, String> query = parseRawQuery(url);

        String authKey = required(query, "authkey", "authkeyを検出できませんでした");
        String region = required(query, "region", "regionを検出できませんでした");
        String authKeyVersion = query.getOrDefault("authkey_ver", "1");
        String signType = query.getOrDefault("sign_type", "2");
        String authAppId = query.getOrDefault("auth_appid", "webview_gacha");
        String gameBiz = query.getOrDefault("game_biz", game.marker);
        String lang = query.getOrDefault("lang", "ja-jp");

        // Rebuild the request with only the parameters used by the official history API.
        // WebView-only values such as timestamp, device_model and gacha_id are omitted.
        return game.apiPrefix +
                "?authkey_ver=" + authKeyVersion +
                "&sign_type=" + signType +
                "&auth_appid=" + authAppId +
                "&game_biz=" + gameBiz +
                "&size=" + ITEMS_PER_PAGE +
                "&authkey=" + authKey +
                "&region=" + region +
                "&lang=" + lang;
    }

    private static Map<String, String> parseRawQuery(String url) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            throw new IllegalArgumentException("認証パラメータを含むURLではありません");
        }

        int fragmentStart = url.indexOf('#', queryStart);
        String rawQuery = fragmentStart >= 0
                ? url.substring(queryStart + 1, fragmentStart)
                : url.substring(queryStart + 1);

        Map<String, String> result = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int equals = part.indexOf('=');
            String name = equals >= 0 ? part.substring(0, equals) : part;
            String value = equals >= 0 ? part.substring(equals + 1) : "";
            if (!name.isEmpty()) {
                result.put(name, value);
            }
        }
        return result;
    }

    private static String required(Map<String, String> query, String name, String message) {
        String value = query.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String cleanCapturedUrl(String input) {
        String value = input.trim()
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .replace("\\/", "/");
        while (!value.isEmpty()) {
            char last = value.charAt(value.length() - 1);
            if (last == ')' || last == ']' || last == '}' || last == ',' || last == ';' || last == '"' || last == '\'') {
                value = value.substring(0, value.length() - 1);
            } else {
                break;
            }
        }
        return value;
    }

    private static JSONObject getJsonWithRateLimitRetry(String requestUrl) throws Exception {
        for (int attempt = 0; ; attempt++) {
            JSONObject response = getJsonOnce(requestUrl);
            if (response.optInt("retcode", -1) != -110) {
                return response;
            }

            if (attempt >= RATE_LIMIT_BACKOFF_MS.length) {
                throw new IllegalStateException(
                        "HoYoverse APIのアクセス制限（-110）です。1〜2分待ってから再取得してください"
                );
            }
            SystemClock.sleep(RATE_LIMIT_BACKOFF_MS[attempt]);
        }
    }

    private static JSONObject getJsonOnce(String requestUrl) throws Exception {
        awaitRequestSlot();

        HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en;q=0.8");
        connection.setRequestProperty("User-Agent", WEBVIEW_USER_AGENT);
        connection.setRequestProperty("Origin", "https://gs.hoyoverse.com");
        connection.setRequestProperty("Referer", "https://gs.hoyoverse.com/");

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + status);
        }

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        } finally {
            connection.disconnect();
        }

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + ": " + body);
        }
        return new JSONObject(body.toString());
    }

    private static synchronized void awaitRequestSlot() {
        long now = SystemClock.elapsedRealtime();
        long wait = REQUEST_INTERVAL_MS - (now - lastRequestStartedAt);
        if (wait > 0) {
            SystemClock.sleep(wait);
        }
        lastRequestStartedAt = SystemClock.elapsedRealtime();
    }
}
