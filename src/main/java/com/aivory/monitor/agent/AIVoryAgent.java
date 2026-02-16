package com.aivory.monitor.agent;

import com.aivory.monitor.agent.connection.BackendConnection;
import com.aivory.monitor.agent.instrumentation.ExceptionInterceptor;
import com.aivory.monitor.agent.breakpoint.BreakpointManager;
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

/**
 * AIVory Monitor Java Agent entry point.
 * Provides bytecode instrumentation for exception capture and breakpoints.
 */
public class AIVoryAgent {

    private static final Logger LOG = LoggerFactory.getLogger(AIVoryAgent.class);

    private static volatile boolean initialized = false;
    private static AgentConfig config;
    private static BackendConnection connection;
    private static BreakpointManager breakpointManager;
    private static Instrumentation instrumentation;

    /**
     * JVM agent premain entry point (static attachment).
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        LOG.info("AIVory Monitor Agent: Starting (premain)");
        initialize(agentArgs, inst);
    }

    /**
     * JVM agent agentmain entry point (dynamic attachment).
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        LOG.info("AIVory Monitor Agent: Starting (agentmain)");
        initialize(agentArgs, inst);
    }

    private static synchronized void initialize(String agentArgs, Instrumentation inst) {
        if (initialized) {
            LOG.warn("AIVory Monitor Agent: Already initialized, skipping");
            return;
        }

        try {
            instrumentation = inst;

            // Parse configuration
            config = AgentConfig.parse(agentArgs);
            LOG.info("AIVory Monitor Agent: Configuration loaded");
            LOG.info("  Backend URL: {}", config.getBackendUrl());
            LOG.info("  Environment: {}", config.getEnvironment());
            LOG.info("  Include patterns: {}", config.getIncludePatterns());
            LOG.info("  Exclude patterns: {}", config.getExcludePatterns());
            LOG.info("  Debug enabled: {}", config.isDebugEnabled());

            // Validate API key
            if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
                LOG.error("AIVory Monitor Agent: API key not set. Set AIVORY_API_KEY or -Daivory.api.key");
                return;
            }

            // Initialize breakpoint manager
            breakpointManager = new BreakpointManager(inst);

            // Connect to backend
            connection = new BackendConnection(config, breakpointManager);
            connection.connect();

            // Install bytecode transformers
            installTransformers(inst);

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("AIVory Monitor Agent: Shutting down");
                if (connection != null) {
                    connection.disconnect();
                }
            }));

            initialized = true;
            LOG.info("AIVory Monitor Agent: Initialized successfully");

        } catch (Exception e) {
            LOG.error("AIVory Monitor Agent: Failed to initialize", e);
        }
    }

    private static void installTransformers(Instrumentation inst) {
        LOG.info("AIVory Monitor Agent: Installing bytecode transformers");

        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription typeDescription,
                                                  ClassLoader classLoader,
                                                  JavaModule module,
                                                  boolean loaded,
                                                  DynamicType dynamicType) {
                        if (config.isDebugEnabled()) {
                            LOG.info("[AIVory] Transformed: {} (loaded={})", typeDescription.getName(), loaded);
                        }
                    }

                    @Override
                    public void onIgnored(TypeDescription typeDescription,
                                         ClassLoader classLoader,
                                         JavaModule module,
                                         boolean loaded) {
                        // Log ignored classes at TRACE level to avoid spam
                        if (config.isDebugEnabled() && typeDescription.getName().startsWith("com.aivory.test")) {
                            LOG.warn("[AIVory] IGNORED: {} - should not be ignored!", typeDescription.getName());
                        }
                    }

                    @Override
                    public void onError(String typeName,
                                       ClassLoader classLoader,
                                       JavaModule module,
                                       boolean loaded,
                                       Throwable throwable) {
                        LOG.warn("[AIVory] Transform error for {}: {}", typeName, throwable.getMessage());
                        if (config.isDebugEnabled()) {
                            LOG.warn("[AIVory] Full error:", throwable);
                        }
                    }
                })
                .ignore(buildIgnoreMatcher());

        // Add exception interceptor to all matching methods
        agentBuilder = agentBuilder
                .type(buildTypeMatcher())
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ExceptionInterceptor.class)
                                .on(ElementMatchers.isMethod()
                                        .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                                        .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                                        .and(ElementMatchers.not(ElementMatchers.isNative())))));

        agentBuilder.installOn(inst);

        LOG.info("AIVory Monitor Agent: Bytecode transformers installed");
    }

    private static net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> buildTypeMatcher() {
        net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> matcher =
                ElementMatchers.any();

        // Apply include patterns
        for (String pattern : config.getIncludePatterns()) {
            if ("*".equals(pattern)) {
                // Include all
                break;
            } else if (pattern.endsWith(".*")) {
                String pkg = pattern.substring(0, pattern.length() - 2);
                matcher = matcher.and(ElementMatchers.nameStartsWith(pkg));
            } else {
                matcher = matcher.and(ElementMatchers.named(pattern));
            }
        }

        return matcher;
    }

    private static net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> buildIgnoreMatcher() {
        net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> ignore =
                ElementMatchers.nameStartsWith("com.aivory.monitor.agent")
                        .or(ElementMatchers.nameStartsWith("net.bytebuddy"))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("java.lang.invoke"))
                        .or(ElementMatchers.nameStartsWith("java.lang.ref"))
                        .or(ElementMatchers.isSynthetic());

        // Apply exclude patterns from config
        for (String pattern : config.getExcludePatterns()) {
            if (pattern.endsWith(".*")) {
                String pkg = pattern.substring(0, pattern.length() - 2);
                ignore = ignore.or(ElementMatchers.nameStartsWith(pkg));
            } else {
                ignore = ignore.or(ElementMatchers.named(pattern));
            }
        }

        return ignore;
    }

    // Static accessors for interceptors

    public static AgentConfig getConfig() {
        return config;
    }

    public static BackendConnection getConnection() {
        return connection;
    }

    public static BreakpointManager getBreakpointManager() {
        return breakpointManager;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
