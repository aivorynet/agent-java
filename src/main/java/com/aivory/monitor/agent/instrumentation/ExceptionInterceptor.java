package com.aivory.monitor.agent.instrumentation;

import com.aivory.monitor.agent.AIVoryAgent;
import com.aivory.monitor.agent.AgentConfig;
import com.aivory.monitor.agent.capture.ExceptionCapture;
import com.aivory.monitor.agent.connection.BackendConnection;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice for intercepting exceptions.
 * This class is used by ByteBuddy to inject exception handling code into target methods.
 */
public class ExceptionInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionInterceptor.class);

    // Thread-local to prevent recursive interception
    // Must be public because ByteBuddy inlines advice into target classes
    public static final ThreadLocal<Boolean> INTERCEPTING = ThreadLocal.withInitial(() -> false);

    // Track already-captured exceptions to prevent duplicate sends as exception propagates up
    // Uses identity hash to track specific exception instance
    public static final ThreadLocal<Integer> LAST_EXCEPTION_HASH = ThreadLocal.withInitial(() -> 0);

    /**
     * Called when a method exits with an exception.
     * Captures the exception context and sends it to the backend.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.This(optional = true) Object thiz,
            @Advice.Origin Method method,
            @Advice.AllArguments Object[] args,
            @Advice.Thrown Throwable thrown) {

        if (thrown == null) {
            return;
        }

        // Prevent recursive interception
        if (INTERCEPTING.get()) {
            return;
        }

        // Check if we already captured this exact exception instance (prevents duplicate sends as it propagates)
        int exceptionHash = System.identityHashCode(thrown);
        if (LAST_EXCEPTION_HASH.get() == exceptionHash) {
            return; // Already captured this exception
        }

        try {
            INTERCEPTING.set(true);
            LAST_EXCEPTION_HASH.set(exceptionHash);

            // Check if agent is initialized
            if (!AIVoryAgent.isInitialized()) {
                return;
            }

            AgentConfig config = AIVoryAgent.getConfig();
            BackendConnection connection = AIVoryAgent.getConnection();

            if (config == null || connection == null) {
                return;
            }

            // Check sampling
            if (!config.shouldSample()) {
                return;
            }

            // Capture exception
            ExceptionCapture capture = new ExceptionCapture(config);
            capture.capture(thrown, thiz, method, args);

            // Send to backend
            connection.sendException(capture);

        } catch (Exception e) {
            // Silently ignore errors during interception
            if (AIVoryAgent.getConfig() != null && AIVoryAgent.getConfig().isDebugEnabled()) {
                LOG.warn("Exception during interception: {}", e.getMessage());
            }
        } finally {
            INTERCEPTING.set(false);
        }
    }
}
