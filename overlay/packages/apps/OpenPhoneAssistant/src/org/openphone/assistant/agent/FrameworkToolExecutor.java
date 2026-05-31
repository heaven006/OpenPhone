package org.openphone.assistant.agent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.openphone.OpenPhoneAgentManager;

import org.json.JSONException;
import org.json.JSONObject;

public final class FrameworkToolExecutor {
    private final Context mContext;
    private final OpenPhoneAgentManager mAgentManager;

    public FrameworkToolExecutor(Context context, OpenPhoneAgentManager agentManager) {
        mContext = context;
        mAgentManager = agentManager;
    }

    public String execute(String taskId, String toolName, JSONObject arguments) {
        if (mAgentManager == null) {
            return error("framework_unavailable");
        }
        if (taskId == null || taskId.isEmpty()) {
            return error("no_active_task");
        }
        try {
            switch (toolName) {
                case "get_screen":
                    return mAgentManager.getScreen(taskId, arguments.toString());
                case "open_app":
                    String requestedPackage = packageName(arguments.optString("package",
                            arguments.optString("package_or_label")));
                    return mAgentManager.executeAction(taskId, action("open_app")
                            .put("package", requestedPackage)
                            .put("label", arguments.optString("label",
                                    arguments.optString("package_or_label"))).toString());
                case "open_url":
                    return openUrl(arguments.optString("url"));
                case "tap":
                    return mAgentManager.executeAction(taskId, action("tap")
                            .put("target", point(arguments.optDouble("x"), arguments.optDouble("y")))
                            .put("reason", arguments.optString("reason")).toString());
                case "long_press":
                    return mAgentManager.executeAction(taskId, action("long_press")
                            .put("target", point(arguments.optDouble("x"), arguments.optDouble("y")))
                            .put("duration_ms", arguments.optLong("duration_ms", 650))
                            .put("reason", arguments.optString("reason")).toString());
                case "swipe":
                    return mAgentManager.executeAction(taskId, action("scroll")
                            .put("target", new JSONObject()
                                    .put("start_x", arguments.optDouble("start_x"))
                                    .put("start_y", arguments.optDouble("start_y"))
                                    .put("end_x", arguments.optDouble("end_x"))
                                    .put("end_y", arguments.optDouble("end_y")))
                            .put("reason", arguments.optString("reason")).toString());
                case "type_text":
                    return mAgentManager.executeAction(taskId, action("type_text")
                            .put("text", arguments.optString("text"))
                            .put("reason", arguments.optString("reason")).toString());
                case "press_key":
                    return mAgentManager.executeAction(taskId, action(arguments.optString("key",
                            "back")).put("reason", arguments.optString("reason")).toString());
                case "set_clipboard":
                    return mAgentManager.executeAction(taskId, action("copy")
                            .put("text", arguments.optString("text"))
                            .put("reason", arguments.optString("reason")).toString());
                case "paste":
                    return mAgentManager.executeAction(taskId, action("paste")
                            .put("reason", arguments.optString("reason")).toString());
                case "share_text":
                    return mAgentManager.executeAction(taskId, action("share")
                            .put("text", arguments.optString("text"))
                            .put("chooser_title", arguments.optString("chooser_title"))
                            .put("reason", arguments.optString("reason")).toString());
                case "finish_task":
                    return status("task.finished", arguments.optString("summary"));
                case "fail_task":
                    return status("task.failed", arguments.optString("reason"));
                default:
                    return error("unknown_tool:" + toolName);
            }
        } catch (JSONException e) {
            return error("json_error:" + e.getMessage());
        }
    }

    private String openUrl(String url) throws JSONException {
        if (mContext == null) {
            return error("context_unavailable");
        }
        if (url == null || url.trim().isEmpty()) {
            return error("missing_url");
        }

        String normalizedUrl = url.trim();
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage("org.lineageos.jelly");
        try {
            mContext.startActivity(intent);
        } catch (RuntimeException e) {
            return error("open_url_failed:" + e.getClass().getSimpleName());
        }

        return status("action.executed", "open_url:" + normalizedUrl);
    }

    private static JSONObject action(String type) throws JSONException {
        return new JSONObject().put("type", type);
    }

    private static JSONObject point(double x, double y) throws JSONException {
        return new JSONObject().put("x", x).put("y", y);
    }

    private static String packageName(String packageOrLabel) {
        if (packageOrLabel == null) {
            return "";
        }
        String value = packageOrLabel.trim();
        if (value.indexOf('.') >= 0) {
            return value;
        }
        if ("settings".equalsIgnoreCase(value)) {
            return "com.android.settings";
        }
        if ("browser".equalsIgnoreCase(value) || "web".equalsIgnoreCase(value)
                || "jelly".equalsIgnoreCase(value)) {
            return "org.lineageos.jelly";
        }
        if ("play store".equalsIgnoreCase(value) || "google play".equalsIgnoreCase(value)
                || "google play store".equalsIgnoreCase(value)) {
            return "com.android.vending";
        }
        if ("spotify".equalsIgnoreCase(value)) {
            return "com.spotify.music";
        }
        if ("assistant".equalsIgnoreCase(value) || "openphone".equalsIgnoreCase(value)) {
            return "org.openphone.assistant";
        }
        return value;
    }

    private static String error(String reason) {
        try {
            return new JSONObject().put("status", "error").put("reason", reason).toString();
        } catch (JSONException e) {
            return "{\"status\":\"error\"}";
        }
    }

    private static String status(String status, String detail) {
        try {
            return new JSONObject().put("status", status).put("detail", detail).toString();
        } catch (JSONException e) {
            return "{\"status\":\"" + status + "\"}";
        }
    }
}
