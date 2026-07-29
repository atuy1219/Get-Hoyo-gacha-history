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

    private void upsert(SQLiteDatabase db, String game, JSONObject item) {
        ContentValues values = new ContentValues();
        values.put("game", game);
        values.put("id", item.optString("id"));
        values.put("uid", item.optString("uid"));
        values.put("gacha_type", item.optString("gacha_type", item.optString("real_gacha_type")));
        values.put("item_id", item.optString("item_id"));
        values.put("time", item.optString("time"));
        values.put("item_type", item.optString("item_type"));
        values.put("rank_type", item.optString("rank_type"));
        values.put("name", item.optString("name"));
        db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int upsertPage(String game, JSONArray items) throws Exception {
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
                        upsert(db, game, item);
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

    public Stats stats(String game) {
        return readWithRetry(db -> {
            int total = 0;
            int fiveStar = 0;
            String latest = "";

            try (Cursor cursor = db.rawQuery(
                    "SELECT COUNT(*), SUM(CASE WHEN rank_type='5' THEN 1 ELSE 0 END), MAX(time) FROM history WHERE game=?",
                    new String[]{game}
            )) {
                if (cursor.moveToFirst()) {
                    total = cursor.getInt(0);
                    fiveStar = cursor.isNull(1) ? 0 : cursor.getInt(1);
                    latest = cursor.isNull(2) ? "" : cursor.getString(2);
                }
            }
            return new Stats(total, fiveStar, latest);
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
                        item.put("id", cursor.getString(0));
                        item.put("uid", cursor.getString(1));
                        item.put("gacha_type", cursor.getString(2));
                        item.put("item_id", cursor.getString(3));
                        item.put("time", cursor.getString(4));
                        item.put("item_type", cursor.getString(5));
                        item.put("rank_type", cursor.getString(6));
                        item.put("name", cursor.getString(7));
                        result.put(item);
                    } catch (Exception ignored) {
                    }
                }
            }
            return result;
        });
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
}
