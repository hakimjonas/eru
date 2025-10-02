# Eru

Eru is a pragmatic effect system for Scala 3, built for correctness, performance, and developer ergonomics. It provides a cross-platform foundation with true concurrency support on JVM and seamless synchronous execution on Scala Native.

This project is guided by a strong philosophical vision for what a modern effect system should be. To understand the design principles and goals of Eru, please read our core document:

**[The Eru Manifesto](MANIFESTO.md)**

## Status

**Current Development Status**: Eru has achieved complete cross-platform implementation with full concurrency support on JVM and synchronous execution on Native.

- **Correctness Foundation**: 576+ tests passing across JVM and Native platforms, with a zero-cast runtime implementation enforced by the build linter.

- **Cross-Platform Support**:
    - **JVM**: Full support with true concurrency via Java Virtual Threads (JDK 21+)
    - **Scala Native**: Complete synchronous runtime with identical API surface

- **Concurrency Runtime**: Production-ready concurrent operations including fork, race, zipPar, timeouts, structured concurrency patterns, optimized coordination primitives (Ref, Semaphore, Deferred), degree-limited parallel execution, and error accumulation patterns for domain validation.

- **Performance**: Exceptional performance characteristics with optimized execution paths and minimal allocation overhead. Benchmarks demonstrate **177-694x faster execution** than Cats Effect and **4-5x faster** than ZIO on common operations.

## Quick Start

```scala
import net.ghoula.eru.prelude.*

// Basic effects
val hello = Eru.succeed("Hello, Eru!")

// Resource-safe operations  
val fileOp = Eru.effect {
  Files.newBufferedReader(path)
}.bracket { reader =>
  Eru.effect(reader.close())
} { reader =>
  Eru.effect(reader.readLine())
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
- **Observability**: Comprehensive event system for monitoring and debugging
- **Performance**: Optimized design leveraging Virtual Threads (see Performance section)

## Performance

Eru achieves strong performance through Virtual Threads and optimized design:

- **Core Operations**: 177-694x faster than Cats Effect in microbenchmarks
- **Basic Combinators**: 4-5x faster than ZIO in microbenchmarks
- **Memory Efficiency**: Lower allocation rates and reduced per-operation footprint
- **Concurrency**: 30-70x improvements on concurrent operations

Benchmark results are reproducible and include full environment details. See `eru-bench-jvm` for methodology and raw data.

## Documentation

- **[Quick Start Guide](QUICKSTART.md)** - Get started with Eru basics in 5 minutes
- **[API Documentation](API.md)** - Complete API reference for quick lookup
- **[Observability](OBSERVER.md)** - Monitoring and debugging with observers
- **[External Resources](RESOURCES.md)** - Community resources and ecosystem links
- **[Contributing](CONTRIBUTING.md)** - Guidelines for contributing to Eru

For a comprehensive progressive guide, see **[The Eru Book](https://hakimjonas.github.io/eru/book/)** (coming soon).

## Commands

### Development Workflow
```bash
sbt prepare          # Format, compile, and prepare for commit
sbt check            # Validate formatting and run quality checks  
sbt testAll          # Run all tests including integration tests
sbt test             # Run unit tests only
sbt docs             # Validate documentation examples
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

## Contributing

Eru welcomes contributions! Please see CONTRIBUTING.md in the repository root for development workflow, code quality standards, and guidelines.

## Author

Eru is designed and developed by **Hakim Jonas Ghoula**.

## License

Eru is licensed under the MIT License.