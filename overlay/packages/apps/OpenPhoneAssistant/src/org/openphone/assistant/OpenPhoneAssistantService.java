package org.openphone.assistant;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.openphone.OpenPhoneAgentManager;
import android.util.Log;

import org.openphone.assistant.agent.ActionExecution;
import org.openphone.assistant.agent.AgentOrchestrator;
import org.openphone.assistant.agent.ScreenUnderstanding;
import org.openphone.assistant.agent.TaskRegistry;
import org.openphone.assistant.policy.AuditLog;
import org.openphone.assistant.policy.PolicyDecision;
import org.openphone.assistant.policy.PolicyEngine;

public final class OpenPhoneAssistantService extends Service {
    private static final String TAG = "OpenPhoneAssistant";

    private OpenPhoneAgentManager mAgentManager;
    private PointerOverlayController mPointerOverlayController;
    private String mNotificationTaskId;

    private final PolicyEngine mPolicyEngine = new PolicyEngine();
    private final AuditLog mAuditLog = new AuditLog(TAG);
    private final TaskRegistry mTaskRegistry = new TaskRegistry();
    private final ScreenUnderstanding mScreenUnderstanding = new ScreenUnderstanding(mAuditLog);
    private final ActionExecution mActionExecution = new ActionExecution(mPolicyEngine, mAuditLog);
    private final AgentOrchestrator mOrchestrator =
            new AgentOrchestrator(mTaskRegistry, mScreenUnderstanding, mActionExecution, mAuditLog);
    private final IOpenPhoneAssistant.Stub mBinder = new IOpenPhoneAssistant.Stub() {
        @Override
        public String getStatus() {
            if (mAgentManager == null) {
                return "assistant.ready framework.unavailable";
            }
            return "assistant.ready " + mAgentManager.getServiceStatus();
        }

        @Override
        public String startTask(String taskRequestJson) throws RemoteException {
            if (mAgentManager != null) {
                return mAgentManager.startTask(taskRequestJson);
            }
            return mOrchestrator.startTask(taskRequestJson);
        }

        @Override
        public String getScreenContext(String taskId) throws RemoteException {
            if (mAgentManager != null) {
                return mAgentManager.getScreenContext(taskId);
            }
            return mOrchestrator.getScreenContext(taskId);
        }

        @Override
        public String executeAction(String taskId, String actionRequestJson) throws RemoteException {
            if (mAgentManager != null) {
                return mAgentManager.executeAction(taskId, actionRequestJson);
            }
            return mOrchestrator.executeAction(taskId, actionRequestJson);
        }

        @Override
        public String confirmAction(String pendingActionId, boolean approved)
                throws RemoteException {
            if (mAgentManager != null) {
                return mAgentManager.confirmAction(pendingActionId, approved);
            }
            return "{\"status\":\"denied\",\"reason\":\"framework_unavailable\"}";
        }

        @Override
        public String evaluateCapability(String capabilityId) throws RemoteException {
            if (mAgentManager != null) {
                return mAgentManager.evaluateCapability(capabilityId);
            }
            PolicyDecision decision = mPolicyEngine.evaluate(capabilityId);
            mAuditLog.recordPolicyDecision(capabilityId, decision);
            return decision.toWireString();
        }

        @Override
        public String getAuditLog(int maxEvents) throws RemoteException {
            if (mAgentManager != null) {
                return mAgentManager.getAuditLog(maxEvents);
            }
            return "{\"events\":[],\"source\":\"assistant.framework_unavailable\"}";
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mPointerOverlayController = new PointerOverlayController(this);
        mAgentManager = getSystemService(OpenPhoneAgentManager.class);
        if (mAgentManager == null) {
            Log.w(TAG, "OpenPhone framework service is not available");
            OpenPhoneNotificationController.showReady(this);
            return;
        }
        Log.i(TAG, "OpenPhone framework service status: " + mAgentManager.getServiceStatus());
        Log.i(TAG, "OpenPhone assistant service created");
        OpenPhoneNotificationController.showReady(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (OpenPhoneNotificationController.ACTION_START.equals(action)) {
            mNotificationTaskId = startNotificationTask();
            mPointerOverlayController.show(mNotificationTaskId);
            OpenPhoneNotificationController.showActive(this, mNotificationTaskId);
            return START_STICKY;
        }
        if (OpenPhoneNotificationController.ACTION_STOP.equals(action)) {
            stopNotificationTask();
            return START_STICKY;
        }
        OpenPhoneNotificationController.showReady(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private String startNotificationTask() {
        if (mAgentManager == null) {
            return null;
        }
        String response = mAgentManager.startTask("{"
                + "\"goal\":\"Notification-triggered OpenPhone task\","
                + "\"user_visible\":true,"
                + "\"background_allowed\":false,"
                + "\"approved_capabilities\":[\"screen.read.visible\",\"tasks.observe\"]"
                + "}");
        int marker = response.indexOf("\"task_id\"");
        if (marker < 0) {
            return null;
        }
        int colon = response.indexOf(':', marker);
        int firstQuote = response.indexOf('"', colon + 1);
        int secondQuote = response.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return null;
        }
        return response.substring(firstQuote + 1, secondQuote);
    }

    private void stopNotificationTask() {
        if (mAgentManager != null && mNotificationTaskId != null) {
            mAgentManager.stopTask(mNotificationTaskId,
                    "{\"reason\":\"user_stopped_from_notification\"}");
        }
        mNotificationTaskId = null;
        if (mPointerOverlayController != null) {
            mPointerOverlayController.hide();
        }
        OpenPhoneNotificationController.showReady(this);
    }
}
