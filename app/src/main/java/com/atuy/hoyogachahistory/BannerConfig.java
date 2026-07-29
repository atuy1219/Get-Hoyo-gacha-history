package com.atuy.hoyogachahistory;

import java.util.Locale;

public final class BannerConfig {
    public final String key;
    public final String displayName;
    public final int[] queryTypes;
    public final int fiveStarHardPity;
    public final int fourStarHardPity;
    public final double fiveStarBaseRate;
    public final int fiveStarSoftPityStart;

    public BannerConfig(
            String key,
            String displayName,
            int[] queryTypes,
            int fiveStarHardPity,
            double fiveStarBaseRate,
            int fiveStarSoftPityStart
    ) {
        this.key = key;
        this.displayName = displayName;
        this.queryTypes = queryTypes;
        this.fiveStarHardPity = fiveStarHardPity;
        this.fourStarHardPity = 10;
        this.fiveStarBaseRate = fiveStarBaseRate;
        this.fiveStarSoftPityStart = fiveStarSoftPityStart;
    }

    public boolean containsType(String type) {
        if (type == null) {
            return false;
        }
        for (int value : queryTypes) {
            if (Integer.toString(value).equals(type)) {
                return true;
            }
        }
        return false;
    }

    public double fiveStarRateAtPull(int pullNumber) {
        if (pullNumber >= fiveStarHardPity) {
            return 1.0;
        }
        if (pullNumber < fiveStarSoftPityStart) {
            return fiveStarBaseRate;
        }
        int softPullCount = fiveStarHardPity - fiveStarSoftPityStart + 1;
        int position = pullNumber - fiveStarSoftPityStart + 1;
        return Math.min(1.0, fiveStarBaseRate + (1.0 - fiveStarBaseRate) * position / softPullCount);
    }

    public double probabilityWithinNextPulls(int currentPity, int pulls) {
        double miss = 1.0;
        for (int i = 1; i <= pulls; i++) {
            miss *= 1.0 - fiveStarRateAtPull(currentPity + i);
        }
        return 1.0 - miss;
    }

    public String formatProbability(double probability) {
        return String.format(Locale.JAPAN, "%.2f%%", probability * 100.0);
    }
}
