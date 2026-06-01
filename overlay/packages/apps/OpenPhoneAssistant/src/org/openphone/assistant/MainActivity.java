package org.openphone.assistant;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.openphone.OpenPhoneAgentManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openphone.assistant.agent.FrameworkToolExecutor;
import org.openphone.assistant.agent.AuditEvidenceExporter;
import org.openphone.assistant.agent.TrajectoryRecorder;
import org.openphone.assistant.model.LocalHeuristicModelAdapter;
import org.openphone.assistant.model.ModelAdapter;
import org.openphone.assistant.model.OpenAiRealtimeAdapter;
import org.openphone.assistant.model.OpenAiSpeechTranscriber;

import java.io.IOException;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final int VOICE_CAPTURE_MILLIS = 5000;

    private OpenPhoneAgentManager mAgentManager;
    private PointerOverlayController mPointerOverlayController;
    private String mActiveTaskId;
    private String mPendingActionId;
    private LinearLayout mAdvancedPanel;
    private TextView mIslandView;
    private TextView mStatusView;
    private TextView mTaskView;
    private LinearLayout mConfirmationPanel;
    private TextView mConfirmationBody;
    private TextView mModelDisclosureView;
    private TextView mContextView;
    private TextView mAuditView;
    private EditText mGoalInput;
    private EditText mActionInput;
    private EditText mApiKeyInput;
    private CheckBox mInputGrant;
    private CheckBox mScreenCaptureGrant;
    private CheckBox mClipboardGrant;
    private CheckBox mShareGrant;
    private CheckBox mNetworkGrant;
    private CheckBox mUseRealtime;
    private volatile boolean mAgentRunCancelled;
    private int mAgentRunGeneration;
    private Thread mAgentThread;
    private ModelAdapter mRunningModelAdapter;
    private boolean mListening;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAgentManager = getSystemService(OpenPhoneAgentManager.class);
        mPointerOverlayController = new PointerOverlayController(this);
        OpenPhoneAccessibilityService.ensureEnabled(this);
        setContentView(buildView());
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        OpenPhoneAccessibilityService.ensureEnabled(this);
    }

    private View buildView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getColor(R.color.openphone_background));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(52), dp(20), dp(28));
        scrollView.addView(root);

        mIslandView = label("OpenPhone is ready", 14, true);
        mIslandView.setTextColor(getColor(R.color.openphone_accent));
        mIslandView.setGravity(android.view.Gravity.CENTER);
        mIslandView.setTypeface(Typeface.DEFAULT_BOLD);
        mIslandView.setBackground(pillBackground(R.color.openphone_surface_high,
                R.color.openphone_stroke));
        mIslandView.setPadding(dp(18), dp(10), dp(18), dp(10));
        root.addView(mIslandView, blockParams());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(8), 0, dp(18));
        root.addView(header);

        TextView title = label("What should I do?", 30, true);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        TextView subtitle = label("Speak a task and OpenPhone can look, tap, type, and navigate.",
                15, false);
        subtitle.setTextColor(getColor(R.color.openphone_text_muted));
        subtitle.setPadding(0, dp(6), 0, 0);
        header.addView(subtitle);

        LinearLayout goalPanel = panel();
        root.addView(goalPanel, blockParams());

        mGoalInput = new EditText(this);
        mGoalInput.setSingleLine(false);
        mGoalInput.setMinLines(3);
        mGoalInput.setTextSize(16);
        mGoalInput.setTextColor(getColor(R.color.openphone_text_primary));
        mGoalInput.setHintTextColor(getColor(R.color.openphone_text_secondary));
        mGoalInput.setHint("Say or type something like: open Spotify, find flights, change a setting...");
        styleInput(mGoalInput);
        goalPanel.addView(mGoalInput, blockParams());

        Button startAgentButton = button("Start Agent", true, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startVoiceAgent();
            }
        });
        startAgentButton.setTextSize(18);
        goalPanel.addView(startAgentButton, blockParams());

        Button typeAgentButton = button("Run Typed Task", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startAgentFromCurrentGoal();
            }
        });
        goalPanel.addView(typeAgentButton, blockParams());

        mTaskView = section(root, "Agent");
        mTaskView.setText("Idle");

        mConfirmationPanel = panel();
        mConfirmationPanel.setVisibility(View.GONE);
        root.addView(mConfirmationPanel, blockParams());
        TextView confirmationTitle = sectionTitle("Needs your approval");
        mConfirmationPanel.addView(confirmationTitle);
        mConfirmationBody = body();
        mConfirmationBody.setTextColor(getColor(R.color.openphone_text_primary));
        mConfirmationBody.setBackground(panelBackground(R.color.openphone_background));
        mConfirmationBody.setPadding(dp(12), dp(10), dp(12), dp(10));
        mConfirmationPanel.addView(mConfirmationBody, blockParams());
        LinearLayout confirmationRow = buttonRow();
        mConfirmationPanel.addView(confirmationRow, blockParams());
        confirmationRow.addView(button("Approve", true, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmPending(true);
            }
        }), weightedButtonParams());
        confirmationRow.addView(button("Deny", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmPending(false);
            }
        }), weightedButtonParams());

        Button advancedButton = button("Advanced", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleAdvanced();
            }
        });
        root.addView(advancedButton, blockParams());

        mAdvancedPanel = panel();
        mAdvancedPanel.setVisibility(View.GONE);
        root.addView(mAdvancedPanel, blockParams());
        mAdvancedPanel.addView(sectionTitle("Model"));

        mUseRealtime = new CheckBox(this);
        mUseRealtime.setText("Use OpenAI vision model");
        mUseRealtime.setTextColor(getColor(R.color.openphone_text_primary));
        mUseRealtime.setChecked(true);
        mUseRealtime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshModelDisclosure();
            }
        });
        mAdvancedPanel.addView(mUseRealtime, fullWidthParams());

        mModelDisclosureView = body();
        mModelDisclosureView.setTextColor(getColor(R.color.openphone_text_muted));
        mModelDisclosureView.setBackground(panelBackground(R.color.openphone_background));
        mModelDisclosureView.setPadding(dp(12), dp(10), dp(12), dp(10));
        mAdvancedPanel.addView(mModelDisclosureView, blockParams());

        mApiKeyInput = new EditText(this);
        mApiKeyInput.setSingleLine(true);
        mApiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        mApiKeyInput.setTextColor(getColor(R.color.openphone_text_primary));
        mApiKeyInput.setHintTextColor(getColor(R.color.openphone_text_secondary));
        mApiKeyInput.setHint("Dev OpenAI API key (memory only)");
        styleInput(mApiKeyInput);
        mAdvancedPanel.addView(mApiKeyInput, blockParams());

        LinearLayout grantPanel = panel();
        mAdvancedPanel.addView(grantPanel, blockParams());
        grantPanel.addView(sectionTitle("Task Grants"));

        mInputGrant = grantCheckBox("Tap, type, and navigate", true);
        grantPanel.addView(mInputGrant, fullWidthParams());

        mScreenCaptureGrant = grantCheckBox("Capture screenshots while task is active", true);
        grantPanel.addView(mScreenCaptureGrant, fullWidthParams());

        mClipboardGrant = grantCheckBox("Use clipboard", false);
        grantPanel.addView(mClipboardGrant, fullWidthParams());

        mShareGrant = grantCheckBox("Open Android share sheet", false);
        grantPanel.addView(mShareGrant, fullWidthParams());

        mNetworkGrant = grantCheckBox("Open web links", true);
        grantPanel.addView(mNetworkGrant, fullWidthParams());

        mActionInput = new EditText(this);
        mActionInput.setSingleLine(false);
        mActionInput.setMinLines(2);
        mActionInput.setTextColor(getColor(R.color.openphone_text_primary));
        mActionInput.setHintTextColor(getColor(R.color.openphone_text_secondary));
        mActionInput.setHint("Action JSON");
        mActionInput.setText("{\"type\":\"tap\",\"target\":{\"x\":400,\"y\":800}}");
        styleInput(mActionInput);

        LinearLayout actionsPanel = panel();
        mAdvancedPanel.addView(actionsPanel, blockParams());
        actionsPanel.addView(sectionTitle("Developer Controls"));

        LinearLayout primaryRow = buttonRow();
        actionsPanel.addView(primaryRow, blockParams());
        primaryRow.addView(button("Start", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startTask();
            }
        }), weightedButtonParams());
        primaryRow.addView(button("Run Agent", true, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runAgent();
            }
        }), weightedButtonParams());
        primaryRow.addView(button("Stop", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopTask();
            }
        }), weightedButtonParams());

        LinearLayout inspectRow = buttonRow();
        actionsPanel.addView(inspectRow, blockParams());
        inspectRow.addView(button("Screen", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                readScreenContext();
            }
        }), weightedButtonParams());
        inspectRow.addView(button("Shot", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                readScreenshotContext();
            }
        }), weightedButtonParams());
        inspectRow.addView(button("Back", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                executeBack();
            }
        }), weightedButtonParams());

        LinearLayout exportRow = buttonRow();
        actionsPanel.addView(exportRow, blockParams());
        exportRow.addView(button("Export Trace", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportLatestTrajectory();
            }
        }), weightedButtonParams());
        exportRow.addView(button("Export Audit", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportAuditEvidence();
            }
        }), weightedButtonParams());

        actionsPanel.addView(mActionInput, blockParams());

        LinearLayout actionRow = buttonRow();
        actionsPanel.addView(actionRow, blockParams());
        actionRow.addView(button("Action", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestAction();
            }
        }), weightedButtonParams());
        actionRow.addView(button("Approve", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmPending(true);
            }
        }), weightedButtonParams());
        actionRow.addView(button("Deny", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmPending(false);
            }
        }), weightedButtonParams());
        actionRow.addView(button("Refresh", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshAll();
            }
        }), weightedButtonParams());

        mContextView = section(mAdvancedPanel, "Screen Context");
        mContextView.setTypeface(Typeface.MONOSPACE);
        mAuditView = section(mAdvancedPanel, "Audit Log");
        mAuditView.setTypeface(Typeface.MONOSPACE);

        mStatusView = body();
        mStatusView.setVisibility(View.GONE);
        refreshModelDisclosure();
        return scrollView;
    }

    private TextView section(LinearLayout root, String title) {
        LinearLayout panel = panel();
        root.addView(panel, blockParams());
        panel.addView(sectionTitle(title));
        TextView body = body();
        body.setTextColor(getColor(R.color.openphone_text_secondary));
        body.setBackground(panelBackground(R.color.openphone_background));
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.addView(body, blockParams());
        return body;
    }

    private Button button(String text, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? Color.BLACK : getColor(R.color.openphone_text_primary));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(buttonBackground(primary));
        button.setOnClickListener(listener);
        return button;
    }

    private CheckBox grantCheckBox(String text, boolean checked) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(text);
        checkBox.setTextColor(getColor(R.color.openphone_text_primary));
        checkBox.setChecked(checked);
        return checkBox;
    }

    private TextView label(String text, int sp, boolean primary) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setIncludeFontPadding(true);
        view.setTextColor(getColor(primary
                ? R.color.openphone_text_primary : R.color.openphone_text_secondary));
        return view;
    }

    private TextView body() {
        TextView view = label("", 13, false);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView title = label(text, 15, true);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(10));
        return title;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(panelBackground(R.color.openphone_surface));
        return panel;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        return row;
    }

    private void styleInput(EditText input) {
        input.setTextSize(14);
        input.setBackground(panelBackground(R.color.openphone_surface_high));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    private GradientDrawable panelBackground(int colorRes) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(getColor(colorRes));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), getColor(R.color.openphone_stroke));
        return drawable;
    }

    private GradientDrawable pillBackground(int fillColorRes, int strokeColorRes) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(getColor(fillColorRes));
        drawable.setCornerRadius(dp(28));
        drawable.setStroke(dp(1), getColor(strokeColorRes));
        return drawable;
    }

    private StateListDrawable buttonBackground(boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(primary ? R.color.openphone_accent_pressed : R.color.openphone_surface,
                        primary ? R.color.openphone_accent_pressed : R.color.openphone_stroke));
        states.addState(new int[]{},
                rounded(primary ? R.color.openphone_accent : R.color.openphone_surface_high,
                        primary ? R.color.openphone_accent : R.color.openphone_stroke));
        return states;
    }

    private GradientDrawable rounded(int fillColorRes, int strokeColorRes) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(getColor(fillColorRes));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), getColor(strokeColorRes));
        return drawable;
    }

    private LinearLayout.LayoutParams blockParams() {
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(12));
        return params;
    }

    private LinearLayout.LayoutParams fullWidthParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshAll() {
        if (mAgentManager == null) {
            updateIsland("Assistant unavailable");
            mTaskView.setText("OpenPhone system service is unavailable.");
            mContextView.setText("");
            mAuditView.setText("");
            return;
        }
        updateIsland(statusSummary(mAgentManager.getServiceStatus()));
        refreshAudit();
    }

    private void refreshModelDisclosure() {
        if (mModelDisclosureView == null || mUseRealtime == null) {
            return;
        }
        ModelAdapter adapter = selectedModelAdapter("");
        String disclosure = modelRunDisclosure(adapter);
        if (adapter.usesCloud()) {
            disclosure += "\n\nVoice Start Agent: " + OpenAiSpeechTranscriber.providerDisplayName()
                    + " / " + OpenAiSpeechTranscriber.modelName()
                    + ". " + OpenAiSpeechTranscriber.privacyDisclosure();
        }
        mModelDisclosureView.setText(disclosure);
    }

    private ModelAdapter selectedModelAdapter(String apiKey) {
        return mUseRealtime != null && mUseRealtime.isChecked()
                ? new OpenAiRealtimeAdapter(apiKey)
                : new LocalHeuristicModelAdapter();
    }

    private static String modelRunDisclosure(ModelAdapter adapter) {
        return "Provider: " + adapter.providerDisplayName()
                + "\nModel: " + adapter.modelName()
                + "\nCloud model: " + (adapter.usesCloud() ? "yes" : "no")
                + "\n" + adapter.privacyDisclosure();
    }

    private void startVoiceAgent() {
        if (mListening) {
            return;
        }
        if (mApiKeyInput.getText().toString().trim().isEmpty()) {
            mTaskView.setText("Add a dev OpenAI API key in Advanced, then tap Start Agent.");
            updateIsland("Setup needed");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        listenThenRun();
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
            mTaskView.setText("Microphone permission is needed for Start Agent.");
            updateIsland("Mic blocked");
        }
    }

    private void listenThenRun() {
        final String apiKey = mApiKeyInput.getText().toString();
        mListening = true;
        mTaskView.setText("Listening...\n\n" + OpenAiSpeechTranscriber.privacyDisclosure()
                + "\nProvider: " + OpenAiSpeechTranscriber.providerDisplayName()
                + "\nModel: " + OpenAiSpeechTranscriber.modelName());
        updateIsland("Listening...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = "";
                String error = null;
                try {
                    text = new OpenAiSpeechTranscriber(apiKey)
                            .recordAndTranscribe(VOICE_CAPTURE_MILLIS);
                } catch (IOException e) {
                    error = e.getMessage();
                }
                final String finalText = text;
                final String finalError = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mListening = false;
                        if (finalError != null) {
                            mTaskView.setText("I couldn't hear the task.\n\n" + finalError);
                            updateIsland("Try again");
                            return;
                        }
                        if (finalText == null || finalText.trim().isEmpty()) {
                            mTaskView.setText("I didn't catch that. Tap Start Agent and speak again.");
                            updateIsland("Try again");
                            return;
                        }
                        mGoalInput.setText(finalText.trim());
                        startAgentFromCurrentGoal();
                    }
                });
            }
        }, "OpenPhoneVoiceCommand").start();
    }

    private void startAgentFromCurrentGoal() {
        String goal = mGoalInput.getText().toString().trim();
        if (goal.isEmpty()) {
            mTaskView.setText("Tell me what to do first.");
            updateIsland("Waiting for task");
            return;
        }
        if (mAgentManager == null) {
            refreshAll();
            return;
        }
        if (mUseRealtime.isChecked() && mApiKeyInput.getText().toString().trim().isEmpty()) {
            mTaskView.setText("Model key is missing. Open Advanced and add a dev key.");
            updateIsland("Setup needed");
            return;
        }
        startTask();
        runAgent();
    }

    private void toggleAdvanced() {
        if (mAdvancedPanel == null) {
            return;
        }
        mAdvancedPanel.setVisibility(mAdvancedPanel.getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);
    }

    private void updateIsland(String text) {
        if (mIslandView != null) {
            mIslandView.setText(text == null || text.isEmpty() ? "OpenPhone is ready" : text);
        }
    }

    private void startTask() {
        if (mAgentManager == null) {
            refreshAll();
            return;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("goal", mGoalInput.getText().toString());
            request.put("user_visible", true);
            request.put("background_allowed", false);
            JSONArray capabilities = new JSONArray();
            capabilities.put("screen.read.visible");
            capabilities.put("tasks.observe");
            capabilities.put("apps.launch");
            if (mInputGrant.isChecked()) {
                capabilities.put("input.perform");
            }
            if (mScreenCaptureGrant.isChecked()) {
                capabilities.put("screen.capture");
            }
            if (mClipboardGrant.isChecked()) {
                capabilities.put("clipboard.read");
                capabilities.put("clipboard.write");
            }
            if (mShareGrant.isChecked()) {
                capabilities.put("share.content");
            }
            if (mNetworkGrant.isChecked()) {
                capabilities.put("network.use");
            }
            request.put("approved_capabilities", capabilities);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }

        String response = mAgentManager.startTask(request.toString());
        mActiveTaskId = parseString(response, "task_id");
        mTaskView.setText("Working on: " + mGoalInput.getText().toString());
        updateIsland("Agent active");
        mPointerOverlayController.show(mActiveTaskId);
        OpenPhoneNotificationController.showActive(this, mActiveTaskId);
        refreshAudit();
    }

    private void stopTask() {
        cancelAgentRun();
        String taskId = mActiveTaskId;
        if (mAgentManager != null && taskId != null) {
            mAgentManager.stopTask(taskId, "{\"reason\":\"user_stopped_from_assistant\"}");
        }
        mActiveTaskId = null;
        mPendingActionId = null;
        hidePendingConfirmation();
        mPointerOverlayController.hide();
        OpenPhoneNotificationController.showReady(this);
        mTaskView.setText("Task stopped");
        updateIsland("Stopped");
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
            mContextView.setText("Start a task first");
            return;
        }
        mContextView.setText(prettyForDisplay(mAgentManager.getScreen(mActiveTaskId,
                "{\"include_screenshot\":false,\"include_activity\":true}")));
        refreshAudit();
    }

    private void readScreenshotContext() {
        if (mAgentManager == null || mActiveTaskId == null) {
            mContextView.setText("Start a task first");
            return;
        }
        mContextView.setText(prettyForDisplay(mAgentManager.getScreen(mActiveTaskId,
                "{\"include_screenshot\":true,\"include_activity\":true,"
                        + "\"max_dimension\":512,\"quality\":65}")));
        refreshAudit();
    }

    private void executeBack() {
        if (mAgentManager == null || mActiveTaskId == null) {
            mTaskView.setText("Start a task first");
            return;
        }
        String result = mAgentManager.executeAction(mActiveTaskId, "{\"type\":\"back\"}");
        mTaskView.setText(prettyForDisplay(result));
        refreshAudit();
    }

    private void requestAction() {
        if (mAgentManager == null || mActiveTaskId == null) {
            mTaskView.setText("Start a task first");
            return;
        }
        String result = mAgentManager.executeAction(mActiveTaskId,
                mActionInput.getText().toString());
        mPendingActionId = parseString(result, "pending_action_id");
        if (mPendingActionId != null && !mPendingActionId.isEmpty()) {
            showPendingConfirmation(confirmationBodyFromActionResult(result));
        }
        mTaskView.setText(prettyForDisplay(result));
        movePointerFromAction();
        refreshAudit();
    }

    private void runAgent() {
        if (mAgentManager == null || mActiveTaskId == null) {
            mTaskView.setText("Start a task first");
            return;
        }
        cancelAgentRun();
        mAgentRunCancelled = false;
        final int runGeneration = ++mAgentRunGeneration;
        final String taskId = mActiveTaskId;
        final String goal = mGoalInput.getText().toString();
        final String apiKey = mApiKeyInput.getText().toString();
        mTaskView.setText("Working on: " + goal);
        updateIsland("Agent is working");
        FrameworkToolExecutor toolExecutor = new FrameworkToolExecutor(this, mAgentManager);
        ModelAdapter adapter = selectedModelAdapter(apiKey);
        mRunningModelAdapter = adapter;
        mTaskView.setText("Working on: " + goal + "\n\n" + modelRunDisclosure(adapter));
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
                            String grantDenied = taskGrantDenial(toolName,
                                    new JSONObject(argumentsJson));
                            if (grantDenied != null) {
                                trajectory.recordToolResult(toolName, grantDenied);
                                return grantDenied;
                            }
                            movePointerFromTool(toolName, new JSONObject(argumentsJson));
                            if (isCancelled()) {
                                String cancelled = "{\"status\":\"cancelled\","
                                        + "\"reason\":\"user_stopped\"}";
                                trajectory.recordToolResult(toolName, cancelled);
                                return cancelled;
                            }
                            String toolResult = toolExecutor.execute(taskId, toolName,
                                    new JSONObject(argumentsJson));
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
                        if (mAgentRunCancelled || isCancelledResult(result)) {
                            mTaskView.setText("Task stopped"
                                    + "\n\nTrajectory: " + trajectory.sessionPath());
                            updateIsland("Stopped");
                            refreshAudit();
                            return;
                        }
                        mTaskView.setText(agentResultForDisplay(result)
                                + "\n\nTrajectory: " + trajectory.sessionPath());
                        showConfirmationIfNeeded(result);
                        updateIsland(result.contains("\"status\":\"task.finished\"")
                                || result.contains("\"status\": \"task.finished\"")
                                ? "Done" : "Needs review");
                        refreshAudit();
                    }
                });
            }
        }, "OpenPhoneModelRunner");
        mAgentThread = agentThread;
        agentThread.start();
    }

    private void confirmPending(boolean approved) {
        if (mAgentManager == null || mPendingActionId == null) {
            mTaskView.setText("There is no pending system action to approve. "
                    + "If the agent asked for review, check the request and run the task again.");
            return;
        }
        String result = mAgentManager.confirmAction(mPendingActionId, approved);
        mPendingActionId = null;
        hidePendingConfirmation();
        mTaskView.setText(prettyForDisplay(result));
        refreshAudit();
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
            showPendingConfirmation(confirmationBodyFromAgentResult(status, confirmation));
        } catch (JSONException e) {
            hidePendingConfirmation();
        }
    }

    private void showPendingConfirmation(String body) {
        if (mConfirmationPanel == null || mConfirmationBody == null) {
            return;
        }
        mConfirmationBody.setText(body == null || body.isEmpty()
                ? "Review the requested action before continuing." : body);
        mConfirmationPanel.setVisibility(View.VISIBLE);
        updateIsland("Approval needed");
    }

    private void hidePendingConfirmation() {
        if (mConfirmationPanel != null) {
            mConfirmationPanel.setVisibility(View.GONE);
        }
    }

    private void refreshAudit() {
        if (mAgentManager == null) {
            return;
        }
        mAuditView.setText(prettyForDisplay(mAgentManager.getAuditLog(25)));
    }

    private void exportLatestTrajectory() {
        try {
            mTaskView.setText(TrajectoryRecorder.exportLatestToDownloads(this));
            updateIsland("Trace exported");
        } catch (IOException e) {
            mTaskView.setText("Could not export the latest trace.\n\n" + e.getMessage());
            updateIsland("Export failed");
        }
    }

    private void exportAuditEvidence() {
        try {
            mTaskView.setText(AuditEvidenceExporter.exportToDownloads(this, mAgentManager, 200));
            updateIsland("Audit exported");
        } catch (IOException e) {
            mTaskView.setText("Could not export audit evidence.\n\n" + e.getMessage());
            updateIsland("Export failed");
        }
    }

    private String taskGrantDenial(String toolName, JSONObject arguments) {
        String missingCapability = missingTaskGrant(toolName, arguments);
        if (missingCapability == null) {
            return null;
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

    private String missingTaskGrant(String toolName, JSONObject arguments) {
        if (("tap".equals(toolName) || "long_press".equals(toolName)
                || "swipe".equals(toolName) || "type_text".equals(toolName)
                || "press_key".equals(toolName))
                && !mInputGrant.isChecked()) {
            return "input.perform";
        }
        if (("get_screen".equals(toolName) || "watch_screen".equals(toolName))
                && arguments.optBoolean("include_screenshot", false)
                && !mScreenCaptureGrant.isChecked()) {
            return "screen.capture";
        }
        if (("set_clipboard".equals(toolName) || "paste".equals(toolName))
                && !mClipboardGrant.isChecked()) {
            return "clipboard.write";
        }
        if ("share_text".equals(toolName) && !mShareGrant.isChecked()) {
            return "share.content";
        }
        if ("open_url".equals(toolName) && !mNetworkGrant.isChecked()) {
            return "network.use";
        }
        return null;
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
            JSONObject action = new JSONObject(mActionInput.getText().toString());
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
        if ("tap".equals(type) || "long_press".equals(type) || "scroll".equals(type)
                || "swipe".equals(type)) {
            return type.replace('_', ' ') + " on the screen";
        }
        return type.replace('_', ' ');
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
            String reason = result.optString("reason", "").replace('_', ' ').trim();
            if (!reason.isEmpty()) {
                return "I need review before continuing.\n\n" + reason;
            }
        } catch (JSONException ignored) {
        }
        return "I need review before continuing. Open Advanced for details.";
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
