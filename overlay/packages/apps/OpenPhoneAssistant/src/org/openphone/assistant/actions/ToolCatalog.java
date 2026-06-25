package org.openphone.assistant.actions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/**
 * Generates every model-facing tool surface from the installed action
 * registry so the registry stays the single source of truth: the Responses
 * prompt tool catalog, the Realtime session tool definitions, the
 * allowed-tool checks, and reason enforcement.
 */
public final class ToolCatalog {
    private static volatile ToolCatalog sInstance;

    private final ActionRegistry mRegistry;

    private ToolCatalog(ActionRegistry registry) {
        mRegistry = registry;
    }

    public static ToolCatalog get() {
        ToolCatalog instance = sInstance;
        if (instance == null || !instance.isLoaded()) {
            synchronized (ToolCatalog.class) {
                instance = sInstance;
                if (instance == null || !instance.isLoaded()) {
                    instance = new ToolCatalog(ActionRegistry.load());
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    public boolean isLoaded() {
        return mRegistry.isLoaded() && !mRegistry.tools().isEmpty();
    }

    public boolean isAllowedTool(String toolName) {
        return mRegistry.hasTool(toolName);
    }

    public boolean isTerminalTool(String toolName) {
        return "finish_task".equals(toolName) || "fail_task".equals(toolName);
    }

    /**
     * Whether the reason preflight applies. Terminal tools are exempt even
     * though their schemas require "reason"/"summary": for them the field is
     * the task outcome payload, not an action justification.
     */
    public boolean requiresReason(String toolName) {
        if (isTerminalTool(toolName)) {
            return false;
        }
        ActionRegistry.ActionMetadata action = mRegistry.forTool(toolName);
        return action != null && action.requiresReason();
    }

    public String capabilityForTool(String toolName) {
        ActionRegistry.ActionMetadata action = mRegistry.forTool(toolName);
        return action == null || action.primaryCapability.isEmpty()
                ? "input.perform" : action.primaryCapability;
    }

    /** Tools whose registry kind is "action": they mutate device or external state. */
    public boolean isStateChangingTool(String toolName) {
        ActionRegistry.ActionMetadata action = mRegistry.forTool(toolName);
        return action != null && "action".equals(action.kind);
    }

    /**
     * Actions that drive the visible UI or foreground app, where the agent
     * should re-observe the screen afterwards to verify progress.
     */
    public boolean drivesVisibleUi(String toolName) {
        if (!isStateChangingTool(toolName)) {
            return false;
        }
        String capability = capabilityForTool(toolName);
        return "input.perform".equals(capability)
                || "apps.launch".equals(capability)
                || "network.use".equals(capability)
                || "clipboard.write".equals(capability)
                || "clipboard.read".equals(capability)
                || "share.content".equals(capability);
    }

    /** Pipe-separated tool names for the Responses decision schema line. */
    public String toolNamesPipeList() {
        StringBuilder builder = new StringBuilder();
        for (ActionRegistry.ActionMetadata action : mRegistry.tools()) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(action.modelTool);
        }
        return builder.toString();
    }

    /** Per-tool argument documentation injected into the Responses prompt. */
    public String promptToolCatalog() {
        StringBuilder builder = new StringBuilder();
        for (ActionRegistry.ActionMetadata action : mRegistry.tools()) {
            builder.append("- ").append(action.modelTool);
            if (!action.description.isEmpty()) {
                builder.append(": ").append(action.description);
            }
            builder.append('\n');
            String arguments = promptArguments(action);
            if (!arguments.isEmpty()) {
                builder.append("  arguments: ").append(arguments).append('\n');
            }
        }
        return builder.toString();
    }

    private static String promptArguments(ActionRegistry.ActionMetadata action) {
        JSONObject properties = action.inputSchema.optJSONObject("properties");
        if (properties == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        Iterator<String> keys = properties.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject property = properties.optJSONObject(key);
            if (property == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(key)
                    .append(" (")
                    .append(property.optString("type", "string"));
            if (action.requiredInputs.contains(key)) {
                builder.append(", required");
            }
            builder.append(')');
            String description = property.optString("description", "");
            if (!description.isEmpty()) {
                builder.append(' ').append(description);
            }
        }
        return builder.toString();
    }

    /** OpenAI Realtime session tool definitions generated from the registry. */
    public JSONArray realtimeToolDefinitions() throws JSONException {
        JSONArray tools = new JSONArray();
        for (ActionRegistry.ActionMetadata action : mRegistry.tools()) {
            JSONObject parameters = new JSONObject(action.inputSchema.toString());
            if (!parameters.has("type")) {
                parameters.put("type", "object");
            }
            if (!parameters.has("properties")) {
                parameters.put("properties", new JSONObject());
            }
            if (!parameters.has("required")) {
                parameters.put("required", new JSONArray());
            }
            parameters.put("additionalProperties", true);
            tools.put(new JSONObject()
                    .put("type", "function")
                    .put("name", action.modelTool)
                    .put("description", action.description)
                    .put("parameters", parameters));
        }
        return tools;
    }

    /** Gemini Live function declarations generated from the same registry. */
    public JSONArray geminiFunctionDeclarations() throws JSONException {
        JSONArray declarations = new JSONArray();
        for (ActionRegistry.ActionMetadata action : mRegistry.tools()) {
            JSONObject parameters = new JSONObject(action.inputSchema.toString());
            if (!parameters.has("type")) {
                parameters.put("type", "object");
            }
            if (!parameters.has("properties")) {
                parameters.put("properties", new JSONObject());
            }
            if (!parameters.has("required")) {
                parameters.put("required", new JSONArray());
            }
            declarations.put(new JSONObject()
                    .put("name", action.modelTool)
                    .put("description", action.description)
                    .put("parameters", parameters));
        }
        return declarations;
    }

    public List<ActionRegistry.ActionMetadata> tools() {
        return mRegistry.tools();
    }
}
