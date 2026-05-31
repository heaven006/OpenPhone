package org.openphone.assistant;

import android.app.Activity;
import android.graphics.Typeface;
import android.openphone.OpenPhoneAgentManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
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
import org.openphone.assistant.model.LocalHeuristicModelAdapter;
import org.openphone.assistant.model.ModelAdapter;
import org.openphone.assistant.model.OpenAiRealtimeAdapter;

public final class MainActivity extends Activity {
    private OpenPhoneAgentManager mAgentManager;
    private PointerOverlayController mPointerOverlayController;
    private String mActiveTaskId;
    private String mPendingActionId;
    private TextView mStatusView;
    private TextView mTaskView;
    private TextView mContextView;
    private TextView mAuditView;
    private EditText mGoalInput;
    private EditText mActionInput;
    private EditText mApiKeyInput;
    private CheckBox mInputGrant;
    private CheckBox mUseRealtime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAgentManager = getSystemService(OpenPhoneAgentManager.class);
        mPointerOverlayController = new PointerOverlayController(this);
        setContentView(buildView());
        refreshAll();
    }

    private View buildView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        scrollView.addView(root);

        TextView title = label("OpenPhone Assistant", 22, true);
        root.addView(title);

        mStatusView = body();
        root.addView(mStatusView);

        mGoalInput = new EditText(this);
        mGoalInput.setSingleLine(false);
        mGoalInput.setMinLines(2);
        mGoalInput.setTextColor(getColor(R.color.openphone_text_primary));
        mGoalInput.setHintTextColor(getColor(R.color.openphone_text_secondary));
        mGoalInput.setHint("Task goal");
        mGoalInput.setText("Inspect the current screen");
        root.addView(mGoalInput);

        mInputGrant = new CheckBox(this);
        mInputGrant.setText("Approve input.perform for this task");
        mInputGrant.setTextColor(getColor(R.color.openphone_text_primary));
        mInputGrant.setChecked(true);
        root.addView(mInputGrant);

        mUseRealtime = new CheckBox(this);
        mUseRealtime.setText("Use OpenAI Responses vision test");
        mUseRealtime.setTextColor(getColor(R.color.openphone_text_primary));
        root.addView(mUseRealtime);

        mApiKeyInput = new EditText(this);
        mApiKeyInput.setSingleLine(true);
        mApiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        mApiKeyInput.setTextColor(getColor(R.color.openphone_text_primary));
        mApiKeyInput.setHintTextColor(getColor(R.color.openphone_text_secondary));
        mApiKeyInput.setHint("Dev OpenAI API key (memory only)");
        root.addView(mApiKeyInput);

        mActionInput = new EditText(this);
        mActionInput.setSingleLine(false);
        mActionInput.setMinLines(2);
        mActionInput.setTextColor(getColor(R.color.openphone_text_primary));
        mActionInput.setHintTextColor(getColor(R.color.openphone_text_secondary));
        mActionInput.setHint("Action JSON");
        mActionInput.setText("{\"type\":\"tap\",\"target\":{\"x\":400,\"y\":800}}");
        root.addView(mActionInput);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions);

        actions.addView(button("Start", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startTask();
            }
        }));
        actions.addView(button("Stop", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopTask();
            }
        }));
        actions.addView(button("Screen", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                readScreenContext();
            }
        }));
        actions.addView(button("Shot", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                readScreenshotContext();
            }
        }));
        actions.addView(button("Run Agent", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runAgent();
            }
        }));
        actions.addView(button("Back", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                executeBack();
            }
        }));
        actions.addView(button("Action", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestAction();
            }
        }));
        actions.addView(button("Approve", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmPending(true);
            }
        }));
        actions.addView(button("Deny", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmPending(false);
            }
        }));
        actions.addView(button("Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshAll();
            }
        }));

        mTaskView = section(root, "Task");
        mContextView = section(root, "Screen Context");
        mAuditView = section(root, "Audit Log");
        return scrollView;
    }

    private TextView section(LinearLayout root, String title) {
        TextView heading = label(title, 16, true);
        heading.setPadding(0, 28, 0, 8);
        root.addView(heading);

        TextView body = body();
        body.setTypeface(Typeface.MONOSPACE);
        root.addView(body);
        return body;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView label(String text, int sp, boolean primary) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(getColor(primary
                ? R.color.openphone_text_primary : R.color.openphone_text_secondary));
        return view;
    }

    private TextView body() {
        TextView view = label("", 13, false);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private void refreshAll() {
        if (mAgentManager == null) {
            mStatusView.setText("Framework service unavailable");
            mTaskView.setText("No framework service");
            mContextView.setText("");
            mAuditView.setText("");
            return;
        }
        mStatusView.setText(pretty(mAgentManager.getServiceStatus()));
        refreshAudit();
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
            if (mInputGrant.isChecked()) {
                capabilities.put("input.perform");
            }
            request.put("approved_capabilities", capabilities);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }

        String response = mAgentManager.startTask(request.toString());
        mActiveTaskId = parseString(response, "task_id");
        mTaskView.setText(prettyForDisplay(response));
        mPointerOverlayController.show(mActiveTaskId);
        OpenPhoneNotificationController.showActive(this, mActiveTaskId);
        refreshAudit();
    }

    private void stopTask() {
        String taskId = mActiveTaskId;
        if (mAgentManager != null && taskId != null) {
            mAgentManager.stopTask(taskId, "{\"reason\":\"user_stopped_from_assistant\"}");
        }
        mActiveTaskId = null;
        mPendingActionId = null;
        mPointerOverlayController.hide();
        OpenPhoneNotificationController.showReady(this);
        mTaskView.setText("Task stopped");
        refreshAudit();
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
        mTaskView.setText(prettyForDisplay(result));
        movePointerFromAction();
        refreshAudit();
    }

    private void runAgent() {
        if (mAgentManager == null || mActiveTaskId == null) {
            mTaskView.setText("Start a task first");
            return;
        }
        final String taskId = mActiveTaskId;
        final String goal = mGoalInput.getText().toString();
        final boolean useRealtime = mUseRealtime.isChecked();
        final String apiKey = mApiKeyInput.getText().toString();
        mTaskView.setText("Agent running...");
        FrameworkToolExecutor toolExecutor = new FrameworkToolExecutor(mAgentManager);
        ModelAdapter adapter = useRealtime
                ? new OpenAiRealtimeAdapter(apiKey)
                : new LocalHeuristicModelAdapter();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result = adapter.runTask(taskId, goal, new ModelAdapter.ToolExecutor() {
                    @Override
                    public String callTool(String toolName, String argumentsJson) {
                        try {
                            movePointerFromTool(toolName, new JSONObject(argumentsJson));
                            return toolExecutor.execute(taskId, toolName,
                                    new JSONObject(argumentsJson));
                        } catch (JSONException e) {
                            return "{\"status\":\"error\",\"reason\":\"bad_tool_json\"}";
                        }
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mTaskView.setText(prettyForDisplay(result));
                        refreshAudit();
                    }
                });
            }
        }, "OpenPhoneModelRunner").start();
    }

    private void confirmPending(boolean approved) {
        if (mAgentManager == null || mPendingActionId == null) {
            mTaskView.setText("No pending action");
            return;
        }
        String result = mAgentManager.confirmAction(mPendingActionId, approved);
        mPendingActionId = null;
        mTaskView.setText(prettyForDisplay(result));
        refreshAudit();
    }

    private void refreshAudit() {
        if (mAgentManager == null) {
            return;
        }
        mAuditView.setText(prettyForDisplay(mAgentManager.getAuditLog(25)));
    }

    private void movePointerFromAction() {
        try {
            JSONObject action = new JSONObject(mActionInput.getText().toString());
            JSONObject target = action.optJSONObject("target");
            if (target != null) {
                mPointerOverlayController.pointerMove(
                        (float) target.optDouble("x", 0),
                        (float) target.optDouble("y", 0));
            }
        } catch (JSONException ignored) {
        }
    }

    private void movePointerFromTool(String toolName, JSONObject arguments) {
        if (!"tap".equals(toolName) && !"long_press".equals(toolName)
                && !"swipe".equals(toolName)) {
            return;
        }
        final float x = (float) arguments.optDouble("x",
                arguments.optDouble("end_x", arguments.optDouble("start_x", 0)));
        final float y = (float) arguments.optDouble("y",
                arguments.optDouble("end_y", arguments.optDouble("start_y", 0)));
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mPointerOverlayController.pointerMove(x, y);
            }
        });
    }

    private static String parseString(String json, String key) {
        try {
            return new JSONObject(json).optString(key, null);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String pretty(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            return new JSONObject(json).toString(2);
        } catch (JSONException e) {
            return json;
        }
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
}
