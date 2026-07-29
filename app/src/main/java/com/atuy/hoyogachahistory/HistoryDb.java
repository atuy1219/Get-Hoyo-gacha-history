package com.atuy.hoyogachahistory;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class HistoryDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "gacha_history.db";
    private static final int DB_VERSION = 1;
    private static final int LOCK_RETRY_COUNT = 5;

    public HistoryDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history (" +
                "game TEXT NOT NULL," +
                "id TEXT NOT NULL," +
                "uid TEXT," +
                "gacha_type TEXT," +
                "item_id TEXT," +
                "time TEXT," +
                "item_type TEXT," +
                "rank_type TEXT," +
                "name TEXT," +
                "PRIMARY KEY(game, id))");
        db.execSQL("CREATE INDEX history_game_time ON history(game, time DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS history");
        onCreate(db);
    }

    private void upsert(SQLiteDatabase db, String game, String queryType, JSONObject item) {
        ContentValues values = new ContentValues();
        values.put("game", game);
        values.put("id", item.optString("id"));
        values.put("uid", item.optString("uid"));
        values.put("gacha_type", queryType);
        values.put("item_id", item.optString("item_id"));
        values.put("time", item.optString("time"));
        values.put("item_type", item.optString("item_type"));
        values.put("rank_type", item.optString("rank_type"));
        values.put("name", item.optString("name"));
        db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int upsertPage(String game, String queryType, JSONArray items) throws Exception {
        SQLiteDatabaseLockedException lastLock = null;
        for (int attempt = 0; attempt < LOCK_RETRY_COUNT; attempt++) {
            SQLiteDatabase db = null;
            try {
                db = getWritableDatabase();
                db.beginTransactionNonExclusive();
                int inserted = 0;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (!item.optString("id").isEmpty()) {
                        upsert(db, game, queryType, item);
                        inserted++;
                    }
                }
                db.setTransactionSuccessful();
                return inserted;
            } catch (SQLiteDatabaseLockedException error) {
                lastLock = error;
            } finally {
                if (db != null && db.inTransaction()) {
                    db.endTransaction();
                }
            }
            SystemClock.sleep(100L * (attempt + 1));
        }
        throw lastLock == null
                ? new SQLiteDatabaseLockedException("database remained locked")
                : lastLock;
    }

    public String latestId(String game, String gachaType) {
        return readWithRetry(db -> {
            try (Cursor cursor = db.rawQuery(
                    "SELECT id FROM history WHERE game=? AND gacha_type=? " +
                            "ORDER BY time DESC, id DESC LIMIT 1",
                    new String[]{game, gachaType}
            )) {
                return cursor.moveToFirst() ? cursor.getString(0) : null;
            }
        });
    }

    public Stats stats(GameConfig game) {
        return readWithRetry(db -> {
            int total = 0;
            int topRank = 0;
            String latest = "";
            try (Cursor cursor = db.rawQuery(
                    "SELECT COUNT(*), SUM(CASE WHEN rank_type=? THEN 1 ELSE 0 END), MAX(time) " +
                            "FROM history WHERE game=?",
                    new String[]{game.topRankValue, game.key}
            )) {
                if (cursor.moveToFirst()) {
                    total = cursor.getInt(0);
                    topRank = cursor.isNull(1) ? 0 : cursor.getInt(1);
                    latest = cursor.isNull(2) ? "" : cursor.getString(2);
                }
            }
            return new Stats(total, topRank, latest);
        });
    }

    public Stats stats(String gameKey) {
        return stats(GameConfig.fromKey(gameKey));
    }

    public BannerStats bannerStats(GameConfig game, BannerConfig banner) {
        return readWithRetry(db -> {
            int total = 0;
            int currentTopPity = 0;
            int currentMidPity = 0;
            String lastTopName = "";
            String lastTopTime = "";
            int lastTopPullCount = 0;
            String lastMidName = "";
            String lastMidTime = "";
            int lastMidPullCount = 0;

            QueryParts query = queryParts(game.key, banner.queryTypes);
            try (Cursor cursor = db.rawQuery(
                    "SELECT time, rank_type, name FROM history WHERE " + query.selection +
                            " ORDER BY time ASC, id ASC",
                    query.args
            )) {
                while (cursor.moveToNext()) {
                    total++;
                    currentTopPity++;
                    currentMidPity++;
                    String rank = cursor.getString(1);
                    if (game.isTopRank(rank)) {
                        lastTopName = value(cursor, 2);
                        lastTopTime = value(cursor, 0);
                        lastTopPullCount = currentTopPity;
                        currentTopPity = 0;
                    }
                    if (game.isMidRank(rank)) {
                        lastMidName = value(cursor, 2);
                        lastMidTime = value(cursor, 0);
                        lastMidPullCount = currentMidPity;
                        currentMidPity = 0;
                    }
                }
            }
            return new BannerStats(
                    total,
                    currentTopPity,
                    currentMidPity,
                    lastTopName,
                    lastTopTime,
                    lastTopPullCount,
                    lastMidName,
                    lastMidTime,
                    lastMidPullCount
            );
        });
    }

    public JSONArray rareHistory(GameConfig game, BannerConfig banner, RarityFilter filter, int limit) {
        return readWithRetry(db -> {
            List<JSONObject> rareItems = new ArrayList<>();
            int topPity = 0;
            int midPity = 0;
            QueryParts query = queryParts(game.key, banner.queryTypes);
            try (Cursor cursor = db.rawQuery(
                    "SELECT id, uid, gacha_type, item_id, time, item_type, rank_type, name " +
                            "FROM history WHERE " + query.selection + " ORDER BY time ASC, id ASC",
                    query.args
            )) {
                while (cursor.moveToNext()) {
                    topPity++;
                    midPity++;
                    String rank = value(cursor, 6);
                    boolean top = game.isTopRank(rank);
                    boolean mid = game.isMidRank(rank);
                    if (!top && !mid) {
                        continue;
                    }

                    int pullCount;
                    String displayRank;
                    if (top) {
                        pullCount = topPity;
                        topPity = 0;
                        displayRank = game.topRankLabel;
                    } else {
                        pullCount = midPity;
                        midPity = 0;
                        displayRank = game.midRankLabel;
                    }

                    if ((filter == RarityFilter.TOP && !top) || (filter == RarityFilter.MID && !mid)) {
                        continue;
                    }

                    JSONObject item = new JSONObject();
                    try {
                        item.put("id", value(cursor, 0));
                        item.put("uid", value(cursor, 1));
                        item.put("gacha_type", value(cursor, 2));
                        item.put("item_id", value(cursor, 3));
                        item.put("time", value(cursor, 4));
                        item.put("item_type", value(cursor, 5));
                        item.put("rank_type", rank);
                        item.put("rank_label", displayRank);
                        item.put("name", value(cursor, 7));
                        item.put("pull_count", pullCount);
                        rareItems.add(item);
                    } catch (Exception ignored) {
                    }
                }
            }

            JSONArray result = new JSONArray();
            int start = Math.max(0, rareItems.size() - limit);
            for (int i = rareItems.size() - 1; i >= start; i--) {
                result.put(rareItems.get(i));
            }
            return result;
        });
    }

    public JSONArray latest(String game, int limit) {
        return readWithRetry(db -> {
            JSONArray result = new JSONArray();
            try (Cursor cursor = db.query(
                    "history",
                    new String[]{"id", "uid", "gacha_type", "item_id", "time", "item_type", "rank_type", "name"},
                    "game=?",
                    new String[]{game},
                    null,
                    null,
                    "time DESC, id DESC",
                    Integer.toString(limit)
            )) {
                while (cursor.moveToNext()) {
                    JSONObject item = new JSONObject();
                    try {
                        item.put("id", value(cursor, 0));
                        item.put("uid", value(cursor, 1));
                        item.put("gacha_type", value(cursor, 2));
                        item.put("item_id", value(cursor, 3));
                        item.put("time", value(cursor, 4));
                        item.put("item_type", value(cursor, 5));
                        item.put("rank_type", value(cursor, 6));
                        item.put("name", value(cursor, 7));
                        result.put(item);
                    } catch (Exception ignored) {
                    }
                }
            }
            return result;
        });
    }

    private QueryParts queryParts(String game, int[] types) {
        StringBuilder selection = new StringBuilder("game=? AND gacha_type IN (");
        String[] args = new String[types.length + 1];
        args[0] = game;
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                selection.append(',');
            }
            selection.append('?');
            args[i + 1] = Integer.toString(types[i]);
        }
        selection.append(')');
        return new QueryParts(selection.toString(), args);
    }

    private static String value(Cursor cursor, int index) {
        return cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private <T> T readWithRetry(ReadOperation<T> operation) {
        SQLiteDatabaseLockedException lastLock = null;
        for (int attempt = 0; attempt < LOCK_RETRY_COUNT; attempt++) {
            try {
                return operation.run(getReadableDatabase());
            } catch (SQLiteDatabaseLockedException error) {
                lastLock = error;
                SystemClock.sleep(100L * (attempt + 1));
            }
        }
        throw lastLock == null
                ? new SQLiteDatabaseLockedException("database remained locked")
                : lastLock;
    }

    private interface ReadOperation<T> {
        T run(SQLiteDatabase db);
    }

    private static final class QueryParts {
        final String selection;
        final String[] args;

        QueryParts(String selection, String[] args) {
            this.selection = selection;
            this.args = args;
        }
    }

    public enum RarityFilter {
        BOTH,
        TOP,
        MID
    }

    public static final class Stats {
        public final int total;
        public final int fiveStar;
        public final String latest;

        public Stats(int total, int fiveStar, String latest) {
            this.total = total;
            this.fiveStar = fiveStar;
            this.latest = latest;
        }
    }

    public static final class BannerStats {
        public final int total;
        public final int currentTopPity;
        public final int currentMidPity;
        public final String lastTopName;
        public final String lastTopTime;
        public final int lastTopPullCount;
        public final String lastMidName;
        public final String lastMidTime;
        public final int lastMidPullCount;

        public BannerStats(
                int total,
                int currentTopPity,
                int currentMidPity,
                String lastTopName,
                String lastTopTime,
                int lastTopPullCount,
                String lastMidName,
                String lastMidTime,
                int lastMidPullCount
        ) {
            this.total = total;
            this.currentTopPity = currentTopPity;
            this.currentMidPity = currentMidPity;
            this.lastTopName = lastTopName;
            this.lastTopTime = lastTopTime;
            this.lastTopPullCount = lastTopPullCount;
            this.lastMidName = lastMidName;
            this.lastMidTime = lastMidTime;
            this.lastMidPullCount = lastMidPullCount;
        }
    }
}
