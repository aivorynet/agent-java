package com.aivory.monitor.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration for the AIVory Monitor agent.
 *
 * <p>Git/release context is resolved at startup using a cascading priority:
 * <ol>
 *   <li>Agent arguments (release=, version=, commit=, branch=, repository=)</li>
 *   <li>JVM system properties (-Daivory.release=, -Daivory.version=, etc.)</li>
 *   <li>AIVORY_* environment variables (AIVORY_RELEASE, AIVORY_VERSION, etc.)</li>
 *   <li>Platform-specific env vars (HEROKU_SLUG_COMMIT, GITHUB_SHA, etc.)</li>
 * </ol>
 */
public class AgentConfig {

    private static final Pattern REPO_PATTERN = Pattern.compile("[:/]([^/]+/[^/]+?)(?:\\.git)?$");

    private String apiKey;
    private String backendUrl = "wss://api.aivory.net/ws/agent";
    private String environment = "production";
    private double samplingRate = 1.0;
    private int maxCaptureDepth = 10;
    private int maxStringLength = 1000;
    private int maxCollectionSize = 100;
    private List<String> includePatterns = new ArrayList<>(Arrays.asList("*"));
    private List<String> excludePatterns = new ArrayList<>(Arrays.asList(
            "java.*", "javax.*", "sun.*", "jdk.*", "com.sun.*",
            "org.slf4j.*", "ch.qos.logback.*", "org.apache.logging.*"
    ));
    private boolean debugEnabled = false;
    private String hostname;
    private String agentId;

    // Release context fields (from agent args, system props, or env vars)
    private String release;
    private String version;
    private String commit;
    private String branch;
    private String repository;

    /** Cached git context, built once at startup. Null if no info available. */
    private Map<String, Object> gitContext;

    public AgentConfig() {
        this.hostname = resolveHostname();
        this.agentId = generateAgentId();
    }

    /**
     * Parses configuration from agent arguments, system properties, and env vars.
     * Git context is resolved and cached after all config sources are loaded.
     */
    public static AgentConfig parse(String agentArgs) {
        AgentConfig config = new AgentConfig();

        // Parse agent arguments (key=value,key=value format)
        if (agentArgs != null && !agentArgs.isEmpty()) {
            for (String arg : agentArgs.split(",")) {
                String[] kv = arg.split("=", 2);
                if (kv.length == 2) {
                    config.setFromKeyValue(kv[0].trim(), kv[1].trim());
                }
            }
        }

        // Override with system properties
        config.loadFromSystemProperties();

        // Override with environment variables
        config.loadFromEnvironment();

        // Build cached git context from all resolved values
        config.gitContext = config.resolveGitContext();

        if (config.gitContext != null) {
            System.out.println("[AIVory Monitor] Release context: version=" +
                    config.gitContext.getOrDefault("version", "N/A") + ", commit=" +
                    config.gitContext.getOrDefault("commit_short", "N/A") + ", branch=" +
                    config.gitContext.getOrDefault("branch", "N/A") + ", project=" +
                    config.gitContext.getOrDefault("project_identifier", "N/A"));
        } else if (config.debugEnabled) {
            System.out.println("[AIVory Monitor] No release context available (set AIVORY_RELEASE or pass release= in agent args)");
        }

        return config;
    }

    private void setFromKeyValue(String key, String value) {
        switch (key.toLowerCase()) {
            case "apikey":
            case "api_key":
                this.apiKey = value;
                break;
            case "backendurl":
            case "backend_url":
                this.backendUrl = value;
                break;
            case "environment":
            case "env":
                this.environment = value;
                break;
            case "samplingrate":
            case "sampling_rate":
                this.samplingRate = Double.parseDouble(value);
                break;
            case "maxdepth":
            case "max_depth":
                this.maxCaptureDepth = Integer.parseInt(value);
                break;
            case "include":
                this.includePatterns = Arrays.asList(value.split(";"));
                break;
            case "exclude":
                this.excludePatterns = Arrays.asList(value.split(";"));
                break;
            case "debug":
                this.debugEnabled = Boolean.parseBoolean(value);
                break;
            case "release":
                this.release = value;
                break;
            case "version":
                this.version = value;
                break;
            case "commit":
                this.commit = value;
                break;
            case "branch":
                this.branch = value;
                break;
            case "repository":
            case "repo":
                this.repository = value;
                break;
        }
    }

    private void loadFromSystemProperties() {
        String val;

        val = System.getProperty("aivory.api.key");
        if (val != null) apiKey = val;

        val = System.getProperty("aivory.backend.url");
        if (val != null) backendUrl = val;

        val = System.getProperty("aivory.environment");
        if (val != null) environment = val;

        val = System.getProperty("aivory.sampling.rate");
        if (val != null) samplingRate = Double.parseDouble(val);

        val = System.getProperty("aivory.capture.maxDepth");
        if (val != null) maxCaptureDepth = Integer.parseInt(val);

        val = System.getProperty("aivory.capture.maxStringLength");
        if (val != null) maxStringLength = Integer.parseInt(val);

        val = System.getProperty("aivory.capture.maxCollectionSize");
        if (val != null) maxCollectionSize = Integer.parseInt(val);

        val = System.getProperty("aivory.include");
        if (val != null) includePatterns = Arrays.asList(val.split(";"));

        val = System.getProperty("aivory.exclude");
        if (val != null) excludePatterns = Arrays.asList(val.split(";"));

        val = System.getProperty("aivory.log.level");
        if (val != null && "DEBUG".equalsIgnoreCase(val)) debugEnabled = true;

        val = System.getProperty("aivory.debug");
        if (val != null) debugEnabled = Boolean.parseBoolean(val);

        // Release context from system properties
        val = System.getProperty("aivory.release");
        if (val != null) release = val;

        val = System.getProperty("aivory.version");
        if (val != null) version = val;

        val = System.getProperty("aivory.commit");
        if (val != null) commit = val;

        val = System.getProperty("aivory.branch");
        if (val != null) branch = val;

        val = System.getProperty("aivory.repository");
        if (val != null) repository = val;
    }

    private void loadFromEnvironment() {
        String val;

        val = System.getenv("AIVORY_API_KEY");
        if (val != null) apiKey = val;

        val = System.getenv("AIVORY_BACKEND_URL");
        if (val != null) backendUrl = val;

        val = System.getenv("AIVORY_ENVIRONMENT");
        if (val != null) environment = val;

        val = System.getenv("AIVORY_SAMPLING_RATE");
        if (val != null) samplingRate = Double.parseDouble(val);

        val = System.getenv("AIVORY_MAX_DEPTH");
        if (val != null) maxCaptureDepth = Integer.parseInt(val);

        val = System.getenv("AIVORY_MAX_STRING_LENGTH");
        if (val != null) maxStringLength = Integer.parseInt(val);

        val = System.getenv("AIVORY_MAX_COLLECTION_SIZE");
        if (val != null) maxCollectionSize = Integer.parseInt(val);

        val = System.getenv("AIVORY_INCLUDE");
        if (val != null) includePatterns = Arrays.asList(val.split(";"));

        val = System.getenv("AIVORY_EXCLUDE");
        if (val != null) excludePatterns = Arrays.asList(val.split(";"));

        val = System.getenv("AIVORY_DEBUG");
        if (val != null) debugEnabled = Boolean.parseBoolean(val);

        // Release context from AIVORY_* env vars
        val = System.getenv("AIVORY_RELEASE");
        if (val != null) release = val;

        val = System.getenv("AIVORY_VERSION");
        if (val != null) version = val;

        val = System.getenv("AIVORY_COMMIT");
        if (val != null) commit = val;

        val = System.getenv("AIVORY_BRANCH");
        if (val != null) branch = val;

        val = System.getenv("AIVORY_REPOSITORY");
        if (val != null) repository = val;
    }

    /**
     * Resolves git context using cascading priority:
     * 1. Explicit config (agent args / system props / AIVORY_* env vars) - already loaded
     * 2. Platform-specific environment variables (Heroku, Vercel, GitHub Actions, etc.)
     *
     * Returns null if no version/release information is available from any source.
     */
    private Map<String, Object> resolveGitContext() {
        // Parse release string if set (supports "myapp@1.2.3" format)
        String parsedVersion = nonEmpty(version);
        String parsedCommit = nonEmpty(commit);

        if (release != null && !release.isEmpty() && parsedVersion == null) {
            int atIndex = release.indexOf('@');
            if (atIndex > 0) {
                parsedVersion = release.substring(atIndex + 1);
            } else if (release.matches("[0-9a-fA-F]{7,40}")) {
                // Looks like a commit SHA
                if (parsedCommit == null) parsedCommit = release;
            } else {
                parsedVersion = release;
            }
        }

        // Platform-specific commit detection
        if (parsedCommit == null) {
            parsedCommit = firstNonEmpty(
                    System.getenv("HEROKU_SLUG_COMMIT"),
                    System.getenv("VERCEL_GIT_COMMIT_SHA"),
                    System.getenv("CODEBUILD_RESOLVED_SOURCE_VERSION"),
                    System.getenv("CIRCLE_SHA1"),
                    System.getenv("GITHUB_SHA"),
                    System.getenv("CI_COMMIT_SHA"),
                    System.getenv("GIT_COMMIT"),
                    System.getenv("SOURCE_VERSION")
            );
        }

        // Platform-specific branch detection
        String resolvedBranch = nonEmpty(branch);
        if (resolvedBranch == null) {
            resolvedBranch = firstNonEmpty(
                    System.getenv("VERCEL_GIT_COMMIT_REF"),
                    System.getenv("CIRCLE_BRANCH"),
                    System.getenv("GITHUB_REF_NAME"),
                    System.getenv("CI_COMMIT_BRANCH"),
                    System.getenv("CI_COMMIT_TAG")
            );
        }

        // Platform-specific repository detection
        String resolvedRepo = nonEmpty(repository);
        if (resolvedRepo == null) {
            String vercelSlug = System.getenv("VERCEL_GIT_REPO_SLUG");
            String vercelOwner = System.getenv("VERCEL_GIT_REPO_OWNER");
            String githubRepo = System.getenv("GITHUB_REPOSITORY");
            String gitlabPath = System.getenv("CI_PROJECT_PATH");
            String circleRepo = System.getenv("CIRCLE_REPOSITORY_URL");

            if (vercelSlug != null && vercelOwner != null) {
                resolvedRepo = "https://github.com/" + vercelOwner + "/" + vercelSlug;
            } else if (githubRepo != null) {
                resolvedRepo = "https://github.com/" + githubRepo;
            } else if (gitlabPath != null) {
                resolvedRepo = "https://gitlab.com/" + gitlabPath;
            } else if (circleRepo != null) {
                resolvedRepo = circleRepo;
            }
        }

        // Platform-specific version detection
        if (parsedVersion == null) {
            parsedVersion = firstNonEmpty(
                    System.getenv("HEROKU_RELEASE_VERSION"),
                    System.getenv("APP_VERSION")
            );
        }

        // If we have nothing at all, return null
        if (parsedVersion == null && parsedCommit == null &&
                resolvedBranch == null && resolvedRepo == null) {
            return null;
        }

        // Derive project identifier from repository URL
        String projectIdentifier = "";
        String projectName = "";
        if (resolvedRepo != null) {
            Matcher m = REPO_PATTERN.matcher(resolvedRepo);
            if (m.find()) {
                projectIdentifier = m.group(1);
                String[] parts = projectIdentifier.split("/");
                projectName = parts[parts.length - 1];
            }
        }

        // Build short commit
        String commitShort = parsedCommit != null && parsedCommit.length() >= 7
                ? parsedCommit.substring(0, 7) : (parsedCommit != null ? parsedCommit : "");

        Map<String, Object> context = new HashMap<>();
        context.put("commit_hash", parsedCommit != null ? parsedCommit : "");
        context.put("commit_short", commitShort);
        context.put("branch", resolvedBranch != null ? resolvedBranch : "");
        context.put("remote_url", resolvedRepo != null ? resolvedRepo : "");
        context.put("version", parsedVersion != null ? parsedVersion : "");
        context.put("project_name", projectName);
        context.put("project_identifier", projectIdentifier);
        context.put("source", "agent");
        context.put("captured_at", Instant.now().toString());
        return context;
    }

    private static String nonEmpty(String val) {
        return (val != null && !val.isEmpty()) ? val : null;
    }

    private static String firstNonEmpty(String... values) {
        for (String val : values) {
            if (val != null && !val.isEmpty()) return val;
        }
        return null;
    }

    private String resolveHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String generateAgentId() {
        return "agent-" + Long.toHexString(System.currentTimeMillis())
                + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    // Getters

    public String getApiKey() {
        return apiKey;
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public String getEnvironment() {
        return environment;
    }

    public double getSamplingRate() {
        return samplingRate;
    }

    public int getMaxCaptureDepth() {
        return maxCaptureDepth;
    }

    public int getMaxStringLength() {
        return maxStringLength;
    }

    public int getMaxCollectionSize() {
        return maxCollectionSize;
    }

    public List<String> getIncludePatterns() {
        return includePatterns;
    }

    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public String getHostname() {
        return hostname;
    }

    public String getAgentId() {
        return agentId;
    }

    /**
     * Returns the cached git context, built at startup from config/env vars.
     * Returns null if no release information is available.
     */
    public Map<String, Object> getGitContext() {
        return gitContext;
    }

    /**
     * Checks if sampling should capture this exception.
     */
    public boolean shouldSample() {
        if (samplingRate >= 1.0) return true;
        if (samplingRate <= 0.0) return false;
        return Math.random() < samplingRate;
    }
}
