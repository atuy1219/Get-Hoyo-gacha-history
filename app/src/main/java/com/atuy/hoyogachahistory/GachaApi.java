package com.atuy.hoyogachahistory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GachaApi {
    private static final Set<String> REMOVED_QUERY_KEYS = new HashSet<>(Arrays.asList(
            "gacha_type", "real_gacha_type", "page", "size", "end_id"
    ));

    private GachaApi() {
    }

    public static int fetchAll(GameConfig game, String capturedUrl, HistoryDb historyDb) throws Exception {
        String prefix = normalizePrefix(game, capturedUrl);
        int imported = 0;

        for (int type : game.gachaTypes) {
            String endpoint = prefix;
            if (game == GameConfig.STAR_RAIL && (type == 21 || type == 22)) {
                endpoint = prefix.replace("/getGachaLog", "/getLdGachaLog");
            }

            String endId = "0";
            for (int page = 1; page <= 200; page++) {
                String requestUrl = endpoint +
                        "&" + game.typeParameter + "=" + type +
                        "&page=" + page +
                        "&size=20" +
                        "&end_id=" + endId;

                // Network I/O must stay outside a SQLite transaction. Holding a write
                // transaction here used to lock the database for the entire import.
                JSONObject response = getJson(requestUrl);
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

                // Commit only this page (at most 20 rows), then immediately release
                // the writer lock before the next request or delay.
                imported += historyDb.upsertPage(game.key, list);

                if (list.length() < 20) {
                    break;
                }
                endId = list.getJSONObject(list.length() - 1).optString("id", "0");
                Thread.sleep(250L);
            }
        }
        return imported;
    }

    static String normalizePrefix(GameConfig game, String input) {
        String url = cleanCapturedUrl(input);
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            throw new IllegalArgumentException("認証パラメータを含むURLではありません");
        }

        int fragmentStart = url.indexOf('#', queryStart);
        String rawQuery = fragmentStart >= 0
                ? url.substring(queryStart + 1, fragmentStart)
                : url.substring(queryStart + 1);

        List<String> kept = new ArrayList<>();
        boolean hasAuthKey = false;
        boolean hasLang = false;
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            String name = part;
            int equals = part.indexOf('=');
            if (equals >= 0) {
                name = part.substring(0, equals);
            }
            if (REMOVED_QUERY_KEYS.contains(name)) {
                continue;
            }
            if ("lang".equals(name)) {
                kept.add("lang=ja-jp");
                hasLang = true;
                continue;
            }
            if ("authkey".equals(name)) {
                hasAuthKey = true;
            }
            kept.add(part);
        }

        if (!hasAuthKey) {
            throw new IllegalArgumentException("authkeyを検出できませんでした");
        }
        if (!hasLang) {
            kept.add("lang=ja-jp");
        }
        return game.apiPrefix + "?" + String.join("&", kept);
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

    private static JSONObject getJson(String requestUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Get-Hoyo-gacha-history/0.1 Android");

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
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
}
