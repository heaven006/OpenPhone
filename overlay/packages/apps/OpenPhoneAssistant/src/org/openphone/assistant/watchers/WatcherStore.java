package org.openphone.assistant.watchers;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.openphone.OpenPhoneAssistantDataManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Client of the OS-owned OpenPhone assistant data service (system_server
 * openphone_assistant_data). The assistant no longer owns the durable watcher
 * store; a one-time migration moves rows from the legacy app-local SQLite
 * database into the OS store, preserving ids (alarm PendingIntents reference
 * them).
 */
public final class WatcherStore {
    private static final String TAG = "OpenPhoneWatcherStore";
    private static final String LEGACY_DB_NAME = "openphone_watchers.db";
    private static final String PREFS_NAME = "openphone_assistant_data";
    private static final String KEY_MIGRATED = "watcher_os_store_migrated_v1";
    private static final int MIGRATION_CHUNK = 100;

    private static final Object sMigrationLock = new Object();

    private final Context mContext;
    private final OpenPhoneAssistantDataManager mManager;

    public WatcherStore(Context context) {
        mContext = context.getApplicationContext();
        OpenPhoneAssistantDataManager manager = null;
        try {
            manager = mContext.getSystemService(OpenPhoneAssistantDataManager.class);
        } catch (RuntimeException e) {
            Log.w(TAG, "OpenPhone assistant data service unavailable", e);
        }
        mManager = manager;
        try {
            // Before user unlock CE storage is unavailable; retry on next use.
            migrateLegacyStoreIfNeeded();
        } catch (RuntimeException e) {
            Log.w(TAG, "watcher migration deferred", e);
        }
    }

    public long createWatcher(String type, String title, String conditionJson,
            String scheduleJson, String sessionTarget, String deliveryJson,
            long nextRunAtMillis) {
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty() || mManager == null) {
            return -1L;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("type", type == null ? "" : type)
                    .put("title", cleanTitle)
                    .put("condition_json", conditionJson == null ? "" : conditionJson)
                    .put("schedule_json", scheduleJson == null ? "" : scheduleJson)
                    .put("session_target", sessionTarget == null ? "" : sessionTarget.trim())
                    .put("delivery_json", deliveryJson == null ? "" : deliveryJson)
                    .put("next_run_at", nextRunAtMillis);
        } catch (JSONException e) {
            return -1L;
        }
        try {
            return parseOrEmpty(mManager.watcherCreate(request.toString())).optLong("id", -1L);
        } catch (RuntimeException e) {
            Log.w(TAG, "watcher create failed", e);
            return -1L;
        }
    }

    public boolean stop(long id) {
        if (id <= 0) {
            return false;
        }
        return update(updateRequest(id, "stop", System.currentTimeMillis()));
    }

    public boolean markRunning(long id, long nowMillis) {
        return update(updateRequest(id, "mark_running", nowMillis));
    }

    public boolean markFired(long id, String resultHash, long nowMillis) {
        JSONObject request = updateRequest(id, "mark_fired", nowMillis);
        try {
            request.put("result_hash", resultHash == null ? "" : resultHash);
        } catch (JSONException e) {
            return false;
        }
        return update(request);
    }

    public boolean markFailed(long id, String resultHash, long nextRunAtMillis,
            long failureCount, long failureAlertAtMillis, long nowMillis) {
        JSONObject request = updateRequest(id, "mark_failed", nowMillis);
        try {
            request.put("result_hash", resultHash == null ? "" : resultHash)
                    .put("next_run_at", nextRunAtMillis)
                    .put("failure_count", failureCount)
                    .put("failure_alert_at", failureAlertAtMillis);
        } catch (JSONException e) {
            return false;
        }
        return update(request);
    }

    public int repairStuck(long staleBeforeMillis, long nowMillis) {
        if (mManager == null) {
            return 0;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("action", "repair_stuck")
                    .put("stale_before", staleBeforeMillis)
                    .put("now", nowMillis);
        } catch (JSONException e) {
            return 0;
        }
        try {
            return parseOrEmpty(mManager.watcherUpdate(request.toString()))
                    .optInt("repaired", 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "watcher repairStuck failed", e);
            return 0;
        }
    }

    public boolean markNoop(long id, long nextRunAtMillis, long nowMillis) {
        return markNoop(id, nextRunAtMillis, null, nowMillis);
    }

    public boolean markNoop(long id, long nextRunAtMillis, String resultHash, long nowMillis) {
        JSONObject request = updateRequest(id, "mark_noop", nowMillis);
        try {
            request.put("next_run_at", nextRunAtMillis);
            if (resultHash != null) {
                request.put("result_hash", resultHash);
            }
        } catch (JSONException e) {
            return false;
        }
        return update(request);
    }

    public List<WatcherRecord> active(int limit) {
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "active").put("limit", Math.max(1, Math.min(limit, 50)));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return queryWatchers(request);
    }

    public List<WatcherRecord> activeByType(String type, int limit) {
        String cleanType = type == null ? "" : type.trim();
        if (cleanType.isEmpty()) {
            return new ArrayList<>();
        }
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "active_by_type").put("type", cleanType)
                    .put("limit", Math.max(1, Math.min(limit, 50)));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return queryWatchers(request);
    }

    public List<WatcherRecord> due(long nowMillis, int limit) {
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "due").put("now", nowMillis)
                    .put("limit", Math.max(1, Math.min(limit, 50)));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return queryWatchers(request);
    }

    public long nextRunAt(long nowMillis) {
        if (mManager == null) {
            return 0L;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "next_run_at").put("now", nowMillis);
        } catch (JSONException e) {
            return 0L;
        }
        try {
            return parseOrEmpty(mManager.watcherQuery(request.toString()))
                    .optLong("next_run_at", 0L);
        } catch (RuntimeException e) {
            Log.w(TAG, "watcher nextRunAt failed", e);
            return 0L;
        }
    }

    public static long backoffMillis(int failureCount) {
        int bounded = Math.max(1, Math.min(failureCount, 6));
        return (1L << (bounded - 1)) * 60L * 1000L;
    }

    public String listJson(String query, int limit) {
        String cleanQuery = query == null ? "" : query.trim();
        JSONObject request = new JSONObject();
        try {
            request.put("mode", cleanQuery.isEmpty() ? "active" : "search")
                    .put("query", cleanQuery)
                    .put("limit", Math.max(1, Math.min(limit, 50)));
        } catch (JSONException e) {
            return "{\"watchers\":[]}";
        }
        JSONArray array = new JSONArray();
        for (WatcherRecord watcher : queryWatchers(request)) {
            array.put(watcherJson(watcher));
        }
        try {
            return new JSONObject().put("watchers", array).toString();
        } catch (JSONException e) {
            return "{\"watchers\":[]}";
        }
    }

    private static JSONObject updateRequest(long id, String action, long nowMillis) {
        JSONObject request = new JSONObject();
        try {
            request.put("id", id).put("action", action).put("now", nowMillis);
        } catch (JSONException ignored) {
        }
        return request;
    }

    private boolean update(JSONObject request) {
        if (mManager == null) {
            return false;
        }
        try {
            return parseOrEmpty(mManager.watcherUpdate(request.toString()))
                    .optBoolean("updated", false);
        } catch (RuntimeException e) {
            Log.w(TAG, "watcher update failed", e);
            return false;
        }
    }

    private List<WatcherRecord> queryWatchers(JSONObject request) {
        List<WatcherRecord> watchers = new ArrayList<>();
        if (mManager == null) {
            return watchers;
        }
        JSONObject response;
        try {
            response = parseOrEmpty(mManager.watcherQuery(request.toString()));
        } catch (RuntimeException e) {
            Log.w(TAG, "watcher query failed", e);
            return watchers;
        }
        JSONArray array = response.optJSONArray("watchers");
        if (array == null) {
            return watchers;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject watcher = array.optJSONObject(i);
            if (watcher == null) {
                continue;
            }
            watchers.add(new WatcherRecord(
                    watcher.optLong("id"),
                    watcher.optString("type", ""),
                    watcher.optString("title", ""),
                    watcher.optString("condition_json", "{}"),
                    watcher.optString("schedule_json", "{}"),
                    watcher.optString("session_target", ""),
                    watcher.optString("delivery_json", "{}"),
                    watcher.optString("status", ""),
                    watcher.optLong("created_at"),
                    watcher.optLong("updated_at"),
                    watcher.optLong("next_run_at"),
                    watcher.optLong("running_at"),
                    watcher.optLong("last_run_at"),
                    watcher.optString("last_result_hash", ""),
                    watcher.optInt("failure_count"),
                    watcher.optLong("failure_alert_at")));
        }
        return watchers;
    }

    /**
     * One-time move of the legacy assistant-local watcher database into the
     * OS-owned store. Rows keep their ids; the prefs flag is only set after a
     * fully successful copy, so a crash mid-migration retries on next start.
     */
    private void migrateLegacyStoreIfNeeded() {
        if (mManager == null) {
            return;
        }
        synchronized (sMigrationLock) {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME,
                    Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_MIGRATED, false)) {
                return;
            }
            File legacyDb = mContext.getDatabasePath(LEGACY_DB_NAME);
            if (!legacyDb.exists()) {
                prefs.edit().putBoolean(KEY_MIGRATED, true).apply();
                return;
            }
            int migrated = 0;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(legacyDb.getAbsolutePath(),
                    null, SQLiteDatabase.OPEN_READONLY)) {
                long lastId = 0;
                while (true) {
                    JSONArray chunk = new JSONArray();
                    try (Cursor cursor = db.rawQuery("SELECT id, type, title, "
                            + "condition_json, schedule_json, session_target, "
                            + "delivery_json, status, created_at, updated_at, next_run_at, "
                            + "running_at, last_run_at, last_result_hash, failure_count, "
                            + "failure_alert_at "
                            + "FROM watcher WHERE deleted_at IS NULL AND id > ? "
                            + "ORDER BY id ASC LIMIT ?",
                            new String[]{Long.toString(lastId),
                                    Integer.toString(MIGRATION_CHUNK)})) {
                        while (cursor.moveToNext()) {
                            lastId = cursor.getLong(0);
                            JSONObject row = new JSONObject();
                            try {
                                row.put("id", cursor.getLong(0))
                                        .put("type", cursor.getString(1))
                                        .put("title", cursor.getString(2))
                                        .put("condition_json", cursor.getString(3))
                                        .put("schedule_json", cursor.getString(4))
                                        .put("session_target", cursor.getString(5))
                                        .put("delivery_json", cursor.getString(6))
                                        .put("status", cursor.getString(7))
                                        .put("created_at", cursor.getLong(8))
                                        .put("updated_at", cursor.getLong(9))
                                        .put("next_run_at", cursor.getLong(10))
                                        .put("running_at", cursor.getLong(11))
                                        .put("last_run_at", cursor.getLong(12))
                                        .put("last_result_hash", cursor.getString(13))
                                        .put("failure_count", cursor.getLong(14))
                                        .put("failure_alert_at", cursor.getLong(15));
                            } catch (JSONException e) {
                                continue;
                            }
                            chunk.put(row);
                        }
                    }
                    if (chunk.length() == 0) {
                        break;
                    }
                    JSONObject result = parseOrEmpty(
                            mManager.migrateRows("watcher", chunk.toString()));
                    if (result.has("error")) {
                        Log.w(TAG, "Legacy watcher migration failed: "
                                + result.optString("error"));
                        return;
                    }
                    migrated += result.optInt("inserted", 0);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Legacy watcher migration failed", e);
                return;
            }
            prefs.edit()
                    .putBoolean(KEY_MIGRATED, true)
                    .putInt(KEY_MIGRATED + "_count", migrated)
                    .apply();
            Log.i(TAG, "Migrated " + migrated + " legacy watchers into the OS store");
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
}
