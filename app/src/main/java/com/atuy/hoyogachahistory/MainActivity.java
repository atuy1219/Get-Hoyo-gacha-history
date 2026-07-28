package com.atuy.hoyogachahistory;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int SHIZUKU_PERMISSION_REQUEST = 1219;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1220;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<GameConfig, TextView> statsViews = new EnumMap<>(GameConfig.class);
    private final Map<GameConfig, TextView> statusViews = new EnumMap<>(GameConfig.class);
    private TextView shizukuStatus;
    private GameConfig pendingGame;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshShizukuStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshShizukuStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        refreshShizukuStatus();
        if (requestCode == SHIZUKU_PERMISSION_REQUEST && grantResult == PackageManager.PERMISSION_GRANTED && pendingGame != null) {
            GameConfig game = pendingGame;
            pendingGame = null;
            beginCapture(game);
        }
    };

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshAllCards();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        registerUpdateReceiver();
        refreshShizukuStatus();
        refreshAllCards();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAllCards();
        refreshShizukuStatus();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(updateReceiver);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(245, 246, 250));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("HoYoガチャ履歴", 28, true);
        root.addView(title);

        TextView subtitle = text(
                "Shizukuでゲームのlogcatから認証URLを検出し、公式APIの履歴を端末内に保存します。",
                14,
                false
        );
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        LinearLayout shizukuCard = card();
        shizukuStatus = text("Shizukuを確認中", 16, true);
        shizukuCard.addView(shizukuStatus);
        Button permissionButton = button("Shizuku権限を許可");
        permissionButton.setOnClickListener(v -> requestShizukuPermission());
        shizukuCard.addView(permissionButton, matchWidth());
        Button openShizuku = button("Shizukuを開く");
        openShizuku.setOnClickListener(v -> openShizuku());
        shizukuCard.addView(openShizuku, matchWidth());
        root.addView(shizukuCard, cardMargins());

        for (GameConfig game : GameConfig.values()) {
            root.addView(buildGameCard(game), cardMargins());
        }

        TextView note = text(
                "取得方法: ボタンを押す → ゲームが開く → ガチャ画面の「履歴」を開く。監視は2分で終了します。認証URLは保存しません。",
                13,
                false
        );
        note.setTextColor(Color.DKGRAY);
        note.setPadding(dp(2), dp(8), dp(2), 0);
        root.addView(note);
        return scrollView;
    }

    private View buildGameCard(GameConfig game) {
        LinearLayout card = card();
        TextView name = text(game.displayName, 20, true);
        card.addView(name);

        TextView stats = text("履歴なし", 15, false);
        stats.setPadding(0, dp(8), 0, dp(2));
        statsViews.put(game, stats);
        card.addView(stats);

        TextView status = text("未取得", 13, false);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, 0, 0, dp(8));
        statusViews.put(game, status);
        card.addView(status);

        Button capture = button("履歴を自動取得");
        capture.setOnClickListener(v -> requestCapture(game));
        card.addView(capture, matchWidth());

        Button show = button("保存済み履歴を見る");
        show.setOnClickListener(v -> showHistory(game));
        card.addView(show, matchWidth());
        return card;
    }

    private void requestCapture(GameConfig game) {
        pendingGame = game;
        if (!hasShizukuPermission()) {
            requestShizukuPermission();
            return;
        }
        beginCapture(game);
    }

    private void beginCapture(GameConfig game) {
        pendingGame = null;
        requestNotificationPermissionIfNeeded();

        Intent service = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_GAME, game.key);
        startForegroundService(service);

        TextView status = statusViews.get(game);
        if (status != null) {
            status.setText("監視を開始しています…");
        }

        handler.postDelayed(() -> {
            Intent launch = game.launchIntent(this);
            if (launch == null) {
                Toast.makeText(this, game.displayName + "がインストールされていません", Toast.LENGTH_LONG).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        }, 1500L);
    }

    private void refreshShizukuStatus() {
        if (shizukuStatus == null) {
            return;
        }
        try {
            int permission = Shizuku.checkSelfPermission();
            String identity = Shizuku.getUid() == 0 ? "root" : "ADB shell";
            shizukuStatus.setText(permission == PackageManager.PERMISSION_GRANTED
                    ? "Shizuku: 接続済み（" + identity + "）"
                    : "Shizuku: 接続済み・権限未許可");
        } catch (Throwable error) {
            shizukuStatus.setText("Shizuku: 未起動または未接続");
        }
    }

    private boolean hasShizukuPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            Toast.makeText(this, "Shizukuを起動してください", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void requestShizukuPermission() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshShizukuStatus();
                if (pendingGame != null) {
                    GameConfig game = pendingGame;
                    pendingGame = null;
                    beginCapture(game);
                }
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, "Shizuku側で本アプリの権限を許可してください", Toast.LENGTH_LONG).show();
                openShizuku();
                return;
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
        } catch (Throwable error) {
            Toast.makeText(this, "Shizukuを起動してください", Toast.LENGTH_LONG).show();
            openShizuku();
        }
    }

    private void openShizuku() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (intent != null) {
            startActivity(intent);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:moe.shizuku.privileged.api")));
        } catch (Exception ignored) {
            Toast.makeText(this, "Shizukuがインストールされていません", Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void refreshAllCards() {
        HistoryDb db = new HistoryDb(this);
        for (GameConfig game : GameConfig.values()) {
            HistoryDb.Stats stats = db.stats(game.key);
            TextView statsView = statsViews.get(game);
            if (statsView != null) {
                String latest = stats.latest.isEmpty() ? "なし" : stats.latest;
                statsView.setText("保存: " + stats.total + "件 / ★5: " + stats.fiveStar + "件\n最終履歴: " + latest);
            }
            TextView statusView = statusViews.get(game);
            if (statusView != null) {
                String value = getSharedPreferences("capture_status", MODE_PRIVATE)
                        .getString(game.key, "未取得");
                statusView.setText(value);
            }
        }
        db.close();
    }

    private void showHistory(GameConfig game) {
        HistoryDb db = new HistoryDb(this);
        JSONArray history = db.latest(game.key, 100);
        db.close();

        if (history.length() == 0) {
            Toast.makeText(this, "保存済み履歴はありません", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) {
                continue;
            }
            text.append(item.optString("time"))
                    .append("  ★").append(item.optString("rank_type"))
                    .append("  ").append(item.optString("name"))
                    .append("\n")
                    .append(item.optString("item_type"))
                    .append(" / 種別 ").append(item.optString("gacha_type"))
                    .append("\n\n");
        }

        TextView content = text(text.toString().trim(), 14, false);
        content.setTextIsSelectable(true);
        content.setPadding(dp(20), dp(8), dp(20), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setTitle(game.displayName + "（新しい順・最大100件）")
                .setView(scroll)
                .setPositiveButton("閉じる", null)
                .show();
    }

    private void registerUpdateReceiver() {
        IntentFilter filter = new IntentFilter(CaptureService.ACTION_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        card.setBackground(background);
        card.setElevation(dp(2));
        return card;
    }

    private LinearLayout.LayoutParams cardMargins() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(14));
        return params;
    }

    private LinearLayout.LayoutParams matchWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(25, 27, 35));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
