package org.openphone.assistant.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TaskRegistry {
    private final Map<String, AgentTask> mTasks = new HashMap<>();

    public synchronized AgentTask create(String taskRequestJson) {
        String id = UUID.randomUUID().toString();
        AgentTask task = new AgentTask(id, taskRequestJson);
        mTasks.put(id, task);
        return task;
    }

    public synchronized boolean exists(String taskId) {
        return mTasks.containsKey(taskId);
    }
}

