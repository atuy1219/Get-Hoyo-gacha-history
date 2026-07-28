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
            new int[]{100, 200, 301, 302, 500}
    ),
    STAR_RAIL(
            "starrail",
            "崩壊：スターレイル",
            "com.HoYoverse.hkrpgoversea",
            "hkrpg_global",
            "https://public-operation-hkrpg-sg.hoyoverse.com/common/gacha_record/api/getGachaLog",
            "gacha_type",
            new int[]{1, 2, 11, 12, 21, 22}
    ),
    ZZZ(
            "zzz",
            "ゼンレスゾーンゼロ",
            "com.HoYoverse.Nap",
            "nap_global",
            "https://public-operation-nap-sg.hoyoverse.com/common/gacha_record/api/getGachaLog",
            "real_gacha_type",
            new int[]{1, 2, 3, 5, 102, 103}
    );

    public final String key;
    public final String displayName;
    public final String packageName;
    public final String marker;
    public final String apiPrefix;
    public final String typeParameter;
    public final int[] gachaTypes;

    GameConfig(
            String key,
            String displayName,
            String packageName,
            String marker,
            String apiPrefix,
            String typeParameter,
            int[] gachaTypes
    ) {
        this.key = key;
        this.displayName = displayName;
        this.packageName = packageName;
        this.marker = marker;
        this.apiPrefix = apiPrefix;
        this.typeParameter = typeParameter;
        this.gachaTypes = gachaTypes;
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
