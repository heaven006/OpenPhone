package org.openphone.assistant.watchers;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WatcherStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "openphone_watchers.db";
    private static final int DB_VERSION = 2;

    public WatcherStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE watcher ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "type TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "condition_json TEXT NOT NULL,"
                + "schedule_json TEXT NOT NULL,"
                + "session_target TEXT,"
                + "delivery_json TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "next_run_at INTEGER,"
                + "running_at INTEGER,"
                + "last_run_at INTEGER,"
                + "last_result_hash TEXT,"
                + "failure_count INTEGER NOT NULL DEFAULT 0,"
                + "failure_alert_at INTEGER,"
                + "deleted_at INTEGER"
                + ")");
        db.execSQL("CREATE VIRTUAL TABLE watcher_fts USING fts4("
                + "type, title, condition_json, content='watcher')");
        db.execSQL("CREATE TRIGGER watcher_ai AFTER INSERT ON watcher "
                + "BEGIN INSERT INTO watcher_fts(rowid, type, title, condition_json) "
                + "VALUES (new.id, new.type, new.title, new.condition_json); END");
        db.execSQL("CREATE TRIGGER watcher_ad AFTER DELETE ON watcher "
                + "BEGIN DELETE FROM watcher_fts WHERE rowid = old.id; END");
        db.execSQL("CREATE TRIGGER watcher_au AFTER UPDATE ON watcher "
                + "BEGIN DELETE FROM watcher_fts WHERE rowid = old.id; "
                + "INSERT INTO watcher_fts(rowid, type, title, condition_json) "
                + "VALUES (new.id, new.type, new.title, new.condition_json); END");
        db.execSQL("CREATE INDEX watcher_status_idx ON watcher(status, next_run_at)");
        db.execSQL("CREATE INDEX watcher_type_idx ON watcher(type, status)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Durable user data: migrations must be additive and stepwise, never DROP TABLE.
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS watcher_type_idx ON watcher(type, status)");
        }
    }

    public long createWatcher(String type, String title, String conditionJson,
            String scheduleJson, String sessionTarget, String deliveryJson,
            long nextRunAtMillis) {
        String cleanType = safe(type, "time");
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty()) {
            return -1L;
        }
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("type", cleanType);
        values.put("title", cleanTitle);
        values.put("condition_json", safeJson(conditionJson));
        values.put("schedule_json", safeJson(scheduleJson));
        values.put("session_target", sessionTarget == null ? "" : sessionTarget.trim());
        values.put("delivery_json", safeJson(deliveryJson));
        values.put("status", "active");
        values.put("created_at", now);
        values.put("updated_at", now);
        values.put("next_run_at", nextRunAtMillis);
        values.put("running_at", 0L);
        values.put("last_run_at", 0L);
        values.put("last_result_hash", "");
        values.put("failure_count", 0);
        values.put("failure_alert_at", 0L);
        return getWritableDatabase().insert("watcher", null, values);
    }

    public boolean stop(long id) {
        if (id <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("status", "stopped");
        values.put("updated_at", now);
        values.put("deleted_at", now);
        return getWritableDatabase().update("watcher", values, "id = ?",
                new String[]{Long.toString(id)}) > 0;
    }

    public boolean markRunning(long id, long nowMillis) {
        ContentValues values = new ContentValues();
        values.put("running_at", nowMillis);
        values.put("updated_at", nowMillis);
        return getWritableDatabase().update("watcher", values,
                "id = ? AND status = 'active'",
                new String[]{Long.toString(id)}) > 0;
    }

    public boolean markFired(long id, String resultHash, long nowMillis) {
        ContentValues values = new ContentValues();
        values.put("status", "fired");
        values.put("running_at", 0L);
        values.put("last_run_at", nowMillis);
        values.put("updated_at", nowMillis);
        values.put("last_result_hash", resultHash == null ? "" : resultHash);
        return getWritableDatabase().update("watcher", values, "id = ?",
                new String[]{Long.toString(id)}) > 0;
    }

    public boolean markFailed(long id, String resultHash, long nextRunAtMillis,
            long failureCount, long failureAlertAtMillis, long nowMillis) {
        ContentValues values = new ContentValues();
        values.put("status", "active");
        values.put("running_at", 0L);
        values.put("last_run_at", nowMillis);
        values.put("updated_at", nowMillis);
        values.put("next_run_at", nextRunAtMillis);
        values.put("last_result_hash", resultHash == null ? "" : resultHash);
        values.put("failure_count", failureCount);
        values.put("failure_alert_at", failureAlertAtMillis);
        return getWritableDatabase().update("watcher", values,
                "id = ? AND deleted_at IS NULL", new String[]{Long.toString(id)}) > 0;
    }

    public int repairStuck(long staleBeforeMillis, long nowMillis) {
        int repaired = 0;
        for (WatcherRecord watcher : query("status = 'active' AND deleted_at IS NULL "
                + "AND running_at > 0 AND running_at <= ?",
                new String[]{Long.toString(staleBeforeMillis)}, "running_at ASC", 50)) {
            long nextRunAt = nowMillis + backoffMillis(watcher.failureCount + 1);
            ContentValues values = new ContentValues();
            values.put("running_at", 0L);
            values.put("updated_at", nowMillis);
            values.put("next_run_at", nextRunAt);
            values.put("last_run_at", nowMillis);
            values.put("failure_count", watcher.failureCount + 1);
            values.put("last_result_hash", "stuck_running_repaired");
            if (watcher.failureCount + 1 >= 3) {
                values.put("failure_alert_at", nowMillis);
            }
            repaired += getWritableDatabase().update("watcher", values, "id = ?",
                    new String[]{Long.toString(watcher.id)});
        }
        return repaired;
    }

    public boolean markNoop(long id, long nextRunAtMillis, long nowMillis) {
        return markNoop(id, nextRunAtMillis, null, nowMillis);
    }

    public boolean markNoop(long id, long nextRunAtMillis, String resultHash, long nowMillis) {
        ContentValues values = new ContentValues();
        values.put("running_at", 0L);
        values.put("last_run_at", nowMillis);
        values.put("next_run_at", nextRunAtMillis);
        values.put("updated_at", nowMillis);
        if (resultHash != null) {
            values.put("last_result_hash", resultHash);
        }
        return getWritableDatabase().update("watcher", values, "id = ?",
                new String[]{Long.toString(id)}) > 0;
    }

    public List<WatcherRecord> active(int limit) {
        return query("status = 'active' AND deleted_at IS NULL",
                null, "next_run_at ASC, updated_at DESC", limit);
    }

    public List<WatcherRecord> activeByType(String type, int limit) {
        String cleanType = safe(type, "");
        if (cleanType.isEmpty()) {
            return new ArrayList<>();
        }
        return query("status = 'active' AND deleted_at IS NULL AND type = ?",
                new String[]{cleanType}, "updated_at ASC", limit);
    }

    public List<WatcherRecord> due(long nowMillis, int limit) {
        return query("status = 'active' AND deleted_at IS NULL "
                + "AND next_run_at > 0 AND next_run_at <= ?",
                new String[]{Long.toString(nowMillis)},
                "next_run_at ASC", limit);
    }

    public long nextRunAt(long nowMillis) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT next_run_at FROM watcher "
                + "WHERE status = 'active' AND deleted_at IS NULL AND next_run_at > ? "
                + "ORDER BY next_run_at ASC LIMIT 1",
                new String[]{Long.toString(nowMillis)})) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return 0L;
    }

    public static long backoffMillis(int failureCount) {
        int bounded = Math.max(1, Math.min(failureCount, 6));
        return (1L << (bounded - 1)) * 60L * 1000L;
    }

    public String listJson(String query, int limit) {
        JSONArray array = new JSONArray();
        for (WatcherRecord watcher : search(query, limit)) {
            array.put(watcherJson(watcher));
        }
        try {
            return new JSONObject().put("watchers", array).toString();
        } catch (JSONException e) {
            return "{\"watchers\":[]}";
        }
    }

    private List<WatcherRecord> search(String query, int limit) {
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.isEmpty()) {
            return active(limit);
        }
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        List<WatcherRecord> watchers = new ArrayList<>();
        String ftsQuery = cleanQuery.replace('"', ' ').trim();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT w.id, w.type, w.title, "
                + "w.condition_json, w.schedule_json, w.session_target, w.delivery_json, "
                + "w.status, w.created_at, w.updated_at, w.next_run_at, w.running_at, "
                + "w.last_run_at, w.last_result_hash, w.failure_count, w.failure_alert_at "
                + "FROM watcher_fts f JOIN watcher w ON w.id = f.rowid "
                + "WHERE watcher_fts MATCH ? AND w.deleted_at IS NULL "
                + "ORDER BY w.updated_at DESC LIMIT ?",
                new String[]{ftsQuery, Integer.toString(boundedLimit)})) {
            readWatchers(cursor, watchers);
        } catch (RuntimeException e) {
            return active(boundedLimit);
        }
        return watchers;
    }

    private List<WatcherRecord> query(String where, String[] args, String order, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        List<WatcherRecord> watchers = new ArrayList<>();
        String sql = "SELECT id, type, title, condition_json, schedule_json, session_target, "
                + "delivery_json, status, created_at, updated_at, next_run_at, running_at, "
                + "last_run_at, last_result_hash, failure_count, failure_alert_at "
                + "FROM watcher WHERE " + where + " ORDER BY " + order + " LIMIT ?";
        String[] queryArgs = appendLimit(args, boundedLimit);
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, queryArgs)) {
            readWatchers(cursor, watchers);
        }
        return watchers;
    }

    private static String[] appendLimit(String[] args, int limit) {
        if (args == null || args.length == 0) {
            return new String[]{Integer.toString(limit)};
        }
        String[] result = new String[args.length + 1];
        System.arraycopy(args, 0, result, 0, args.length);
        result[args.length] = Integer.toString(limit);
        return result;
    }

    private static void readWatchers(Cursor cursor, List<WatcherRecord> watchers) {
        while (cursor.moveToNext()) {
            watchers.add(new WatcherRecord(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    cursor.getString(5),
                    cursor.getString(6),
                    cursor.getString(7),
                    cursor.getLong(8),
                    cursor.getLong(9),
                    cursor.getLong(10),
                    cursor.getLong(11),
                    cursor.getLong(12),
                    cursor.getString(13),
                    cursor.getInt(14),
                    cursor.getLong(15)));
        }
    }

    private static JSONObject watcherJson(WatcherRecord watcher) {
        try {
            return new JSONObject()
                    .put("id", watcher.id)
                    .put("type", watcher.type)
                    .put("title", watcher.title)
                    .put("condition", parseOrEmpty(watcher.conditionJson))
                    .put("schedule", parseOrEmpty(watcher.scheduleJson))
                    .put("session_target", watcher.sessionTarget)
                    .put("delivery", parseOrEmpty(watcher.deliveryJson))
                    .put("status", watcher.status)
                    .put("created_at", watcher.createdAtMillis)
                    .put("updated_at", watcher.updatedAtMillis)
                    .put("next_run_at", watcher.nextRunAtMillis)
                    .put("running_at", watcher.runningAtMillis)
                    .put("last_run_at", watcher.lastRunAtMillis)
                    .put("last_result_hash", watcher.lastResultHash)
                    .put("failure_count", watcher.failureCount)
                    .put("failure_alert_at", watcher.failureAlertAtMillis);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject parseOrEmpty(String json) {
        try {
            return new JSONObject(json == null || json.isEmpty() ? "{}" : json);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static String safeJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "{}";
        }
        try {
            return new JSONObject(json).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private static String safe(String value, String fallback) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return clean.isEmpty() ? fallback : clean;
    }
}
