package org.openphone.assistant.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class LocalHeuristicModelAdapter implements ModelAdapter {
    @Override
    public String name() {
        return "local-heuristic-dev";
    }

    @Override
    public String runTask(String taskId, String userGoal, ToolExecutor executor) {
        String goal = userGoal == null ? "" : userGoal.toLowerCase(Locale.US);
        StringBuilder transcript = new StringBuilder();
        transcript.append("model=").append(name()).append('\n');
        transcript.append("observe=").append(executor.callTool("get_screen", "{}")).append('\n');
        try {
            if (goal.contains("setting")) {
                transcript.append("open_app=").append(executor.callTool("open_app",
                        new JSONObject().put("package_or_label", "Settings")
                                .put("reason", "User asked for Settings").toString())).append('\n');
            } else if (goal.contains("home")) {
                transcript.append("home=").append(executor.callTool("press_key",
                        new JSONObject().put("key", "home")
                                .put("reason", "User asked to go home").toString())).append('\n');
            } else if (goal.contains("back")) {
                transcript.append("back=").append(executor.callTool("press_key",
                        new JSONObject().put("key", "back")
                                .put("reason", "User asked to go back").toString())).append('\n');
            } else {
                transcript.append("finish=").append(executor.callTool("finish_task",
                        new JSONObject().put("summary",
                                "No cloud model is configured; observed screen only").toString()));
            }
        } catch (JSONException e) {
            transcript.append("error=").append(e.getMessage());
        }
        return transcript.toString();
    }
}
