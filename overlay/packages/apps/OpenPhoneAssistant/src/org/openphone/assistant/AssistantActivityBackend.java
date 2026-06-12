package org.openphone.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.openphone.OpenPhoneAgentManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openphone.assistant.agent.FrameworkToolExecutor;
import org.openphone.assistant.agent.AuditEvidenceExporter;
import org.openphone.assistant.agent.TrajectoryRecorder;
import org.openphone.assistant.actions.ActionRegistry;
import org.openphone.assistant.context.ContextIndexStore;
import org.openphone.assistant.model.LocalHeuristicModelAdapter;
import org.openphone.assistant.model.ModelEndpointConfig;
import org.openphone.assistant.model.ModelAdapter;
import org.openphone.assistant.model.OpenAiRealtimeAdapter;
import org.openphone.assistant.model.OpenAiSpeechTranscriber;
import org.openphone.assistant.orchestrator.OpenPhoneOrchestrator;
import org.openphone.assistant.orchestrator.OperatingMode;
import org.openphone.assistant.orchestrator.OrchestratorDecision;
import org.openphone.assistant.ota.OtaUpdateClient;
import org.openphone.assistant.policy.AppCapabilityPolicy;
import org.openphone.assistant.watchers.OpenPhoneWatcherScheduler;

import java.io.IOException;
import java.util.Locale;

public class AssistantActivityBackend extends ComponentActivity {
    public interface ComposeStateCallbacks {
        void setTaskStatus(String text);
        void setContextText(String text);
        void setAuditText(String text);
        void setModelDisclosure(String text);
        void setModelConfig(boolean useRealtime, boolean useBroker, String apiKey,
                String brokerUrl, String brokerToken);
        void setOtaStatus(String text, boolean canDownload);
        void setRuntimeStatus(String text, String activeTaskId, boolean running,
                boolean listening);
        void setAutonomyMode(String mode);
        void setComposerText(String text);
        void addConversationMessage(String speaker, String message);
        void setPendingConfirmation(String actionId, String toolName, String summary);
        void clearPendingConfirmation();
        void showChat();
    }

    private static final String EXTRA_DEV_OPENAI_API_KEY =
            "org.openphone.assistant.extra.DEV_OPENAI_API_KEY";
    private static final String EXTRA_GOAL = "org.openphone.assistant.extra.GOAL";
    private static final String EXTRA_GOAL_BASE64 =
            "org.openphone.assistant.extra.GOAL_BASE64";
    private static final String EXTRA_RUN = "org.openphone.assistant.extra.RUN";
    static final String EXTRA_START_VOICE =
            "org.openphone.assistant.extra.START_VOICE";
    static final String EXTRA_STOP_AGENT =
            "org.openphone.assistant.extra.STOP_AGENT";
    static final String EXTRA_TOGGLE_AGENT =
            "org.openphone.assistant.extra.TOGGLE_AGENT";
    static final String EXTRA_CONFIRM_APPROVE =
            "org.openphone.assistant.extra.CONFIRM_APPROVE";
    static final String EXTRA_CONFIRM_DENY =
            "org.openphone.assistant.extra.CONFIRM_DENY";

    /**
     * Called from the system overlay (PointerOverlayController inline
     * Approve/Deny). Routes through AgentControlActivity so the live activity
     * state with mPendingActionId can fulfil the confirmation.
     */
    public static void confirmPendingFromOverlay(Context context, boolean approved) {
        Intent intent = new Intent(context, AgentControlActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(approved ? EXTRA_CONFIRM_APPROVE : EXTRA_CONFIRM_DENY, true);
        try {
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private static final int REQUEST_RECORD_AUDIO = 1001;
    // Cap is a safety net; the transcriber stops earlier on speech-end silence
    // (END_SILENCE_MILLIS in OpenAiSpeechTranscriber). Bumped from 12s — long
    // questions about a screen ("Can you see what I'm looking at and tell me…")
    // were hitting the cap mid-sentence.
    private static final int VOICE_CAPTURE_MILLIS = 30000;
    private static final String PREFS = "openphone_assistant";
    private static final String PREF_GRANT_INPUT = "grant_input";
    private static final String PREF_GRANT_SCREEN_CAPTURE = "grant_screen_capture";
    private static final String PREF_GRANT_CLIPBOARD = "grant_clipboard";
    private static final String PREF_GRANT_SHARE = "grant_share";
    private static final String PREF_GRANT_NETWORK = "grant_network";
    private static final String PREF_AUTONOMY_MODE = "autonomy_mode";
    private static final String SECURE_GRANT_INPUT = "openphone_task_grant_input";
    private static final String SECURE_GRANT_SCREEN_CAPTURE = "openphone_task_grant_screenshot";
    private static final String SECURE_GRANT_CLIPBOARD = "openphone_task_grant_clipboard";
    private static final String SECURE_GRANT_SHARE = "openphone_task_grant_share";
    private static final String SECURE_GRANT_NETWORK = "openphone_task_grant_network";
    private static final String SECURE_AUTONOMY_MODE = "openphone_autonomy_mode";
    private static final String SECURE_DEV_OPENAI_API_KEY = "openphone_dev_openai_api_key";
    private static AssistantActivityBackend sActiveControlRunner;
    private final OpenPhoneOrchestrator mOrchestrator = new OpenPhoneOrchestrator();
    private OpenPhoneAgentManager mAgentManager;
    private PointerOverlayController mPointerOverlayController;
    private ContextIndexStore mContextIndexStore;
    private ActionRegistry mActionRegistry;
    private String mActiveTaskId;
    private String mActiveTaskGoal;
    private String mPendingActionId;
    private String mPendingToolName;
    private JSONObject mPendingToolArguments;
    private String mPendingOneShotMessage;
    private volatile boolean mAgentRunCancelled;
    private boolean mIslandVoiceLaunch;
    private boolean mSuppressNextUserAppend;
    private int mAgentRunGeneration;
    private int mAuditRefreshGeneration;
    private int mVoiceRunGeneration;
    private int mChatRunGeneration;
    private Thread mAgentThread;
    private Thread mChatThread;
    private ModelAdapter mRunningModelAdapter;
    private ModelAdapter mRunningChatAdapter;
    private volatile OpenAiSpeechTranscriber mRunningSpeechTranscriber;
    private OtaUpdateClient.Update mLatestOtaUpdate;
    private boolean mListening;
    private String mComposeGoalText = "";
    private String mComposeActionJson = "";
    private String mComposeApiKey = "";
    private String mComposeBrokerUrl = "";
    private String mComposeBrokerToken = "";
    private String mComposeOtaFeedUrl = "";
    private String mComposeTaskText = "";
    private String mComposeContextText = "";
    private String mComposeAuditText = "";
    private String mComposeModelDisclosureText = "";
    private String mComposeOtaStatusText = "";
    private boolean mComposeUseBroker;
    private boolean mComposeUseRealtime;
    private boolean mComposeInputGrant = true;
    private boolean mComposeScreenCaptureGrant = true;
    private boolean mComposeClipboardGrant;
    private boolean mComposeShareGrant;
    private boolean mComposeNetworkGrant;
    private String mAutonomyMode = "reviewed";
    private boolean mComposeAdvancedVisible;
    private ComposeStateCallbacks mComposeStateCallbacks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAgentManager = getSystemService(OpenPhoneAgentManager.class);
        mPointerOverlayController = new PointerOverlayController(this);
        mContextIndexStore = new ContextIndexStore(this);
        mContextIndexStore.backfillChatHistoryIfNeeded();
        mActionRegistry = ActionRegistry.load();
        OpenPhoneWatcherScheduler.checkNow(this);
        OpenPhoneAccessibilityService.ensureEnabled(this);
        OpenPhoneNotificationListenerService.ensureEnabled(this);
        loadComposeDefaults();
        if (isControlSurface()) {
            applyDebugIntentExtras(getIntent());
            return;
        }
        OpenPhoneBootReceiver.applyOpenPhoneWallpaperIfNeeded(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isAdvancedVisible()) {
                    showChatSurface();
                    if (mComposeStateCallbacks != null) {
                        mComposeStateCallbacks.showChat();
                    }
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        setContentView(AssistantComposeHost.createView(this));
        setServiceIslandVisible(false);
        mPointerOverlayController.hide();
        refreshAll();
        applyDebugIntentExtras(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isControlSurface()) {
            return;
        }
        setServiceIslandVisible(false);
        if (!mIslandVoiceLaunch) {
            mPointerOverlayController.hide();
        }
        OpenPhoneAccessibilityService.ensureEnabled(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyDebugIntentExtras(intent);
    }

    @Override
    protected void onDestroy() {
        if (isControlSurface()) {
            super.onDestroy();
            return;
        }
        cancelAgentRun();
        if (mPointerOverlayController != null) {
            mPointerOverlayController.hide();
        }
        setServiceIslandVisible(true);
        super.onDestroy();
    }

    private void setServiceIslandVisible(boolean visible) {
        Intent intent = new Intent(this, OpenPhoneAssistantService.class);
        intent.setAction(visible
                ? OpenPhoneAssistantService.ACTION_SHOW_MIC_ISLAND
                : OpenPhoneAssistantService.ACTION_HIDE_ISLAND);
        try {
            startService(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private void applyDebugIntentExtras(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    startIslandVoiceAgent();
                }
            });
        }
        if (intent.getBooleanExtra(EXTRA_STOP_AGENT, false)) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    stopTask();
                    moveTaskToBack(true);
                }
            });
        }
        if (intent.getBooleanExtra(EXTRA_CONFIRM_APPROVE, false)) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    AssistantActivityBackend runner = sActiveControlRunner;
                    if (runner != null) {
                        runner.confirmPending(true);
                    } else {
                        confirmPending(true);
                    }
                    moveTaskToBack(true);
                }
            });
        }
        if (intent.getBooleanExtra(EXTRA_CONFIRM_DENY, false)) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    AssistantActivityBackend runner = sActiveControlRunner;
                    if (runner != null) {
                        runner.confirmPending(false);
                    } else {
                        confirmPending(false);
                    }
                    moveTaskToBack(true);
                }
            });
        }
        if (intent.getBooleanExtra(EXTRA_TOGGLE_AGENT, false)) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    AssistantActivityBackend runner = sActiveControlRunner;
                    if (runner != null && runner.isAgentOrVoiceActive()) {
                        runner.stopTask();
                        runner.moveTaskToBack(true);
                    } else if (isAgentOrVoiceActive()) {
                        stopTask();
                        moveTaskToBack(true);
                    } else {
                        startIslandVoiceAgent();
                    }
                }
            });
        }
        if (!debugIntentExtrasAllowed()) {
            return;
        }
        String apiKey = intent.getStringExtra(EXTRA_DEV_OPENAI_API_KEY);
        if (apiKey != null) {
            mComposeUseRealtime = true;
            mComposeUseBroker = false;
            mComposeApiKey = apiKey;
            refreshModelDisclosure();
        }
        String goal = debugGoalFromIntent(intent);
        if (goal != null) {
            setCurrentGoalText(goal);
        }
        if (intent.getBooleanExtra(EXTRA_RUN, false)) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    routeMessageFromCurrentMessage();
                }
            });
        }
    }

    private static boolean debugIntentExtrasAllowed() {
        return "userdebug".equals(Build.TYPE) || "eng".equals(Build.TYPE);
    }

    protected boolean isControlSurface() {
        return false;
    }

    private boolean isAgentOrVoiceActive() {
        return mListening || mActiveTaskId != null || mAgentThread != null;
    }

    private static String debugGoalFromIntent(Intent intent) {
        String encodedGoal = intent.getStringExtra(EXTRA_GOAL_BASE64);
        if (encodedGoal != null && !encodedGoal.isEmpty()) {
            try {
                return new String(Base64.decode(encodedGoal, Base64.DEFAULT),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return "";
            }
        }
        return intent.getStringExtra(EXTRA_GOAL);
    }

    private void postToUi(Runnable runnable) {
        getWindow().getDecorView().post(runnable);
    }

    public void setComposeStateCallbacks(ComposeStateCallbacks callbacks) {
        mComposeStateCallbacks = callbacks;
        pushComposeModelConfig();
        pushComposeAutonomyMode();
        refreshModelDisclosure();
    }

    public void onComposeComposerTextChanged(String text) {
        mComposeGoalText = text == null ? "" : text;
        updateComposerActionButton();
    }

    public void onComposeActionJsonChanged(String text) {
        mComposeActionJson = text == null ? "" : text;
    }

    public void onComposeModelConfigChanged(boolean useRealtime, boolean useBroker,
            String apiKey, String brokerUrl, String brokerToken) {
        mComposeUseRealtime = useRealtime;
        mComposeUseBroker = useBroker;
        mComposeApiKey = apiKey == null ? "" : apiKey;
        mComposeBrokerUrl = brokerUrl == null ? "" : brokerUrl;
        mComposeBrokerToken = brokerToken == null ? "" : brokerToken;
        refreshModelDisclosure();
    }

    public void onComposeGrantsChanged(boolean input, boolean screenCapture, boolean clipboard,
            boolean share, boolean network) {
        mComposeInputGrant = input;
        mComposeScreenCaptureGrant = screenCapture;
        mComposeClipboardGrant = clipboard;
        mComposeShareGrant = share;
        mComposeNetworkGrant = network;
        persistGrantDefault(PREF_GRANT_INPUT, SECURE_GRANT_INPUT, input);
        persistGrantDefault(PREF_GRANT_SCREEN_CAPTURE, SECURE_GRANT_SCREEN_CAPTURE,
                screenCapture);
        persistGrantDefault(PREF_GRANT_CLIPBOARD, SECURE_GRANT_CLIPBOARD, clipboard);
        persistGrantDefault(PREF_GRANT_SHARE, SECURE_GRANT_SHARE, share);
        persistGrantDefault(PREF_GRANT_NETWORK, SECURE_GRANT_NETWORK, network);
    }

    public void onComposeAutonomyModeChanged(String mode) {
        mAutonomyMode = cleanAutonomyMode(mode);
        grantPrefs().edit().putString(PREF_AUTONOMY_MODE, mAutonomyMode).apply();
        try {
            Settings.Secure.putString(getContentResolver(), SECURE_AUTONOMY_MODE, mAutonomyMode);
        } catch (SecurityException ignored) {
        }
        pushIslandAutonomy();
        updateIsland(autonomyModeLabel(mAutonomyMode) + " mode");
        setTaskText("Autonomy mode: " + autonomyModeLabel(mAutonomyMode));
    }

    public void onComposeOtaFeedUrlChanged(String feedUrl) {
        mComposeOtaFeedUrl = feedUrl == null ? "" : feedUrl;
    }

    public void onComposeSend() {
        routeMessageFromCurrentMessage();
    }

    public void onComposeMic() {
        startVoiceAgent();
    }

    public void onComposeStop() {
        if (mActiveTaskId == null && mAgentThread == null && mChatThread != null) {
            cancelChatRun();
            setTaskText("Chat stopped");
            updateIsland("Ready");
            updateComposerActionButton();
            return;
        }
        stopTask();
    }

    public void onComposeShowAdvanced() {
        mComposeAdvancedVisible = true;
        showAdvancedSurface();
    }

    public void onComposeShowChat() {
        mComposeAdvancedVisible = false;
        showChatSurface();
    }

    public void onComposeStartTask() {
        startTask();
    }

    public void onComposeRunAgent() {
        runAgent();
    }

    public void onComposeRefresh() {
        refreshAll();
    }

    public void onComposeCheckOta() {
        checkOtaFeed();
    }

    public void onComposeDownloadOta() {
        downloadLatestOta();
    }

    public void onComposeApprove() {
        confirmPending(true);
    }

    public void onComposeDeny() {
        confirmPending(false);
    }

    public void onComposeReadScreen() {
        readScreenContext();
    }

    public void onComposeReadScreenshot() {
        readScreenshotContext();
    }

    public void onComposeExecuteBack() {
        executeBack();
    }

    public void onComposeRunRawAction() {
        requestAction();
    }

    public void onComposeExportTrace() {
        exportLatestTrajectory();
    }

    public void onComposeExportAudit() {
        exportAuditEvidence();
    }

    private String currentGoalText() {
        return mComposeGoalText == null ? "" : mComposeGoalText;
    }

    private void setCurrentGoalText(String text) {
        mComposeGoalText = text == null ? "" : text;
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setComposerText(mComposeGoalText);
        }
        updateComposerActionButton();
    }

    private String currentActionJson() {
        return mComposeActionJson == null ? "" : mComposeActionJson;
    }

    private boolean inputGrantEnabled() {
        return mComposeInputGrant;
    }

    private boolean screenCaptureGrantEnabled() {
        return mComposeScreenCaptureGrant;
    }

    private boolean clipboardGrantEnabled() {
        return mComposeClipboardGrant;
    }

    private boolean shareGrantEnabled() {
        return mComposeShareGrant;
    }

    private boolean networkGrantEnabled() {
        return mComposeNetworkGrant;
    }

    private boolean useRealtimeModel() {
        return mComposeUseRealtime;
    }

    private boolean useBrokerModel() {
        return mComposeUseBroker;
    }

    private void setTaskText(String text) {
        mComposeTaskText = text == null ? "" : text;
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setTaskStatus(mComposeTaskText);
        }
    }

    private void setContextText(String text) {
        mComposeContextText = text == null ? "" : text;
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setContextText(mComposeContextText);
        }
    }

    private void setAuditText(String text) {
        mComposeAuditText = text == null ? "" : text;
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setAuditText(mComposeAuditText);
        }
    }

    private void setModelDisclosureText(String text) {
        mComposeModelDisclosureText = text == null ? "" : text;
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setModelDisclosure(mComposeModelDisclosureText);
        }
    }

    private void pushComposeModelConfig() {
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setModelConfig(mComposeUseRealtime, mComposeUseBroker,
                    mComposeApiKey, mComposeBrokerUrl, mComposeBrokerToken);
        }
    }

    private void pushComposeAutonomyMode() {
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setAutonomyMode(mAutonomyMode);
        }
    }

    private void setOtaStatusText(String text) {
        mComposeOtaStatusText = text == null ? "" : text;
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setOtaStatus(mComposeOtaStatusText, mLatestOtaUpdate != null);
        }
    }

    private void loadComposeDefaults() {
        mComposeInputGrant = grantDefault(PREF_GRANT_INPUT, SECURE_GRANT_INPUT, true);
        mComposeScreenCaptureGrant = grantDefault(PREF_GRANT_SCREEN_CAPTURE,
                SECURE_GRANT_SCREEN_CAPTURE, true);
        mComposeClipboardGrant = grantDefault(PREF_GRANT_CLIPBOARD, SECURE_GRANT_CLIPBOARD,
                false);
        mComposeShareGrant = grantDefault(PREF_GRANT_SHARE, SECURE_GRANT_SHARE, false);
        mComposeNetworkGrant = grantDefault(PREF_GRANT_NETWORK, SECURE_GRANT_NETWORK, false);
        mAutonomyMode = autonomyModeDefault();
        pushIslandAutonomy();
        if (debugIntentExtrasAllowed()) {
            String apiKey = Settings.Secure.getString(getContentResolver(),
                    SECURE_DEV_OPENAI_API_KEY);
            mComposeApiKey = apiKey == null ? "" : apiKey;
            if (!mComposeApiKey.isEmpty()) {
                mComposeUseRealtime = true;
                mComposeUseBroker = false;
            }
        }
    }

    private SharedPreferences grantPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String autonomyModeDefault() {
        String fallback = grantPrefs().getString(PREF_AUTONOMY_MODE, "reviewed");
        String secure = Settings.Secure.getString(getContentResolver(), SECURE_AUTONOMY_MODE);
        return cleanAutonomyMode(secure == null || secure.trim().isEmpty() ? fallback : secure);
    }

    private void reloadAutonomyMode() {
        String previous = mAutonomyMode;
        mAutonomyMode = autonomyModeDefault();
        if (!mAutonomyMode.equals(previous)) {
            pushIslandAutonomy();
            pushComposeAutonomyMode();
        }
    }

    private static String cleanAutonomyMode(String mode) {
        String clean = mode == null ? "" : mode.trim().toLowerCase(Locale.US);
        if ("yolo".equals(clean)) {
            return "yolo";
        }
        if ("dry_run".equals(clean) || "dry run".equals(clean) || "dry-run".equals(clean)) {
            return "dry_run";
        }
        return "reviewed";
    }

    private static String autonomyModeLabel(String mode) {
        if ("yolo".equals(mode)) {
            return "YOLO";
        }
        if ("dry_run".equals(mode)) {
            return "Dry run";
        }
        return "Reviewed";
    }

    private boolean grantDefault(String prefKey, String secureKey, boolean defaultChecked) {
        int fallback = grantPrefs().getBoolean(prefKey, defaultChecked) ? 1 : 0;
        return Settings.Secure.getInt(getContentResolver(), secureKey, fallback) == 1;
    }

    private void writeSecureGrantDefault(String secureKey, boolean enabled) {
        try {
            Settings.Secure.putInt(getContentResolver(), secureKey, enabled ? 1 : 0);
        } catch (SecurityException ignored) {
            grantPrefs();
        }
    }

    private void persistGrantDefault(String prefKey, String secureKey, boolean enabled) {
        grantPrefs().edit().putBoolean(prefKey, enabled).apply();
        writeSecureGrantDefault(secureKey, enabled);
    }

    private void updateComposerActionButton() {
    }

    private void refreshAll() {
        if (mAgentManager == null) {
            updateIsland("Assistant unavailable");
            setTaskText("OpenPhone system service is unavailable.");
            setContextText("");
            setAuditText("");
            return;
        }
        updateIsland(statusSummary(mAgentManager.getServiceStatus()));
        refreshAudit();
    }

    private void refreshModelDisclosure() {
        ModelAdapter adapter = selectedModelAdapter(modelEndpointConfig());
        String disclosure = modelRunDisclosure(adapter);
        if (adapter.usesCloud()) {
            ModelEndpointConfig endpointConfig = modelEndpointConfig();
            disclosure += "\n\nVoice command: "
                    + (endpointConfig.isBrokerMode()
                            ? endpointConfig.providerDisplayName()
                            : OpenAiSpeechTranscriber.providerDisplayName())
                    + " / " + OpenAiSpeechTranscriber.modelName()
                    + ". " + voicePrivacyDisclosure(endpointConfig);
        }
        setModelDisclosureText(disclosure);
    }

    private ModelAdapter selectedModelAdapter(ModelEndpointConfig endpointConfig) {
        return useRealtimeModel()
                ? new OpenAiRealtimeAdapter(endpointConfig)
                : new LocalHeuristicModelAdapter();
    }

    private ModelEndpointConfig modelEndpointConfig() {
        if (useBrokerModel()) {
            return ModelEndpointConfig.broker(mComposeBrokerUrl, mComposeBrokerToken);
        }
        String apiKey = mComposeApiKey;
        if (apiKey.isEmpty() && debugIntentExtrasAllowed()) {
            apiKey = Settings.Secure.getString(getContentResolver(), SECURE_DEV_OPENAI_API_KEY);
        }
        return ModelEndpointConfig.directOpenAi(apiKey == null ? "" : apiKey);
    }

    private static String modelRunDisclosure(ModelAdapter adapter) {
        return "Provider: " + adapter.providerDisplayName()
                + "\nModel: " + adapter.modelName()
                + "\nCloud model: " + (adapter.usesCloud() ? "yes" : "no")
                + "\n" + adapter.privacyDisclosure();
    }

    private static String voicePrivacyDisclosure(ModelEndpointConfig endpointConfig) {
        if (endpointConfig != null && endpointConfig.isBrokerMode()) {
            return "Voice start records a short command and sends it to the configured "
                    + "OpenPhone model broker for transcription. The transcript becomes the "
                    + "task text; audio is not stored by OpenPhone.";
        }
        return OpenAiSpeechTranscriber.privacyDisclosure();
    }

    private void startVoiceAgent() {
        if (mListening) {
            return;
        }
        if (!modelEndpointConfig().isConfigured()) {
            setTaskText("Model setup is missing. Open Developer settings and add a "
                    + "broker token or development API key.");
            updateIsland("Setup needed");
            if (mIslandVoiceLaunch) {
                finishIslandVoiceLaunch();
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        listenThenRun();
        if (mIslandVoiceLaunch) {
            getWindow().getDecorView().postDelayed(new Runnable() {
                @Override
                public void run() {
                    moveTaskToBack(true);
                }
            }, 250);
        }
    }

    private void startIslandVoiceAgent() {
        mIslandVoiceLaunch = true;
        startVoiceAgent();
    }

    private void finishIslandVoiceLaunch() {
        mIslandVoiceLaunch = false;
        moveTaskToBack(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            listenThenRun();
        } else {
            setTaskText("Microphone permission is needed before I can listen.");
            updateIsland("Mic blocked");
        }
    }

    private void listenThenRun() {
        final ModelEndpointConfig endpointConfig = modelEndpointConfig();
        if (isControlSurface()) {
            sActiveControlRunner = this;
        }
        mListening = true;
        updateComposerActionButton();
        final int voiceGeneration = ++mVoiceRunGeneration;
        setTaskText("Listening...\n\n" + voicePrivacyDisclosure(endpointConfig)
                + "\nProvider: " + (endpointConfig.isBrokerMode()
                        ? endpointConfig.providerDisplayName()
                        : OpenAiSpeechTranscriber.providerDisplayName())
                + "\nModel: " + OpenAiSpeechTranscriber.modelName());
        updateIsland("Listening...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = "";
                String error = null;
                OpenAiSpeechTranscriber transcriber = new OpenAiSpeechTranscriber(endpointConfig);
                mRunningSpeechTranscriber = transcriber;
                try {
                    text = transcriber.recordAndTranscribe(VOICE_CAPTURE_MILLIS);
                } catch (IOException e) {
                    error = e.getMessage();
                } finally {
                    if (mRunningSpeechTranscriber == transcriber) {
                        mRunningSpeechTranscriber = null;
                    }
                }
                final String finalText = text;
                final String finalError = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (voiceGeneration != mVoiceRunGeneration) {
                            return;
                        }
                        mListening = false;
                        updateComposerActionButton();
                        if (mIslandVoiceLaunch) {
                            mIslandVoiceLaunch = false;
                        }
                        if (finalError != null) {
                            if (sActiveControlRunner == AssistantActivityBackend.this) {
                                sActiveControlRunner = null;
                            }
                            setTaskText("I couldn't hear the task.\n\n" + finalError);
                            updateIsland("Try again");
                            return;
                        }
                        if (finalText == null || finalText.trim().isEmpty()) {
                            if (sActiveControlRunner == AssistantActivityBackend.this) {
                                sActiveControlRunner = null;
                            }
                            setTaskText("I didn't catch that. Tap Speak and try again.");
                            updateIsland("Try again");
                            return;
                        }
                        String transcript = finalText.trim();
                        setCurrentGoalText(transcript);
                        mPointerOverlayController.showTranscript(transcript);
                        getWindow().getDecorView().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (voiceGeneration == mVoiceRunGeneration) {
                                    routeMessageFromCurrentMessage();
                                }
                            }
                        }, 700);
                    }
                });
            }
        }, "OpenPhoneVoiceCommand").start();
    }

    private void startAgentFromCurrentGoal() {
        String goal = currentGoalText().trim();
        if (goal.isEmpty()) {
            setTaskText("Tell me what to do first.");
            updateIsland("Waiting for task");
            return;
        }
        if (mSuppressNextUserAppend) {
            mSuppressNextUserAppend = false;
        } else {
            appendConversation("You", goal);
        }
        if (mAgentManager == null) {
            refreshAll();
            return;
        }
        if (useRealtimeModel() && !modelEndpointConfig().isConfigured()) {
            String setupMessage = "Model setup is missing. Open Developer settings and add a "
                    + "broker token or development API key.";
            setTaskText(setupMessage);
            appendConversation("OpenPhone", setupMessage);
            updateIsland("Setup needed");
            setCurrentGoalText("");
            return;
        }
        if (!useRealtimeModel() && !LocalHeuristicModelAdapter.canHandleTask(goal)) {
            String localOnlyMessage = "I did not run that task. The local development adapter "
                    + "only handles simple Settings/Home/Back commands. Enable the "
                    + "OpenPhone model broker or development API key for full phone-agent tasks.";
            setTaskText(localOnlyMessage);
            appendConversation("OpenPhone", localOnlyMessage);
            updateIsland("Setup needed");
            setCurrentGoalText("");
            return;
        }
        if (mActiveTaskId != null && mAgentThread != null) {
            goal = continuationGoal(goal);
        }
        startTaskThenRunAgent(goal);
        setCurrentGoalText("");
    }

    private void startChatFromCurrentMessage() {
        final String message = currentGoalText().trim();
        if (message.isEmpty()) {
            setTaskText("Ask me anything, or tell me what to do on this phone.");
            updateIsland("Ready");
            return;
        }
        setCurrentGoalText("");
        startChatMessage(message, false);
    }

    private void startChatMessage(final String message, boolean alreadyAppended) {
        if (!alreadyAppended) {
            appendConversation("You", message);
        }
        cancelChatRun();
        mAgentRunCancelled = false;
        final int chatGeneration = ++mChatRunGeneration;
        final ModelAdapter adapter = selectedModelAdapter(modelEndpointConfig());
        mRunningChatAdapter = adapter;
        setTaskText("Thinking...");
        updateIsland("Thinking");
        Thread chatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final String reply = adapter.chat(message);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (chatGeneration != mChatRunGeneration) {
                            return;
                        }
                        mChatThread = null;
                        mRunningChatAdapter = null;
                        appendConversation("OpenPhone", reply);
                        setTaskText("OpenPhone is ready.");
                        updateIsland("Ready");
                        if (reply != null && !reply.trim().isEmpty()
                                && mPointerOverlayController != null) {
                            mPointerOverlayController.showReply(reply);
                        }
                        updateComposerActionButton();
                    }
                });
            }
        }, "OpenPhoneChat");
        mChatThread = chatThread;
        updateComposerActionButton();
        chatThread.start();
    }

    private void routeMessageFromCurrentMessage() {
        final String message = currentGoalText().trim();
        if (message.isEmpty()) {
            setTaskText("Ask me anything, or tell me what to do on this phone.");
            updateIsland("Ready");
            return;
        }
        final OperatingMode operatingMode = currentOperatingMode();
        final boolean hasActiveTask = mActiveTaskId != null || mAgentThread != null;
        final String recentConversation = mContextIndexStore == null
                ? "[]" : mContextIndexStore.recentConversationJson(10);
        appendConversation("You", message);
        setCurrentGoalText("");
        cancelChatRun();
        mAgentRunCancelled = false;
        final int chatGeneration = ++mChatRunGeneration;
        final ModelAdapter adapter = selectedModelAdapter(modelEndpointConfig());
        mRunningChatAdapter = adapter;
        setTaskText("Thinking...");
        updateIsland("Thinking");
        Thread chatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final OrchestratorDecision decision = mOrchestrator.decide(adapter, message,
                        hasActiveTask, operatingMode, recentConversation);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (chatGeneration != mChatRunGeneration) {
                            return;
                        }
                        dispatchOrchestratorDecision(message, decision);
                    }
                });
            }
        }, "OpenPhoneOrchestrator");
        mChatThread = chatThread;
        updateComposerActionButton();
        chatThread.start();
    }

    private OperatingMode currentOperatingMode() {
        reloadAutonomyMode();
        if ("yolo".equals(mAutonomyMode)) {
            return OperatingMode.YOLO;
        }
        if ("dry_run".equals(mAutonomyMode)) {
            return OperatingMode.DRY_RUN;
        }
        return OperatingMode.REVIEWED;
    }

    private void dispatchOrchestratorDecision(String originalMessage,
            OrchestratorDecision decision) {
        if (decision == null) {
            startChatMessage(originalMessage, true);
            return;
        }
        String mode = decision.mode();
        if (OrchestratorDecision.MODE_STOP.equals(mode)) {
            mChatThread = null;
            mRunningChatAdapter = null;
            stopTask();
            return;
        }
        if (OrchestratorDecision.MODE_INSPECT_SCREEN.equals(mode)) {
            answerScreenQuestion(originalMessage, true);
            return;
        }
        if (decision.hasProposedActions()) {
            runOneShotActions(originalMessage, decision);
            return;
        }
        if (OrchestratorDecision.MODE_ACT.equals(mode)
                || OrchestratorDecision.MODE_RETRIEVE.equals(mode)
                || OrchestratorDecision.MODE_WATCH.equals(mode)
                || OrchestratorDecision.MODE_MEMORY.equals(mode)) {
            mChatThread = null;
            mRunningChatAdapter = null;
            String taskGoal = decision.taskGoal().trim();
            if (taskGoal.isEmpty()) {
                taskGoal = originalMessage;
            }
            setCurrentGoalText(taskGoal);
            mSuppressNextUserAppend = true;
            startAgentFromCurrentGoal();
            updateComposerActionButton();
            return;
        }
        mChatThread = null;
        mRunningChatAdapter = null;
        String reply = decision.reply().trim();
        if (reply.isEmpty()) {
            startChatMessage(originalMessage, true);
            return;
        }
        appendConversation("OpenPhone", reply);
        setTaskText("OpenPhone is ready.");
        updateIsland("Ready");
        if (mPointerOverlayController != null) {
            mPointerOverlayController.showReply(reply);
        }
        updateComposerActionButton();
    }

    /**
     * Executes orchestrator-proposed one-shot tool calls through the same
     * policy chain as the agent loop (dry-run, task grants, app and action
     * policy) inside a transient task, without the screenshot task loop.
     */
    private void runOneShotActions(final String originalMessage,
            final OrchestratorDecision decision) {
        mChatThread = null;
        mRunningChatAdapter = null;
        final OpenPhoneAgentManager agentManager = mAgentManager;
        if (agentManager == null) {
            startChatMessage(originalMessage, true);
            return;
        }
        reloadAutonomyMode();
        cancelAgentRun();
        mAgentRunCancelled = false;
        final int runGeneration = ++mAgentRunGeneration;
        final JSONArray proposedActions = decision.proposedActions();
        final ModelAdapter adapter = selectedModelAdapter(modelEndpointConfig());
        final FrameworkToolExecutor toolExecutor = new FrameworkToolExecutor(this, agentManager);
        mRunningModelAdapter = adapter;
        setTaskText("Working on it...");
        updateIsland("Working");
        Thread oneShotThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String taskId = null;
                boolean keepTaskForConfirmation = false;
                try {
                    String response = agentManager.startTask(taskRequestJson(originalMessage));
                    taskId = parseString(response, "task_id");
                    if (taskId == null || taskId.isEmpty()) {
                        postOneShotReply(runGeneration, "I could not start that action.",
                                "Needs review");
                        return;
                    }
                    TrajectoryRecorder trajectory = TrajectoryRecorder.start(
                            AssistantActivityBackend.this, taskId, originalMessage,
                            adapter.providerDisplayName(), adapter.modelName(),
                            adapter.usesCloud(), adapter.privacyDisclosure());
                    JSONArray results = new JSONArray();
                    for (int i = 0; i < proposedActions.length(); i++) {
                        if (mAgentRunCancelled || runGeneration != mAgentRunGeneration) {
                            return;
                        }
                        JSONObject action = proposedActions.optJSONObject(i);
                        if (action == null) {
                            continue;
                        }
                        String toolName = action.optString("tool", "");
                        JSONObject arguments = action.optJSONObject("arguments");
                        if (arguments == null) {
                            arguments = new JSONObject();
                        }
                        trajectory.recordToolCall(toolName, arguments.toString());
                        String result = dryRunPreview(toolName, arguments);
                        if (result == null) {
                            result = preflightDenial(toolName, arguments);
                        }
                        if (result == null) {
                            movePointerFromTool(toolName, arguments);
                            result = toolExecutor.execute(taskId, toolName, arguments);
                        }
                        trajectory.recordToolResult(toolName, result);
                        JSONObject parsedResult = parseObjectOrEmpty(result);
                        if ("confirmation_required".equals(parsedResult.optString("status", ""))) {
                            keepTaskForConfirmation = true;
                            trajectory.recordAgentResult(result);
                            showOneShotConfirmation(runGeneration, taskId, originalMessage,
                                    result);
                            return;
                        }
                        try {
                            results.put(new JSONObject()
                                    .put("tool", toolName)
                                    .put("result", parsedResult));
                        } catch (JSONException ignored) {
                        }
                    }
                    trajectory.recordAgentResult(results.toString());
                    recordContextAgentEvent("assistant.agent.one_shot_finished",
                            "One-shot actions finished", originalMessage, taskId,
                            results.toString());
                    postOneShotReply(runGeneration,
                            oneShotChatReply(adapter, originalMessage, results.toString()),
                            "Done");
                } catch (RuntimeException e) {
                    postOneShotReply(runGeneration,
                            "I could not run that: " + e.getClass().getSimpleName(),
                            "Needs review");
                } finally {
                    if (!keepTaskForConfirmation && taskId != null) {
                        try {
                            agentManager.stopTask(taskId, "{\"reason\":\"one_shot_complete\"}");
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
        }, "OpenPhoneOneShot");
        mAgentThread = oneShotThread;
        updateComposerActionButton();
        oneShotThread.start();
    }

    private void postOneShotReply(final int runGeneration, final String reply,
            final String islandStatus) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (runGeneration != mAgentRunGeneration) {
                    return;
                }
                mAgentThread = null;
                mRunningModelAdapter = null;
                appendConversation("OpenPhone", reply);
                setTaskText("OpenPhone is ready.");
                updateIsland(islandStatus);
                if (reply != null && !reply.trim().isEmpty()
                        && mPointerOverlayController != null
                        && "Done".equals(islandStatus)) {
                    mPointerOverlayController.showReply(reply);
                }
                updateComposerActionButton();
                refreshAudit();
            }
        });
    }

    private void showOneShotConfirmation(final int runGeneration, final String taskId,
            final String originalMessage, final String confirmationJson) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (runGeneration != mAgentRunGeneration) {
                    try {
                        mAgentManager.stopTask(taskId, "{\"reason\":\"one_shot_cancelled\"}");
                    } catch (RuntimeException ignored) {
                    }
                    return;
                }
                mAgentThread = null;
                mRunningModelAdapter = null;
                mActiveTaskId = taskId;
                JSONObject confirmation = parseObjectOrEmpty(confirmationJson);
                String pendingActionId = findStringRecursive(confirmation, "pending_action_id");
                if (pendingActionId != null && !pendingActionId.isEmpty()
                        && !"null".equals(pendingActionId)) {
                    mPendingActionId = pendingActionId;
                }
                capturePendingToolAction(confirmation);
                mPendingOneShotMessage = originalMessage;
                String body = confirmationBodyFromAgentResult("confirmation_required",
                        confirmation);
                showPendingConfirmation(body);
                setTaskText(body);
                updateComposerActionButton();
                refreshAudit();
            }
        });
    }

    private void finishOneShotConfirmation(boolean approved, String userMessage,
            String toolName, String resultJson) {
        String taskId = mActiveTaskId;
        mActiveTaskId = null;
        if (taskId != null && mAgentManager != null) {
            try {
                mAgentManager.stopTask(taskId, "{\"reason\":\"one_shot_complete\"}");
            } catch (RuntimeException ignored) {
            }
        }
        mPointerOverlayController.showMicButton();
        recordContextAgentEvent("assistant.agent.one_shot_reviewed",
                approved ? "One-shot action approved" : "One-shot action denied",
                userMessage, taskId, resultJson);
        if (!approved) {
            appendConversation("OpenPhone", "Okay, I did not run that action.");
            setTaskText("OpenPhone is ready.");
            updateIsland("Denied");
            updateComposerActionButton();
            return;
        }
        if (isActionResultFailure(resultJson)) {
            appendConversation("OpenPhone", "I tried to run "
                    + (toolName == null || toolName.isEmpty() ? "that action" : toolName)
                    + " but it failed.");
            updateIsland("Needs review");
            updateComposerActionButton();
            return;
        }
        summarizeApprovedOneShot(userMessage, toolName, resultJson);
    }

    private void summarizeApprovedOneShot(final String userMessage, final String toolName,
            final String resultJson) {
        cancelChatRun();
        final int chatGeneration = ++mChatRunGeneration;
        final ModelAdapter adapter = selectedModelAdapter(modelEndpointConfig());
        mRunningChatAdapter = adapter;
        setTaskText("Thinking...");
        updateIsland("Thinking");
        Thread chatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String resultsJson;
                try {
                    resultsJson = new JSONArray().put(new JSONObject()
                            .put("tool", toolName == null ? "" : toolName)
                            .put("result", parseObjectOrEmpty(resultJson))).toString();
                } catch (JSONException e) {
                    resultsJson = resultJson;
                }
                final String reply = oneShotChatReply(adapter, userMessage, resultsJson);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (chatGeneration != mChatRunGeneration) {
                            return;
                        }
                        mChatThread = null;
                        mRunningChatAdapter = null;
                        appendConversation("OpenPhone", reply);
                        setTaskText("OpenPhone is ready.");
                        updateIsland("Done");
                        if (reply != null && !reply.trim().isEmpty()
                                && mPointerOverlayController != null) {
                            mPointerOverlayController.showReply(reply);
                        }
                        updateComposerActionButton();
                    }
                });
            }
        }, "OpenPhoneOneShotSummary");
        mChatThread = chatThread;
        updateComposerActionButton();
        chatThread.start();
    }

    private String oneShotChatReply(ModelAdapter adapter, String userMessage,
            String resultsJson) {
        String reply = "";
        try {
            reply = adapter.chat("The user said: \"" + userMessage + "\".\n"
                    + "I already ran the requested phone action(s); the tool results are:\n"
                    + truncateForPrompt(resultsJson, 6000)
                    + "\nReply to the user in one or two short sentences using only these "
                    + "results. Mention concrete details (names, counts, times) when "
                    + "present. Do not mention JSON or tool names.");
        } catch (RuntimeException ignored) {
        }
        if (reply == null || reply.trim().isEmpty()) {
            return "I finished that action.";
        }
        return reply.trim();
    }

    private static String truncateForPrompt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private void answerScreenQuestionFromCurrentMessage() {
        final String message = currentGoalText().trim();
        if (message.isEmpty()) {
            setTaskText("Ask me what you want to know about the current screen.");
            updateIsland("Ready");
            return;
        }
        appendConversation("You", message);
        setCurrentGoalText("");
        answerScreenQuestion(message, true);
    }

    private void answerScreenQuestion(final String message, boolean alreadyAppended) {
        cancelChatRun();
        mAgentRunCancelled = false;
        final int chatGeneration = ++mChatRunGeneration;
        final ModelAdapter adapter = selectedModelAdapter(modelEndpointConfig());
        mRunningChatAdapter = adapter;
        setTaskText("Reading the screen...");
        updateIsland("Reading screen");
        Thread chatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String taskId = mActiveTaskId;
                boolean transientTask = false;
                String screenJson = "";
                try {
                    if (mAgentManager == null) {
                        finalReply(adapter.chat(message));
                        return;
                    }
                    if (taskId == null || taskId.isEmpty()) {
                        String response = mAgentManager.startTask(taskRequestJson(message));
                        taskId = parseString(response, "task_id");
                        transientTask = taskId != null && !taskId.isEmpty();
                    }
                    if (taskId == null || taskId.isEmpty()) {
                        finalReply("I could not start a screen observation.");
                        return;
                    }
                    screenJson = mAgentManager.getScreen(taskId,
                            "{\"include_screenshot\":true,\"include_activity\":true,"
                                    + "\"include_ui_tree\":true,\"max_dimension\":512,"
                                    + "\"quality\":65,"
                                    + "\"reason\":\"answer the user's screen question\"}");
                    finalReply(adapter.answerScreenQuestion(message, screenJson));
                } catch (RuntimeException e) {
                    finalReply("I could not read the screen: " + e.getClass().getSimpleName());
                } finally {
                    if (transientTask && taskId != null && mAgentManager != null) {
                        try {
                            mAgentManager.stopTask(taskId,
                                    "{\"reason\":\"screen_question_complete\"}");
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }

            private void finalReply(final String reply) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (chatGeneration != mChatRunGeneration) {
                            return;
                        }
                        mChatThread = null;
                        mRunningChatAdapter = null;
                        appendConversation("OpenPhone", reply);
                        setTaskText("OpenPhone is ready.");
                        updateIsland("Ready");
                        if (reply != null && !reply.trim().isEmpty()
                                && mPointerOverlayController != null) {
                            mPointerOverlayController.showReply(reply);
                        }
                        updateComposerActionButton();
                    }
                });
            }
        }, "OpenPhoneScreenQuestion");
        mChatThread = chatThread;
        updateComposerActionButton();
        chatThread.start();
    }

    private void cancelChatRun() {
        mChatRunGeneration++;
        ModelAdapter adapter = mRunningChatAdapter;
        if (adapter != null) {
            adapter.cancel();
        }
        Thread thread = mChatThread;
        if (thread != null) {
            thread.interrupt();
        }
        mChatThread = null;
        mRunningChatAdapter = null;
    }

    private static String continuationGoal(String followUp) {
        return "Continue the current phone task from the visible screen state. "
                + "The user gave this follow-up instruction while the agent was running: "
                + (followUp == null ? "" : followUp);
    }

    private void appendConversation(String speaker, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        if (mContextIndexStore != null) {
            mContextIndexStore.recordConversationMessage(speaker, message.trim());
        }
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.addConversationMessage(speaker, message.trim());
        }
    }

    private void recordContextAgentEvent(String eventType, String title, String text,
            String taskId, String payloadJson) {
        if (mContextIndexStore == null) {
            return;
        }
        mContextIndexStore.recordAgentEvent(eventType, title, text, taskId, payloadJson);
    }

    private boolean isAdvancedVisible() {
        return mComposeAdvancedVisible;
    }

    private void showAdvancedSurface() {
        mComposeAdvancedVisible = true;
    }

    private void showChatSurface() {
        mComposeAdvancedVisible = false;
    }

    
    public void onBackPressed() {
        if (isAdvancedVisible()) {
            mComposeAdvancedVisible = false;
            if (mComposeStateCallbacks != null) {
                mComposeStateCallbacks.showChat();
            }
            showChatSurface();
            return;
        }
        super.onBackPressed();
    }

    private void updateIsland(String text) {
        String status = text == null || text.isEmpty() ? "OpenPhone is ready" : text;
        String islandState = islandStateForStatus(status);
        // Always let terminal-state transitions through, even when no task is
        // active. Without this, postOneShotReply (which clears mAgentThread
        // and mActiveTaskId before calling updateIsland) leaves the island
        // stuck in its last running state — glow keeps pulsing, "Stop" stays
        // up — because the gate below would refuse the answer_ready / idle
        // / error transition that's supposed to dismiss it.
        boolean isTerminal = "answer_ready".equals(islandState)
                || "idle".equals(islandState)
                || "error".equals(islandState)
                || "needs_review".equals(islandState);
        if (mPointerOverlayController != null && islandState != null
                && (isTerminal || mIslandVoiceLaunch
                        || mActiveTaskId != null || mAgentThread != null)) {
            mPointerOverlayController.setIslandState(islandState, status);
        }
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setRuntimeStatus(status, mActiveTaskId,
                    mAgentThread != null || mChatThread != null,
                    mListening || mIslandVoiceLaunch);
        }
    }

    private static String islandStateForStatus(String status) {
        switch (status) {
            case "Thinking":
            case "Reading screen":
                return "thinking";
            case "Done":
            case "Trace exported":
            case "Audit exported":
                return "answer_ready";
            case "Needs review":
            case "Approval needed":
                return "needs_review";
            case "Start failed":
            case "Action failed":
            case "Export failed":
            case "Assistant unavailable":
            case "Setup needed":
            case "Mic blocked":
            case "Try again":
                return "error";
            case "Listening...":
                return "listening";
            case "Working":
            case "Waiting for task":
            case "Agent active":
            case "Agent is working":
            case "Starting":
            case "Continuing":
                return "action_running";
            case "Ready":
            case "Stopped":
            case "Denied":
                return "idle";
            default:
                return null;
        }
    }

    private void pushIslandAutonomy() {
        if (mPointerOverlayController != null) {
            mPointerOverlayController.setYoloActive("yolo".equals(mAutonomyMode));
        }
    }

    private void startTask() {
        if (mAgentManager == null) {
            refreshAll();
            return;
        }
        String goal = currentGoalText();
        String response = mAgentManager.startTask(taskRequestJson(goal));
        mActiveTaskId = parseString(response, "task_id");
        showTaskStarted(goal);
        refreshAudit();
    }

    private String taskRequestJson(String goal) {
        JSONObject request = new JSONObject();
        try {
            request.put("goal", goal == null ? "" : goal);
            request.put("user_visible", true);
            request.put("background_allowed", false);
            request.put("grant_defaults_source", "settings_secure");
            JSONArray capabilities = new JSONArray();
            capabilities.put("screen.read.visible");
            capabilities.put("tasks.observe");
            capabilities.put("apps.launch");
            if (inputGrantEnabled()) {
                capabilities.put("input.perform");
            }
            if (screenCaptureGrantEnabled()) {
                capabilities.put("screen.capture");
            }
            if (clipboardGrantEnabled()) {
                capabilities.put("clipboard.read");
                capabilities.put("clipboard.write");
            }
            if (shareGrantEnabled()) {
                capabilities.put("share.content");
            }
            if (networkGrantEnabled()) {
                capabilities.put("network.use");
            }
            request.put("approved_capabilities", capabilities);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return request.toString();
    }

    private void showTaskStarted(String goal) {
        mActiveTaskGoal = goal == null ? "" : goal;
        setTaskText("Working on: " + (goal == null ? "" : goal));
        updateIsland("Agent active");
        mPointerOverlayController.show(mActiveTaskId);
        OpenPhoneNotificationController.showActive(this, mActiveTaskId);
        recordContextAgentEvent("assistant.agent.task_started", "Agent task started",
                goal == null ? "" : goal, mActiveTaskId, "");
    }

    private void startTaskThenRunAgent(final String goal) {
        final OpenPhoneAgentManager agentManager = mAgentManager;
        if (agentManager == null) {
            refreshAll();
            return;
        }
        final String requestJson = taskRequestJson(goal);
        setTaskText("Starting: " + goal);
        updateIsland("Starting");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String response = null;
                RuntimeException error = null;
                try {
                    response = agentManager.startTask(requestJson);
                } catch (RuntimeException e) {
                    error = e;
                }
                final String finalResponse = response;
                final RuntimeException finalError = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalError != null) {
                            setTaskText("Could not start task.\n\n"
                                    + finalError.getClass().getSimpleName());
                            updateIsland("Start failed");
                            return;
                        }
                        mActiveTaskId = parseString(finalResponse, "task_id");
                        if (mActiveTaskId == null || mActiveTaskId.isEmpty()) {
                            setTaskText("Could not start task.\n\n"
                                    + prettyForDisplay(finalResponse));
                            updateIsland("Start failed");
                            return;
                        }
                        showTaskStarted(goal);
                        refreshAudit();
                        runAgent(goal);
                    }
                });
            }
        }, "OpenPhoneTaskStart").start();
    }

    private void stopTask() {
        cancelAgentRun();
        cancelChatRun();
        mVoiceRunGeneration++;
        mListening = false;
        mIslandVoiceLaunch = false;
        OpenAiSpeechTranscriber speechTranscriber = mRunningSpeechTranscriber;
        if (speechTranscriber != null) {
            speechTranscriber.cancel();
            mRunningSpeechTranscriber = null;
        }
        String taskId = mActiveTaskId;
        try {
            if (mAgentManager != null && taskId != null) {
                mAgentManager.stopTask(taskId, "{\"reason\":\"user_stopped_from_assistant\"}");
            }
        } catch (RuntimeException ignored) {
        } finally {
            mActiveTaskId = null;
            mActiveTaskGoal = null;
            mAgentThread = null;
            mChatThread = null;
            mRunningModelAdapter = null;
            mRunningChatAdapter = null;
            mPendingActionId = null;
            mPendingOneShotMessage = null;
            clearPendingToolAction();
            hidePendingConfirmation();
            mPointerOverlayController.showMicButton();
            OpenPhoneNotificationController.showReady(this);
            if (sActiveControlRunner == this) {
                sActiveControlRunner = null;
            }
        }
        setTaskText("Task stopped");
        recordContextAgentEvent("assistant.agent.task_stopped", "Agent task stopped",
                "The active agent task was stopped by the user.", taskId, "");
        updateIsland("Stopped");
        updateComposerActionButton();
        refreshAudit();
    }

    private void cancelAgentRun() {
        mAgentRunCancelled = true;
        mAgentRunGeneration++;
        ModelAdapter adapter = mRunningModelAdapter;
        if (adapter != null) {
            adapter.cancel();
        }
        Thread thread = mAgentThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void readScreenContext() {
        if (mAgentManager == null || mActiveTaskId == null) {
            setContextText("Start a task first");
            return;
        }
        setContextText(prettyForDisplay(mAgentManager.getScreen(mActiveTaskId,
                "{\"include_screenshot\":false,\"include_activity\":true}")));
        refreshAudit();
    }

    private void readScreenshotContext() {
        if (mAgentManager == null || mActiveTaskId == null) {
            setContextText("Start a task first");
            return;
        }
        setContextText(prettyForDisplay(mAgentManager.getScreen(mActiveTaskId,
                "{\"include_screenshot\":true,\"include_activity\":true,"
                        + "\"max_dimension\":512,\"quality\":65}")));
        refreshAudit();
    }

    private void executeBack() {
        if (mAgentManager == null || mActiveTaskId == null) {
            setTaskText("Start a task first");
            return;
        }
        String result = mAgentManager.executeAction(mActiveTaskId, "{\"type\":\"back\"}");
        setTaskText(prettyForDisplay(result));
        refreshAudit();
    }

    private void requestAction() {
        if (mAgentManager == null || mActiveTaskId == null) {
            setTaskText("Start a task first");
            return;
        }
        String result = mAgentManager.executeAction(mActiveTaskId,
                currentActionJson());
        mPendingActionId = parseString(result, "pending_action_id");
        if (mPendingActionId != null && !mPendingActionId.isEmpty()) {
            showPendingConfirmation(confirmationBodyFromActionResult(result));
        }
        setTaskText(prettyForDisplay(result));
        movePointerFromAction();
        refreshAudit();
    }

    private void runAgent() {
        runAgent(currentGoalText());
    }

    private void runAgent(final String goal) {
        if (mAgentManager == null || mActiveTaskId == null) {
            setTaskText("Start a task first");
            return;
        }
        reloadAutonomyMode();
        cancelAgentRun();
        mAgentRunCancelled = false;
        final int runGeneration = ++mAgentRunGeneration;
        final String taskId = mActiveTaskId;
        final ModelEndpointConfig endpointConfig = modelEndpointConfig();
        setTaskText("Working on: " + goal);
        updateIsland("Agent is working");
        FrameworkToolExecutor toolExecutor = new FrameworkToolExecutor(this, mAgentManager);
        ModelAdapter adapter = selectedModelAdapter(endpointConfig);
        mRunningModelAdapter = adapter;
        setTaskText("Working on: " + goal + "\n\n" + modelRunDisclosure(adapter));
        TrajectoryRecorder trajectory = TrajectoryRecorder.start(this, taskId, goal,
                adapter.providerDisplayName(), adapter.modelName(), adapter.usesCloud(),
                adapter.privacyDisclosure());
        Thread agentThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String result = adapter.runTask(taskId, goal, new ModelAdapter.ToolExecutor() {
                    @Override
                    public String callTool(String toolName, String argumentsJson) {
                        if (isCancelled()) {
                            return "{\"status\":\"cancelled\",\"reason\":\"user_stopped\"}";
                        }
                        try {
                            trajectory.recordToolCall(toolName, argumentsJson);
                            JSONObject toolArguments = new JSONObject(argumentsJson);
                            String dryRunPreview = dryRunPreview(toolName, toolArguments);
                            if (dryRunPreview != null) {
                                trajectory.recordToolResult(toolName, dryRunPreview);
                                return dryRunPreview;
                            }
                            String grantDenied = preflightDenial(toolName,
                                    toolArguments);
                            if (grantDenied != null) {
                                trajectory.recordToolResult(toolName, grantDenied);
                                return grantDenied;
                            }
                            movePointerFromTool(toolName, toolArguments);
                            if (isCancelled()) {
                                String cancelled = "{\"status\":\"cancelled\","
                                        + "\"reason\":\"user_stopped\"}";
                                trajectory.recordToolResult(toolName, cancelled);
                                return cancelled;
                            }
                            String toolResult = toolExecutor.execute(taskId, toolName,
                                    toolArguments);
                            trajectory.recordToolResult(toolName, toolResult);
                            return toolResult;
                        } catch (JSONException e) {
                            String error = "{\"status\":\"error\",\"reason\":\"bad_tool_json\"}";
                            trajectory.recordToolResult(toolName, error);
                            return error;
                        }
                    }

                    @Override
                    public boolean isCancelled() {
                        return mAgentRunCancelled
                                || runGeneration != mAgentRunGeneration
                                || Thread.currentThread().isInterrupted();
                    }
                });
                trajectory.recordAgentResult(result);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (runGeneration != mAgentRunGeneration) {
                            return;
                        }
                        mAgentThread = null;
                        mRunningModelAdapter = null;
                        updateComposerActionButton();
                        if (mAgentRunCancelled || isCancelledResult(result)) {
                            mPointerOverlayController.showMicButton();
                            setTaskText("Task stopped"
                                    + "\n\nTrajectory: " + trajectory.sessionPath());
                            recordContextAgentEvent("assistant.agent.task_cancelled",
                                    "Agent task cancelled", agentResultForDisplay(result),
                                    taskId, result);
                            updateIsland("Stopped");
                            refreshAudit();
                            return;
                        }
                        setTaskText(agentResultForDisplay(result)
                                + "\n\nTrajectory: " + trajectory.sessionPath());
                        showConfirmationIfNeeded(result);
                        boolean finished = result.contains("\"status\":\"task.finished\"")
                                || result.contains("\"status\": \"task.finished\"");
                        String displayReply = finished
                                ? taskFinishedMessage(result) : agentResultForDisplay(result);
                        appendConversation("OpenPhone", displayReply);
                        recordContextAgentEvent(finished
                                        ? "assistant.agent.task_finished"
                                        : "assistant.agent.task_needs_review",
                                finished ? "Agent task finished" : "Agent task needs review",
                                displayReply,
                                taskId, result);
                        updateIsland(finished
                                ? "Done" : "Needs review");
                        if (finished && displayReply != null
                                && !displayReply.trim().isEmpty()
                                && mPointerOverlayController != null) {
                            mPointerOverlayController.showReply(displayReply);
                        }
                        refreshAudit();
                    }
                });
            }
        }, "OpenPhoneModelRunner");
        mAgentThread = agentThread;
        updateComposerActionButton();
        agentThread.start();
    }

    private void confirmPending(boolean approved) {
        if (mAgentManager == null) {
            setTaskText("Agent service is unavailable.");
            return;
        }
        final String pendingActionId = mPendingActionId;
        final String pendingToolName = mPendingToolName;
        final JSONObject pendingToolArguments = copyJsonObject(mPendingToolArguments);
        final String pendingOneShotMessage = mPendingOneShotMessage;
        if (pendingActionId == null && pendingToolName == null) {
            setTaskText("There is no pending system action to approve. "
                    + "If the agent asked for review, check the request and run the task again.");
            return;
        }

        mPendingActionId = null;
        mPendingOneShotMessage = null;
        clearPendingToolAction();
        hidePendingConfirmation();
        setTaskText(approved ? "Approved. Continuing..." : "Denied.");
        updateIsland(approved ? "Continuing" : "Denied");

        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                if (pendingActionId != null) {
                    result = mAgentManager.confirmAction(pendingActionId, approved);
                } else if (approved) {
                    result = executeApprovedPendingTool(pendingToolName, pendingToolArguments);
                } else {
                    result = "{\"status\":\"action.denied\","
                            + "\"reason\":\"user_denied_confirmation\"}";
                }
                final String finalResult = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshAudit();
                        if (pendingOneShotMessage != null) {
                            finishOneShotConfirmation(approved, pendingOneShotMessage,
                                    pendingToolName, finalResult);
                            return;
                        }
                        setTaskText(prettyForDisplay(finalResult));
                        if (approved && mActiveTaskId != null
                                && !isActionResultFailure(finalResult)) {
                            String resumeGoal = mActiveTaskGoal == null
                                    || mActiveTaskGoal.isEmpty()
                                    ? currentGoalText() : mActiveTaskGoal;
                            runAgent("Continue this task: " + resumeGoal
                                    + "\nThe user just approved the previously blocked "
                                    + "action and its result is: " + finalResult
                                    + "\nDo not ask for that approval again; use the "
                                    + "result and finish the task.");
                        } else {
                            updateIsland(approved ? "Action failed" : "Denied");
                        }
                    }
                });
            }
        }, "OpenPhoneApprovalExecutor").start();
    }

    private void showConfirmationIfNeeded(String agentResultJson) {
        try {
            JSONObject agentResult = new JSONObject(agentResultJson);
            String status = agentResult.optString("status", "");
            if (!"confirmation_required".equals(status)
                    && !"action_denied".equals(status)
                    && !"agent.blocked".equals(status)) {
                hidePendingConfirmation();
                return;
            }
            JSONObject confirmation = findLastConfirmation(agentResult);
            String pendingActionId = findStringRecursive(agentResult, "pending_action_id");
            if (pendingActionId != null && !pendingActionId.isEmpty()
                    && !"null".equals(pendingActionId)) {
                mPendingActionId = pendingActionId;
            }
            capturePendingToolAction(confirmation);
            showPendingConfirmation(confirmationBodyFromAgentResult(status, confirmation));
        } catch (JSONException e) {
            hidePendingConfirmation();
        }
    }

    private String executeApprovedPendingTool(String toolName, JSONObject arguments) {
        if (mActiveTaskId == null || toolName == null) {
            return "{\"status\":\"error\",\"reason\":\"no_pending_tool_action\"}";
        }
        FrameworkToolExecutor toolExecutor = new FrameworkToolExecutor(this, mAgentManager);
        JSONObject safeArguments = arguments == null ? new JSONObject() : arguments;
        try {
            movePointerFromTool(toolName, safeArguments);
            return toolExecutor.execute(mActiveTaskId, toolName, safeArguments);
        } catch (RuntimeException e) {
            return "{\"status\":\"error\",\"reason\":\"approved_tool_failed\"}";
        }
    }

    private static JSONObject copyJsonObject(JSONObject object) {
        if (object == null) {
            return null;
        }
        try {
            return new JSONObject(object.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void capturePendingToolAction(JSONObject confirmation) {
        JSONObject action = confirmation.optJSONObject("action");
        if (action == null) {
            action = confirmation.optJSONObject("action_json");
        }
        if (action == null) {
            action = parseObjectOrEmpty(confirmation.optString("input", ""));
        }
        String toolName = action.optString("tool", action.optString("type", ""));
        JSONObject arguments = action.optJSONObject("arguments");
        if (arguments == null) {
            arguments = parseObjectOrEmpty(action.toString());
            arguments.remove("tool");
            arguments.remove("type");
        }
        if (toolName == null || toolName.isEmpty()) {
            clearPendingToolAction();
            return;
        }
        mPendingToolName = toolName;
        mPendingToolArguments = arguments;
    }

    private void clearPendingToolAction() {
        mPendingToolName = null;
        mPendingToolArguments = null;
    }

    private void showPendingConfirmation(String body) {
        String confirmationText = body == null || body.isEmpty()
                ? "Review the requested action before continuing." : body;
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.setPendingConfirmation(
                    mPendingActionId == null ? "" : mPendingActionId,
                    mPendingToolName == null ? "" : mPendingToolName,
                    confirmationText);
        }
        // Pass the actual confirmation text so the inline Approve/Deny island
        // shows what's being approved instead of generic "Approval needed".
        if (mPointerOverlayController != null) {
            mPointerOverlayController.setIslandState("needs_review", confirmationText);
        }
        updateIsland("Approval needed");
    }

    private void hidePendingConfirmation() {
        if (mComposeStateCallbacks != null) {
            mComposeStateCallbacks.clearPendingConfirmation();
        }
    }

    private void refreshAudit() {
        final OpenPhoneAgentManager agentManager = mAgentManager;
        if (agentManager == null) {
            return;
        }
        final int refreshGeneration = ++mAuditRefreshGeneration;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String auditText;
                try {
                    auditText = prettyForDisplay(agentManager.getAuditLog(25));
                } catch (RuntimeException e) {
                    auditText = "Audit unavailable: " + e.getClass().getSimpleName();
                }
                final String finalAuditText = auditText;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (refreshGeneration == mAuditRefreshGeneration) {
                            setAuditText(finalAuditText);
                        }
                    }
                });
            }
        }, "OpenPhoneAuditRefresh").start();
    }

    private void exportLatestTrajectory() {
        try {
            setTaskText(TrajectoryRecorder.exportLatestToDownloads(this));
            updateIsland("Trace exported");
        } catch (IOException e) {
            setTaskText("Could not export the latest trace.\n\n" + e.getMessage());
            updateIsland("Export failed");
        }
    }

    private void exportAuditEvidence() {
        try {
            setTaskText(AuditEvidenceExporter.exportToDownloads(this, mAgentManager, 200));
            updateIsland("Audit exported");
        } catch (IOException e) {
            setTaskText("Could not export audit evidence.\n\n" + e.getMessage());
            updateIsland("Export failed");
        }
    }

    private void checkOtaFeed() {
        final String feedUrl = mComposeOtaFeedUrl.trim();
        if (feedUrl.isEmpty()) {
            setOtaStatus("Paste an OTA feed URL first.");
            return;
        }
        setOtaStatus("Checking OTA feed for " + Build.DEVICE + "...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String status;
                OtaUpdateClient.Update update = null;
                try {
                    update = OtaUpdateClient.fetchLatest(feedUrl, Build.DEVICE);
                    status = "Update available.\n\n" + update.summary();
                } catch (IOException | JSONException e) {
                    status = "OTA check failed.\n\n" + e.getMessage();
                }
                final String finalStatus = status;
                final OtaUpdateClient.Update finalUpdate = update;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mLatestOtaUpdate = finalUpdate;
                        setOtaStatus(finalStatus);
                    }
                });
            }
        }, "OpenPhoneOtaCheck").start();
    }

    private void downloadLatestOta() {
        final OtaUpdateClient.Update update = mLatestOtaUpdate;
        if (update == null) {
            setOtaStatus("Check an OTA feed before downloading.");
            return;
        }
        setOtaStatus("Starting OTA download...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String status;
                try {
                    status = OtaUpdateClient.downloadToDownloads(AssistantActivityBackend.this, update,
                            new OtaUpdateClient.ProgressCallback() {
                                @Override
                                public void onProgress(final String progress) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            setOtaStatus(progress);
                                        }
                                    });
                                }
                            });
                } catch (IOException e) {
                    status = "OTA download failed.\n\n" + e.getMessage();
                }
                final String finalStatus = status;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setOtaStatus(finalStatus);
                    }
                });
            }
        }, "OpenPhoneOtaDownload").start();
    }

    private void setOtaStatus(String status) {
        setOtaStatusText(status == null ? "" : status);
        setTaskText(status == null ? "" : status);
    }

    private String preflightDenial(String toolName, JSONObject arguments) {
        String missingCapability = missingTaskGrant(toolName, arguments);
        if (missingCapability == null) {
            String appPolicy = appPolicyDenial(toolName, arguments);
            if (appPolicy != null) {
                return appPolicy;
            }
            return actionPolicyDenial(toolName, arguments);
        }
        try {
            return new JSONObject()
                    .put("status", "confirmation_required")
                    .put("reason", "task_grant_required")
                    .put("capability", missingCapability)
                    .put("summary", "This task does not currently allow "
                            + capabilityLabel(missingCapability) + ".")
                    .put("risk", riskForCapability(missingCapability, toolName))
                    .put("action_json", new JSONObject()
                            .put("tool", toolName)
                            .put("arguments", arguments))
                    .toString();
        } catch (JSONException e) {
            return "{\"status\":\"confirmation_required\","
                    + "\"reason\":\"task_grant_required\"}";
        }
    }

    private String actionPolicyDenial(String toolName, JSONObject arguments) {
        if (mActionRegistry == null || !mActionRegistry.isLoaded()) {
            return null;
        }
        ActionRegistry.ActionMetadata action = mActionRegistry.forTool(toolName);
        if (action == null || "control".equals(action.kind)) {
            return null;
        }
        if ("task_grant".equals(action.authorizationPolicy)) {
            return null;
        }
        String capability = capabilityForTool(toolName, arguments);
        if (yoloAllows(action, capability)) {
            return null;
        }
        try {
            return new JSONObject()
                    .put("status", "confirmation_required")
                    .put("reason", "action_policy_" + action.authorizationPolicy)
                    .put("action_name", action.name)
                    .put("capability", capability == null ? "" : capability)
                    .put("summary", actionConfirmationSummary(action, toolName, arguments))
                    .put("risk", riskLabel(action.riskClass))
                    .put("autonomy_mode", mAutonomyMode)
                    .put("action_json", new JSONObject()
                            .put("tool", toolName)
                            .put("arguments", arguments == null ? new JSONObject() : arguments))
                    .toString();
        } catch (JSONException e) {
            return "{\"status\":\"confirmation_required\","
                    + "\"reason\":\"action_policy_required\"}";
        }
    }

    private String dryRunPreview(String toolName, JSONObject arguments) {
        if (!"dry_run".equals(mAutonomyMode) || !isDryRunPreviewTool(toolName)) {
            return null;
        }
        String capability = capabilityForTool(toolName, arguments);
        ActionRegistry.ActionMetadata action = mActionRegistry == null
                ? null : mActionRegistry.forTool(toolName);
        try {
            JSONObject result = new JSONObject()
                    .put("status", "dry_run.preview")
                    .put("reason", "dry_run_mode")
                    .put("tool", toolName == null ? "" : toolName)
                    .put("capability", capability == null ? "" : capability)
                    .put("risk", action == null ? riskForCapability(
                            capability == null ? "" : capability, toolName)
                            : riskLabel(action.riskClass))
                    .put("summary", "Dry run previewed this action without executing it.")
                    .put("arguments", arguments == null ? new JSONObject() : arguments);
            if (action != null) {
                result.put("action_name", action.name)
                        .put("authorization_policy", action.authorizationPolicy);
            }
            return result.toString();
        } catch (JSONException e) {
            return "{\"status\":\"dry_run.preview\",\"reason\":\"dry_run_mode\"}";
        }
    }

    private boolean isDryRunPreviewTool(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }
        if ("finish_task".equals(toolName) || "fail_task".equals(toolName)
                || "get_screen".equals(toolName) || "watch_screen".equals(toolName)
                || "context_search".equals(toolName)
                || "notifications_list".equals(toolName)
                || "notifications_search".equals(toolName)
                || "notifications_summary".equals(toolName)
                || "calendar_search".equals(toolName)
                || "calendar_check_availability".equals(toolName)
                || "contacts_search".equals(toolName)
                || "messages_search".equals(toolName)
                || "messages_summary".equals(toolName)
                || "calls_search".equals(toolName)
                || "phone_context".equals(toolName)
                || "memory_search".equals(toolName)
                || "commitment_search".equals(toolName)
                || "watcher_list".equals(toolName)
                || "browser_fetch_page".equals(toolName)
                || "wait".equals(toolName)
                || "ask_user_confirmation".equals(toolName)) {
            return false;
        }
        ActionRegistry.ActionMetadata action = mActionRegistry == null
                ? null : mActionRegistry.forTool(toolName);
        if (action != null) {
            return "action".equals(action.kind) || "control".equals(action.kind);
        }
        return "open_app".equals(toolName)
                || "open_url".equals(toolName)
                || "tap".equals(toolName)
                || "tap_element".equals(toolName)
                || "long_press".equals(toolName)
                || "long_press_element".equals(toolName)
                || "swipe".equals(toolName)
                || "type_text".equals(toolName)
                || "press_key".equals(toolName)
                || "set_clipboard".equals(toolName)
                || "paste".equals(toolName)
                || "share_text".equals(toolName)
                || "notifications_open".equals(toolName)
                || "calendar_create_event".equals(toolName)
                || "message_calendar_event_create".equals(toolName)
                || "calendar_update_event".equals(toolName)
                || "calendar_delete_event".equals(toolName)
                || "messages_draft".equals(toolName)
                || "messages_send".equals(toolName)
                || "calls_place".equals(toolName)
                || "memory_save".equals(toolName)
                || "commitment_create".equals(toolName)
                || "notification_commitment_create".equals(toolName)
                || "message_commitment_create".equals(toolName)
                || "commitment_update_status".equals(toolName)
                || "watcher_create".equals(toolName)
                || "watcher_stop".equals(toolName);
    }

    private boolean yoloAllows(ActionRegistry.ActionMetadata action, String capability) {
        if (!"yolo".equals(mAutonomyMode)) {
            return false;
        }
        if (!"confirm".equals(action.authorizationPolicy)
                || !"medium".equals(action.riskClass)) {
            return false;
        }
        return isYoloCapability(capability);
    }

    private static boolean isYoloCapability(String capability) {
        return "memory.write".equals(capability)
                || "calendar.write".equals(capability)
                || "commitments.write".equals(capability)
                || "watchers.write".equals(capability)
                || "notifications.read".equals(capability)
                || "notifications.act".equals(capability)
                || "messages.read".equals(capability)
                || "messages.draft".equals(capability)
                || "calls.read".equals(capability)
                || "settings.write".equals(capability)
                || "background.run".equals(capability)
                || "network.use".equals(capability);
    }

    private static String actionConfirmationSummary(ActionRegistry.ActionMetadata action,
            String toolName, JSONObject arguments) {
        String actionName = action.name == null || action.name.isEmpty()
                ? toolName : action.name;
        return "Approve " + actionName + ".";
    }

    private static String riskLabel(String riskClass) {
        if ("high".equals(riskClass)) {
            return "High";
        }
        if ("medium".equals(riskClass)) {
            return "Medium";
        }
        if ("low".equals(riskClass)) {
            return "Low";
        }
        return "Review";
    }

    private String appPolicyDenial(String toolName, JSONObject arguments) {
        String capability = capabilityForTool(toolName, arguments);
        if (capability == null) {
            return null;
        }
        String foregroundPackage = currentForegroundPackage();
        AppCapabilityPolicy.Decision decision =
                AppCapabilityPolicy.evaluate(this, foregroundPackage, capability);
        if (!decision.requiresIntervention()) {
            return null;
        }
        if ("confirm".equals(decision.action) && "yolo".equals(mAutonomyMode)
                && isYoloCapability(capability)) {
            return null;
        }
        try {
            String status = "deny".equals(decision.action)
                    ? "action_denied" : "confirmation_required";
            return new JSONObject()
                    .put("status", status)
                    .put("reason", "app_policy_" + decision.action)
                    .put("foreground_package", foregroundPackage)
                    .put("capability", capability)
                    .put("summary", "This app has a stricter OpenPhone policy for "
                            + capabilityLabel(capability) + ".")
                    .put("risk", "explicit_confirm".equals(decision.action)
                            || "deny".equals(decision.action) ? "High" : "Medium")
                    .put("policy_reason", decision.reason)
                    .put("action_json", new JSONObject()
                            .put("tool", toolName)
                            .put("arguments", arguments))
                    .toString();
        } catch (JSONException e) {
            return "{\"status\":\"confirmation_required\","
                    + "\"reason\":\"app_policy_required\"}";
        }
    }

    private String missingTaskGrant(String toolName, JSONObject arguments) {
        if (("tap".equals(toolName) || "tap_element".equals(toolName)
                || "long_press".equals(toolName) || "long_press_element".equals(toolName)
                || "swipe".equals(toolName) || "type_text".equals(toolName)
                || "press_key".equals(toolName))
                && !inputGrantEnabled()) {
            return "input.perform";
        }
        if (("get_screen".equals(toolName) || "watch_screen".equals(toolName))
                && arguments.optBoolean("include_screenshot", false)
                && !screenCaptureGrantEnabled()) {
            return "screen.capture";
        }
        if (("set_clipboard".equals(toolName) || "paste".equals(toolName))
                && !clipboardGrantEnabled()) {
            return "clipboard.write";
        }
        if ("share_text".equals(toolName) && !shareGrantEnabled()) {
            return "share.content";
        }
        if (("open_url".equals(toolName) || "browser_search".equals(toolName))
                && !networkGrantEnabled()) {
            return "network.use";
        }
        return null;
    }

    private static String capabilityForTool(String toolName, JSONObject arguments) {
        if ("tap".equals(toolName) || "tap_element".equals(toolName)
                || "long_press".equals(toolName) || "long_press_element".equals(toolName)
                || "swipe".equals(toolName) || "type_text".equals(toolName)
                || "press_key".equals(toolName)) {
            return "input.perform";
        }
        if (("get_screen".equals(toolName) || "watch_screen".equals(toolName))
                && arguments.optBoolean("include_screenshot", false)) {
            return "screen.capture";
        }
        if ("get_screen".equals(toolName) || "watch_screen".equals(toolName)) {
            return "screen.read.visible";
        }
        if ("context_search".equals(toolName) || "wait".equals(toolName)
                || "ask_user_confirmation".equals(toolName)
                || "finish_task".equals(toolName) || "fail_task".equals(toolName)) {
            return "tasks.observe";
        }
        if ("notifications_list".equals(toolName)
                || "notifications_search".equals(toolName)
                || "notifications_summary".equals(toolName)) {
            return "notifications.read";
        }
        if ("notifications_open".equals(toolName)) {
            return "notifications.act";
        }
        if ("calendar_search".equals(toolName)
                || "calendar_check_availability".equals(toolName)) {
            return "calendar.read";
        }
        if ("calendar_create_event".equals(toolName)
                || "message_calendar_event_create".equals(toolName)
                || "calendar_update_event".equals(toolName)) {
            return "calendar.write";
        }
        if ("calendar_delete_event".equals(toolName)) {
            return "calendar.delete";
        }
        if ("contacts_search".equals(toolName)) {
            return "contacts.read";
        }
        if ("messages_search".equals(toolName) || "messages_summary".equals(toolName)) {
            return "messages.read";
        }
        if ("messages_draft".equals(toolName)) {
            return "messages.draft";
        }
        if ("messages_send".equals(toolName)) {
            return "messages.send";
        }
        if ("calls_search".equals(toolName) || "phone_context".equals(toolName)) {
            return "calls.read";
        }
        if ("calls_place".equals(toolName)) {
            return "calls.place";
        }
        if ("memory_search".equals(toolName)) {
            return "memory.read";
        }
        if ("memory_save".equals(toolName)) {
            return "memory.write";
        }
        if ("commitment_search".equals(toolName)) {
            return "commitments.read";
        }
        if ("commitment_create".equals(toolName)
                || "notification_commitment_create".equals(toolName)
                || "message_commitment_create".equals(toolName)
                || "commitment_update_status".equals(toolName)) {
            return "commitments.write";
        }
        if ("watcher_list".equals(toolName)) {
            return "watchers.read";
        }
        if ("watcher_create".equals(toolName) || "watcher_stop".equals(toolName)) {
            return "watchers.write";
        }
        if ("set_clipboard".equals(toolName)) {
            return "clipboard.write";
        }
        if ("paste".equals(toolName)) {
            return "clipboard.read";
        }
        if ("share_text".equals(toolName)) {
            return "share.content";
        }
        if ("open_url".equals(toolName) || "browser_search".equals(toolName)) {
            return "network.use";
        }
        if ("browser_fetch_page".equals(toolName)) {
            return "network.use";
        }
        if ("open_app".equals(toolName)) {
            return "apps.launch";
        }
        return null;
    }

    private static String currentForegroundPackage() {
        try {
            JSONObject snapshot = new JSONObject(OpenPhoneAccessibilityService.snapshotJson());
            String foregroundPackage = snapshot.optString("foreground_package", "");
            if (!foregroundPackage.isEmpty()) {
                return foregroundPackage;
            }
            JSONArray packages = snapshot.optJSONArray("root_packages");
            if (packages != null && packages.length() > 0) {
                return packages.optString(0, "");
            }
        } catch (JSONException ignored) {
        }
        return "";
    }

    private static String capabilityLabel(String capability) {
        if ("input.perform".equals(capability)) {
            return "tapping, typing, or navigation";
        }
        if ("screen.capture".equals(capability)) {
            return "screenshot capture";
        }
        if ("clipboard.write".equals(capability) || "clipboard.read".equals(capability)) {
            return "clipboard access";
        }
        if ("share.content".equals(capability)) {
            return "sharing content";
        }
        if ("network.use".equals(capability)) {
            return "opening web links";
        }
        return capability;
    }

    private void movePointerFromAction() {
        try {
            JSONObject action = new JSONObject(currentActionJson());
            movePointerForActionJson(action);
        } catch (JSONException ignored) {
        }
    }

    private void movePointerForActionJson(JSONObject action) {
        String type = action.optString("type", "");
        if ("tap".equals(type) || "long_press".equals(type)) {
            JSONObject target = action.optJSONObject("target");
            if (target != null) {
                mPointerOverlayController.pointerTap(
                        (float) target.optDouble("x", 0),
                        (float) target.optDouble("y", 0),
                        "long_press".equals(type));
            }
        } else if ("scroll".equals(type) || "swipe".equals(type)) {
            JSONObject target = action.optJSONObject("target");
            if (target == null) {
                target = action;
            }
            mPointerOverlayController.pointerSwipe(
                    (float) target.optDouble("start_x", 0),
                    (float) target.optDouble("start_y", 0),
                    (float) target.optDouble("end_x", 0),
                    (float) target.optDouble("end_y", 0));
        } else if ("type_text".equals(type) || "paste".equals(type)) {
            mPointerOverlayController.typingIndicator();
        }
    }

    private void movePointerFromTool(String toolName, JSONObject arguments) {
        if ("tap".equals(toolName) || "long_press".equals(toolName)) {
            final float x = (float) arguments.optDouble("x", 0);
            final float y = (float) arguments.optDouble("y", 0);
            final boolean longPress = "long_press".equals(toolName);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mPointerOverlayController.pointerTap(x, y, longPress);
                }
            });
            return;
        }
        if ("tap_element".equals(toolName) || "long_press_element".equals(toolName)) {
            final JSONObject center = elementCenterFromCurrentSnapshot(arguments.optString(
                    "element_id", ""));
            if (center != null) {
                final float x = (float) center.optDouble("x", 0);
                final float y = (float) center.optDouble("y", 0);
                final boolean longPress = "long_press_element".equals(toolName);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mPointerOverlayController.pointerTap(x, y, longPress);
                    }
                });
            }
            return;
        }
        if ("swipe".equals(toolName)) {
            final float startX = (float) arguments.optDouble("start_x", 0);
            final float startY = (float) arguments.optDouble("start_y", 0);
            final float endX = (float) arguments.optDouble("end_x", 0);
            final float endY = (float) arguments.optDouble("end_y", 0);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mPointerOverlayController.pointerSwipe(startX, startY, endX, endY);
                }
            });
            return;
        }
        if ("type_text".equals(toolName) || "paste".equals(toolName)) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mPointerOverlayController.typingIndicator();
                }
            });
        }
    }

    private static String parseString(String json, String key) {
        try {
            return new JSONObject(json).optString(key, null);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String statusSummary(String json) {
        if (json == null || json.isEmpty()) {
            return "Service status unavailable";
        }
        try {
            JSONObject status = new JSONObject(json);
            String state = status.optString("state", "unknown");
            int pendingActions = status.optInt("pending_actions", 0);
            return "OpenPhone " + state + "  |  " + pendingActions + " pending";
        } catch (JSONException e) {
            return json;
        }
    }

    private static boolean isActionResultFailure(String json) {
        JSONObject result = parseObjectOrEmpty(json);
        String status = result.optString("status", result.optString("state", ""));
        return status.contains("error")
                || status.contains("failed")
                || status.contains("denied")
                || status.contains("blocked");
    }

    private static String confirmationBodyFromActionResult(String json) {
        try {
            JSONObject result = new JSONObject(json);
            String capability = result.optString("capability", "unknown");
            String input = result.optString("input", "");
            JSONObject action = parseObjectOrEmpty(input);
            String type = action.optString("type", "unknown");
            return "Risk: " + riskForCapability(capability, type) + "\n"
                    + "Capability: " + capability + "\n"
                    + "Requested action: " + actionSummary(action) + "\n\n"
                    + "Approve only if this matches your current task. Deny if it would "
                    + "send, post, buy, install, delete, share private data, or change "
                    + "security settings unexpectedly.";
        } catch (JSONException e) {
            return "Review the requested action before continuing.";
        }
    }

    private static String confirmationBodyFromAgentResult(String status,
            JSONObject confirmation) {
        String summary = confirmation.optString("summary",
                confirmation.optString("detail", "The agent needs your review."));
        String risk = confirmation.optString("risk", "");
        JSONObject action = confirmation.optJSONObject("action");
        if (action == null) {
            action = confirmation.optJSONObject("action_json");
        }
        if (action == null) {
            action = parseObjectOrEmpty(confirmation.optString("input", ""));
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Risk: ").append(risk.isEmpty()
                ? riskForCapability(confirmation.optString("capability", ""), action.optString("type", ""))
                : risk).append('\n');
        builder.append("Request: ").append(summary).append('\n');
        if (action.length() > 0) {
            builder.append("Requested action: ").append(actionSummary(action)).append('\n');
        }
        builder.append('\n');
        if ("action_denied".equals(status)) {
            builder.append("OpenPhone stopped because policy denied the action.");
        } else {
            builder.append("OpenPhone stopped before this action. Approve only after "
                    + "checking that it is expected for the active task.");
        }
        return builder.toString();
    }

    private static JSONObject findLastConfirmation(JSONObject agentResult) {
        JSONObject found = new JSONObject();
        JSONArray steps = agentResult.optJSONArray("steps");
        if (steps == null) {
            return found;
        }
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) {
                continue;
            }
            JSONObject toolResult = objectFromValue(step.opt("tool_result"));
            String status = toolResult.optString("status", toolResult.optString("state", ""));
            if ("confirmation_requested".equals(status)
                    || "confirmation_required".equals(status)
                    || "action.confirmation_required".equals(status)) {
                found = toolResult;
            }
        }
        return found;
    }

    private static String findStringRecursive(Object value, String key) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String direct = object.optString(key, "");
            if (!direct.isEmpty() && !"null".equals(direct)) {
                return direct;
            }
            JSONArray names = object.names();
            if (names == null) {
                return "";
            }
            for (int i = 0; i < names.length(); i++) {
                String nested = findStringRecursive(object.opt(names.optString(i)), key);
                if (nested != null && !nested.isEmpty()) {
                    return nested;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String nested = findStringRecursive(array.opt(i), key);
                if (nested != null && !nested.isEmpty()) {
                    return nested;
                }
            }
        } else if (value instanceof String) {
            JSONObject object = parseObjectOrEmpty((String) value);
            if (object.length() > 0) {
                return findStringRecursive(object, key);
            }
        }
        return "";
    }

    private static JSONObject parseObjectOrEmpty(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject objectFromValue(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value instanceof String) {
            return parseObjectOrEmpty((String) value);
        }
        return new JSONObject();
    }

    private static String actionSummary(JSONObject action) {
        String type = action.optString("type", action.optString("tool", "unknown"));
        if ("open_app".equals(type)) {
            return "Open app " + action.optString("package",
                    action.optString("package_or_label", ""));
        }
        if ("share".equals(type) || "share_text".equals(type)) {
            return "Share text";
        }
        if ("copy".equals(type) || "set_clipboard".equals(type)) {
            return "Copy text to clipboard";
        }
        if ("paste".equals(type)) {
            return "Paste clipboard text";
        }
        if ("type_text".equals(type)) {
            return "Type text";
        }
        if ("tap".equals(type) || "tap_element".equals(type)
                || "long_press".equals(type) || "long_press_element".equals(type)
                || "scroll".equals(type) || "swipe".equals(type)) {
            return type.replace('_', ' ') + " on the screen";
        }
        return type.replace('_', ' ');
    }

    private static JSONObject elementCenterFromCurrentSnapshot(String elementId) {
        if (elementId == null || elementId.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject snapshot = new JSONObject(OpenPhoneAccessibilityService.snapshotJson());
            JSONArray elements = snapshot.optJSONArray("interactive_elements");
            if (elements == null) {
                return null;
            }
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.optJSONObject(i);
                if (element == null || !elementId.equals(element.optString("id"))) {
                    continue;
                }
                JSONArray bounds = element.optJSONArray("bounds");
                if (bounds == null || bounds.length() < 4) {
                    return null;
                }
                double left = bounds.optDouble(0);
                double top = bounds.optDouble(1);
                double right = bounds.optDouble(2);
                double bottom = bounds.optDouble(3);
                if (right <= left || bottom <= top) {
                    return null;
                }
                return new JSONObject()
                        .put("x", (left + right) / 2.0)
                        .put("y", (top + bottom) / 2.0);
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    private static String riskForCapability(String capability, String actionType) {
        String combined = (capability + " " + actionType).toLowerCase();
        if (combined.contains("share") || combined.contains("payment")
                || combined.contains("purchase") || combined.contains("install")
                || combined.contains("delete") || combined.contains("security")
                || combined.contains("account") || combined.contains("message")
                || combined.contains("call")) {
            return "High";
        }
        if (combined.contains("clipboard") || combined.contains("paste")
                || combined.contains("type")) {
            return "Medium";
        }
        return "Review";
    }

    private static String prettyForDisplay(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            Object parsed = json.trim().startsWith("[") ? new JSONArray(json) : new JSONObject(json);
            redactForDisplay(parsed);
            if (parsed instanceof JSONArray) {
                return ((JSONArray) parsed).toString(2);
            }
            return ((JSONObject) parsed).toString(2);
        } catch (JSONException e) {
            return json;
        }
    }

    private static void redactForDisplay(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                Object child = object.opt(name);
                if (isSecretField(name)) {
                    object.put(name, "<redacted>");
                } else if ("data".equals(name) && isScreenshotObject(object) && child instanceof String) {
                    object.put(name, "<base64 chars=" + ((String) child).length() + ">");
                } else {
                    redactForDisplay(child);
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                redactForDisplay(array.opt(i));
            }
        }
    }

    private static boolean isScreenshotObject(JSONObject object) {
        return object.has("mime_type") && "base64".equals(object.optString("encoding"));
    }

    private static boolean isSecretField(String name) {
        String lower = name.toLowerCase();
        return lower.contains("api_key") || lower.contains("authorization")
                || lower.contains("token") || lower.contains("secret");
    }

    private static String agentResultForDisplay(String json) {
        if (json == null || json.isEmpty()) {
            return "I stopped before returning a result.";
        }
        try {
            JSONObject result = new JSONObject(json);
            String status = result.optString("status", "");
            if ("task.finished".equals(status)) {
                String answer = result.optString("answer", "").trim();
                return answer.isEmpty() ? "Done." : "Done.\n\n" + answer;
            }
            if ("dry_run.preview".equals(status)) {
                return "Dry run previewed the next action without executing it.";
            }
            if ("confirmation_required".equals(status) || "action_denied".equals(status)) {
                String reason = result.optString("reason", "").replace('_', ' ').trim();
                return reason.isEmpty()
                        ? "I need your approval before continuing."
                        : "I need your approval before continuing.\n\n" + reason;
            }
            if ("realtime_error".equals(status)) {
                String message = result.optString("message", "").trim();
                return message.isEmpty()
                        ? "Realtime request failed."
                        : "Realtime request failed.\n\n" + message;
            }
            if ("provider_unavailable".equals(status) || "unavailable".equals(status)) {
                String reason = result.optString("reason", result.optString("message", ""))
                        .replace('_', ' ').trim();
                return reason.isEmpty()
                        ? "Model provider is not configured."
                        : "Model provider is not configured.\n\n" + reason;
            }
            if ("agent.blocked".equals(status) || "task.failed".equals(status)
                    || "screen_capture_failed".equals(status)
                    || "step_limit_reached".equals(status)
                    || "duration_limit_reached".equals(status)) {
                String reason = result.optString("reason", result.optString("model_text",
                        result.optString("message", ""))).replace('_', ' ').trim();
                return reason.isEmpty()
                        ? "The agent stopped before finishing. Status: " + status
                        : "The agent stopped before finishing.\n\n" + reason;
            }
            String reason = result.optString("reason", "").replace('_', ' ').trim();
            if (!reason.isEmpty()) {
                return "The agent stopped before finishing.\n\n" + reason;
            }
            if (!status.isEmpty()) {
                return "The agent stopped before finishing. Status: " + status;
            }
        } catch (JSONException ignored) {
        }
        return "The agent stopped before finishing.";
    }

    private static String taskFinishedMessage(String json) {
        if (json == null || json.isEmpty()) {
            return "Done.";
        }
        try {
            JSONObject result = new JSONObject(json);
            String answer = result.optString("answer", "").trim();
            return answer.isEmpty() ? "Done." : answer;
        } catch (JSONException ignored) {
        }
        return "Done.";
    }

    private static boolean isCancelledResult(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        try {
            return "cancelled".equals(new JSONObject(json).optString("status"));
        } catch (JSONException e) {
            return json.contains("\"status\":\"cancelled\"")
                    || json.contains("\"status\": \"cancelled\"");
        }
    }
}
