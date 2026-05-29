package org.openphone.assistant.policy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class PolicyEngine {
    private static final Map<String, CapabilityRisk> CAPABILITY_RISKS = buildCapabilityRisks();

    public PolicyDecision evaluate(String capabilityId) {
        CapabilityRisk risk = CAPABILITY_RISKS.get(capabilityId);
        if (risk == null) {
            return new PolicyDecision(
                    PolicyDecision.Action.DENY,
                    CapabilityRisk.UNKNOWN,
                    "unknown_capability");
        }

        switch (risk) {
            case LOW:
                return new PolicyDecision(
                        PolicyDecision.Action.ALLOW_TASK_SCOPED,
                        risk,
                        "low_risk_task_scope");
            case MEDIUM:
                return new PolicyDecision(
                        PolicyDecision.Action.REQUIRE_CONFIRMATION,
                        risk,
                        "medium_risk_confirmation_required");
            case HIGH:
                return new PolicyDecision(
                        PolicyDecision.Action.REQUIRE_EXPLICIT_CONFIRMATION,
                        risk,
                        "high_risk_explicit_confirmation_required");
            case UNKNOWN:
            default:
                return new PolicyDecision(
                        PolicyDecision.Action.DENY,
                        CapabilityRisk.UNKNOWN,
                        "unknown_risk");
        }
    }

    private static Map<String, CapabilityRisk> buildCapabilityRisks() {
        Map<String, CapabilityRisk> risks = new HashMap<>();
        risks.put("screen.read.visible", CapabilityRisk.LOW);
        risks.put("screen.capture", CapabilityRisk.MEDIUM);
        risks.put("input.perform", CapabilityRisk.MEDIUM);
        risks.put("apps.launch", CapabilityRisk.LOW);
        risks.put("tasks.observe", CapabilityRisk.LOW);
        risks.put("notifications.read", CapabilityRisk.MEDIUM);
        risks.put("notifications.act", CapabilityRisk.MEDIUM);
        risks.put("clipboard.read", CapabilityRisk.MEDIUM);
        risks.put("clipboard.write", CapabilityRisk.LOW);
        risks.put("files.read.scoped", CapabilityRisk.MEDIUM);
        risks.put("contacts.read", CapabilityRisk.MEDIUM);
        risks.put("calendar.read", CapabilityRisk.MEDIUM);
        risks.put("calendar.write", CapabilityRisk.MEDIUM);
        risks.put("messages.draft", CapabilityRisk.MEDIUM);
        risks.put("messages.send", CapabilityRisk.HIGH);
        risks.put("calls.place", CapabilityRisk.HIGH);
        risks.put("settings.read", CapabilityRisk.LOW);
        risks.put("settings.write", CapabilityRisk.MEDIUM);
        risks.put("background.run", CapabilityRisk.MEDIUM);
        risks.put("network.use", CapabilityRisk.MEDIUM);
        risks.put("account.access", CapabilityRisk.HIGH);
        return Collections.unmodifiableMap(risks);
    }
}

