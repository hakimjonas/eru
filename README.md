# Eru

Eru is an effect system for Scala 3 that makes correctness visible in types. It provides true concurrency on JVM via Virtual Threads, and cross-compiles to Scala Native for single-threaded applications.

This project is guided by a clear vision for what a modern effect system should be. To understand the design principles and goals of Eru, please read our core document:

**[The Eru Manifesto](MANIFESTO.md)**

## Status

**Current Status**: Production-ready on JVM with full concurrency support. Native support provides API compatibility for cross-compilation with synchronous execution.

**Test Coverage**: 576+ tests across JVM and Native platforms, with zero-cast runtime enforced by build linting.

**JVM Runtime**: Virtual Threads enable millions of concurrent fibers. Operations include fork, race, zipPar, timeouts, structured concurrency, coordination primitives (Ref, Semaphore, Deferred, Promise), degree-limited parallelism, and error accumulation patterns.

**Native Runtime**: Synchronous execution model with identical API surface. Concurrent operations compile but execute sequentially. Suitable for CLIs, scripts, and single-threaded applications.

**Architecture**: GADT-based interpreter with continuation-passing execution (no stack frame allocation). Platform-optimized backends share a unified core.

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
- **Async Operations**: Real timeouts, sleep, and non-blocking boundaries
- **Structured Concurrency**: Parent-child fiber relationships with automatic cleanup
- **Observer Integration**: Complete fiber lifecycle events and tracing
- **Use Cases**: Servers, concurrent applications, high-throughput systems

### Scala Native Platform
- **Synchronous Execution**: Single-threaded, deterministic execution model
- **API Compatibility**: Same API surface as JVM - code compiles identically
- **Execution Differences**: `fork`, `race`, `timeout` compile but execute synchronously
- **Resource Safety**: Full support for finalizers and resource management
- **Zero Reflection**: No runtime reflection dependencies
- **Use Cases**: CLIs, scripts, single-threaded applications, Native binaries

**Note**: Native provides API compatibility for cross-compilation, but concurrent operations (fork, race, timeout) do not provide true concurrency. They execute synchronously to maintain deterministic behavior.

## What Makes Eru Different

**Suspension Safety**: Operations that may suspend indefinitely return `Suspending[E, A]`, which has no `unsafeRunSync` method. The type system prevents deadlocks at compile time by forcing you to use `timeout` or `fork`. Non-suspending operations return `Immediate[E, A]` which can be run synchronously.

**Typed Error Channel**: Errors are values in the type signature (`Eru[E, A]`), with full union type support. The compiler tracks which errors your program can produce.

**Zero-Cast Interpreter**: The core runtime uses no unsafe casts, verified by build-time linting. The GADT-based interpreter preserves types through the entire execution path.

**FILO Finalizer Semantics**: Resource cleanup happens in First-In-Last-Out order, matching natural acquisition/release patterns. Finalizers compose predictably across flatMap and other combinators.

**Structured Concurrency**: Parent fibers automatically interrupt and await child fibers on exit. No fiber leaks, no manual cleanup, no surprises.

**Cross-Platform Core**: The same effect system kernel runs on JVM and Native. Platform-specific runtimes provide optimized execution (Virtual Threads on JVM, synchronous on Native) behind a unified API.

## Requirements

**Minimum Versions**:
- **Scala**: 3.7.3 or later
- **JVM**: Java 21 or later (for Virtual Threads support)
- **Scala Native**: 0.5.x (for Native compilation)

**Platform Dependencies**: Eru's design leverages modern language and runtime features:
- **Scala 3**: Union types for error channels, opaque types for domain modeling, GADT enums for the effect representation, match types for advanced type-level programming
- **Java 21+**: Virtual Threads enable lightweight concurrency with millions of fibers, structured concurrency primitives provide safe parent-child relationships
- **Scala Native 0.5+**: Cross-platform `java.time` support via scala-java-time, deterministic single-threaded execution

These platform improvements made it possible to build an effect system with compile-time safety guarantees and runtime efficiency that weren't previously achievable.

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

---

*P.S. It's also quite fast.*