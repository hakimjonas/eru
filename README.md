# Eru

Eru is an effect system for Scala 3 that makes correctness visible in types. It provides Virtual Thread-based concurrency on JVM and cross-compiles to Scala Native.

Read **[The Eru Manifesto](MANIFESTO.md)** to understand the design principles and goals.

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-core" % "0.1.0",
  "net.ghoula" %% "eru-runtime" % "0.1.0"
)
```

**Requirements**: Scala 3.7.3+, Java 21+ (for JVM), Scala Native 0.5+ (for Native)

## Quick Start

```scala
import net.ghoula.eru.prelude.*

// Pure effects - no execution yet
val program = Eru.succeed(42).map(_ * 2)

// Run synchronously
val result = program.unsafeRunSync() // 84

// Resource safety
val readFile = Eru.effect {
  Files.newBufferedReader(path)
}.bracket { reader =>
  Eru.effect(reader.close())
} { reader =>
  Eru.effect(reader.readLine())
}

// Error handling with typed errors
val validated: Eru[String, Int] =
  Eru.effect(readInput())
    .flatMap(validate)
    .recover { case "invalid" => 0 }
```

**See [Quick Start Guide](QUICKSTART.md) for detailed examples.**

## Core Features

**Pure Effects**: Programs are immutable values. Side effects are suspended until execution.

**Typed Errors**: The error channel is part of the type signature (`Eru[E, A]`). Scala 3's union types track all possible errors at compile time.

**Resource Safety**: Bracket patterns and finalizers ensure cleanup. Resources are released in acquisition-reverse order (FILO).

**Zero-Cast Runtime**: The interpreter uses no unsafe casts. Type safety is preserved through the entire execution path, verified by build linting.

**Cross-Platform**: Same core effect system on JVM and Native. Write once, compile to both platforms.

## What Makes Eru Different

**Suspension Safety**: Operations that may block indefinitely (like `queue.take`) return a `Suspending[E, A]` type. This type has no `unsafeRunSync` method - you must use `timeout` or `fork`. The compiler prevents accidental deadlocks, making the safe path the obvious path.

**Virtual Thread-Native Design**: Built directly on Java Virtual Threads rather than implementing a custom fiber runtime. Uses ThreadLocal scope propagation for structured concurrency semantics, making Eru forward-compatible with Java's emerging structured concurrency APIs (JEP 480, Fifth Preview in JDK 25).

**Zero-Dependency Primitives**: Concurrency primitives like Queue and Semaphore are built entirely from Eru's core abstractions (Ref + Promise), not `java.util.concurrent`. This demonstrates true compositional concurrency without hidden dependencies.

## Platform Support

### JVM (Java 21+)
- **Concurrency**: Virtual Threads provide lightweight fibers with true parallelism
- **Operations**: fork, race, timeout, sleep all work as expected
- **Primitives**: Ref, Semaphore, Deferred, Promise, Queue for coordination
- **Use cases**: Web servers, concurrent applications, high-throughput systems

### Scala Native (0.5+)
- **Execution**: Single-threaded, synchronous execution
- **API Compatibility**: Same API as JVM - code compiles identically
- **Behavior**: Concurrent operations (`fork`, `race`, `timeout`) compile but execute sequentially
- **Use cases**: CLI tools, scripts, single-threaded applications

**Important**: Native support enables cross-compilation but does not provide true concurrency. Choose Native for deterministic single-threaded execution, not for parallel workloads.

## Technical Foundation

Eru's design leverages modern platform capabilities:

- **Scala 3.7.3+**: Union types for error channels, GADT enums for the effect representation, opaque types for domain modeling
- **Java 21+**: Virtual Threads for scalable concurrency, structured concurrency for safe fiber management
- **Scala Native 0.5+**: Cross-platform `java.time` via scala-java-time, zero-reflection runtime

These platform improvements enabled compile-time safety guarantees that weren't previously possible.

## Documentation

- **[Quick Start Guide](QUICKSTART.md)** - Get started with Eru basics in 5 minutes
- **[API Documentation](API.md)** - Complete API reference for quick lookup
- **[Resource Management](RESOURCES.md)** - Safe resource handling with bracket and ensure
- **[Observability](OBSERVER.md)** - Monitoring and debugging with observers
- **[Contributing](CONTRIBUTING.md)** - Guidelines for contributing to Eru

For a comprehensive progressive guide, see **[The Eru Book](https://hakimjonas.github.io/eru/book/)** (coming soon).

## Project Status

**Version**: 0.1.0 (pre-release)
**Stability**: Core API is stable. Breaking changes may occur before 1.0.
**Test Coverage**: 576+ tests across JVM and Native platforms

The JVM runtime is production-ready with full concurrency support. Native support provides API compatibility for cross-compilation with synchronous execution.

## Contributing

Eru is designed and developed by **Hakim Jonas Ghoula** and licensed under the **MIT License**.

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for development workflow, code quality standards, and build commands.