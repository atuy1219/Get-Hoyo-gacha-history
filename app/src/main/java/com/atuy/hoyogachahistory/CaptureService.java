package com.atuy.hoyogachahistory;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public final class CaptureService extends Service {
    public static final String ACTION_UPDATED = BuildConfig.APPLICATION_ID + ".ACTION_UPDATED";
    public static final String EXTRA_GAME = "game";

    private static final String TAG = "HoyoCapture";
    private static final String CHANNEL_ID = "gacha_capture";
    private static final int NOTIFICATION_ID = 1219;
    private static final Map<GameConfig, String> CAPTURED_URLS = new EnumMap<>(GameConfig.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private GameConfig currentGame;
    private boolean bound;

    private final ServiceConnection privilegedConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = true;
            IPrivilegedService privileged = IPrivilegedService.Stub.asInterface(binder);
            executor.execute(() -> runCapture(privileged));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            if (running.get()) {
                finishWithError("Shizuku UserServiceとの接続が切れました");
            }
        }
    };

    private final Shizuku.UserServiceArgs userServiceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, PrivilegedService.class.getName())
    )
            .daemon(false)
            .processNameSuffix("gacha")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    static synchronized String getCapturedUrl(GameConfig game) {
        return CAPTURED_URLS.get(game);
    }

    static synchronized boolean hasCapturedUrl(GameConfig game) {
        String url = CAPTURED_URLS.get(game);
        return url != null && !url.isEmpty();
    }

    static synchronized void clearCapturedUrl(GameConfig game) {
        CAPTURED_URLS.remove(game);
    }

    private static synchronized void setCapturedUrl(GameConfig game, String url) {
        CAPTURED_URLS.put(game, url);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_GAME)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }

        try {
            currentGame = GameConfig.fromKey(intent.getStringExtra(EXTRA_GAME));
        } catch (Exception e) {
            finishWithError("ゲーム指定が不正です");
            return START_NOT_STICKY;
        }

        clearCapturedUrl(currentGame);
        broadcastUpdate();
        startForeground(
                NOTIFICATION_ID,
                buildNotification(currentGame.displayName + "の履歴URLを待機中", "ゲーム内のガチャ履歴画面を開いてください", true)
        );

        try {
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                finishWithError("Shizuku権限がありません");
                return START_NOT_STICKY;
            }
            Shizuku.bindUserService(userServiceArgs, privilegedConnection);
        } catch (Throwable error) {
            Log.e(TAG, "bindUserService failed", error);
            finishWithError("Shizukuに接続できません: " + safeMessage(error));
        }
        return START_NOT_STICKY;
    }

    private void runCapture(IPrivilegedService privileged) {
        try {
            updateNotification(
                    currentGame.displayName + "を監視中",
                    "ガチャ履歴画面を開くと自動取得します"
            );
            privileged.clearLogcat();
            String url = privileged.captureUrl(currentGame.marker, 120_000);
            if (url == null || url.isEmpty()) {
                throw new IllegalStateException("2分以内に認証URLを検出できませんでした");
            }

            setCapturedUrl(currentGame, url);
            broadcastUpdate();
            updateNotification(
                    currentGame.displayName + "のURLを取得済み",
                    "公式APIから履歴を読み込んでいます"
            );
            HistoryDb historyDb = new HistoryDb(this);
            int imported = GachaApi.fetchAll(currentGame, url, historyDb);
            HistoryDb.Stats stats = historyDb.stats(currentGame.key);

            String message = "取得完了: " + stats.total + "件（今回照会 " + imported + "件）";
            saveStatus(message);
            running.set(false);
            updateNotification(currentGame.displayName + "の取得完了", message);
            broadcastUpdate();
            stopForeground(STOP_FOREGROUND_DETACH);
            cleanupAndStop();
        } catch (Throwable error) {
            Log.e(TAG, "capture failed", error);
            finishWithError(safeMessage(error));
        }
    }

    private void finishWithError(String message) {
        saveStatus("失敗: " + message);
        running.set(false);
        updateNotification(currentGame == null ? "取得失敗" : currentGame.displayName + "の取得失敗", message);
        broadcastUpdate();
        stopForeground(STOP_FOREGROUND_DETACH);
        cleanupAndStop();
    }

    private synchronized void cleanupAndStop() {
        if (bound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, privilegedConnection, true);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        running.set(false);
        stopSelf();
    }

    private void saveStatus(String value) {
        if (currentGame == null) {
            return;
        }
        getSharedPreferences("capture_status", MODE_PRIVATE)
                .edit()
                .putString(currentGame.key, value)
                .apply();
    }

    private void broadcastUpdate() {
        sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ガチャ履歴取得",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("ゲームのガチャ履歴URL取得状況");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String title, String text, boolean ongoing) {
        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(ongoing)
                .setAutoCancel(!ongoing)
                .build();
    }

    private void updateNotification(String title, String text) {
        boolean ongoing = running.get();
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(title, text, ongoing));
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
