package org.openphone.assistant;

import android.app.Activity;
import android.graphics.Typeface;
import android.openphone.OpenPhoneAgentManager;
import android.os.Bundle;
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

public final class MainActivity extends Activity {
    private OpenPhoneAgentManager mAgentManager;
    private String mActiveTaskId;
    private String mPendingActionId;
    private TextView mStatusView;
    private TextView mTaskView;
    private TextView mContextView;
    private TextView mAuditView;
    private EditText mGoalInput;
    private EditText mActionInput;
    private CheckBox mInputGrant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAgentManager = getSystemService(OpenPhoneAgentManager.class);
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
        root.addView(mInputGrant);

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
        actions.addView(button("Screen", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                readScreenContext();
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
        mTaskView.setText(pretty(response));
        refreshAudit();
    }

    private void readScreenContext() {
        if (mAgentManager == null || mActiveTaskId == null) {
            mContextView.setText("Start a task first");
            return;
        }
        mContextView.setText(pretty(mAgentManager.getScreenContext(mActiveTaskId)));
        refreshAudit();
    }

    private void executeBack() {
        if (mAgentManager == null || mActiveTaskId == null) {
            mTaskView.setText("Start a task first");
            return;
        }
        String result = mAgentManager.executeAction(mActiveTaskId, "{\"type\":\"back\"}");
        mTaskView.setText(pretty(result));
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
        mTaskView.setText(pretty(result));
        refreshAudit();
    }

    private void confirmPending(boolean approved) {
        if (mAgentManager == null || mPendingActionId == null) {
            mTaskView.setText("No pending action");
            return;
        }
        String result = mAgentManager.confirmAction(mPendingActionId, approved);
        mPendingActionId = null;
        mTaskView.setText(pretty(result));
        refreshAudit();
    }

    private void refreshAudit() {
        if (mAgentManager == null) {
            return;
        }
        mAuditView.setText(pretty(mAgentManager.getAuditLog(25)));
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
}
