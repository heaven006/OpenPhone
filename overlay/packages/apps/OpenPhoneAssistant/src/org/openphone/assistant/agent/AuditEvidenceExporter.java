package org.openphone.assistant.agent;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.openphone.OpenPhoneAgentManager;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AuditEvidenceExporter {
    private AuditEvidenceExporter() {
    }

    public static String exportToDownloads(Context context, OpenPhoneAgentManager agentManager,
            int maxEvents) throws IOException {
        if (agentManager == null) {
            return "OpenPhone system service is unavailable.";
        }

        JSONObject export = new JSONObject();
        try {
            export.put("schema", "openphone.audit_evidence.v1");
            export.put("exported_at_ms", System.currentTimeMillis());
            export.put("redaction", "api keys, tokens, secrets, and base64 screenshot data removed");
            export.put("service_status", parseOrRaw(agentManager.getServiceStatus()));
            export.put("audit", parseOrRaw(agentManager.getAuditLog(maxEvents)));
            redact(export);
        } catch (JSONException e) {
            throw new IOException("Could not build audit export JSON", e);
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        String displayName = "openphone-audit-" + timestamp + ".json";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/OpenPhone");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore did not return an export URI");
        }

        try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
            if (stream == null) {
                throw new IOException("Could not open export URI");
            }
            stream.write(pretty(export).getBytes(StandardCharsets.UTF_8));
            stream.write('\n');
        } catch (IOException e) {
            context.getContentResolver().delete(uri, null, null);
            throw e;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(uri, done, null, null);
        return "Exported " + displayName + " to Downloads/OpenPhone.";
    }

    private static Object parseOrRaw(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }
        String trimmed = json.trim();
        if (trimmed.startsWith("{")) {
            return new JSONObject(trimmed);
        }
        if (trimmed.startsWith("[")) {
            return new JSONArray(trimmed);
        }
        return trimmed;
    }

    private static String pretty(JSONObject object) {
        try {
            return object.toString(2);
        } catch (JSONException e) {
            return object.toString();
        }
    }

    private static void redact(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                Object child = object.opt(name);
                if (isSecretField(name)) {
                    object.put(name, "<redacted>");
                } else if ("data".equals(name) && isScreenshotObject(object)
                        && child instanceof String) {
                    object.put(name, "<base64 chars=" + ((String) child).length() + ">");
                } else {
                    redact(child);
                }
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                redact(array.opt(i));
            }
        }
    }

    private static boolean isScreenshotObject(JSONObject object) {
        return object.has("mime_type") && "base64".equals(object.optString("encoding"));
    }

    private static boolean isSecretField(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.contains("api_key") || lower.contains("authorization")
                || lower.contains("token") || lower.contains("secret");
    }
}
