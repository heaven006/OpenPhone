package org.openphone.assistant;

interface IOpenPhoneAssistant {
    String getStatus();
    String startTask(String taskRequestJson);
    String getScreenContext(String taskId);
    String executeAction(String taskId, String actionRequestJson);
    String confirmAction(String pendingActionId, boolean approved);
    String evaluateCapability(String capabilityId);
    String getAuditLog(int maxEvents);
}
