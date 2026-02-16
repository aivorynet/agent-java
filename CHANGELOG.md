# Changelog

All notable changes to the AIVory Monitor Java Agent will be documented in this file.

This project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-02-16

### Added
- ByteBuddy-based bytecode instrumentation for exception interception
- Automatic uncaught exception capture with full stack traces
- Local variable capture at exception throw sites
- Non-breaking breakpoint support via backend commands
- WebSocket connection to AIVory backend with automatic reconnection
- Configurable sampling rate and capture depth
- Include/exclude patterns for class filtering
- Spring Boot, Micronaut, and Quarkus framework support
- JVM property and environment variable configuration
- Heartbeat and metrics reporting
