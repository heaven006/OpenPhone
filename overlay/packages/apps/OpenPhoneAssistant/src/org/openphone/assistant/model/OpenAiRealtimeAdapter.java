package org.openphone.assistant.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class OpenAiRealtimeAdapter implements ModelAdapter {
    private static final String MODEL = "gpt-4.1-mini";
    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final int MAX_STEPS = 10;
    private static final int MAX_CONSECUTIVE_TOOL_ERRORS = 2;
    private static final long MAX_DURATION_MS = 120000;

    private final String mApiKey;
    private volatile boolean mCancelled;
    private volatile HttpURLConnection mActiveConnection;

    public OpenAiRealtimeAdapter(String apiKey) {
        mApiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public String name() {
        return "openai-responses-vision-dev";
    }

    @Override
    public String providerDisplayName() {
        return "OpenAI Responses vision";
    }

    @Override
    public String modelName() {
        return MODEL;
    }

    @Override
    public boolean usesCloud() {
        return true;
    }

    @Override
    public String privacyDisclosure() {
        return "Cloud task mode sends the goal, task-scoped screenshots, and UI metadata "
                + "to OpenAI while the task is active. The development API key stays in "
                + "memory and is not written to the trajectory.";
    }

    @Override
    public void cancel() {
        mCancelled = true;
        HttpURLConnection connection = mActiveConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    @Override
    public String runTask(String taskId, String userGoal, ToolExecutor executor) {
        mCancelled = false;
        if (mApiKey == null || mApiKey.isEmpty()) {
            return unavailable("missing_dev_api_key");
        }

        JSONArray steps = new JSONArray();
        try {
            long startedAtMillis = System.currentTimeMillis();
            int consecutiveToolErrors = 0;
            for (int step = 1; step <= MAX_STEPS; step++) {
                if (mCancelled || executor.isCancelled()) {
                    return result("cancelled", userGoal, steps);
                }
                if (System.currentTimeMillis() - startedAtMillis > MAX_DURATION_MS) {
                    return result("duration_limit_reached", userGoal, steps);
                }
                String screen = captureScreenWithRetry(executor);
                if (mCancelled || executor.isCancelled()) {
                    return result("cancelled", userGoal, steps);
                }
                JSONObject screenJson = new JSONObject(screen);
                JSONObject screenshot = screenJson.optJSONObject("screenshot");
                if (screenshot == null || screenshot.optString("data").isEmpty()) {
                    return new JSONObject()
                            .put("status", "screen_capture_failed")
                            .put("provider", name())
                            .put("steps", steps)
                            .put("screen", redactScreenshot(screenJson))
                            .put("screenshot_error", screenJson.optString("screenshot_error"))
                            .toString(2);
                }

                JSONObject response = callResponsesApi(userGoal, steps, screenJson, screenshot);
                if (mCancelled || executor.isCancelled()) {
                    return result("cancelled", userGoal, steps);
                }
                String outputText = extractOutputText(response);
                JSONObject decision = parseDecision(outputText);
                JSONObject stepJson = new JSONObject()
                        .put("step", step)
                        .put("openai_response_id", response.optString("id"))
                        .put("thought", decision.optString("thought"))
                        .put("tool", decision.optString("tool"))
                        .put("arguments", decision.optJSONObject("arguments") == null
                                ? new JSONObject() : decision.optJSONObject("arguments"))
                        .put("screen", redactScreenshot(new JSONObject(screenJson.toString())));
                steps.put(stepJson);

                String toolName = decision.optString("tool", "");
                JSONObject arguments = decision.optJSONObject("arguments");
                if (arguments == null) {
                    arguments = new JSONObject();
                }

                if ("finish_task".equals(toolName)) {
                    String toolResult = executor.callTool(toolName, arguments.toString());
                    stepJson.put("tool_result", toolResult);
                    return result("task.finished", userGoal, steps);
                }
                if ("fail_task".equals(toolName)) {
                    String toolResult = executor.callTool(toolName, arguments.toString());
                    stepJson.put("tool_result", toolResult);
                    return result("task.failed", userGoal, steps);
                }
                if (!isAllowedTool(toolName)) {
                    stepJson.put("tool_result", errorJson("unknown_model_tool:" + toolName));
                    return result("agent.blocked", userGoal, steps);
                }
                JSONObject guardrail = guardrailConfirmation(userGoal, screenJson, toolName,
                        arguments);
                if (guardrail != null) {
                    stepJson.put("guardrail", guardrail);
                    String toolResult = executor.callTool("ask_user_confirmation",
                            guardrail.toString());
                    stepJson.put("tool_result", toolResult);
                    return result("confirmation_required", userGoal, steps);
                }

                String toolResult = executor.callTool(toolName, arguments.toString());
                stepJson.put("tool_result", toolResult);
                if (mCancelled || executor.isCancelled()) {
                    return result("cancelled", userGoal, steps);
                }
                String toolStatus = toolStatus(toolResult);
                if ("confirmation_requested".equals(toolStatus)
                        || "confirmation_required".equals(toolStatus)) {
                    return result("confirmation_required", userGoal, steps);
                }
                if ("denied".equals(toolStatus)) {
                    return result("action_denied", userGoal, steps);
                }
                if (isToolFailure(toolStatus)) {
                    consecutiveToolErrors++;
                    stepJson.put("consecutive_tool_errors", consecutiveToolErrors);
                    if (consecutiveToolErrors >= MAX_CONSECUTIVE_TOOL_ERRORS) {
                        return result("action_failed", userGoal, steps);
                    }
                } else {
                    consecutiveToolErrors = 0;
                }
                sleepAfterAction();
            }
            return result("step_limit_reached", userGoal, steps);
        } catch (JSONException e) {
            return error("json_error", e.getMessage(), steps);
        } catch (IOException e) {
            if (mCancelled || executor.isCancelled()) {
                return cancelledResult(userGoal, steps);
            }
            return error("network_error", e.getMessage(), steps);
        }
    }

    private String captureScreenWithRetry(ToolExecutor executor) {
        String request = "{\"include_screenshot\":true,\"include_activity\":true,"
                + "\"include_ui_tree\":true,"
                + "\"max_dimension\":512,\"quality\":65}";
        String screen = executor.callTool("get_screen", request);
        if (hasScreenshot(screen) || mCancelled || executor.isCancelled()) {
            return screen;
        }
        try {
            Thread.sleep(750);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return screen;
        }
        return executor.callTool("get_screen", request);
    }

    private JSONObject callResponsesApi(String userGoal, JSONArray steps, JSONObject screenJson,
            JSONObject screenshot) throws IOException, JSONException {
        String mimeType = screenshot.optString("mime_type", "image/jpeg");
        String dataUrl = "data:" + mimeType + ";base64," + screenshot.optString("data");

        String prompt = "You are running inside OpenPhone, an experimental Android OS assistant. "
                + "You can control the phone by returning exactly one JSON object. "
                + "Use the screenshot to decide the next action. Keep actions small and safe. "
                + "If the task is complete, use finish_task. If you are stuck, use fail_task. "
                + "Do not include markdown.\n\n"
                + "Allowed JSON schema:\n"
                + "{\"thought\":\"short reason\",\"tool\":\"get_screen|watch_screen|"
                + "open_app|open_url|tap|long_press|swipe|type_text|press_key|"
                + "set_clipboard|paste|share_text|wait|"
                + "ask_user_confirmation|finish_task|fail_task\","
                + "\"arguments\":{...}}\n\n"
                + "Tool arguments:\n"
                + "- get_screen: {\"include_screenshot\":true,\"include_activity\":true,"
                + "\"include_ui_tree\":true,\"reason\":\"...\"}\n"
                + "- watch_screen: {\"fps\":1,\"duration_ms\":1500,\"include_ui_tree\":true,"
                + "\"reason\":\"...\"}\n"
                + "- open_app: {\"package_or_label\":\"Settings|Browser|org.lineageos.jelly\"}\n"
                + "- open_url: {\"url\":\"https://example.com\"}\n"
                + "- tap/long_press: {\"x\":540,\"y\":1200,\"reason\":\"...\"}\n"
                + "- swipe: {\"start_x\":540,\"start_y\":1800,\"end_x\":540,\"end_y\":800,"
                + "\"reason\":\"...\"}\n"
                + "- type_text/set_clipboard/share_text: {\"text\":\"...\",\"reason\":\"...\"}\n"
                + "- paste: {\"reason\":\"...\"}\n"
                + "- press_key: {\"key\":\"back|home|recents\",\"reason\":\"...\"}\n"
                + "- wait: {\"duration_ms\":1000,\"reason\":\"...\"}\n"
                + "- ask_user_confirmation: {\"summary\":\"...\",\"risk\":\"...\","
                + "\"action_json\":{...}}\n"
                + "- finish_task: {\"summary\":\"...\"}\n"
                + "- fail_task: {\"reason\":\"...\"}\n\n"
                + "For app downloads, prefer an already-installed app store if visible. "
                + "Use open_url for exact official download URLs instead of manually typing. "
                + "If no app store is installed, use Browser and official websites only. "
                + "If the official website only offers an app store link and that store is not "
                + "installed, finish_task with that explanation instead of continuing to scroll. "
                + "Do not bypass Android install-security prompts, enter credentials, or "
                + "accept payments/subscriptions.\n\n"
                + "User goal: " + userGoal
                + "\n\nPrevious steps:\n" + steps.toString(2)
                + "\n\nScreen metadata without image bytes:\n"
                + redactScreenshot(new JSONObject(screenJson.toString())).toString(2);

        JSONArray content = new JSONArray()
                .put(new JSONObject()
                        .put("type", "input_text")
                        .put("text", prompt))
                .put(new JSONObject()
                        .put("type", "input_image")
                        .put("image_url", dataUrl)
                        .put("detail", "low"));

        JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("input", new JSONArray()
                        .put(new JSONObject()
                                .put("role", "user")
                                .put("content", content)))
                .put("max_output_tokens", 500);

        HttpURLConnection connection = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
        mActiveConnection = connection;
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + mApiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bodyBytes.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(bodyBytes);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readAll(statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("OpenAI HTTP " + statusCode + ": "
                        + summarizeError(responseBody));
            }
            return new JSONObject(responseBody);
        } finally {
            if (mActiveConnection == connection) {
                mActiveConnection = null;
            }
            connection.disconnect();
        }
    }

    private static JSONObject parseDecision(String outputText) throws JSONException {
        String text = outputText == null ? "" : outputText.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new JSONObject()
                    .put("thought", "Model did not return JSON")
                    .put("tool", "fail_task")
                    .put("arguments", new JSONObject().put("reason", text));
        }
        JSONObject decision = new JSONObject(text.substring(start, end + 1));
        if (!decision.has("arguments") || decision.optJSONObject("arguments") == null) {
            decision.put("arguments", new JSONObject());
        }
        return decision;
    }

    private static boolean isAllowedTool(String toolName) {
        return "get_screen".equals(toolName)
                || "watch_screen".equals(toolName)
                || "open_app".equals(toolName)
                || "open_url".equals(toolName)
                || "tap".equals(toolName)
                || "long_press".equals(toolName)
                || "swipe".equals(toolName)
                || "type_text".equals(toolName)
                || "press_key".equals(toolName)
                || "set_clipboard".equals(toolName)
                || "paste".equals(toolName)
                || "share_text".equals(toolName)
                || "wait".equals(toolName)
                || "ask_user_confirmation".equals(toolName);
    }

    private static JSONObject guardrailConfirmation(String userGoal, JSONObject screenJson,
            String toolName, JSONObject arguments) throws JSONException {
        if (!canChangeDeviceOrExternalState(toolName)) {
            return null;
        }

        String screenText = normalizedScreenText(screenJson);
        String goal = userGoal == null ? "" : userGoal.toLowerCase(Locale.US);
        String risk = "";
        String summary = "";
        if (containsAny(screenText, "install", "update", "download", "get app",
                "unknown apps", "allow from this source", "permission", "allow access")) {
            risk = "High";
            summary = "The agent is about to act on an install, update, download, "
                    + "or permission screen.";
        } else if (containsAny(screenText, "buy", "purchase", "payment", "subscribe",
                "checkout", "card", "₪", "$", "order")) {
            risk = "High";
            summary = "The agent is about to act on a purchase, payment, or "
                    + "subscription screen.";
        } else if (containsAny(screenText, "delete", "remove", "erase", "reset",
                "factory reset")) {
            risk = "High";
            summary = "The agent is about to act on a destructive data-change screen.";
        } else if (containsAny(screenText, "send", "post", "share", "call", "message",
                "sms", "whatsapp", "email")) {
            risk = "High";
            summary = "The agent is about to communicate, share, call, message, "
                    + "or post content.";
        } else if (containsAny(screenText, "password", "sign in", "login", "log in",
                "account", "verification code", "2-step", "two-step")) {
            risk = "High";
            summary = "The agent is about to act on an account, login, password, "
                    + "or verification screen.";
        } else if (("share_text".equals(toolName) || "set_clipboard".equals(toolName)
                || "paste".equals(toolName))
                && containsAny(goal, "send", "share", "post", "message", "email")) {
            risk = "High";
            summary = "The agent is about to prepare or share user-provided content.";
        }

        if (risk.isEmpty()) {
            return null;
        }

        return new JSONObject()
                .put("summary", summary)
                .put("risk", risk)
                .put("capability", capabilityForTool(toolName))
                .put("action_json", new JSONObject()
                        .put("tool", toolName)
                        .put("arguments", arguments == null ? new JSONObject() : arguments));
    }

    private static boolean canChangeDeviceOrExternalState(String toolName) {
        return "tap".equals(toolName)
                || "long_press".equals(toolName)
                || "swipe".equals(toolName)
                || "type_text".equals(toolName)
                || "press_key".equals(toolName)
                || "set_clipboard".equals(toolName)
                || "paste".equals(toolName)
                || "share_text".equals(toolName);
    }

    private static String capabilityForTool(String toolName) {
        if ("share_text".equals(toolName)) {
            return "content.share";
        }
        if ("set_clipboard".equals(toolName) || "paste".equals(toolName)) {
            return "clipboard.write";
        }
        if ("type_text".equals(toolName)) {
            return "input.text";
        }
        return "input.perform";
    }

    private static String normalizedScreenText(JSONObject screenJson) throws JSONException {
        JSONObject copy = redactScreenshot(new JSONObject(screenJson.toString()));
        return copy.toString().toLowerCase(Locale.US);
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String toolStatus(String toolResult) {
        if (toolResult == null || toolResult.trim().isEmpty()) {
            return "";
        }
        try {
            return new JSONObject(toolResult).optString("status", "");
        } catch (JSONException e) {
            return "";
        }
    }

    private static boolean isToolFailure(String toolStatus) {
        return "error".equals(toolStatus)
                || "task.failed".equals(toolStatus)
                || "screen_capture_failed".equals(toolStatus);
    }

    private static boolean hasScreenshot(String screen) {
        if (screen == null || screen.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject screenshot = new JSONObject(screen).optJSONObject("screenshot");
            return screenshot != null && !screenshot.optString("data").isEmpty();
        } catch (JSONException e) {
            return false;
        }
    }

    private static void sleepAfterAction() {
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream,
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String extractOutputText(JSONObject response) throws JSONException {
        String direct = response.optString("output_text", "");
        if (!direct.isEmpty()) {
            return direct;
        }
        StringBuilder builder = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) {
            return "";
        }
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONArray content = item.optJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int j = 0; j < content.length(); j++) {
                JSONObject contentItem = content.optJSONObject(j);
                if (contentItem == null) {
                    continue;
                }
                String text = contentItem.optString("text", "");
                if (!text.isEmpty()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
        }
        return builder.toString();
    }

    private static JSONObject redactScreenshot(JSONObject screenJson) throws JSONException {
        JSONObject screenshot = screenJson.optJSONObject("screenshot");
        if (screenshot != null) {
            String data = screenshot.optString("data", "");
            if (!data.isEmpty()) {
                screenshot.put("data", "<base64 chars=" + data.length() + ">");
            }
        }
        return screenJson;
    }

    private static String summarizeError(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        try {
            JSONObject error = new JSONObject(responseBody).optJSONObject("error");
            if (error != null) {
                return error.optString("message", responseBody);
            }
        } catch (JSONException ignored) {
        }
        return responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody;
    }

    private static String unavailable(String reason) {
        try {
            return new JSONObject()
                    .put("status", "provider_unavailable")
                    .put("provider", "openai_responses")
                    .put("reason", reason)
                    .put("next", "Enter a temporary dev API key in the UI")
                    .toString(2);
        } catch (JSONException e) {
            return "{\"status\":\"provider_unavailable\"}";
        }
    }

    private static String result(String status, String userGoal, JSONArray steps) throws JSONException {
        return new JSONObject()
                .put("status", status)
                .put("provider", "openai_responses")
                .put("model", MODEL)
                .put("goal", userGoal == null ? "" : userGoal)
                .put("steps", steps)
                .toString(2);
    }

    private static String cancelledResult(String userGoal, JSONArray steps) {
        try {
            return result("cancelled", userGoal, steps);
        } catch (JSONException e) {
            return "{\"status\":\"cancelled\",\"reason\":\"user_stopped\"}";
        }
    }

    private static String error(String status, String reason, JSONArray steps) {
        try {
            return new JSONObject()
                    .put("status", status)
                    .put("provider", "openai_responses")
                    .put("reason", reason == null ? "" : reason)
                    .put("steps", steps)
                    .toString(2);
        } catch (JSONException e) {
            return "{\"status\":\"error\"}";
        }
    }

    private static String errorJson(String reason) throws JSONException {
        return new JSONObject().put("status", "error").put("reason", reason).toString();
    }
}
