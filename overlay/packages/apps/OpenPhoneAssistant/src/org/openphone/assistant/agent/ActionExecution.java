package org.openphone.assistant.agent;

import org.openphone.assistant.policy.AuditLog;
import org.openphone.assistant.policy.PolicyDecision;
import org.openphone.assistant.policy.PolicyEngine;

public final class ActionExecution {
    private final PolicyEngine mPolicyEngine;
    private final AuditLog mAuditLog;

    public ActionExecution(PolicyEngine policyEngine, AuditLog auditLog) {
        mPolicyEngine = policyEngine;
        mAuditLog = auditLog;
    }

    public String execute(String taskId, String actionRequestJson) {
        String capabilityId = capabilityFromAction(actionRequestJson);
        PolicyDecision decision = mPolicyEngine.evaluate(capabilityId);
        mAuditLog.recordPolicyDecision(capabilityId, decision);

        switch (decision.action()) {
            case ALLOW_TASK_SCOPED:
                mAuditLog.recordAction(taskId, "action_executed", capabilityId);
                return "{\"status\":\"executed\",\"capability\":\"" + capabilityId + "\"}";
            case REQUIRE_CONFIRMATION:
            case REQUIRE_EXPLICIT_CONFIRMATION:
                mAuditLog.recordAction(taskId, "action_blocked", capabilityId);
                return "{\"status\":\"confirmation_required\",\"decision\":\""
                        + decision.toWireString() + "\"}";
            case DENY:
            default:
                mAuditLog.recordAction(taskId, "action_blocked", capabilityId);
                return "{\"status\":\"denied\",\"decision\":\"" + decision.toWireString() + "\"}";
        }
    }

    private static String capabilityFromAction(String actionRequestJson) {
        if (contains(actionRequestJson, "\"type\":\"open_app\"")
                || contains(actionRequestJson, "\"type\": \"open_app\"")) {
            return "apps.launch";
        }
        if (contains(actionRequestJson, "\"type\":\"back\"")
                || contains(actionRequestJson, "\"type\":\"home\"")
                || contains(actionRequestJson, "\"type\":\"recents\"")) {
            return "input.perform";
        }
        if (contains(actionRequestJson, "\"capability\":\"")) {
            int start = actionRequestJson.indexOf("\"capability\":\"") + "\"capability\":\"".length();
            int end = actionRequestJson.indexOf('"', start);
            if (end > start) {
                return actionRequestJson.substring(start, end);
            }
        }
        return "input.perform";
    }

    private static boolean contains(String value, String token) {
        return value != null && value.contains(token);
    }
}

