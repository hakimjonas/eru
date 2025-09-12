# Contributing to Eru

Thank you for your interest in contributing to Eru! This document outlines the development workflow and guidelines for contributions.

## Development Workflow

### Prerequisites

- **JDK 21** (required for both JVM and Native builds)
- **sbt** (Scala Build Tool)
- **Git**

### Setup

```bash
git clone https://github.com/hakimjonas/eru.git
cd eru
sbt prepare  # Format code, apply fixes, compile tests
```

### Core Commands

```bash
# Main development workflow
sbt prepare          # Format, compile, and prepare for commit
sbt check            # Validate formatting and run quality checks  
sbt testAll          # Run all tests including integration tests
sbt docs             # Validate documentation examples

# Platform-specific testing  
sbt eruCoreJVM/test       # JVM tests for core module
sbt eruCoreNative/test    # Native tests for core module
sbt eruIntegrationTest/test # Integration tests (JVM only)

# Performance benchmarks
sbt bench             # Full benchmark suite (JVM only)
sbt benchCore         # Core performance benchmarks
```

## Code Quality Standards

Eru maintains exceptionally high code quality standards:

### Four Pillars Framework
1. **Correctness as Foundation** - Correctness is non-negotiable
2. **Radical Ergonomics** - Joyful, intuitive developer experience  
3. **Guided Correctness** - Easy path must be the correct path
4. **Exceptional Observability** - Runtime must not be a black box

### Scala 3 Requirements
- Use `enum` for ADTs (not sealed traits)
- Use `opaque type` for domain integrity
- Use `extension` methods for fluent APIs
- Use `given`/`using` for typeclasses
- Leverage intersection (`&`) and union (`|`) types

### Documentation & Testing
- **Zero inline comments** - Code must be self-documenting
- **Complete Scaladoc** for all public APIs
- **Comprehensive tests** with full logical coverage
- **Validated documentation** - All examples must compile via mdoc

### Mandatory Workflow
Before any commit or PR:

1. **Understand** - Fully understand the existing code and patterns
2. **Implement** - Follow existing conventions and patterns  
3. **Test** - Ensure 100% test pass rate
4. **Check** - Run `sbt check` to validate formatting and linting
5. **Prepare** - Run `sbt prepare` to format and compile

## Continuous Integration

All contributions are automatically validated via GitHub Actions:

### Build Process
- **Code Quality**: Formatting, linting, API compatibility checks
- **Cross-Platform Testing**: Full test suite on JVM and Scala Native
- **Documentation Validation**: All examples compiled via mdoc
- **Performance Regression**: Smoke test benchmarks
- **Release Automation**: Automatic publishing to Maven Central on tags

### Pull Request Requirements
- All CI checks must pass (no exceptions)
- 100% test coverage maintained
- Documentation updated if API changes
- Performance benchmarks must not regress significantly

## Release Process

Eru uses automated releases:

1. **Tag Release**: `git tag v1.0.0 && git push origin v1.0.0`
2. **Automatic Publishing**: CI publishes to Maven Central
3. **GitHub Release**: Automated release notes and artifact creation

## Architecture Guidelines

### Module Organization
- **eru-core**: Pure synchronous kernel (cross-platform JVM/Native)
- **eru-runtime**: Runtime with concurrency support (cross-platform)  
- **eru-bench-jvm**: JMH performance benchmarks (JVM only)
- **eru-integration-test**: End-to-end integration tests (JVM only)

### Performance Expectations
Eru achieves exceptional performance (4,756-160,143 ops/ms). Contributions must maintain this performance standard.

### Cross-Platform Compatibility
- **JVM**: Full support including Virtual Threads concurrency
- **Native**: Synchronous operations with identical API surface

## Getting Help

- **Issues**: Report bugs or request features via GitHub Issues
- **Discussions**: Join design discussions via GitHub Discussions
- **Documentation**: Comprehensive guides in `docs-src/`

## Code of Conduct

We are committed to providing a welcoming and inclusive experience for all contributors. Please treat all community members with respect and kindness.

---

*Thank you for helping make Eru the definitive effect system for Scala 3!*