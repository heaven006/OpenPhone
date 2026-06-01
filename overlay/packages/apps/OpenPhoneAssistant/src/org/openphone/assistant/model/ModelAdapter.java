package org.openphone.assistant.model;

public interface ModelAdapter {
    String name();
    String providerDisplayName();
    String modelName();
    boolean usesCloud();
    String privacyDisclosure();
    String runTask(String taskId, String userGoal, ToolExecutor executor);
    void cancel();

    interface ToolExecutor {
        String callTool(String toolName, String argumentsJson);
        boolean isCancelled();
    }
}
