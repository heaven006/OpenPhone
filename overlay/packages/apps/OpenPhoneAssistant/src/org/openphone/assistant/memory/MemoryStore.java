package org.openphone.assistant.memory;

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
import java.util.Locale;

/**
 * Client of the OS-owned OpenPhone assistant data service (system_server
 * openphone_assistant_data). The assistant no longer owns the durable memory
 * store; a one-time migration moves rows from the legacy app-local SQLite
 * database into the OS store, preserving ids.
 */
public final class MemoryStore {
    private static final String TAG = "OpenPhoneMemoryStore";
    private static final String LEGACY_DB_NAME = "openphone_memory.db";
    private static final String PREFS_NAME = "openphone_assistant_data";
    private static final String KEY_MIGRATED = "memory_os_store_migrated_v1";
    private static final int MIGRATION_CHUNK = 100;

    private static final Object sMigrationLock = new Object();

    private final Context mContext;
    private final OpenPhoneAssistantDataManager mManager;

    public MemoryStore(Context context) {
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
            Log.w(TAG, "memory migration deferred", e);
        }
    }

    public long saveExplicitMemory(String text, String evidenceJson) {
        return saveMemory(classifyType(text), "user", text, 1.0f, evidenceJson);
    }

    public long saveMemory(String type, String subject, String text, float confidence,
            String evidenceJson) {
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.isEmpty() || mManager == null) {
            return -1L;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("type", type == null ? "" : type)
                    .put("subject", subject == null ? "" : subject)
                    .put("text", cleanText)
                    .put("confidence", Math.max(0f, Math.min(confidence, 1f)))
                    .put("evidence_json", evidenceJson == null ? "" : evidenceJson);
        } catch (JSONException e) {
            return -1L;
        }
        try {
            return parseOrEmpty(mManager.memorySave(request.toString())).optLong("id", -1L);
        } catch (RuntimeException e) {
            Log.w(TAG, "memory save failed", e);
            return -1L;
        }
    }

    public List<MemoryRecord> search(String query, int limit) {
        List<MemoryRecord> memories = new ArrayList<>();
        if (mManager == null) {
            return memories;
        }
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        JSONObject request = new JSONObject();
        try {
            request.put("query", query == null ? "" : query.trim())
                    .put("limit", boundedLimit);
        } catch (JSONException e) {
            return memories;
        }
        JSONObject response;
        try {
            response = parseOrEmpty(mManager.memoryQuery(request.toString()));
        } catch (RuntimeException e) {
            Log.w(TAG, "memory query failed", e);
            return memories;
        }
        JSONArray array = response.optJSONArray("memories");
        if (array == null) {
            return memories;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject memory = array.optJSONObject(i);
            if (memory == null) {
                continue;
            }
            memories.add(new MemoryRecord(
                    memory.optLong("id"),
                    memory.optString("type", ""),
                    memory.optString("subject", ""),
                    memory.optString("text", ""),
                    (float) memory.optDouble("confidence", 1.0),
                    memory.optLong("created_at"),
                    memory.optLong("updated_at"),
                    memory.optString("evidence_json", "{}")));
        }
        return memories;
    }

    public List<MemoryRecord> latest(int limit) {
        return search("", limit);
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

    /**
     * One-time move of the legacy assistant-local memory database into the
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
                    try (Cursor cursor = db.rawQuery("SELECT id, type, subject, text, "
                            + "normalized_text, confidence, status, evidence_json, "
                            + "created_at, updated_at, last_used_at "
                            + "FROM memory WHERE deleted_at IS NULL AND id > ? "
                            + "ORDER BY id ASC LIMIT ?",
                            new String[]{Long.toString(lastId),
                                    Integer.toString(MIGRATION_CHUNK)})) {
                        while (cursor.moveToNext()) {
                            lastId = cursor.getLong(0);
                            JSONObject row = new JSONObject();
                            try {
                                row.put("id", cursor.getLong(0))
                                        .put("type", cursor.getString(1))
                                        .put("subject", cursor.getString(2))
                                        .put("text", cursor.getString(3))
                                        .put("normalized_text", cursor.getString(4))
                                        .put("confidence", cursor.getDouble(5))
                                        .put("status", cursor.getString(6))
                                        .put("evidence_json", cursor.getString(7))
                                        .put("created_at", cursor.getLong(8))
                                        .put("updated_at", cursor.getLong(9));
                                if (cursor.isNull(10)) {
                                    row.put("last_used_at", JSONObject.NULL);
                                } else {
                                    row.put("last_used_at", cursor.getLong(10));
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
                            mManager.migrateRows("memory", chunk.toString()));
                    if (result.has("error")) {
                        Log.w(TAG, "Legacy memory migration failed: "
                                + result.optString("error"));
                        return;
                    }
                    migrated += result.optInt("inserted", 0);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Legacy memory migration failed", e);
                return;
            }
            prefs.edit()
                    .putBoolean(KEY_MIGRATED, true)
                    .putInt(KEY_MIGRATED + "_count", migrated)
                    .apply();
            Log.i(TAG, "Migrated " + migrated + " legacy memories into the OS store");
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
}
