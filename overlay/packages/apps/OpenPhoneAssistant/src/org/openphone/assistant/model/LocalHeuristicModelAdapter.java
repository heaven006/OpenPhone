package org.openphone.assistant.model;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public final class LocalHeuristicModelAdapter implements ModelAdapter {
    private volatile boolean mCancelled;

    @Override
    public String name() {
        return "local-heuristic-dev";
    }

    @Override
    public String providerDisplayName() {
        return "Local heuristic";
    }

    @Override
    public String modelName() {
        return "local-heuristic";
    }

    @Override
    public boolean usesCloud() {
        return false;
    }

    @Override
    public String privacyDisclosure() {
        return "Runs on device without a cloud model request. It uses the active screen "
                + "context exposed by OpenPhone for this task.";
    }

    @Override
    public void cancel() {
        mCancelled = true;
    }

    @Override
    public String runTask(String taskId, String userGoal, ToolExecutor executor) {
        mCancelled = false;
        String goal = userGoal == null ? "" : userGoal.toLowerCase(Locale.US);
        JSONArray steps = new JSONArray();
        if (mCancelled || executor.isCancelled()) {
            return cancelled();
        }
        String screen = executor.callTool("get_screen", "{}");
        recordStep(steps, "get_screen", "{}", screen);
        if (mCancelled || executor.isCancelled()) {
            return cancelled();
        }
        try {
            if (goal.contains("setting")) {
                JSONObject arguments = new JSONObject().put("package_or_label", "Settings")
                        .put("reason", "User asked for Settings");
                recordStep(steps, "open_app", arguments.toString(),
                        executor.callTool("open_app", arguments.toString()));
                return result("task.finished", userGoal,
                        "Opened Settings through the local development adapter.", steps);
            } else if (goal.contains("home")) {
                JSONObject arguments = new JSONObject().put("key", "home")
                        .put("reason", "User asked to go home");
                recordStep(steps, "press_key", arguments.toString(),
                        executor.callTool("press_key", arguments.toString()));
                return result("task.finished", userGoal,
                        "Went home through the local development adapter.", steps);
            } else if (goal.contains("back")) {
                JSONObject arguments = new JSONObject().put("key", "back")
                        .put("reason", "User asked to go back");
                recordStep(steps, "press_key", arguments.toString(),
                        executor.callTool("press_key", arguments.toString()));
                return result("task.finished", userGoal,
                        "Went back through the local development adapter.", steps);
            }
            JSONObject arguments = new JSONObject().put("summary",
                    "No cloud model is configured; observed screen only");
            recordStep(steps, "finish_task", arguments.toString(),
                    executor.callTool("finish_task", arguments.toString()));
            return result("task.finished", userGoal,
                    "Observed the current screen with the local development adapter.", steps);
        } catch (JSONException e) {
            return error("json_error", e.getMessage(), steps);
        }
    }

    private static String cancelled() {
        return "{\"status\":\"cancelled\",\"reason\":\"user_stopped\"}";
    }

    private static void recordStep(JSONArray steps, String tool, String arguments,
            String toolResult) {
        try {
            steps.put(new JSONObject()
                    .put("tool", tool)
                    .put("arguments", arguments == null ? "" : arguments)
                    .put("tool_result", toolResult == null ? "" : toolResult));
        } catch (JSONException ignored) {
        }
    }

    private static String result(String status, String userGoal, String answer, JSONArray steps) {
        try {
            return new JSONObject()
                    .put("status", status)
                    .put("provider", "local_heuristic")
                    .put("goal", userGoal == null ? "" : userGoal)
                    .put("answer", answer)
                    .put("steps", steps)
                    .toString(2);
        } catch (JSONException e) {
            return "{\"status\":\"task.finished\"}";
        }
    }

    private static String error(String status, String reason, JSONArray steps) {
        try {
            return new JSONObject()
                    .put("status", status)
                    .put("provider", "local_heuristic")
                    .put("reason", reason == null ? "" : reason)
                    .put("steps", steps)
                    .toString(2);
        } catch (JSONException e) {
            return "{\"status\":\"error\"}";
        }
    }
}
