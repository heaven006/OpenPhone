package org.openphone.assistant.policy;

import android.util.Log;

public final class AuditLog {
    private final String mTag;

    public AuditLog(String tag) {
        mTag = tag;
    }

    public void recordPolicyDecision(String capabilityId, PolicyDecision decision) {
        Log.i(mTag, "policy capability=" + capabilityId + " decision=" + decision.toWireString());
    }

    public void recordTaskStarted(String taskId) {
        Log.i(mTag, "audit event=task_started task=" + taskId);
    }

    public void recordScreenRead(String taskId) {
        Log.i(mTag, "audit event=screen_context_read task=" + taskId);
    }

    public void recordAction(String taskId, String eventType, String capabilityId) {
        Log.i(mTag, "audit event=" + eventType + " task=" + taskId + " capability=" + capabilityId);
    }
}
