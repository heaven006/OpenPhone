package org.openphone.assistant.context;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ContextIndexStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "openphone_context_index.db";
    private static final int DB_VERSION = 2;
    private static final String SOURCE_APP = "org.openphone.assistant";
    private static final String CHAT_PREFS_NAME = "openphone_chat_history";
    private static final String KEY_CURRENT_MESSAGES = "current_messages";
    private static final String KEY_HISTORY = "history";
    private static final String BACKFILL_PREFS_NAME = "openphone_context_index";
    private static final String KEY_CHAT_BACKFILLED_V1 = "chat_backfilled_v1";

    private final Context mContext;

    public ContextIndexStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        mContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE context_event ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "source_type TEXT NOT NULL,"
                + "source_app TEXT NOT NULL,"
                + "source_record_id TEXT,"
                + "observed_at INTEGER NOT NULL,"
                + "title TEXT,"
                + "text TEXT,"
                + "payload_json TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "deleted_at INTEGER"
                + ")");
        db.execSQL("CREATE VIRTUAL TABLE context_event_fts USING fts4("
                + "title, text, content='context_event')");
        db.execSQL("CREATE TRIGGER context_event_ai AFTER INSERT ON context_event "
                + "BEGIN INSERT INTO context_event_fts(rowid, title, text) "
                + "VALUES (new.id, new.title, new.text); END");
        db.execSQL("CREATE TRIGGER context_event_ad AFTER DELETE ON context_event "
                + "BEGIN DELETE FROM context_event_fts WHERE rowid = old.id; END");
        db.execSQL("CREATE TRIGGER context_event_au AFTER UPDATE ON context_event "
                + "BEGIN DELETE FROM context_event_fts WHERE rowid = old.id; "
                + "INSERT INTO context_event_fts(rowid, title, text) "
                + "VALUES (new.id, new.title, new.text); END");
        db.execSQL("CREATE INDEX context_event_source_idx ON context_event("
                + "source_type, observed_at)");
        db.execSQL("CREATE INDEX context_event_record_idx ON context_event("
                + "source_record_id)");
        db.execSQL("CREATE INDEX context_event_recent_idx ON context_event("
                + "deleted_at, observed_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Durable user data: migrations must be additive and stepwise, never DROP TABLE.
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS context_event_recent_idx "
                    + "ON context_event(deleted_at, observed_at)");
        }
    }

    public long recordConversationMessage(String speaker, String message) {
        String cleanMessage = message == null ? "" : message.trim();
        if (cleanMessage.isEmpty()) {
            return -1L;
        }
        boolean user = "You".equals(speaker);
        JSONObject payload = new JSONObject();
        try {
            payload.put("speaker", speaker == null ? "" : speaker)
                    .put("is_user", user);
        } catch (JSONException ignored) {
        }
        return insert("assistant.conversation.message", user ? "User message" : "Assistant reply",
                cleanMessage, payload.toString(), "");
    }

    public void backfillChatHistoryIfNeeded() {
        SharedPreferences backfillPrefs = mContext.getSharedPreferences(BACKFILL_PREFS_NAME,
                Context.MODE_PRIVATE);
        if (backfillPrefs.getBoolean(KEY_CHAT_BACKFILLED_V1, false)) {
            return;
        }
        SharedPreferences chatPrefs = mContext.getSharedPreferences(CHAT_PREFS_NAME,
                Context.MODE_PRIVATE);
        int count = 0;
        count += backfillMessages(chatPrefs.getString(KEY_CURRENT_MESSAGES, null),
                "current");
        String history = chatPrefs.getString(KEY_HISTORY, null);
        if (history != null && !history.isEmpty()) {
            try {
                JSONArray sessions = new JSONArray(history);
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject session = sessions.optJSONObject(i);
                    if (session == null) {
                        continue;
                    }
                    count += backfillMessages(session.optJSONArray("messages"),
                            session.optString("id", "history"));
                }
            } catch (JSONException ignored) {
            }
        }
        backfillPrefs.edit()
                .putBoolean(KEY_CHAT_BACKFILLED_V1, true)
                .putInt("chat_backfilled_v1_count", count)
                .apply();
    }

    public long recordAgentEvent(String eventType, String title, String text, String taskId,
            String payloadJson) {
        JSONObject payload = parseOrEmpty(payloadJson);
        try {
            payload.put("task_id", taskId == null ? "" : taskId);
        } catch (JSONException ignored) {
        }
        return insert(eventType == null || eventType.isEmpty() ? "assistant.agent.event" : eventType,
                title, text, payload.toString(), taskId);
    }

    public long recordNotificationEvent(String eventType, String packageName,
            String notificationKey, String title, String text, long observedAtMillis,
            String payloadJson) {
        String sourceType = eventType == null || eventType.isEmpty()
                ? "notification.posted" : eventType;
        String cleanPackage = packageName == null ? "" : packageName;
        String cleanKey = notificationKey == null ? "" : notificationKey;
        JSONObject payload = parseOrEmpty(payloadJson);
        try {
            payload.put("package", cleanPackage)
                    .put("notification_key", cleanKey);
        } catch (JSONException ignored) {
        }
        return insert(sourceType, cleanPackage, cleanKey, observedAtMillis, title, text,
                payload.toString());
    }

    public List<ContextEvent> search(String query, int limit) {
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.isEmpty()) {
            return latest(limit);
        }
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        SQLiteDatabase db = getReadableDatabase();
        List<ContextEvent> events = new ArrayList<>();
        String ftsQuery = cleanQuery.replace('"', ' ').trim();
        try (Cursor cursor = db.rawQuery("SELECT e.id, e.source_type, e.source_app, "
                + "e.source_record_id, e.observed_at, e.title, e.text, e.payload_json "
                + "FROM context_event_fts f JOIN context_event e ON e.id = f.rowid "
                + "WHERE context_event_fts MATCH ? AND e.deleted_at IS NULL "
                + "ORDER BY e.observed_at DESC LIMIT ?",
                new String[]{ftsQuery, Integer.toString(boundedLimit)})) {
            readEvents(cursor, events);
        } catch (RuntimeException e) {
            return latest(boundedLimit);
        }
        return events;
    }

    public List<ContextEvent> latest(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        SQLiteDatabase db = getReadableDatabase();
        List<ContextEvent> events = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id, source_type, source_app, "
                + "source_record_id, observed_at, title, text, payload_json "
                + "FROM context_event WHERE deleted_at IS NULL "
                + "ORDER BY observed_at DESC LIMIT ?",
                new String[]{Integer.toString(boundedLimit)})) {
            readEvents(cursor, events);
        }
        return events;
    }

    public List<ContextEvent> notifications(String query, int limit) {
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.isEmpty()) {
            return latestNotifications(limit);
        }
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        SQLiteDatabase db = getReadableDatabase();
        List<ContextEvent> events = new ArrayList<>();
        String ftsQuery = cleanQuery.replace('"', ' ').trim();
        try (Cursor cursor = db.rawQuery("SELECT e.id, e.source_type, e.source_app, "
                + "e.source_record_id, e.observed_at, e.title, e.text, e.payload_json "
                + "FROM context_event_fts f JOIN context_event e ON e.id = f.rowid "
                + "WHERE context_event_fts MATCH ? AND e.deleted_at IS NULL "
                + "AND e.source_type LIKE 'notification.%' "
                + "ORDER BY e.observed_at DESC LIMIT ?",
                new String[]{ftsQuery, Integer.toString(boundedLimit)})) {
            readEvents(cursor, events);
        } catch (RuntimeException e) {
            return latestNotifications(boundedLimit);
        }
        return events;
    }

    public List<ContextEvent> latestNotifications(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        SQLiteDatabase db = getReadableDatabase();
        List<ContextEvent> events = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id, source_type, source_app, "
                + "source_record_id, observed_at, title, text, payload_json "
                + "FROM context_event WHERE deleted_at IS NULL "
                + "AND source_type LIKE 'notification.%' "
                + "ORDER BY observed_at DESC LIMIT ?",
                new String[]{Integer.toString(boundedLimit)})) {
            readEvents(cursor, events);
        }
        return events;
    }

    /** Recent chat messages as a JSON array of {speaker, text}, oldest first. */
    public String recentConversationJson(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        SQLiteDatabase db = getReadableDatabase();
        List<ContextEvent> events = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id, source_type, source_app, "
                + "source_record_id, observed_at, title, text, payload_json "
                + "FROM context_event WHERE deleted_at IS NULL "
                + "AND source_type = 'assistant.conversation.message' "
                + "ORDER BY observed_at DESC, id DESC LIMIT ?",
                new String[]{Integer.toString(boundedLimit)})) {
            readEvents(cursor, events);
        } catch (RuntimeException e) {
            return "[]";
        }
        JSONArray conversation = new JSONArray();
        for (int i = events.size() - 1; i >= 0; i--) {
            ContextEvent event = events.get(i);
            JSONObject payload = parseOrEmpty(event.payloadJson);
            try {
                conversation.put(new JSONObject()
                        .put("speaker", payload.optString("speaker",
                                payload.optBoolean("is_user", false) ? "You" : "OpenPhone"))
                        .put("text", event.text == null ? "" : event.text));
            } catch (JSONException ignored) {
            }
        }
        return conversation.toString();
    }

    public String searchJson(String query, int limit) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"events\":[");
        List<ContextEvent> events = search(query, limit);
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(eventJson(events.get(i)));
        }
        builder.append("]}");
        return builder.toString();
    }

    public String notificationsJson(String query, int limit) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"notifications\":[");
        List<ContextEvent> events = notifications(query, limit);
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(eventJson(events.get(i)));
        }
        builder.append("]}");
        return builder.toString();
    }

    private long insert(String sourceType, String title, String text, String payloadJson,
            String sourceRecordId) {
        return insert(sourceType, SOURCE_APP, sourceRecordId, System.currentTimeMillis(),
                title, text, payloadJson);
    }

    private long insert(String sourceType, String sourceApp, String sourceRecordId,
            long observedAtMillis, String title, String text, String payloadJson) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("source_type", sourceType == null ? "" : sourceType);
        values.put("source_app", sourceApp == null ? "" : sourceApp);
        values.put("source_record_id", sourceRecordId == null ? "" : sourceRecordId);
        values.put("observed_at", observedAtMillis > 0 ? observedAtMillis : now);
        values.put("title", title == null ? "" : title);
        values.put("text", text == null ? "" : text);
        values.put("payload_json", payloadJson == null ? "{}" : payloadJson);
        values.put("created_at", now);
        return getWritableDatabase().insert("context_event", null, values);
    }

    private int backfillMessages(String rawMessages, String sessionId) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return 0;
        }
        try {
            return backfillMessages(new JSONArray(rawMessages), sessionId);
        } catch (JSONException e) {
            return 0;
        }
    }

    private int backfillMessages(JSONArray messages, String sessionId) {
        if (messages == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String body = message.optString("body", "").trim();
            if (body.isEmpty()) {
                continue;
            }
            String speaker = message.optString("speaker",
                    message.optBoolean("is_user", false) ? "You" : "OpenPhone");
            JSONObject payload = new JSONObject();
            try {
                payload.put("speaker", speaker)
                        .put("is_user", message.optBoolean("is_user", false))
                        .put("backfilled", true)
                        .put("chat_session_id", sessionId == null ? "" : sessionId);
            } catch (JSONException ignored) {
            }
            insert("assistant.conversation.message",
                    message.optBoolean("is_user", false) ? "User message" : "Assistant reply",
                    body, payload.toString(), sessionId == null ? "" : sessionId);
            count++;
        }
        return count;
    }

    private static JSONObject parseOrEmpty(String payloadJson) {
        try {
            return new JSONObject(payloadJson == null || payloadJson.isEmpty()
                    ? "{}" : payloadJson);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void readEvents(Cursor cursor, List<ContextEvent> events) {
        while (cursor.moveToNext()) {
            events.add(new ContextEvent(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getLong(4),
                    cursor.getString(5),
                    cursor.getString(6),
                    cursor.getString(7)));
        }
    }

    private static String eventJson(ContextEvent event) {
        try {
            return new JSONObject()
                    .put("id", event.id)
                    .put("source_type", event.sourceType)
                    .put("source_app", event.sourceApp)
                    .put("source_record_id", event.sourceRecordId)
                    .put("observed_at", event.observedAtMillis)
                    .put("title", event.title)
                    .put("text", event.text)
                    .put("payload", parseOrEmpty(event.payloadJson))
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }
}
