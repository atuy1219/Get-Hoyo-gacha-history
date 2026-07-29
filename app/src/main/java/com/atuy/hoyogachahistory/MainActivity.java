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
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int SHIZUKU_PERMISSION_REQUEST = 1219;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1220;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Map<GameConfig, TextView> statsViews = new EnumMap<>(GameConfig.class);
    private final Map<GameConfig, TextView> statusViews = new EnumMap<>(GameConfig.class);
    private TextView shizukuStatus;
    private GameConfig pendingGame;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshShizukuStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshShizukuStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        refreshShizukuStatus();
        if (requestCode == SHIZUKU_PERMISSION_REQUEST
                && grantResult == PackageManager.PERMISSION_GRANTED
                && pendingGame != null) {
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
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        dbExecutor.shutdownNow();
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
                "Shizukuで認証URLを取得し、ガチャ種別ごとの天井・排出履歴を端末内で計算します。",
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
                "確率は基礎排出率・ソフト天井・確定天井から算出した推定値です。ゲーム内の表示や仕様変更を優先してください。",
                12,
                false
        );
        note.setTextColor(Color.DKGRAY);
        root.addView(note);
        return scrollView;
    }

    private View buildGameCard(GameConfig game) {
        LinearLayout gameCard = card();
        TextView name = text(game.displayName, 20, true);
        gameCard.addView(name);

        TextView stats = text("履歴を読み込み中", 15, false);
        stats.setPadding(0, dp(8), 0, dp(2));
        statsViews.put(game, stats);
        gameCard.addView(stats);

        TextView status = text("未取得", 13, false);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, 0, 0, dp(8));
        statusViews.put(game, status);
        gameCard.addView(status);

        Button capture = button("履歴を自動取得");
        capture.setOnClickListener(v -> requestCapture(game));
        gameCard.addView(capture, matchWidth());

        Button dashboard = button("ガチャ別の天井・履歴を見る");
        dashboard.setOnClickListener(v -> showBannerDashboard(game));
        gameCard.addView(dashboard, matchWidth());
        return gameCard;
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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void refreshAllCards() {
        dbExecutor.execute(() -> {
            Map<GameConfig, HistoryDb.Stats> values = new EnumMap<>(GameConfig.class);
            try (HistoryDb db = new HistoryDb(this)) {
                for (GameConfig game : GameConfig.values()) {
                    values.put(game, db.stats(game));
                }
            } catch (Throwable error) {
                handler.post(() -> Toast.makeText(this, "履歴の読み込みに失敗しました", Toast.LENGTH_SHORT).show());
                return;
            }

            handler.post(() -> {
                for (GameConfig game : GameConfig.values()) {
                    HistoryDb.Stats stats = values.get(game);
                    TextView statsView = statsViews.get(game);
                    if (statsView != null && stats != null) {
                        String latest = stats.latest.isEmpty() ? "なし" : stats.latest;
                        statsView.setText(
                                "保存: " + stats.total + "件 / " + game.topRankLabel + ": " + stats.fiveStar + "件\n" +
                                        "最終履歴: " + latest
                        );
                    }
                    TextView statusView = statusViews.get(game);
                    if (statusView != null) {
                        String value = getSharedPreferences("capture_status", MODE_PRIVATE)
                                .getString(game.key, "未取得");
                        statusView.setText(value);
                    }
                }
            });
        });
    }

    private void showBannerDashboard(GameConfig game) {
        Toast.makeText(this, "天井情報を計算しています", Toast.LENGTH_SHORT).show();
        dbExecutor.execute(() -> {
            Map<BannerConfig, HistoryDb.BannerStats> values = new LinkedHashMap<>();
            try (HistoryDb db = new HistoryDb(this)) {
                for (BannerConfig banner : game.banners) {
                    values.put(banner, db.bannerStats(game, banner));
                }
            } catch (Throwable error) {
                handler.post(() -> Toast.makeText(this, "天井情報の計算に失敗しました", Toast.LENGTH_LONG).show());
                return;
            }
            handler.post(() -> showBannerDashboardDialog(game, values));
        });
    }

    private void showBannerDashboardDialog(
            GameConfig game,
            Map<BannerConfig, HistoryDb.BannerStats> values
    ) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(24));

        for (BannerConfig banner : game.banners) {
            HistoryDb.BannerStats stats = values.get(banner);
            if (stats == null) {
                continue;
            }
            content.addView(buildBannerPanel(game, banner, stats), cardMargins());
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(game.displayName + " 天井カウンター")
                .setView(scroll)
                .setPositiveButton("閉じる", null)
                .create();
        dialog.setOnShowListener(ignored -> resizeDialog(dialog));
        dialog.show();
    }

    private View buildBannerPanel(
            GameConfig game,
            BannerConfig banner,
            HistoryDb.BannerStats stats
    ) {
        LinearLayout panel = card();
        TextView title = text(banner.displayName, 18, true);
        panel.addView(title);

        int topRemaining = Math.max(0, banner.fiveStarHardPity - stats.currentTopPity);
        int midRemaining = Math.max(0, banner.fourStarHardPity - stats.currentMidPity);
        String summary =
                game.topRankLabel + " 天井: " + stats.currentTopPity + " / " + banner.fiveStarHardPity +
                        "（確定まであと" + topRemaining + "連）\n" +
                "最後の" + game.topRankLabel + ": " + formatLast(
                        stats.lastTopName,
                        stats.lastTopPullCount,
                        stats.lastTopTime
                ) + "\n\n" +
                game.midRankLabel + " 天井: " + stats.currentMidPity + " / " + banner.fourStarHardPity +
                        "（確定まであと" + midRemaining + "連）\n" +
                "最後の" + game.midRankLabel + ": " + formatLast(
                        stats.lastMidName,
                        stats.lastMidPullCount,
                        stats.lastMidTime
                ) + "\n\n" +
                "次の1連で" + game.topRankLabel + ": " +
                        banner.formatProbability(banner.probabilityWithinNextPulls(stats.currentTopPity, 1)) + "\n" +
                "次の10連で" + game.topRankLabel + ": " +
                        banner.formatProbability(banner.probabilityWithinNextPulls(stats.currentTopPity, 10)) + "（推定）";

        TextView body = text(summary, 14, false);
        body.setPadding(0, dp(8), 0, dp(8));
        panel.addView(body);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button both = smallButton(game.topRankLabel + "+" + game.midRankLabel);
        both.setOnClickListener(v -> showRareHistory(game, banner, HistoryDb.RarityFilter.BOTH));
        row.addView(both, weightedButton());
        Button top = smallButton(game.topRankLabel);
        top.setOnClickListener(v -> showRareHistory(game, banner, HistoryDb.RarityFilter.TOP));
        row.addView(top, weightedButton());
        Button mid = smallButton(game.midRankLabel);
        mid.setOnClickListener(v -> showRareHistory(game, banner, HistoryDb.RarityFilter.MID));
        row.addView(mid, weightedButton());
        panel.addView(row);
        return panel;
    }

    private void showRareHistory(
            GameConfig game,
            BannerConfig banner,
            HistoryDb.RarityFilter filter
    ) {
        dbExecutor.execute(() -> {
            JSONArray history;
            try (HistoryDb db = new HistoryDb(this)) {
                history = db.rareHistory(game, banner, filter, 200);
            } catch (Throwable error) {
                handler.post(() -> Toast.makeText(this, "履歴の計算に失敗しました", Toast.LENGTH_LONG).show());
                return;
            }
            handler.post(() -> showRareHistoryDialog(game, banner, filter, history));
        });
    }

    private void showRareHistoryDialog(
            GameConfig game,
            BannerConfig banner,
            HistoryDb.RarityFilter filter,
            JSONArray history
    ) {
        if (history.length() == 0) {
            Toast.makeText(this, "該当する履歴はありません", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder output = new StringBuilder();
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) {
                continue;
            }
            output.append(item.optString("rank_label"))
                    .append("  ")
                    .append(item.optString("name"))
                    .append("\n")
                    .append(item.optInt("pull_count"))
                    .append("連で排出  /  ")
                    .append(item.optString("time"))
                    .append("\n")
                    .append(item.optString("item_type"))
                    .append("\n\n");
        }

        TextView content = text(output.toString().trim(), 14, false);
        content.setTextIsSelectable(true);
        content.setPadding(dp(20), dp(8), dp(20), dp(20));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        String filterName = filter == HistoryDb.RarityFilter.TOP
                ? game.topRankLabel
                : filter == HistoryDb.RarityFilter.MID
                ? game.midRankLabel
                : game.topRankLabel + "・" + game.midRankLabel;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(banner.displayName + " — " + filterName)
                .setView(scroll)
                .setPositiveButton("閉じる", null)
                .create();
        dialog.setOnShowListener(ignored -> resizeDialog(dialog));
        dialog.show();
    }

    private String formatLast(String name, int pullCount, String time) {
        if (name == null || name.isEmpty()) {
            return "なし";
        }
        return name + "（" + pullCount + "連 / " + time + "）";
    }

    private void resizeDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(getResources().getDisplayMetrics().heightPixels * 0.88f)
            );
        }
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
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        result.setBackground(background);
        result.setElevation(dp(2));
        return result;
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

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private Button button(String label) {
        Button result = new Button(this);
        result.setText(label);
        result.setTextSize(14);
        result.setAllCaps(false);
        result.setGravity(Gravity.CENTER);
        return result;
    }

    private Button smallButton(String label) {
        Button result = button(label);
        result.setTextSize(12);
        result.setPadding(dp(2), 0, dp(2), 0);
        return result;
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
