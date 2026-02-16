# Contributing to AIVory Monitor Java Agent

Thank you for your interest in contributing to the AIVory Monitor Java Agent. Contributions of all kinds are welcome -- bug reports, feature requests, documentation improvements, and code changes.

## How to Contribute

- **Bug reports**: Open an issue at [GitHub Issues](https://github.com/aivorynet/agent-java/issues) with a clear description, steps to reproduce, and your environment details (JDK version, OS, application server).
- **Feature requests**: Open an issue describing the use case and proposed behavior.
- **Pull requests**: See the Pull Request Process below.

## Development Setup

### Prerequisites

- JDK 17 or later
- Gradle (wrapper included)

### Build and Test

```bash
cd monitor-agents/agent-java
./gradlew build
./gradlew test
```

### Running the Agent

```bash
java -javaagent:build/libs/aivory-agent.jar -jar your-app.jar
```

## Coding Standards

- Follow the existing code style in the repository.
- Write tests for all new features and bug fixes.
- Keep ByteBuddy instrumentation non-intrusive -- the agent must not break the host application.
- Ensure the agent JAR remains shaded and self-contained with no dependency conflicts.

## Pull Request Process

1. Fork the repository and create a feature branch from `main`.
2. Make your changes and write tests.
3. Ensure all tests pass (`./gradlew test`).
4. Submit a pull request on [GitHub](https://github.com/aivorynet/agent-java) or GitLab.
5. All pull requests require at least one review before merge.

## Reporting Bugs

Use [GitHub Issues](https://github.com/aivorynet/agent-java/issues). Include:

- Java version and OS
- Agent version
- Stack trace or error output
- Minimal reproduction steps

## Security

Do not open public issues for security vulnerabilities. Report them to **security@aivory.net**. See [SECURITY.md](SECURITY.md) for details.

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
