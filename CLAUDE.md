# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Main Development Workflow
```bash
sbt prepare          # Format code, apply fixes, compile tests - run before commits
sbt check            # Validate formatting, linting, documentation
./run-all-tests.sh   # Run all tests in isolated JVM instances (recommended)
sbt test             # Run unit tests only
```

### Test Isolation (IMPORTANT)
Due to resource contention between concurrent test suites, use the isolated test runner:
- `./run-all-tests.sh` - Runs each test suite in separate JVM instances with timeouts
- Prevents thread pool exhaustion and coordination deadlocks that cause hanging
- Individual commands: `sbt testNative`, `sbt testJVM`, `sbt testIntegration`

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

### Alternative Benchmark Tools
```bash
# Comprehensive benchmark runner with multiple modes
./tools/run-benchmarks.sh smoke      # Quick smoke test validation
./tools/run-benchmarks.sh fair       # Fair benchmarks with JSON output (all categories)
./tools/run-benchmarks.sh matrix     # Parametric scaling benchmarks
./tools/run-benchmarks.sh memory     # Memory & GC analysis benchmarks
./tools/run-benchmarks.sh full       # Complete benchmark suite (all modes)

# Options:
./tools/run-benchmarks.sh smoke --quick    # Fast execution with minimal iterations
./tools/run-benchmarks.sh fair --full      # Full statistical run with high iterations
./tools/run-benchmarks.sh core --gc        # Include GC profiling
```

### Performance Benchmarking
```bash
# Direct sbt benchmark commands (fast)
sbt benchCore                # Core operations only (~2min)
sbt benchState               # State management benchmarks
sbt benchConcurrency         # Concurrency benchmarks
sbt benchWithGC              # Core benchmarks with GC profiling

# Matrix benchmark commands
sbt benchMatrix              # All matrix benchmarks
sbt benchConcurrencyMatrix   # Concurrency scaling only
sbt benchDepthMatrix         # Depth scaling only
sbt benchDataMatrix          # Data size scaling only

# Individual benchmark debugging
LANG=C LC_ALL=C sbt "eruBenchJVM/Jmh/run -rf json -rff results.json CoreOperationsBench.eruSucceed"  # Single method with JSON
sbt "eruBenchJVM/Jmh/run -prof gc *StateManagementBench*"  # With profiler (console output)
sbt "eruBenchJVM/Jmh/run CoreOperationsBench.eruSucceed"  # Quick console output
```

### Code Quality
```bash
sbt scalafixAll       # Apply scalafix linting rules (very strict)
sbt scalafmtAll       # Format all code (120 char width)
sbt cleanAll          # Clean all target directories (including custom cleanup)
```

### Additional Build Commands
```bash
# Specialized smoke testing
./tools/smoke-test.sh     # 15-minute comprehensive validation
./validate-docs.sh        # Validate documentation files

# Platform-specific targets
sbt eruCoreJVM/test       # JVM tests for core module
sbt eruCoreNative/test    # Native tests for core module
sbt eruRuntimeJVM/test    # JVM runtime tests
sbt eruRuntimeNative/test # Native runtime tests
sbt eruBenchJVM/Jmh/run   # JMH benchmarks
sbt eruBenchMatrix/Jmh/run # Matrix benchmarks
```

### API Development Helper
```bash
# Interactive API helper tool for development assistance
scala tools/eru-api-helper.scala --list-methods              # List all public methods in Eru
scala tools/eru-api-helper.scala --validate "code snippet"   # Validate code against Eru API
scala tools/eru-api-helper.scala --imports parTraverse       # Show required imports for method
scala tools/eru-api-helper.scala --example parallel-processing # Generate working examples

# Example usage:
scala tools/eru-api-helper.scala --validate "import net.ghoula.eru.prelude.*; parTraverse(items)(f)"
scala tools/eru-api-helper.scala --example basic-composition
```

### Documentation
```bash
sbt docs              # Validate documentation examples with mdoc
sbt docsWatch         # Watch and validate documentation examples
sbt docsApi           # Generate cross-platform ScalaDoc API documentation
sbt docsSite          # Generate complete site with versioned docs
sbt docsPublish       # Publish to GitHub Pages at eru.ghoula.net
```

**Local Preview:**
- ScalaDoc API: `eru-site/target/scala-3.7.2/unidoc/index.html`
- Complete site: `eru-site/target/site/index.md` (with `api/` subfolder)
- mdoc output: `target/mdoc/` (validated markdown)

## Architecture

Eru is a high-performance effect system built with modern Scala 3, organized as a cross-platform library with specialized modules:

- **eru-core**: Pure synchronous kernel (cross-platform JVM/Native)
- **eru-runtime**: Runtime with concurrency support (cross-platform with platform-specific backends)
- **eru-bench-jvm**: JMH performance benchmarks (JVM only)
- **eru-bench-matrix**: Parametric scaling benchmarks (JVM only)
- **eru-integration-test**: End-to-end integration tests (JVM only)
- **eru-docs**: Documentation validation with mdoc
- **eru-site**: Site generation and ScalaDoc publishing

### Key Design Principles

1. **Pure Effect System**: `Eru[E, A]` represents immutable computation descriptions
2. **Zero-Cast Runtime**: No unsafe operations in the interpreter
3. **GADT-based**: Uses Scala 3 enums with type-safe chaining
4. **Cross-Platform**: Shared core with platform-specific runtime backends

### Core Components

- `eru-core/src/main/scala/net/ghoula/eru/Eru.scala` - Main effect type (1,631 lines)
- `eru-core/src/main/scala/net/ghoula/eru/EruObserver.scala` - Observability system
- `eru-core/src/main/scala/net/ghoula/eru/Exit.scala` - Exit/result modeling
- `eru-runtime/shared/src/main/scala/net/ghoula/eru/EruRuntime.scala` - Runtime execution

## Development Guidelines

### Using the API Helper for Development

The `tools/eru-api-helper.scala` tool is particularly useful for:
- **API Discovery**: Quickly list all available methods and their classifications (CORE vs RUNTIME)
- **Code Validation**: Check code snippets for common anti-patterns and missing imports
- **Example Generation**: Generate working code examples for common patterns
- **Import Assistance**: Determine exactly what imports are needed for specific methods

When developing or helping users with Eru code, use this tool to verify API usage and generate accurate examples.

### Four Pillars Framework
1. **Correctness as Foundation** - Correctness is non-negotiable
2. **Radical Ergonomics** - Joyful, intuitive developer experience
3. **Guided Correctness** - Easy path must be the correct path
4. **Exceptional Observability** - Runtime must not be a black box

### Critical Correctness Mandate
**NEVER compromise on correctness.** This project aims to deliver a world-class, best-of-its-kind Scala 3 effects system. Any attempt to:
- Skip failing tests by ignoring/commenting them out
- Hide test failures or reduce coverage
- Take shortcuts that compromise functionality
- Claim "fixes" that only mask underlying problems

...is a fundamental violation of our mission. Every single test must pass. Every feature must work correctly in all scenarios. There are no acceptable compromises on correctness - the easy path must also be the correct path.

### Data-Driven Development Mantra
**Be data-driven, not assumption-driven.** Before suggesting fixes or claiming something is broken:
- Run tests to verify actual behavior
- Examine the code carefully to understand the implementation
- Prove issues with concrete evidence (test failures, error messages, incorrect output)
- Don't assume the implementation is wrong when tests are passing - the test might be flawed
- When tests hang or fail, investigate both the test specification AND the implementation

Remember: "Don't just assume and start fixing things that are not broken - but if you can prove it, let's look at a fix."

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

## CI/CD and Release Process

### GitHub Actions Workflow
The CI builds documentation and tests on every PR and push:
- **Build step**: Runs tests, checks formatting, builds documentation site
- **Release step**: Publishes to Sonatype and eru.ghoula.net (tags only)
- **Snapshot step**: Publishes SNAPSHOT versions (main branch only)

### Required GitHub Secrets
For publishing to work, these secrets must be configured in repository settings:

**GPG Signing:**
- `PGP_SECRET`: GPG private key (base64 encoded)
- `PGP_PASSPHRASE`: GPG key passphrase

**Sonatype Publishing:**
- `SONATYPE_USERNAME`: Sonatype Central username/token
- `SONATYPE_PASSWORD`: Sonatype Central password/token

**Documentation Publishing:**
- `GH_PAGES_DEPLOY_KEY`: SSH private key for gh-pages deployment

### Release Process
1. Create and push a version tag: `git tag v1.0.0 && git push origin v1.0.0`
2. CI automatically publishes to Sonatype Central and eru.ghoula.net
3. Versioned documentation is available at `eru.ghoula.net/v1.0.0/` and `eru.ghoula.net/latest/`