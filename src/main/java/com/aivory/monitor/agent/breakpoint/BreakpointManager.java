package com.aivory.monitor.agent.breakpoint;

import com.aivory.monitor.agent.AIVoryAgent;
import com.aivory.monitor.agent.capture.BreakpointCapture;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages non-breaking breakpoints set from the IDE.
 */
public class BreakpointManager {

    private static final Logger LOG = LoggerFactory.getLogger(BreakpointManager.class);

    private final Instrumentation instrumentation;
    private final Map<String, BreakpointInfo> breakpoints = new ConcurrentHashMap<>();
    private final Set<String> transformedClasses = ConcurrentHashMap.newKeySet();

    // Static reference for advice access
    private static BreakpointManager instance;

    public BreakpointManager(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        instance = this;
    }

    public static BreakpointManager getInstance() {
        return instance;
    }

    /**
     * Sets a breakpoint at the specified location.
     */
    public void setBreakpoint(String id, String className, int lineNumber, String condition) {
        BreakpointInfo info = new BreakpointInfo(id, className, lineNumber, condition);
        breakpoints.put(id, info);

        // Track breakpoints by class for quick lookup
        String classKey = className + ":" + lineNumber;
        breakpoints.put(classKey, info);

        // Retransform the class if already loaded
        retransformClass(className);

        LOG.info("Breakpoint set: {} at {}:{}", id, className, lineNumber);
    }

    /**
     * Removes a breakpoint.
     */
    public void removeBreakpoint(String id) {
        BreakpointInfo info = breakpoints.remove(id);
        if (info != null) {
            String classKey = info.className + ":" + info.lineNumber;
            breakpoints.remove(classKey);

            // Check if class still has breakpoints
            boolean hasOtherBreakpoints = breakpoints.values().stream()
                    .anyMatch(bp -> bp.className.equals(info.className) && !bp.id.equals(id));

            if (!hasOtherBreakpoints) {
                // Could retransform to remove instrumentation, but for simplicity
                // we'll just leave the instrumentation in place (it will no-op)
            }

            LOG.info("Breakpoint removed: {}", id);
        }
    }

    /**
     * Checks if a breakpoint exists at the specified location.
     */
    public boolean hasBreakpoint(String className, int lineNumber) {
        String key = className + ":" + lineNumber;
        return breakpoints.containsKey(key);
    }

    /**
     * Gets breakpoint info for a location.
     */
    public BreakpointInfo getBreakpoint(String className, int lineNumber) {
        String key = className + ":" + lineNumber;
        return breakpoints.get(key);
    }

    /**
     * Called when a breakpoint is hit.
     */
    public void onBreakpointHit(String className, int lineNumber, Object target, Object[] args) {
        BreakpointInfo info = getBreakpoint(className, lineNumber);
        if (info == null) {
            return;
        }

        // Check condition if set
        if (info.condition != null && !info.condition.isEmpty()) {
            // TODO: Evaluate condition expression
            // For now, always trigger
        }

        // Capture context
        BreakpointCapture capture = new BreakpointCapture(AIVoryAgent.getConfig());
        capture.capture(info.id, className, lineNumber, target, args);

        // Send to backend
        if (AIVoryAgent.getConnection() != null) {
            AIVoryAgent.getConnection().sendBreakpointHit(info.id, capture.toMap());
        }

        LOG.debug("Breakpoint hit: {} at {}:{}", info.id, className, lineNumber);
    }

    private void retransformClass(String className) {
        if (transformedClasses.contains(className)) {
            // Already transformed, trigger retransformation
            try {
                Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
                for (Class<?> clazz : loadedClasses) {
                    if (clazz.getName().equals(className)) {
                        if (instrumentation.isModifiableClass(clazz)) {
                            instrumentation.retransformClasses(clazz);
                            LOG.debug("Retransformed class: {}", className);
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to retransform class: {}", className, e);
            }
        }
        // Class will be transformed when loaded
        transformedClasses.add(className);
    }

    /**
     * Information about a breakpoint.
     */
    public static class BreakpointInfo {
        public final String id;
        public final String className;
        public final int lineNumber;
        public final String condition;
        public int hitCount = 0;

        public BreakpointInfo(String id, String className, int lineNumber, String condition) {
            this.id = id;
            this.className = className;
            this.lineNumber = lineNumber;
            this.condition = condition;
        }
    }
}
