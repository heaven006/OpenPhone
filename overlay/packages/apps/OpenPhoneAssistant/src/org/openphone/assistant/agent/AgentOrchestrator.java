package org.openphone.assistant.agent;

import org.openphone.assistant.policy.AuditLog;

public final class AgentOrchestrator {
    private final TaskRegistry mTaskRegistry;
    private final ScreenUnderstanding mScreenUnderstanding;
    private final ActionExecution mActionExecution;
    private final AuditLog mAuditLog;

    public AgentOrchestrator(
            TaskRegistry taskRegistry,
            ScreenUnderstanding screenUnderstanding,
            ActionExecution actionExecution,
            AuditLog auditLog) {
        mTaskRegistry = taskRegistry;
        mScreenUnderstanding = screenUnderstanding;
        mActionExecution = actionExecution;
        mAuditLog = auditLog;
    }

    public String startTask(String taskRequestJson) {
        AgentTask task = mTaskRegistry.create(taskRequestJson);
        mAuditLog.recordTaskStarted(task.id());
        return "{\"task_id\":\"" + task.id() + "\",\"status\":\"started\"}";
    }

    public String getScreenContext(String taskId) {
        if (!mTaskRegistry.exists(taskId)) {
            return "{\"error\":\"unknown_task\"}";
        }
        return mScreenUnderstanding.snapshot(taskId);
    }

    public String executeAction(String taskId, String actionRequestJson) {
        if (!mTaskRegistry.exists(taskId)) {
            return "{\"error\":\"unknown_task\"}";
        }
        return mActionExecution.execute(taskId, actionRequestJson);
    }
}

