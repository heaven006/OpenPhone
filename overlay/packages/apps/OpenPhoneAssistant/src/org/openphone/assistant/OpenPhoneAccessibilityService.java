package org.openphone.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.openphone.OpenPhoneAgentManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class OpenPhoneAccessibilityService extends AccessibilityService {
    private static final int MAX_TEXT_ITEMS = 120;
    private static final int MAX_ELEMENTS = 120;
    private static final Object sLock = new Object();

    private static OpenPhoneAccessibilityService sInstance;
    private static String sLastSnapshot = unavailableSnapshot("service_not_connected");

    @Override
    public void onServiceConnected() {
        sInstance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            setServiceInfo(info);
        }
        refreshSnapshot();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        refreshSnapshot();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        synchronized (sLock) {
            sLastSnapshot = unavailableSnapshot("service_unbound");
        }
        return super.onUnbind(intent);
    }

    public static void ensureEnabled(Context context) {
        if (context == null) {
            return;
        }
        ComponentName component = new ComponentName(context, OpenPhoneAccessibilityService.class);
        String flattened = component.flattenToString();
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) {
                enabled = flattened;
            } else if (!containsService(enabled, flattened)) {
                enabled = enabled + ":" + flattened;
            }
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException e) {
            synchronized (sLock) {
                sLastSnapshot = unavailableSnapshot("enable_denied");
            }
        }
    }

    public static String snapshotJson() {
        OpenPhoneAccessibilityService instance = sInstance;
        if (instance != null) {
            instance.refreshSnapshot();
        }
        synchronized (sLock) {
            return sLastSnapshot;
        }
    }

    private void refreshSnapshot() {
        try {
            JSONObject snapshot = new JSONObject()
                    .put("source", "openphone_accessibility_service")
                    .put("timestamp_ms", System.currentTimeMillis())
                    .put("visible_text", new JSONArray())
                    .put("interactive_elements", new JSONArray())
                    .put("risk_flags", new JSONArray())
                    .put("root_packages", new JSONArray());

            Set<String> textSeen = new HashSet<>();
            Counter counter = new Counter();
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            Rect screenBounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    collect(root, snapshot, textSeen, counter, null, screenBounds);
                    root.recycle();
                } else {
                    snapshot.getJSONArray("risk_flags").put("ui_tree_unavailable");
                }
            } else {
                JSONArray windowJson = new JSONArray();
                for (int i = 0; i < windows.size(); i++) {
                    AccessibilityWindowInfo window = windows.get(i);
                    if (window == null) {
                        continue;
                    }
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    windowJson.put(new JSONObject()
                            .put("id", window.getId())
                            .put("type", window.getType())
                            .put("focused", window.isFocused())
                            .put("active", window.isActive())
                            .put("bounds", boundsJson(bounds)));
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) {
                        collect(root, snapshot, textSeen, counter, window, screenBounds);
                        root.recycle();
                    }
                }
                snapshot.put("windows", windowJson);
            }

            if (snapshot.getJSONArray("visible_text").length() == 0) {
                snapshot.getJSONArray("risk_flags").put("visible_text_empty");
            }
            if (snapshot.getJSONArray("interactive_elements").length() == 0) {
                snapshot.getJSONArray("risk_flags").put("interactive_elements_empty");
            }
            final String snapshotString = snapshot.toString();
            synchronized (sLock) {
                sLastSnapshot = snapshotString;
            }
            // Submit to the framework so getScreenContext can serve a fresh,
            // permission-gated UI tree to other privileged consumers without
            // each one binding the accessibility service directly.
            submitToFramework(snapshotString);
        } catch (JSONException e) {
            synchronized (sLock) {
                sLastSnapshot = unavailableSnapshot("json_error");
            }
        }
    }

    private void submitToFramework(String snapshotJson) {
        try {
            OpenPhoneAgentManager manager = getSystemService(OpenPhoneAgentManager.class);
            if (manager != null) {
                manager.submitUiTreeSnapshot(snapshotJson);
            }
        } catch (RuntimeException e) {
            Log.w("OpenPhoneA11y", "submitUiTreeSnapshot failed", e);
        }
    }

    private static void collect(AccessibilityNodeInfo node, JSONObject snapshot,
            Set<String> textSeen, Counter counter, AccessibilityWindowInfo window,
            Rect screenBounds)
            throws JSONException {
        if (node == null) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!isNodeVisibleOnScreen(node, bounds, screenBounds)) {
            return;
        }
        CharSequence packageName = node.getPackageName();
        if (packageName != null && packageName.length() > 0) {
            String packageNameString = packageName.toString();
            addRootPackage(snapshot, packageNameString);
            if (!snapshot.has("foreground_package")) {
                snapshot.put("foreground_package", packageNameString);
            }
        }
        CharSequence textValue = firstNonEmpty(node.getText(), node.getContentDescription());
        String rawLabel = textValue == null ? "" : textValue.toString().trim();
        if (rawLabel.isEmpty() && isInteractive(node)) {
            rawLabel = subtreeLabel(node, 0).trim();
        }
        boolean sensitiveInput = isSensitiveInput(node);
        boolean sensitiveHint = hasSensitiveHint(rawLabel, node.getViewIdResourceName());
        String label = sensitiveInput ? "<redacted sensitive input>" : rawLabel;
        if (sensitiveInput) {
            addRiskFlag(snapshot, "sensitive_input_visible");
        } else if (sensitiveHint) {
            addRiskFlag(snapshot, "account_or_payment_hint_visible");
        }
        if (!label.isEmpty() && textSeen.add(label)
                && snapshot.getJSONArray("visible_text").length() < MAX_TEXT_ITEMS) {
            snapshot.getJSONArray("visible_text").put(label);
        }

        if (isInteractive(node) && counter.elements < MAX_ELEMENTS) {
            JSONObject element = new JSONObject()
                    .put("id", "el-" + counter.elements)
                    .put("kind", kindFor(node))
                    .put("label", label)
                    .put("bounds", boundsJson(bounds))
                    .put("enabled", node.isEnabled())
                    .put("focused", node.isFocused());
            if (sensitiveInput || sensitiveHint) {
                element.put("sensitive", sensitiveInput)
                        .put("risk_hint", sensitiveInput
                                ? "sensitive_input" : "account_or_payment_hint");
            }
            String viewId = node.getViewIdResourceName();
            if (viewId != null && !viewId.isEmpty()) {
                element.put("view_id", viewId);
            }
            if (window != null) {
                element.put("window_id", window.getId());
            }
            snapshot.getJSONArray("interactive_elements").put(element);
            counter.elements++;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                collect(child, snapshot, textSeen, counter, window, screenBounds);
            } finally {
                child.recycle();
            }
            if (counter.elements >= MAX_ELEMENTS
                    && snapshot.getJSONArray("visible_text").length() >= MAX_TEXT_ITEMS) {
                return;
            }
        }
    }

    private static boolean isInteractive(AccessibilityNodeInfo node) {
        return node.isClickable() || node.isLongClickable() || node.isEditable()
                || node.isFocusable() || node.isScrollable();
    }

    private static boolean isNodeVisibleOnScreen(AccessibilityNodeInfo node, Rect bounds,
            Rect screenBounds) {
        return node.isVisibleToUser()
                && bounds != null
                && screenBounds != null
                && bounds.width() > 0
                && bounds.height() > 0
                && Rect.intersects(bounds, screenBounds);
    }

    private static String kindFor(AccessibilityNodeInfo node) {
        if (node.isEditable()) {
            return "text_field";
        }
        if (node.isScrollable()) {
            return "scroll_container";
        }
        if (node.isClickable() || node.isLongClickable()) {
            return "button";
        }
        if (node.isFocusable()) {
            return "focusable";
        }
        return "element";
    }

    private static boolean isSensitiveInput(AccessibilityNodeInfo node) {
        if (node.isPassword()) {
            return true;
        }
        int inputType = node.getInputType();
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private static boolean hasSensitiveHint(String label, String viewId) {
        String combined = ((label == null ? "" : label) + " "
                + (viewId == null ? "" : viewId)).toLowerCase(Locale.US);
        return combined.contains("password")
                || combined.contains("passcode")
                || containsToken(combined, "pin")
                || combined.contains("otp")
                || combined.contains("one time")
                || combined.contains("2fa")
                || combined.contains("cvv")
                || combined.contains("cvc")
                || combined.contains("credit card")
                || combined.contains("card number")
                || combined.contains("payment")
                || combined.contains("bank")
                || combined.contains("login")
                || combined.contains("log in")
                || combined.contains("sign in");
    }

    private static boolean containsToken(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return false;
        }
        String[] parts = text.split("[^a-z0-9]+");
        for (String part : parts) {
            if (token.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static String subtreeLabel(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        CharSequence textValue = firstNonEmpty(node.getText(), node.getContentDescription());
        if (!TextUtils.isEmpty(textValue)) {
            builder.append(textValue.toString().trim());
        }
        for (int i = 0; i < node.getChildCount() && builder.length() < 160; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                String childText = isSensitiveInput(child)
                        ? "<redacted sensitive input>" : subtreeLabel(child, depth + 1);
                if (!childText.trim().isEmpty()) {
                    if (builder.length() > 0) {
                        builder.append(" | ");
                    }
                    builder.append(childText.trim());
                }
            } finally {
                child.recycle();
            }
        }
        if (builder.length() > 180) {
            return builder.substring(0, 180);
        }
        return builder.toString();
    }

    private static void addRiskFlag(JSONObject snapshot, String flag) throws JSONException {
        JSONArray flags = snapshot.getJSONArray("risk_flags");
        for (int i = 0; i < flags.length(); i++) {
            if (flag.equals(flags.optString(i))) {
                return;
            }
        }
        flags.put(flag);
    }

    private static void addRootPackage(JSONObject snapshot, String packageName)
            throws JSONException {
        JSONArray packages = snapshot.getJSONArray("root_packages");
        for (int i = 0; i < packages.length(); i++) {
            if (packageName.equals(packages.optString(i))) {
                return;
            }
        }
        packages.put(packageName);
    }

    private static JSONArray boundsJson(Rect bounds) throws JSONException {
        return new JSONArray()
                .put(bounds.left)
                .put(bounds.top)
                .put(bounds.right)
                .put(bounds.bottom);
    }

    private static CharSequence firstNonEmpty(CharSequence first, CharSequence second) {
        if (!TextUtils.isEmpty(first)) {
            return first;
        }
        if (!TextUtils.isEmpty(second)) {
            return second;
        }
        return null;
    }

    private static boolean containsService(String enabledServices, String service) {
        String[] parts = enabledServices.split(":");
        for (String part : parts) {
            if (service.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static String unavailableSnapshot(String reason) {
        try {
            return new JSONObject()
                    .put("source", "openphone_accessibility_service")
                    .put("timestamp_ms", System.currentTimeMillis())
                    .put("visible_text", new JSONArray())
                    .put("interactive_elements", new JSONArray())
                    .put("risk_flags", new JSONArray()
                            .put("ui_tree_unavailable")
                            .put(reason == null ? "unknown" : reason.toLowerCase(Locale.US)))
                    .toString();
        } catch (JSONException e) {
            return "{\"source\":\"openphone_accessibility_service\","
                    + "\"visible_text\":[],\"interactive_elements\":[],"
                    + "\"risk_flags\":[\"ui_tree_unavailable\"]}";
        }
    }

    private static final class Counter {
        int elements;
    }
}
