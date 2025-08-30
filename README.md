# Eru

Eru is a pragmatic and ergonomic effect system for Scala 3, built for correctness, performance, and a good developer experience. It serves as the powerful, cross-platform foundation for the Valar validation library.

This project is guided by a strong philosophical vision for what a modern effect system should be. To understand the design principles and goals of Eru, please read our core document:

**The Eru Manifesto**

## Status

**Current Development Status (August 2025)**: The core synchronous kernel is complete and validated against our primary goals from the Manifesto.

- **Correctness Foundation**: 156/156 tests passing, with a zero-casting runtime implementation enforced by the build linter.

- **Performance**: Includes construction-time fusion for pure flatMap chains, resulting in performance for pure computations that is competitive with hand-optimized map chains (~196k ops/ms, depth 1000).

- **Runtime Status**: Sequential “concurrency‑lite” today (zipPar sequential, race deterministic) to preserve a simple, portable core. True concurrency on JVM via Java Virtual Threads (JDK 21+) is the immediate next milestone; optional integration with JDK 25 Structured Concurrency is planned behind preview flags with automatic fallback.

- **Platform Support**: JVM fully supported. Scala Native supports the synchronous core and sequential runtime today; true concurrency on Native is deferred (Native has multithreading but no Virtual Threads).

## License

Eru is licensed under the MIT License. See the LICENSE file for details.

## Quickstart

Start here for the synchronous core and pure composition patterns:

- **Eru Quickstart — Synchronous Core**: quickstart.md

## Development Playbook

For the point-by-point execution plan aligned with our Manifesto and guidelines, see:

- **Eru Development Playbook — Point-by-Point Plan**: PLAYBOOK.md

## Concurrent Runtime

The runtime is a complete fiber-based concurrency model with a structured programming interface.

### Runtime Architecture

- **eru-core**: The pure, synchronous kernel.

- **eru-runtime**: The concurrent runtime with fibers, structured concurrency, and cooperative scheduling.

### Key Features

- **Fiber Management**: Fork/await, interruption, and lifecycle management.

- **Structured Concurrency**: race, zipPar, and principled resource cleanup.

- **Resource Safety**: Automatic cleanup patterns like `.autoClose` and `.ensure`.

For technical details, see the design document: design/async.md

## Guides

- **Quickstart — Synchronous Core and Pure Composition**: quickstart.md

- **Resource Safety — Patterns with .ensure and .autoClose**: resources.md

- **Concurrency — Fibers and structured concurrency**: concurrency.md

- **Observability — EruObserver and debugging**: observer.md

## Public API Spec — Executable

The full, end-to-end Public API Specification is part of the standard test suite and serves as executable documentation that demonstrates the unified API and its ergonomics.

- Run all tests (includes the Public API spec): `sbt test`
- Run just this suite by tag: `sbt testOnly -- -t integration`
