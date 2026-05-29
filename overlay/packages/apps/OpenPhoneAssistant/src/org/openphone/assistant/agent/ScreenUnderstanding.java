package org.openphone.assistant.agent;

import org.openphone.assistant.policy.AuditLog;

public final class ScreenUnderstanding {
    private final AuditLog mAuditLog;

    public ScreenUnderstanding(AuditLog auditLog) {
        mAuditLog = auditLog;
    }

    public String snapshot(String taskId) {
        mAuditLog.recordScreenRead(taskId);
        return "{"
                + "\"foreground_app\":\"unknown\","
                + "\"activity\":\"unknown\","
                + "\"visible_text\":[],"
                + "\"interactive_elements\":[],"
                + "\"notifications\":[],"
                + "\"risk_flags\":[\"framework_not_connected\"]"
                + "}";
    }
}

