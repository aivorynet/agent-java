package com.aivory.monitor.agent.jvmti;

import com.aivory.monitor.agent.AIVoryAgent;
import com.aivory.monitor.agent.AgentConfig;
import com.aivory.monitor.agent.capture.ExceptionCapture;
import com.aivory.monitor.agent.connection.BackendConnection;
import com.aivory.monitor.agent.model.CapturedVariable;
import com.aivory.monitor.agent.model.StackFrameInfo;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Callback receiver for JVMTI native agent.
 * Called by the native JVMTI agent when an exception is captured with full local variables.
 */
public class JVMTICallback {

    private static final Logger LOG = LoggerFactory.getLogger(JVMTICallback.class);
    private static final Gson GSON = new Gson();

    // Deduplication: track recently seen exception hashes
    private static final Map<Integer, Long> recentExceptions = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 100; // Ignore same exception within 100ms

    /**
     * Called by native JVMTI agent when an exception occurs.
     * This provides FULL local variable capture for all stack frames.
     *
     * @param location   The method where exception occurred (e.g., "com.example.MyClass.myMethod")
     * @param variables  JSON string with captured variables per frame
     * @param exception  The actual exception object
     */
    public static void onException(String location, String variables, Throwable exception) {
        try {
            // Check if agent is initialized
            if (!AIVoryAgent.isInitialized()) {
                return;
            }

            AgentConfig config = AIVoryAgent.getConfig();
            BackendConnection connection = AIVoryAgent.getConnection();

            if (config == null || connection == null) {
                return;
            }

            // Deduplication check
            int exceptionHash = System.identityHashCode(exception);
            long now = System.currentTimeMillis();
            Long lastSeen = recentExceptions.get(exceptionHash);
            if (lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS) {
                return; // Skip duplicate
            }
            recentExceptions.put(exceptionHash, now);

            // Cleanup old entries periodically
            if (recentExceptions.size() > 1000) {
                recentExceptions.entrySet().removeIf(e -> now - e.getValue() > DEDUP_WINDOW_MS * 10);
            }

            // Check sampling
            if (!config.shouldSample()) {
                return;
            }

            if (config.isDebugEnabled()) {
                LOG.info("[AIVory JVMTI] Exception captured via JVMTI: {} at {}",
                        exception.getClass().getName(), location);
                LOG.info("[AIVory JVMTI] Variables JSON length: {}", variables != null ? variables.length() : 0);
            }

            // Build exception capture with JVMTI-provided local variables
            Map<String, Object> payload = new HashMap<>();
            payload.put("exception_type", exception.getClass().getName());
            payload.put("message", exception.getMessage());
            payload.put("captured_at", Instant.now().toString());
            payload.put("agent_id", config.getAgentId());
            payload.put("environment", config.getEnvironment());
            payload.put("runtime", "java");
            payload.put("runtime_version", System.getProperty("java.version"));
            payload.put("jvmti_capture", true); // Flag indicating JVMTI capture

            // Parse and set local variables from JVMTI
            Map<String, Object> localVars = parseVariablesJson(variables);
            payload.put("local_variables", localVars);

            // Build stack trace
            List<Map<String, Object>> stackFrames = new ArrayList<>();
            StackTraceElement[] elements = exception.getStackTrace();
            int maxFrames = Math.min(elements.length, 50);

            for (int i = 0; i < maxFrames; i++) {
                StackTraceElement element = elements[i];
                Map<String, Object> frame = new HashMap<>();
                frame.put("class_name", element.getClassName());
                frame.put("method_name", element.getMethodName());
                frame.put("file_name", element.getFileName());
                frame.put("line_number", element.getLineNumber());
                frame.put("native_method", element.isNativeMethod());

                // Attach local variables for this frame if available
                String frameKey = "frame_" + i + "_" + element.getClassName() + "." + element.getMethodName();
                if (localVars.containsKey(frameKey)) {
                    frame.put("locals", localVars.get(frameKey));
                }

                stackFrames.add(frame);
            }
            payload.put("stack_trace", stackFrames);

            // Calculate fingerprint
            String fingerprint = calculateFingerprint(exception, location);
            payload.put("fingerprint", fingerprint);

            // Extract file/line info from first frame
            if (!stackFrames.isEmpty()) {
                Map<String, Object> firstFrame = stackFrames.get(0);
                payload.put("file_name", firstFrame.get("file_name"));
                payload.put("line_number", firstFrame.get("line_number"));
                payload.put("method_name", firstFrame.get("method_name"));
                payload.put("class_name", firstFrame.get("class_name"));
            }

            // Send to backend
            connection.sendRawPayload("exception", payload);

            if (config.isDebugEnabled()) {
                LOG.info("[AIVory JVMTI] Exception sent to backend with {} stack frames and {} variable entries",
                        stackFrames.size(), localVars.size());
            }

        } catch (Exception e) {
            // Don't let callback errors affect the application
            LOG.debug("[AIVory JVMTI] Error in callback: {}", e.getMessage());
        }
    }

    /**
     * Parse the JSON variables string from JVMTI.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseVariablesJson(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) {
            return new LinkedHashMap<>();
        }

        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Object> result = new LinkedHashMap<>();

            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                result.put(entry.getKey(), GSON.fromJson(entry.getValue(), Object.class));
            }

            return result;
        } catch (Exception e) {
            LOG.debug("[AIVory JVMTI] Failed to parse variables JSON: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Calculate exception fingerprint for deduplication.
     */
    private static String calculateFingerprint(Throwable exception, String location) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(exception.getClass().getName()).append(":");
            sb.append(location).append(":");

            // Add first few stack frames
            StackTraceElement[] elements = exception.getStackTrace();
            int frames = Math.min(elements.length, 5);
            for (int i = 0; i < frames; i++) {
                sb.append(elements[i].getClassName()).append(".")
                  .append(elements[i].getMethodName()).append(":")
                  .append(elements[i].getLineNumber()).append(":");
            }

            // Hash it
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();

        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }
}
