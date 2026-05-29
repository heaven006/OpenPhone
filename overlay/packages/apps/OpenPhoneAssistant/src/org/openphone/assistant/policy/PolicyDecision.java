package org.openphone.assistant.policy;

public final class PolicyDecision {
    public enum Action {
        ALLOW_TASK_SCOPED,
        REQUIRE_CONFIRMATION,
        REQUIRE_EXPLICIT_CONFIRMATION,
        DENY
    }

    private final Action mAction;
    private final CapabilityRisk mRisk;
    private final String mReason;

    public PolicyDecision(Action action, CapabilityRisk risk, String reason) {
        mAction = action;
        mRisk = risk;
        mReason = reason;
    }

    public Action action() {
        return mAction;
    }

    public CapabilityRisk risk() {
        return mRisk;
    }

    public String reason() {
        return mReason;
    }

    public String toWireString() {
        return mAction.name() + ":" + mRisk.name() + ":" + mReason;
    }
}

