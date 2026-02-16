package com.aivory.monitor.agent.connection;

import com.aivory.monitor.agent.AgentConfig;
import com.aivory.monitor.agent.breakpoint.BreakpointManager;
import com.aivory.monitor.agent.capture.ExceptionCapture;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket connection to the AIVory backend.
 * Handles authentication, message sending, and reconnection.
 */
public class BackendConnection {

    private static final Logger LOG = LoggerFactory.getLogger(BackendConnection.class);

    private static final int HEARTBEAT_INTERVAL_MS = 30000;
    private static final int RECONNECT_BASE_DELAY_MS = 1000;
    private static final int MAX_RECONNECT_DELAY_MS = 60000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int MESSAGE_QUEUE_SIZE = 1000;

    private final AgentConfig config;
    private final BreakpointManager breakpointManager;
    private final Gson gson;
    private final BlockingQueue<String> messageQueue;

    private AgentWebSocketClient client;
    private Timer heartbeatTimer;
    private Timer reconnectTimer;
    private Thread senderThread;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    public BackendConnection(AgentConfig config, BreakpointManager breakpointManager) {
        this.config = config;
        this.breakpointManager = breakpointManager;
        this.gson = new Gson();
        this.messageQueue = new LinkedBlockingQueue<>(MESSAGE_QUEUE_SIZE);
    }

    /**
     * Connects to the backend WebSocket.
     */
    public void connect() {
        if (connected.get()) {
            LOG.debug("Already connected");
            return;
        }

        try {
            URI serverUri = new URI(config.getBackendUrl());
            client = new AgentWebSocketClient(serverUri);
            client.connect();

            startSenderThread();

            LOG.info("Connecting to backend: {}", config.getBackendUrl());

        } catch (Exception e) {
            LOG.error("Failed to connect to backend", e);
            scheduleReconnect();
        }
    }

    /**
     * Disconnects from the backend.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        stopHeartbeat();
        stopSenderThread();
        cancelReconnect();

        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn("Error closing WebSocket", e);
            }
            client = null;
        }

        connected.set(false);
        authenticated.set(false);
    }

    /**
     * Sends an exception capture to the backend.
     */
    public void sendException(ExceptionCapture capture) {
        if (!authenticated.get()) {
            LOG.debug("Not authenticated, queuing exception");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("exception_type", capture.getExceptionType());
        payload.put("message", capture.getMessage());
        payload.put("fingerprint", capture.getFingerprint());
        payload.put("stack_trace", capture.getStackTrace());
        payload.put("local_variables", capture.getLocalVariables());
        payload.put("method_arguments", capture.getMethodArguments());
        payload.put("captured_at", capture.getCapturedAt());
        payload.put("agent_id", config.getAgentId());
        payload.put("environment", config.getEnvironment());
        payload.put("runtime", "java");
        payload.put("runtime_version", System.getProperty("java.version"));

        // Add file info from first non-agent stack frame
        if (!capture.getStackTrace().isEmpty()) {
            var frame = capture.getStackTrace().get(0);
            payload.put("file_path", frame.getFilePath());
            payload.put("file_name", frame.getFileName());
            payload.put("line_number", frame.getLineNumber());
            payload.put("method_name", frame.getMethodName());
            payload.put("class_name", frame.getClassName());
        }

        // Attach git context from the working directory
        Map<String, Object> gitContext = config.getGitContext();
        if (gitContext != null) {
            payload.put("git_context", gitContext);
        }

        // Debug logging to verify transmission
        if (config.isDebugEnabled()) {
            LOG.info("[AIVory] Sending exception to backend:");
            LOG.info("[AIVory]   Type: {}", capture.getExceptionType());
            LOG.info("[AIVory]   Stack frames: {}", capture.getStackTrace().size());
            LOG.info("[AIVory]   Local vars: {}", capture.getLocalVariables().size());
            LOG.info("[AIVory]   Method args: {}", capture.getMethodArguments().size());
            LOG.info("[AIVory]   Connected: {}, Authenticated: {}", connected.get(), authenticated.get());

            // Log actual variable contents
            for (var entry : capture.getMethodArguments().entrySet()) {
                LOG.info("[AIVory]   ARG: {} = {} (type: {})",
                    entry.getKey(), entry.getValue().getValue(), entry.getValue().getType());
            }
            for (var entry : capture.getLocalVariables().entrySet()) {
                LOG.info("[AIVory]   VAR: {} = {} (type: {})",
                    entry.getKey(), entry.getValue().getValue(), entry.getValue().getType());
            }
        }

        send("exception", payload);
    }

    /**
     * Sends a raw payload to the backend.
     * Used by JVMTI callback for full local variable capture.
     */
    public void sendRawPayload(String type, Map<String, Object> payload) {
        send(type, payload);
    }

    /**
     * Sends a breakpoint hit notification to the backend.
     */
    public void sendBreakpointHit(String breakpointId, Map<String, Object> capturedData) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("breakpoint_id", breakpointId);
        payload.put("agent_id", config.getAgentId());
        payload.put("captured_at", java.time.Instant.now().toString());
        payload.put("local_variables", capturedData.get("local_variables"));
        payload.put("stack_trace", capturedData.get("stack_trace"));

        send("breakpoint_hit", payload);
    }

    /**
     * Sends a message to the backend.
     */
    private void send(String type, Object payload) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("payload", payload);
        message.put("timestamp", System.currentTimeMillis());

        String json = gson.toJson(message);

        // Queue message for sending
        if (!messageQueue.offer(json)) {
            LOG.warn("Message queue full, dropping message of type: {}", type);
        }
    }

    private void sendDirect(String json) {
        if (client != null && client.isOpen()) {
            client.send(json);
        }
    }

    private void startSenderThread() {
        stopSenderThread();

        senderThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String message = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (message != null && client != null && client.isOpen()) {
                        client.send(message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOG.warn("Error sending message", e);
                }
            }
        }, "AIVory-Sender");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private void stopSenderThread() {
        if (senderThread != null) {
            senderThread.interrupt();
            senderThread = null;
        }
    }

    private void authenticate() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("api_key", config.getApiKey());
        payload.put("agent_id", config.getAgentId());
        payload.put("hostname", config.getHostname());
        payload.put("runtime", "java");
        payload.put("runtime_version", System.getProperty("java.version"));
        payload.put("agent_version", "1.0.0");
        payload.put("environment", config.getEnvironment());

        // Include release context in registration so backend knows agent's version
        Map<String, Object> gitContext = config.getGitContext();
        if (gitContext != null) {
            payload.put("git_context", gitContext);
        }

        Map<String, Object> message = new HashMap<>();
        message.put("type", "register");
        message.put("payload", payload);

        sendDirect(gson.toJson(message));
    }

    private void startHeartbeat() {
        stopHeartbeat();

        heartbeatTimer = new Timer("AIVory-Heartbeat", true);
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (authenticated.get()) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("timestamp", System.currentTimeMillis());
                    payload.put("agent_id", config.getAgentId());
                    send("heartbeat", payload);
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);
    }

    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    private void scheduleReconnect() {
        if (!shouldReconnect.get()) {
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            LOG.error("Max reconnect attempts reached, giving up");
            return;
        }

        int delay = Math.min(RECONNECT_BASE_DELAY_MS * (1 << (attempts - 1)), MAX_RECONNECT_DELAY_MS);
        LOG.info("Scheduling reconnect attempt {} in {}ms", attempts, delay);

        cancelReconnect();
        reconnectTimer = new Timer("AIVory-Reconnect", true);
        reconnectTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                connect();
            }
        }, delay);
    }

    private void cancelReconnect() {
        if (reconnectTimer != null) {
            reconnectTimer.cancel();
            reconnectTimer = null;
        }
    }

    private void handleMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : null;

            if (type == null) {
                LOG.warn("Received message without type");
                return;
            }

            LOG.debug("Received message: {}", type);

            switch (type) {
                case "registered":
                    handleRegistered(json);
                    break;
                case "error":
                    handleError(json);
                    break;
                case "set_breakpoint":
                    handleSetBreakpoint(json);
                    break;
                case "remove_breakpoint":
                    handleRemoveBreakpoint(json);
                    break;
                case "configure":
                    handleConfigure(json);
                    break;
                default:
                    LOG.debug("Unhandled message type: {}", type);
            }

        } catch (Exception e) {
            LOG.warn("Error handling message", e);
        }
    }

    private void handleRegistered(JsonObject json) {
        authenticated.set(true);
        reconnectAttempts.set(0);
        startHeartbeat();
        LOG.info("Agent registered successfully");
    }

    private void handleError(JsonObject json) {
        JsonObject payload = json.has("payload") ? json.getAsJsonObject("payload") : null;
        String code = payload != null && payload.has("code") ? payload.get("code").getAsString() : "unknown";
        String errorMessage = payload != null && payload.has("message") ? payload.get("message").getAsString() : "Unknown error";

        LOG.error("Backend error: {} - {}", code, errorMessage);

        if ("auth_error".equals(code) || "invalid_api_key".equals(code)) {
            LOG.error("Authentication failed, agent will not reconnect");
            shouldReconnect.set(false);
            disconnect();
        }
    }

    private void handleSetBreakpoint(JsonObject json) {
        JsonObject payload = json.has("payload") ? json.getAsJsonObject("payload") : null;
        if (payload == null) {
            LOG.warn("Set breakpoint message missing payload");
            return;
        }

        // Validate required fields
        if (!payload.has("id") || !payload.has("class_name") || !payload.has("line_number")) {
            LOG.warn("Set breakpoint message missing required fields (id, class_name, line_number)");
            return;
        }

        try {
            String id = payload.get("id").getAsString();
            String className = payload.get("class_name").getAsString();
            int lineNumber = payload.get("line_number").getAsInt();
            String condition = payload.has("condition") && !payload.get("condition").isJsonNull()
                    ? payload.get("condition").getAsString()
                    : null;

            breakpointManager.setBreakpoint(id, className, lineNumber, condition);
            LOG.info("Breakpoint set: {} at {}:{}", id, className, lineNumber);
        } catch (Exception e) {
            LOG.warn("Error parsing breakpoint message: {}", e.getMessage());
        }
    }

    private void handleRemoveBreakpoint(JsonObject json) {
        JsonObject payload = json.has("payload") ? json.getAsJsonObject("payload") : null;
        if (payload == null || !payload.has("id")) {
            LOG.warn("Remove breakpoint message missing payload or id");
            return;
        }

        try {
            String id = payload.get("id").getAsString();
            breakpointManager.removeBreakpoint(id);
            LOG.info("Breakpoint removed: {}", id);
        } catch (Exception e) {
            LOG.warn("Error parsing remove breakpoint message: {}", e.getMessage());
        }
    }

    private void handleConfigure(JsonObject json) {
        // Handle runtime configuration updates
        LOG.debug("Configuration update received");
    }

    /**
     * Checks if connected and authenticated.
     */
    public boolean isConnected() {
        return authenticated.get();
    }

    /**
     * Internal WebSocket client.
     */
    private class AgentWebSocketClient extends WebSocketClient {

        public AgentWebSocketClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            LOG.info("WebSocket connected");
            connected.set(true);
            authenticate();
        }

        @Override
        public void onMessage(String message) {
            handleMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            LOG.info("WebSocket closed: {} - {} (remote: {})", code, reason, remote);
            connected.set(false);
            authenticated.set(false);
            stopHeartbeat();

            if (remote && shouldReconnect.get()) {
                scheduleReconnect();
            }
        }

        @Override
        public void onError(Exception ex) {
            LOG.error("WebSocket error", ex);
        }
    }
}
