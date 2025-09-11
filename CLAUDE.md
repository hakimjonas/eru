# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Main Development Workflow
```bash
sbt prepare          # Format code, apply fixes, compile tests - run before commits
sbt check            # Validate formatting, linting, documentation
sbt testAll          # Run all tests including integration tests
sbt test             # Run unit tests only
```

### Platform-Specific Testing
```bash
# Targeted test commands for CI optimization
sbt testJVM              # Run all JVM tests (core + runtime)
sbt testNative           # Run all Native tests (core + runtime)
sbt testIntegration      # Run integration tests (JVM only)
sbt testQuick            # Run JVM tests excluding slow tests
sbt testSlow             # Run only slow/stress tests

# Individual module testing
sbt eruCoreJVM/test       # JVM tests for core module
sbt eruCoreNative/test    # Native tests for core module  
sbt eruIntegrationTest/test # Integration tests (JVM only)
```

### Performance Benchmarking
```bash
# Fair benchmark system (recommended)
./run-fair-benchmarks.sh all      # Full comprehensive suite (~27min)
./run-fair-benchmarks.sh core     # Core operations only (~2min)
./run-fair-benchmarks.sh errors state  # Multiple categories
./run-fair-benchmarks.sh concurrency --quick  # Quick concurrency test

# Individual benchmark debugging
sbt "eruBenchJVM/Jmh/run CoreOperationsBench.eruSucceed"  # Single method
sbt "eruBenchJVM/Jmh/run -prof gc *StateManagementBench*"  # With profiler
```

### Code Quality
```bash
sbt scalafixAll       # Apply scalafix linting rules (very strict)
sbt scalafmtAll       # Format all code (120 char width)
sbt cleanAll          # Clean all target directories
```

### Documentation
```bash
sbt docs              # Validate documentation examples with mdoc
sbt docsWatch         # Watch and validate documentation examples
```

## Architecture

Eru is a high-performance effect system built with modern Scala 3, organized as a cross-platform library with four core modules:

- **eru-core**: Pure synchronous kernel (cross-platform JVM/Native)
- **eru-runtime**: Runtime with concurrency support (cross-platform)
- **eru-bench-jvm**: JMH performance benchmarks (JVM only)
- **eru-integration-test**: End-to-end integration tests (JVM only)

### Key Design Principles

1. **Pure Effect System**: `Eru[E, A]` represents immutable computation descriptions
2. **Zero-Cast Runtime**: No unsafe operations in the interpreter
3. **GADT-based**: Uses Scala 3 enums with type-safe chaining
4. **Cross-Platform**: Shared core with platform-specific runtime backends

### Core Components

- `eru-core/src/main/scala/net/ghoula/eru/Eru.scala` - Main effect type (704 lines)
- `eru-core/src/main/scala/net/ghoula/eru/EruObserver.scala` - Observability system
- `eru-core/src/main/scala/net/ghoula/eru/Exit.scala` - Exit/result modeling
- `eru-runtime/shared/src/main/scala/net/ghoula/eru/EruRuntime.scala` - Runtime execution

## Development Guidelines

### Four Pillars Framework
1. **Correctness as Foundation** - Correctness is non-negotiable
2. **Radical Ergonomics** - Joyful, intuitive developer experience
3. **Guided Correctness** - Easy path must be the correct path
4. **Exceptional Observability** - Runtime must not be a black box

### Scala 3 Language Requirements
- Use `enum` for ADTs (not sealed traits)
- Use `opaque type` for domain integrity  
- Use `extension` methods for fluent APIs
- Use `given`/`using` for typeclasses
- Leverage intersection (`&`) and union (`|`) types

### Code Quality Standards
- **Zero inline comments** - Code must be self-documenting
- **Complete Scaladoc** for all public APIs
- **Comprehensive tests** with full logical coverage
- **Mandatory workflow**: Understand → Implement → Test → Check → Prepare

### Current Platform Support
- **JVM**: Full support including virtual threads concurrency
- **Native**: Synchronous operations only (no concurrency runtime)

### Performance Expectations
Eru achieves exceptional performance (4,756-160,143 ops/ms, 50-80x faster than Cats Effect). Maintain this performance standard when making changes.

### JVM Configuration
The repository includes optimized JVM settings in `.jvmopts` to prevent GC thrashing during development and testing:
- Heap: 2GB initial, 8GB max
- G1 Garbage Collector for low-latency performance
- Optimized for high-throughput testing and benchmarking