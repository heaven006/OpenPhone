package org.openphone.assistant.agent;

public final class AgentTask {
    private final String mId;
    private final String mRequestJson;

    public AgentTask(String id, String requestJson) {
        mId = id;
        mRequestJson = requestJson;
    }

    public String id() {
        return mId;
    }

    public String requestJson() {
        return mRequestJson;
    }
}

