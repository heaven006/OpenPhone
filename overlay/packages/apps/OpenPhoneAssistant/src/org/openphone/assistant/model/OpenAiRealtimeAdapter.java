package org.openphone.assistant.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class OpenAiRealtimeAdapter implements ModelAdapter {
    private static final String MODEL = "gpt-4.1-mini";
    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";

    private final String mApiKey;

    public OpenAiRealtimeAdapter(String apiKey) {
        mApiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public String name() {
        return "openai-responses-vision-dev";
    }

    @Override
    public String runTask(String taskId, String userGoal, ToolExecutor executor) {
        if (mApiKey == null || mApiKey.isEmpty()) {
            return unavailable("missing_dev_api_key");
        }

        String screen = executor.callTool("get_screen",
                "{\"include_screenshot\":true,\"include_activity\":true,"
                        + "\"max_dimension\":512,\"quality\":65}");
        try {
            JSONObject screenJson = new JSONObject(screen);
            JSONObject screenshot = screenJson.optJSONObject("screenshot");
            if (screenshot == null || screenshot.optString("data").isEmpty()) {
                return new JSONObject()
                        .put("status", "screen_capture_failed")
                        .put("provider", name())
                        .put("screen", redactScreenshot(screenJson))
                        .put("screenshot_error", screenJson.optString("screenshot_error"))
                        .toString(2);
            }

            JSONObject response = callResponsesApi(userGoal, screenJson, screenshot);
            return new JSONObject()
                    .put("status", "model_response")
                    .put("provider", name())
                    .put("model", MODEL)
                    .put("screen", redactScreenshot(screenJson))
                    .put("openai_response_id", response.optString("id"))
                    .put("openai_output_text", extractOutputText(response))
                    .toString(2);
        } catch (JSONException e) {
            return error("json_error", e.getMessage(), screen);
        } catch (IOException e) {
            return error("network_error", e.getMessage(), screen);
        }
    }

    private JSONObject callResponsesApi(String userGoal, JSONObject screenJson, JSONObject screenshot)
            throws IOException, JSONException {
        String mimeType = screenshot.optString("mime_type", "image/jpeg");
        String dataUrl = "data:" + mimeType + ";base64," + screenshot.optString("data");

        String prompt = "You are running inside OpenPhone, an experimental Android OS assistant. "
                + "Inspect the screenshot and screen metadata. Do not click or type yet. "
                + "Return a concise description of what screen is visible and the next safe "
                + "action you would take for this user goal.\n\nUser goal: " + userGoal
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
                .put("max_output_tokens", 400);

        HttpURLConnection connection = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
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
            throw new IOException("OpenAI HTTP " + statusCode + ": " + summarizeError(responseBody));
        }
        return new JSONObject(responseBody);
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

    private static String error(String status, String reason, String screen) {
        try {
            JSONObject result = new JSONObject()
                    .put("status", status)
                    .put("provider", "openai_responses")
                    .put("reason", reason == null ? "" : reason);
            if (screen != null && !screen.isEmpty()) {
                try {
                    result.put("screen", redactScreenshot(new JSONObject(screen)));
                } catch (JSONException ignored) {
                    result.put("screen", screen);
                }
            }
            return result.toString(2);
        } catch (JSONException e) {
            return "{\"status\":\"error\"}";
        }
    }
}
