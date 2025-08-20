# Eru

Eru is a pragmatic and ergonomic effect system for Scala 3, built for correctness, performance, and a good developer experience. It serves as the powerful, cross-platform foundation for the Valar validation library.

This project is guided by a strong philosophical vision for what a modern effect system should be. To understand the design principles and goals of Eru, please read our core document:

**The Eru Manifesto**

## Status

**Current Development Status (August 2025)**: The core synchronous kernel and concurrent runtime are complete and have been validated against our primary goals from the Manifesto.

- **Correctness Foundation**: 156/156 tests passing, with a zero-casting runtime implementation enforced by the build linter.

- **Performance**: Includes construction-time fusion for pure flatMap chains, resulting in performance for pure computations that is competitive with hand-optimized map chains (~196k ops/ms, depth 1000).

- **Concurrent Runtime**: A complete fiber-based asynchronous runtime with structured concurrency is implemented.

- **Platform Support**: Full support for JVM and Scala Native.

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
