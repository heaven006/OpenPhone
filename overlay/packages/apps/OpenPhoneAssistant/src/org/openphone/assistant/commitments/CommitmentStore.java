package org.openphone.assistant.commitments;

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

public final class CommitmentStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "openphone_commitments.db";
    private static final int DB_VERSION = 2;

    public CommitmentStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE commitment ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "description TEXT,"
                + "normalized_title TEXT NOT NULL,"
                + "trigger_type TEXT NOT NULL,"
                + "trigger_spec_json TEXT,"
                + "due_at INTEGER,"
                + "expires_at INTEGER,"
                + "status TEXT NOT NULL,"
                + "confidence REAL NOT NULL,"
                + "evidence_json TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "last_checked_at INTEGER,"
                + "failure_count INTEGER NOT NULL DEFAULT 0,"
                + "deleted_at INTEGER"
                + ")");
        db.execSQL("CREATE VIRTUAL TABLE commitment_fts USING fts4("
                + "title, description, trigger_type, content='commitment')");
        db.execSQL("CREATE TRIGGER commitment_ai AFTER INSERT ON commitment "
                + "BEGIN INSERT INTO commitment_fts(rowid, title, description, trigger_type) "
                + "VALUES (new.id, new.title, new.description, new.trigger_type); END");
        db.execSQL("CREATE TRIGGER commitment_ad AFTER DELETE ON commitment "
                + "BEGIN DELETE FROM commitment_fts WHERE rowid = old.id; END");
        db.execSQL("CREATE TRIGGER commitment_au AFTER UPDATE ON commitment "
                + "BEGIN DELETE FROM commitment_fts WHERE rowid = old.id; "
                + "INSERT INTO commitment_fts(rowid, title, description, trigger_type) "
                + "VALUES (new.id, new.title, new.description, new.trigger_type); END");
        db.execSQL("CREATE INDEX commitment_status_idx ON commitment(status, due_at)");
        db.execSQL("CREATE INDEX commitment_title_idx ON commitment(normalized_title)");
        db.execSQL("CREATE INDEX commitment_updated_idx ON commitment(deleted_at, updated_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Durable user data: migrations must be additive and stepwise, never DROP TABLE.
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS commitment_updated_idx "
                    + "ON commitment(deleted_at, updated_at)");
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
        if (cleanTitle.isEmpty()) {
            return -1L;
        }
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", cleanTitle);
        values.put("description", description == null ? "" : description.trim());
        values.put("normalized_title", normalize(cleanTitle));
        values.put("trigger_type", safe(triggerType, "manual"));
        values.put("trigger_spec_json", triggerSpecJson == null || triggerSpecJson.isEmpty()
                ? "{}" : triggerSpecJson);
        values.put("due_at", dueAtMillis);
        values.put("expires_at", expiresAtMillis);
        values.put("status", "pending");
        values.put("confidence", Math.max(0f, Math.min(confidence, 1f)));
        values.put("evidence_json", evidenceJson == null || evidenceJson.isEmpty()
                ? "{}" : evidenceJson);
        values.put("created_at", now);
        values.put("updated_at", now);
        return getWritableDatabase().insert("commitment", null, values);
    }

    public boolean updateStatus(long id, String status) {
        if (id <= 0 || status == null || status.trim().isEmpty()) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("status", status.trim());
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update("commitment", values, "id = ?",
                new String[]{Long.toString(id)}) > 0;
    }

    public boolean snooze(long id, long dueAtMillis) {
        if (id <= 0 || dueAtMillis <= 0) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("status", "snoozed");
        values.put("due_at", dueAtMillis);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update("commitment", values, "id = ?",
                new String[]{Long.toString(id)}) > 0;
    }

    public boolean dismiss(long id) {
        if (id <= 0) {
            return false;
        }
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("status", "dismissed");
        values.put("updated_at", now);
        values.put("deleted_at", now);
        return getWritableDatabase().update("commitment", values, "id = ?",
                new String[]{Long.toString(id)}) > 0;
    }

    public List<CommitmentRecord> due(long nowMillis, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        List<CommitmentRecord> commitments = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id, title, description, "
                + "trigger_type, trigger_spec_json, due_at, expires_at, status, confidence, "
                + "evidence_json, created_at, updated_at FROM commitment "
                + "WHERE status IN ('pending','active','snoozed') "
                + "AND deleted_at IS NULL AND due_at > 0 AND due_at <= ? "
                + "ORDER BY due_at ASC LIMIT ?",
                new String[]{Long.toString(nowMillis), Integer.toString(boundedLimit)})) {
            readCommitments(cursor, commitments);
        }
        return commitments;
    }

    public long nextDueAt(long nowMillis) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT due_at FROM commitment "
                + "WHERE status IN ('pending','active','snoozed') "
                + "AND deleted_at IS NULL AND due_at > ? ORDER BY due_at ASC LIMIT 1",
                new String[]{Long.toString(nowMillis)})) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return 0L;
    }

    public List<CommitmentRecord> active(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        List<CommitmentRecord> commitments = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id, title, description, "
                + "trigger_type, trigger_spec_json, due_at, expires_at, status, confidence, "
                + "evidence_json, created_at, updated_at FROM commitment "
                + "WHERE status IN ('pending','active','snoozed') AND deleted_at IS NULL "
                + "ORDER BY CASE WHEN due_at > 0 THEN due_at ELSE 9223372036854775807 END ASC, "
                + "updated_at DESC LIMIT ?",
                new String[]{Integer.toString(boundedLimit)})) {
            readCommitments(cursor, commitments);
        }
        return commitments;
    }

    public List<CommitmentRecord> search(String query, int limit) {
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.isEmpty()) {
            return active(limit);
        }
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        List<CommitmentRecord> commitments = new ArrayList<>();
        String ftsQuery = cleanQuery.replace('"', ' ').trim();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT c.id, c.title, "
                + "c.description, c.trigger_type, c.trigger_spec_json, c.due_at, "
                + "c.expires_at, c.status, c.confidence, c.evidence_json, c.created_at, "
                + "c.updated_at FROM commitment_fts f JOIN commitment c ON c.id = f.rowid "
                + "WHERE commitment_fts MATCH ? AND c.deleted_at IS NULL "
                + "ORDER BY c.updated_at DESC LIMIT ?",
                new String[]{ftsQuery, Integer.toString(boundedLimit)})) {
            readCommitments(cursor, commitments);
        } catch (RuntimeException e) {
            return active(boundedLimit);
        }
        return commitments;
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

    private static void readCommitments(Cursor cursor, List<CommitmentRecord> commitments) {
        while (cursor.moveToNext()) {
            commitments.add(new CommitmentRecord(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    cursor.getLong(5),
                    cursor.getLong(6),
                    cursor.getString(7),
                    cursor.getFloat(8),
                    cursor.getString(9),
                    cursor.getLong(10),
                    cursor.getLong(11)));
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

    private static String normalize(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.US);
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
