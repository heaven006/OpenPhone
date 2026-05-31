package org.openphone.assistant.model;

public interface ModelAdapter {
    String name();
    String runTask(String taskId, String userGoal, ToolExecutor executor);

    interface ToolExecutor {
        String callTool(String toolName, String argumentsJson);
    }
}
