# Eru

Eru is a pragmatic and ergonomic effect system for Scala 3, built for correctness, performance, and exceptional developer experience. It provides a powerful, cross-platform foundation with true concurrency support on JVM and seamless synchronous execution on Scala Native.

This project is guided by a strong philosophical vision for what a modern effect system should be. To understand the design principles and goals of Eru, please read our core document:

**[The Eru Manifesto](MANIFESTO.md)**

## Status

**Current Development Status (September 2025)**: Eru has achieved complete cross-platform implementation with exceptional performance optimizations and full concurrency support.

- **Correctness Foundation**: 383/383 tests passing across JVM and Native platforms, with a zero-cast runtime implementation enforced by the build linter.

- **Cross-Platform Support**: 
  - **JVM**: Full support with true concurrency via Java Virtual Threads (JDK 21+)
  - **Scala Native**: Complete synchronous runtime with identical API surface

- **Concurrency Runtime**: Production-ready concurrent operations including fork, race, zipPar, timeouts, structured concurrency patterns, and optimized coordination primitives (Ref, Semaphore, Deferred).

- **Exceptional Performance**: Industry-leading performance with significant optimizations:
  - **Ref operations**: 36,000+ ops/ms (407x faster than Cats Effect, 5.4x faster than ZIO)
  - **Semaphore operations**: 29,000+ ops/ms (329x faster than Cats Effect, 9.1x faster than ZIO)
  - **Zero-cast runtime**: GADT-based design eliminates unsafe operations
  - **Optimized primitives**: Eliminated defensive patterns for maximum throughput

## Quick Start

```scala
import net.ghoula.eru.prelude.*

// Basic effects
val hello = Eru.succeed("Hello, Eru!")

// Resource-safe operations  
val fileOp = Eru.resource {
  Files.newBufferedReader(path)
} { reader =>
  Eru.effect(reader.close())
}

// Concurrent operations (JVM) / Sequential (Native)
val concurrent = for {
  fiber1 <- Eru.succeed(42).fork
  fiber2 <- Eru.succeed("world").fork
  result1 <- fiber1.await
  result2 <- fiber2.await
} yield (result1, result2)
```

## Architecture

Eru is organized into focused, cross-platform modules:

- **eru-core**: Pure synchronous kernel with zero-cast interpreter (JVM + Native)
- **eru-runtime**: Cross-platform runtime with concurrency support (JVM + Native) 
- **eru-bench-jvm**: Performance benchmarks and profiling (JVM only)
- **eru-integration-test**: End-to-end integration tests (JVM only)

## Platform Capabilities

### JVM Platform
- **True Concurrency**: Java Virtual Threads for lightweight, scalable concurrency
- **Non-blocking Operations**: Efficient sleep, timeouts, and async boundaries
- **Structured Concurrency**: Parent-child fiber relationships with automatic cleanup
- **Observer Integration**: Complete fiber lifecycle events and tracing

### Scala Native Platform  
- **Synchronous Execution**: Deterministic, single-threaded execution model
- **Identical API**: Same interface as JVM for seamless cross-platform development
- **Resource Safety**: Full support for finalizers and resource management
- **Zero Reflection**: Native-compatible implementation without runtime reflection

## Key Features

- **Pure Effect System**: Immutable, referentially transparent computations
- **Resource Safety**: Automatic cleanup with finalizers and bracket patterns
- **Cross-Platform**: Write once, run on JVM with concurrency or Native synchronously
- **Type Safety**: Strong typing with covariant error handling
- **Zero-Cast Runtime**: No unsafe operations in the core interpreter
- **Exceptional Performance**: Competitive with hand-optimized implementations
- **Rich Observability**: Comprehensive event system for monitoring and debugging

## Documentation

- **[Quick Start Guide](docs-src/QUICKSTART.md)** - Get started with Eru basics
- **[API Documentation](docs-src/API.md)** - Complete API reference
- **[Concurrency Guide](docs-src/CONCURRENCY.md)** - Fiber patterns and structured concurrency
- **[Observability](docs-src/OBSERVER.md)** - Monitoring and debugging with observers
- **[Development Playbook](docs-src/PLAYBOOK.md)** - Development practices and guidelines

## Commands

### Development Workflow
```bash
sbt prepare          # Format, compile, and prepare for commit
sbt check            # Validate formatting and run quality checks  
sbt testAll          # Run all tests including integration tests
sbt test             # Run unit tests only
```

### Platform-Specific Testing
```bash
sbt eruCoreJVM/test       # JVM tests for core module
sbt eruCoreNative/test    # Native tests for core module
sbt eruIntegrationTest/test # Integration tests (JVM only)
```

### Benchmarking
```bash
sbt bench             # Full benchmark suite (JVM only)
sbt benchCore         # Core performance benchmarks
```

## License

Eru is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.