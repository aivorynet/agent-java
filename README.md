# AIVory Monitor Java Agent

JVM bytecode instrumentation agent for capturing exceptions and breakpoint data.

## Requirements

- Java 11+ (JVM)
- Gradle 8.x for building

## Installation

```bash
# Download the agent JAR
curl -L https://aivory.net/agent/java/latest -o aivory-agent.jar

# Run with your application
java -javaagent:aivory-agent.jar -jar your-app.jar
```

## Configuration

Set via environment variables or JVM properties:

```bash
# Environment variables
export AIVORY_API_KEY=your_api_key
export AIVORY_ENVIRONMENT=production

# Or JVM properties
java -javaagent:aivory-agent.jar \
     -Daivory.api.key=your_api_key \
     -Daivory.environment=production \
     -jar your-app.jar
```

## Configuration Options

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `aivory.api.key` | `AIVORY_API_KEY` | - | API key (required) |
| `aivory.backend.url` | `AIVORY_BACKEND_URL` | `wss://api.aivory.net` | Backend URL |
| `aivory.environment` | `AIVORY_ENVIRONMENT` | `production` | Environment name |
| `aivory.sampling.rate` | `AIVORY_SAMPLING_RATE` | `1.0` | Exception sampling rate |
| `aivory.capture.maxDepth` | `AIVORY_MAX_DEPTH` | `3` | Max object depth |
| `aivory.include` | `AIVORY_INCLUDE` | `*` | Include patterns |
| `aivory.exclude` | `AIVORY_EXCLUDE` | `java.*,sun.*` | Exclude patterns |

## Building from Source

```bash
cd agent-java
./gradlew build
```

The agent JAR will be at `build/libs/aivory-monitor-agent-*.jar`.

## How It Works

1. **Bytecode Instrumentation**: Uses ByteBuddy to transform classes at load time
2. **Exception Interception**: Wraps methods to catch exceptions before they propagate
3. **Local Variable Capture**: Uses JVMTI to access local variables at breakpoints
4. **WebSocket Streaming**: Sends captured data to backend in real-time

## Framework Support

- **Spring Boot**: Auto-configuration available
- **Micronaut**: Works with standard agent attachment
- **Quarkus**: Native mode requires GraalVM configuration
- **Plain Java**: Works with any Java application

## Troubleshooting

**Agent not loading:**
- Ensure `-javaagent` flag is before `-jar`
- Check agent JAR path is correct

**No data captured:**
- Verify API key is set correctly
- Check include/exclude patterns
- Enable debug logging: `-Daivory.log.level=DEBUG`
