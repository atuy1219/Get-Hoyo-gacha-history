package com.atuy.hoyogachahistory;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrivilegedService extends IPrivilegedService.Stub {
    private static final String TAG = "HoyoPrivileged";
    private static final Pattern PLAIN_URL = Pattern.compile("https://[^\\s\\\"'<>]+");
    private static final Pattern ENCODED_URL = Pattern.compile("https%3A%2F%2F[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);

    public PrivilegedService() {
        Log.i(TAG, "created");
    }

    @SuppressWarnings("unused")
    public PrivilegedService(Context context) {
        Log.i(TAG, "created with context");
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public boolean clearLogcat() {
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-c").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            Log.e(TAG, "clearLogcat failed", e);
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public String captureUrl(String gameMarker, int timeoutMs) {
        Process process = null;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            process = new ProcessBuilder("logcat", "-v", "raw")
                    .redirectErrorStream(true)
                    .start();
            Process finalProcess = process;
            timer.schedule(finalProcess::destroy, Math.max(5_000, timeoutMs), TimeUnit.MILLISECONDS);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String found = findUrl(line, gameMarker);
                    if (!found.isEmpty()) {
                        process.destroy();
                        return found;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "captureUrl failed", e);
        } finally {
            timer.shutdownNow();
            if (process != null) {
                process.destroy();
            }
        }
        return "";
    }

    @Override
    public String identity() {
        return "pid=" + Os.getpid() + ", uid=" + Os.getuid();
    }

    private static String findUrl(String line, String gameMarker) {
        String normalized = line.replace("&amp;", "&").replace("\\u0026", "&");
        Matcher plain = PLAIN_URL.matcher(normalized);
        while (plain.find()) {
            String candidate = trim(plain.group());
            if (isCandidate(candidate, gameMarker)) {
                return candidate;
            }
        }

        Matcher encoded = ENCODED_URL.matcher(normalized);
        while (encoded.find()) {
            try {
                String candidate = trim(URLDecoder.decode(encoded.group(), StandardCharsets.UTF_8.name()));
                if (isCandidate(candidate, gameMarker)) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static boolean isCandidate(String url, String marker) {
        boolean matchingGame = url.contains(marker)
                || (marker.startsWith("hk4e") && url.contains("/genshin/"))
                || (marker.startsWith("hkrpg") && url.contains("/hkrpg/"))
                || (marker.startsWith("nap") && url.contains("/nap/"));
        boolean authenticated = url.contains("authkey=") || url.contains("authkey%3D");
        return matchingGame && authenticated;
    }

    private static String trim(String value) {
        String result = value.replace("\\/", "/");
        while (!result.isEmpty()) {
            char c = result.charAt(result.length() - 1);
            if (c == ')' || c == ']' || c == '}' || c == ',' || c == ';' || c == '"' || c == '\'') {
                result = result.substring(0, result.length() - 1);
            } else {
                break;
            }
        }
        return result;
    }
}
