# Eru

Eru is a pragmatic and ergonomic effect system for Scala 3, built for correctness, performance, and a joyful developer experience. It serves as the powerful, cross-platform foundation for the [Valar](https://github.com/hakimjonas/valar) validation library.

This project is guided by a strong philosophical vision for what a modern effect system should be. To understand the design principles and goals of Eru, please read our core document:

### [**The Eru Manifesto**](https://github.com/hakimjonas/eru/blob/main/docs-src/MANIFESTO.md)

## Status

**Current Development Status (August 2025)**: High-priority manifesto features are complete with exceptional performance validation:

- ✅ **Correctness Foundation**: 156/156 tests passing, zero-casting implementation
- ✅ **Radical Ergonomics**: Built-in caching, timeouts, retries, and resource safety as discoverable extension methods
- ✅ **"Pit of Success"**: Comprehensive resource management patterns with automatic cleanup
- ✅ **Performance Excellence**: 187M+ ops/sec with JMH-validated benchmarks and industry-leading optimizations
- ✅ **Concurrent Runtime**: Full fiber-based async runtime with structured concurrency
- ✅ **Enhanced Observability**: Rich error diagnostics with structured InterruptCause and observer patterns

**Platform Support**: Full JVM + Scala Native compatibility with identical performance characteristics.

## License

Eru is licensed under the **MIT License**. See the [LICENSE](https://github.com/hakimjonas/eru/blob/main/LICENSE) file for details.

## Quickstart

Start here for the synchronous core and pure composition patterns:
- Eru Quickstart — Synchronous Core: [quickstart.md](https://github.com/hakimjonas/eru/blob/main/quickstart.md)

## Development Playbook

For the point-by-point execution plan aligned with our Manifesto and guidelines, see:
- Eru Development Playbook — Point-by-Point Plan: [PLAYBOOK.md](https://github.com/hakimjonas/eru/blob/main/docs-src/PLAYBOOK.md)

## Concurrent Runtime

**✅ Production-Ready Async Runtime**: Complete fiber-based concurrency with structured programming model.

### Runtime Architecture
- **`eru-core`**: Pure synchronous kernel with caching and resource safety extensions
- **`eru-runtime`**: Full concurrent runtime with fibers, structured concurrency, and cooperative scheduling
- **Platform Support**: Identical functionality on JVM and Scala Native

### Key Features
- **Fiber Management**: Fork/await, interruption, and lifecycle management
- **Structured Concurrency**: Race, zipPar, and proper resource cleanup
- **Timeout & Retry**: Built-in as discoverable extension methods (`.timeout()`, `.retry()`, `.retryN()`)
- **Resource Safety**: Automatic cleanup with comprehensive patterns (`.autoClose`, `.ensureAll`, `.pooled`)

For technical details: [design/async.md](./design/async.md)

## Guides

### Core Functionality
- **Quickstart** — Synchronous Core and Pure Composition: [quickstart.md](./quickstart.md)
- **Resource Safety** — Enhanced patterns with `.ensure`, `.autoClose`, `.ensureAll`, `.pooled`: [resources.md](./resources.md)
- **Concurrency** — Fibers, structured concurrency, and cooperative scheduling: [concurrency.md](./concurrency.md)
- **Observability** — EruObserver, structured errors, and debugging: [observer.md](./observer.md)

### Built-in Extension Methods
- **Caching**: `.cached`, `.memoized` for automatic result caching
- **Timeouts**: `.timeout(duration)`, `.timeoutTo(duration, fallback)` for time-bounded operations  
- **Retries**: `.retry(policy)`, `.retryN(count)`, `.retryWithBackoff(duration, max)` for resilient operations
- **Parallel Operations**: `.zipPar(other)`, `.race(other)`, `.fork` for concurrent execution

## Integrations

- Valar Integration Plan — Refactoring Valar on Eru: [integrations/valar.md](./integrations/valar.md)
- Valar repository (open-source): https://github.com/hakimjonas/valar
- Valar on Maven Central: see coordinates in Valar’s README, or search: https://search.maven.org/search?q=valar%20hakimjonas


## Developer Benchmarks

- Benchmarks — Measuring Eru Core (JVM): [dev/benchmarks.md](./dev/benchmarks.md)
