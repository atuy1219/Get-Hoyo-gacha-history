package com.atuy.hoyogachahistory;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.Arrays;

public enum GameConfig {
    GENSHIN(
            "genshin",
            "原神",
            "com.miHoYo.GenshinImpact",
            "hk4e_global",
            "https://public-operation-hk4e-sg.hoyoverse.com/gacha_info/api/getGachaLog",
            "gacha_type",
            new int[]{100, 200, 301, 302, 500},
            new BannerConfig[]{
                    new BannerConfig("event_character", "イベント・キャラクター", new int[]{301}, 90, 0.006, 74),
                    new BannerConfig("event_weapon", "イベント・武器", new int[]{302}, 80, 0.007, 63),
                    new BannerConfig("chronicled", "集録祈願", new int[]{500}, 90, 0.006, 74),
                    new BannerConfig("standard", "恒常", new int[]{100, 200}, 90, 0.006, 74)
            }
    ),
    STAR_RAIL(
            "starrail",
            "崩壊：スターレイル",
            "com.HoYoverse.hkrpgoversea",
            "hkrpg_global",
            "https://public-operation-hkrpg-sg.hoyoverse.com/common/gacha_record/api/getGachaLog",
            "gacha_type",
            new int[]{1, 2, 11, 12, 21, 22},
            new BannerConfig[]{
                    new BannerConfig("event_character", "イベント・キャラクター", new int[]{11}, 90, 0.006, 74),
                    new BannerConfig("event_weapon", "イベント・光円錐", new int[]{12}, 80, 0.008, 63),
                    new BannerConfig("collab_character", "コラボ・キャラクター", new int[]{21}, 90, 0.006, 74),
                    new BannerConfig("collab_weapon", "コラボ・光円錐", new int[]{22}, 80, 0.008, 63),
                    new BannerConfig("standard", "恒常", new int[]{1, 2}, 90, 0.006, 74)
            }
    ),
    ZZZ(
            "zzz",
            "ゼンレスゾーンゼロ",
            "com.HoYoverse.Nap",
            "nap_global",
            "https://public-operation-nap-sg.hoyoverse.com/common/gacha_record/api/getGachaLog",
            "real_gacha_type",
            new int[]{1, 2, 3, 5, 102, 103},
            new BannerConfig[]{
                    new BannerConfig("event_character", "イベント・エージェント", new int[]{2, 102}, 90, 0.006, 74),
                    new BannerConfig("event_weapon", "イベント・音動機", new int[]{3, 103}, 80, 0.010, 65),
                    new BannerConfig("bangboo", "ボンプ", new int[]{5}, 80, 0.010, 65),
                    new BannerConfig("standard", "恒常", new int[]{1}, 90, 0.006, 74)
            }
    );

    public final String key;
    public final String displayName;
    public final String packageName;
    public final String marker;
    public final String apiPrefix;
    public final String typeParameter;
    public final int[] gachaTypes;
    public final BannerConfig[] banners;

    GameConfig(
            String key,
            String displayName,
            String packageName,
            String marker,
            String apiPrefix,
            String typeParameter,
            int[] gachaTypes,
            BannerConfig[] banners
    ) {
        this.key = key;
        this.displayName = displayName;
        this.packageName = packageName;
        this.marker = marker;
        this.apiPrefix = apiPrefix;
        this.typeParameter = typeParameter;
        this.gachaTypes = gachaTypes;
        this.banners = banners;
    }

    public static GameConfig fromKey(String key) {
        return Arrays.stream(values())
                .filter(game -> game.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown game: " + key));
    }

    public Intent launchIntent(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return packageManager.getLaunchIntentForPackage(packageName);
    }
}
