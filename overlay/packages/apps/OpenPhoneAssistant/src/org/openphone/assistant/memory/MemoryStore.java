package org.openphone.assistant.memory;

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

public final class MemoryStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "openphone_memory.db";
    private static final int DB_VERSION = 2;

    public MemoryStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memory ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "type TEXT NOT NULL,"
                + "subject TEXT,"
                + "text TEXT NOT NULL,"
                + "normalized_text TEXT NOT NULL UNIQUE,"
                + "confidence REAL NOT NULL,"
                + "status TEXT NOT NULL,"
                + "evidence_json TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "last_used_at INTEGER,"
                + "deleted_at INTEGER"
                + ")");
        db.execSQL("CREATE VIRTUAL TABLE memory_fts USING fts4("
                + "type, subject, text, content='memory')");
        db.execSQL("CREATE TRIGGER memory_ai AFTER INSERT ON memory "
                + "BEGIN INSERT INTO memory_fts(rowid, type, subject, text) "
                + "VALUES (new.id, new.type, new.subject, new.text); END");
        db.execSQL("CREATE TRIGGER memory_ad AFTER DELETE ON memory "
                + "BEGIN DELETE FROM memory_fts WHERE rowid = old.id; END");
        db.execSQL("CREATE TRIGGER memory_au AFTER UPDATE ON memory "
                + "BEGIN DELETE FROM memory_fts WHERE rowid = old.id; "
                + "INSERT INTO memory_fts(rowid, type, subject, text) "
                + "VALUES (new.id, new.type, new.subject, new.text); END");
        db.execSQL("CREATE INDEX memory_status_idx ON memory(status, updated_at)");
        db.execSQL("CREATE INDEX memory_normalized_idx ON memory(normalized_text)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Durable user data: migrations must be additive and stepwise, never DROP TABLE.
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS memory_normalized_idx "
                    + "ON memory(normalized_text)");
        }
    }

    public long saveExplicitMemory(String text, String evidenceJson) {
        return saveMemory(classifyType(text), "user", text, 1.0f, evidenceJson);
    }

    public long saveMemory(String type, String subject, String text, float confidence,
            String evidenceJson) {
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.isEmpty()) {
            return -1L;
        }
        long now = System.currentTimeMillis();
        String normalized = normalize(cleanText);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("type", safe(type, "fact"));
        values.put("subject", safe(subject, "user"));
        values.put("text", cleanText);
        values.put("normalized_text", normalized);
        values.put("confidence", Math.max(0f, Math.min(confidence, 1f)));
        values.put("status", "active");
        values.put("evidence_json", evidenceJson == null || evidenceJson.isEmpty()
                ? "{}" : evidenceJson);
        values.put("created_at", now);
        values.put("updated_at", now);
        long id = db.insertWithOnConflict("memory", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id >= 0) {
            return id;
        }
        ContentValues update = new ContentValues();
        update.put("type", safe(type, "fact"));
        update.put("subject", safe(subject, "user"));
        update.put("text", cleanText);
        update.put("confidence", Math.max(0f, Math.min(confidence, 1f)));
        update.put("status", "active");
        update.put("evidence_json", evidenceJson == null || evidenceJson.isEmpty()
                ? "{}" : evidenceJson);
        update.put("updated_at", now);
        db.update("memory", update, "normalized_text = ?", new String[]{normalized});
        return idForNormalizedText(db, normalized);
    }

    public List<MemoryRecord> search(String query, int limit) {
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.isEmpty()) {
            return latest(limit);
        }
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        SQLiteDatabase db = getReadableDatabase();
        List<MemoryRecord> memories = new ArrayList<>();
        String ftsQuery = cleanQuery.replace('"', ' ').trim();
        try (Cursor cursor = db.rawQuery("SELECT m.id, m.type, m.subject, m.text, "
                + "m.confidence, m.created_at, m.updated_at, m.evidence_json "
                + "FROM memory_fts f JOIN memory m ON m.id = f.rowid "
                + "WHERE memory_fts MATCH ? AND m.status = 'active' AND m.deleted_at IS NULL "
                + "ORDER BY m.updated_at DESC LIMIT ?",
                new String[]{ftsQuery, Integer.toString(boundedLimit)})) {
            readMemories(cursor, memories);
        } catch (RuntimeException e) {
            return latest(boundedLimit);
        }
        touch(memories);
        return memories;
    }

    public List<MemoryRecord> latest(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        SQLiteDatabase db = getReadableDatabase();
        List<MemoryRecord> memories = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id, type, subject, text, confidence, "
                + "created_at, updated_at, evidence_json FROM memory "
                + "WHERE status = 'active' AND deleted_at IS NULL "
                + "ORDER BY updated_at DESC LIMIT ?",
                new String[]{Integer.toString(boundedLimit)})) {
            readMemories(cursor, memories);
        }
        touch(memories);
        return memories;
    }

    public String searchJson(String query, int limit) {
        JSONArray array = new JSONArray();
        for (MemoryRecord memory : search(query, limit)) {
            array.put(memoryJson(memory));
        }
        try {
            return new JSONObject().put("memories", array).toString();
        } catch (JSONException e) {
            return "{\"memories\":[]}";
        }
    }

    private void touch(List<MemoryRecord> memories) {
        if (memories.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("last_used_at", now);
        for (MemoryRecord memory : memories) {
            db.update("memory", values, "id = ?", new String[]{Long.toString(memory.id)});
        }
    }

    private static String classifyType(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.US);
        if (lower.contains("prefer") || lower.contains("like ")
                || lower.contains("don't like") || lower.contains("do not like")) {
            return "preference";
        }
        if (lower.contains("always ") || lower.contains("never ")) {
            return "standing_instruction";
        }
        return "fact";
    }

    private static long idForNormalizedText(SQLiteDatabase db, String normalized) {
        try (Cursor cursor = db.rawQuery("SELECT id FROM memory WHERE normalized_text = ?",
                new String[]{normalized})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1L;
        }
    }

    private static void readMemories(Cursor cursor, List<MemoryRecord> memories) {
        while (cursor.moveToNext()) {
            memories.add(new MemoryRecord(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getFloat(4),
                    cursor.getLong(5),
                    cursor.getLong(6),
                    cursor.getString(7)));
        }
    }

    private static JSONObject memoryJson(MemoryRecord memory) {
        try {
            return new JSONObject()
                    .put("id", memory.id)
                    .put("type", memory.type)
                    .put("subject", memory.subject)
                    .put("text", memory.text)
                    .put("confidence", memory.confidence)
                    .put("created_at", memory.createdAtMillis)
                    .put("updated_at", memory.updatedAtMillis)
                    .put("evidence", parseOrEmpty(memory.evidenceJson));
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
