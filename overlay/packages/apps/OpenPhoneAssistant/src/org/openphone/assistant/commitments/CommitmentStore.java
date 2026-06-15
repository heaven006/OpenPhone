package org.openphone.assistant.commitments;

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
 * openphone_assistant_data). The assistant no longer owns the durable
 * commitment store; a one-time migration moves rows from the legacy app-local
 * SQLite database into the OS store, preserving ids (alarms and notifications
 * reference them).
 */
public final class CommitmentStore {
    private static final String TAG = "OpenPhoneCommitments";
    private static final String LEGACY_DB_NAME = "openphone_commitments.db";
    private static final String PREFS_NAME = "openphone_assistant_data";
    private static final String KEY_MIGRATED = "commitment_os_store_migrated_v1";
    private static final int MIGRATION_CHUNK = 100;

    private static final Object sMigrationLock = new Object();

    private final Context mContext;
    private final OpenPhoneAssistantDataManager mManager;

    public CommitmentStore(Context context) {
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
            Log.w(TAG, "commitment migration deferred", e);
        }
    }

    public long createExplicitCommitment(String title, long dueAtMillis, String evidenceJson) {
        JSONObject trigger = new JSONObject();
        try {
            trigger.put("source", "explicit_user_request");
            if (dueAtMillis > 0) {
                trigger.put("due_at", dueAtMillis);
            }
        } catch (JSONException ignored) {
        }
        return createCommitment(title, title, dueAtMillis > 0 ? "time" : "manual",
                trigger.toString(), dueAtMillis, 0L, 1.0f, evidenceJson);
    }

    public long createCommitment(String title, String description, String triggerType,
            String triggerSpecJson, long dueAtMillis, long expiresAtMillis, float confidence,
            String evidenceJson) {
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty() || mManager == null) {
            return -1L;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("title", cleanTitle)
                    .put("description", description == null ? "" : description)
                    .put("trigger_type", triggerType == null ? "" : triggerType)
                    .put("trigger_spec_json", triggerSpecJson == null ? "" : triggerSpecJson)
                    .put("due_at", dueAtMillis)
                    .put("expires_at", expiresAtMillis)
                    .put("confidence", Math.max(0f, Math.min(confidence, 1f)))
                    .put("evidence_json", evidenceJson == null ? "" : evidenceJson);
        } catch (JSONException e) {
            return -1L;
        }
        try {
            return parseOrEmpty(mManager.commitmentCreate(request.toString()))
                    .optLong("id", -1L);
        } catch (RuntimeException e) {
            Log.w(TAG, "commitment create failed", e);
            return -1L;
        }
    }

    public boolean updateStatus(long id, String status) {
        if (id <= 0 || status == null || status.trim().isEmpty()) {
            return false;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("id", id).put("action", "status").put("status", status.trim());
        } catch (JSONException e) {
            return false;
        }
        return update(request);
    }

    public boolean snooze(long id, long dueAtMillis) {
        if (id <= 0 || dueAtMillis <= 0) {
            return false;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("id", id).put("action", "snooze").put("due_at", dueAtMillis);
        } catch (JSONException e) {
            return false;
        }
        return update(request);
    }

    public boolean dismiss(long id) {
        if (id <= 0) {
            return false;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("id", id).put("action", "dismiss");
        } catch (JSONException e) {
            return false;
        }
        return update(request);
    }

    public List<CommitmentRecord> due(long nowMillis, int limit) {
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "due").put("now", nowMillis)
                    .put("limit", Math.max(1, Math.min(limit, 50)));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return queryCommitments(request);
    }

    public long nextDueAt(long nowMillis) {
        if (mManager == null) {
            return 0L;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "next_due_at").put("now", nowMillis);
        } catch (JSONException e) {
            return 0L;
        }
        try {
            return parseOrEmpty(mManager.commitmentQuery(request.toString()))
                    .optLong("next_due_at", 0L);
        } catch (RuntimeException e) {
            Log.w(TAG, "commitment nextDueAt failed", e);
            return 0L;
        }
    }

    public List<CommitmentRecord> active(int limit) {
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "active").put("limit", Math.max(1, Math.min(limit, 50)));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return queryCommitments(request);
    }

    public List<CommitmentRecord> search(String query, int limit) {
        String cleanQuery = query == null ? "" : query.trim();
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        if (cleanQuery.isEmpty()) {
            // "What are my reminders?" surface: return everything still on
            // the books (pending/active/snoozed) AND anything that fired
            // recently, ordered by most-recent-update first. Without the
            // recent-fired branch, a commitment that just rang would
            // disappear from this query immediately, which surprised users.
            List<CommitmentRecord> activeList = active(boundedLimit);
            List<CommitmentRecord> recentlyTouched = recentAllStatuses(boundedLimit);
            return mergeUnique(activeList, recentlyTouched, boundedLimit);
        }
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "search").put("query", cleanQuery)
                    .put("limit", boundedLimit);
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return queryCommitments(request);
    }

    /**
     * Issues an FTS query against commitment titles using a stopword-style
     * "match anything" pattern so the framework returns commitments of any
     * status (its `mode=search` path filters only on `deleted_at IS NULL`,
     * not on `status`). Used by {@link #search(String, int)} to surface
     * recently-fired reminders alongside pending ones.
     */
    private List<CommitmentRecord> recentAllStatuses(int limit) {
        JSONObject request = new JSONObject();
        try {
            request.put("mode", "search")
                    // FTS4 has no syntactic "match all", but every commitment
                    // title we create contains at least one ASCII letter; OR-ing
                    // a generous alphabet covers all rows, and the framework
                    // already orders by `updated_at DESC` and returns at most
                    // `limit` rows.
                    .put("query",
                            "a OR b OR c OR d OR e OR f OR g OR h OR i OR j OR k OR l OR m "
                                    + "OR n OR o OR p OR q OR r OR s OR t OR u OR v OR w "
                                    + "OR x OR y OR z OR 0 OR 1 OR 2 OR 3 OR 4 OR 5 OR 6 "
                                    + "OR 7 OR 8 OR 9")
                    .put("limit", Math.max(1, Math.min(limit, 50)));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return queryCommitments(request);
    }

    private static List<CommitmentRecord> mergeUnique(List<CommitmentRecord> primary,
            List<CommitmentRecord> secondary, int limit) {
        ArrayList<CommitmentRecord> out = new ArrayList<>(primary);
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (CommitmentRecord c : primary) {
            seen.add(c.id);
        }
        for (CommitmentRecord c : secondary) {
            if (out.size() >= limit) {
                break;
            }
            if (seen.add(c.id)) {
                out.add(c);
            }
        }
        return out;
    }

    public String searchJson(String query, int limit) {
        JSONArray array = new JSONArray();
        for (CommitmentRecord commitment : search(query, limit)) {
            array.put(commitmentJson(commitment));
        }
        try {
            return new JSONObject().put("commitments", array).toString();
        } catch (JSONException e) {
            return "{\"commitments\":[]}";
        }
    }

    private boolean update(JSONObject request) {
        if (mManager == null) {
            return false;
        }
        try {
            return parseOrEmpty(mManager.commitmentUpdate(request.toString()))
                    .optBoolean("updated", false);
        } catch (RuntimeException e) {
            Log.w(TAG, "commitment update failed", e);
            return false;
        }
    }

    private List<CommitmentRecord> queryCommitments(JSONObject request) {
        List<CommitmentRecord> commitments = new ArrayList<>();
        if (mManager == null) {
            return commitments;
        }
        JSONObject response;
        try {
            response = parseOrEmpty(mManager.commitmentQuery(request.toString()));
        } catch (RuntimeException e) {
            Log.w(TAG, "commitment query failed", e);
            return commitments;
        }
        JSONArray array = response.optJSONArray("commitments");
        if (array == null) {
            return commitments;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject commitment = array.optJSONObject(i);
            if (commitment == null) {
                continue;
            }
            commitments.add(new CommitmentRecord(
                    commitment.optLong("id"),
                    commitment.optString("title", ""),
                    commitment.optString("description", ""),
                    commitment.optString("trigger_type", ""),
                    commitment.optString("trigger_spec_json", "{}"),
                    commitment.optLong("due_at"),
                    commitment.optLong("expires_at"),
                    commitment.optString("status", ""),
                    (float) commitment.optDouble("confidence", 1.0),
                    commitment.optString("evidence_json", "{}"),
                    commitment.optLong("created_at"),
                    commitment.optLong("updated_at")));
        }
        return commitments;
    }

    /**
     * One-time move of the legacy assistant-local commitment database into the
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
                    try (Cursor cursor = db.rawQuery("SELECT id, title, description, "
                            + "normalized_title, trigger_type, trigger_spec_json, due_at, "
                            + "expires_at, status, confidence, evidence_json, created_at, "
                            + "updated_at, last_checked_at, failure_count "
                            + "FROM commitment WHERE deleted_at IS NULL AND id > ? "
                            + "ORDER BY id ASC LIMIT ?",
                            new String[]{Long.toString(lastId),
                                    Integer.toString(MIGRATION_CHUNK)})) {
                        while (cursor.moveToNext()) {
                            lastId = cursor.getLong(0);
                            JSONObject row = new JSONObject();
                            try {
                                row.put("id", cursor.getLong(0))
                                        .put("title", cursor.getString(1))
                                        .put("description", cursor.getString(2))
                                        .put("normalized_title", cursor.getString(3))
                                        .put("trigger_type", cursor.getString(4))
                                        .put("trigger_spec_json", cursor.getString(5))
                                        .put("due_at", cursor.getLong(6))
                                        .put("expires_at", cursor.getLong(7))
                                        .put("status", cursor.getString(8))
                                        .put("confidence", cursor.getDouble(9))
                                        .put("evidence_json", cursor.getString(10))
                                        .put("created_at", cursor.getLong(11))
                                        .put("updated_at", cursor.getLong(12))
                                        .put("failure_count", cursor.getLong(14));
                                if (cursor.isNull(13)) {
                                    row.put("last_checked_at", JSONObject.NULL);
                                } else {
                                    row.put("last_checked_at", cursor.getLong(13));
                                }
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
                            mManager.migrateRows("commitment", chunk.toString()));
                    if (result.has("error")) {
                        Log.w(TAG, "Legacy commitment migration failed: "
                                + result.optString("error"));
                        return;
                    }
                    migrated += result.optInt("inserted", 0);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Legacy commitment migration failed", e);
                return;
            }
            prefs.edit()
                    .putBoolean(KEY_MIGRATED, true)
                    .putInt(KEY_MIGRATED + "_count", migrated)
                    .apply();
            Log.i(TAG, "Migrated " + migrated + " legacy commitments into the OS store");
        }
    }

    private static JSONObject commitmentJson(CommitmentRecord commitment) {
        try {
            return new JSONObject()
                    .put("id", commitment.id)
                    .put("title", commitment.title)
                    .put("description", commitment.description)
                    .put("trigger_type", commitment.triggerType)
                    .put("trigger_spec", parseOrEmpty(commitment.triggerSpecJson))
                    .put("due_at", commitment.dueAtMillis)
                    .put("expires_at", commitment.expiresAtMillis)
                    .put("status", commitment.status)
                    .put("confidence", commitment.confidence)
                    .put("evidence", parseOrEmpty(commitment.evidenceJson))
                    .put("created_at", commitment.createdAtMillis)
                    .put("updated_at", commitment.updatedAtMillis);
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
