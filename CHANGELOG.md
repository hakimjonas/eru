# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-alpha] - 2026-08

Initial public release.

Eru is an effect system for Scala 3 built on Java Virtual Threads. Requires
Scala 3.8.4+ and Java 25+.

### Added

- `Eru[E, A]` effect type: GADT enum with union-typed error channels, stack-safe
  `flatMap`, construction-time fusion, and a fast-path interpreter
- Structured concurrency on Java Virtual Threads: `fork`, `forkDaemon`,
  `forkTracked`, `forkWithObserver`, scope-aware interruption and cleanup
- Concurrency primitives built from Eru's own abstractions: `Ref`, `Promise`,
  `Queue`, `Semaphore`, `Deferred`, `Hub`, `RefMap`, `KeyedSemaphore`,
  `CountDownLatch`, `CyclicBarrier`
- `Suspending[E, A]` effect type: operations that may block indefinitely cannot
  be run synchronously without `timeout` or `fork`, making the safe path the
  obvious path
- Time algebra: `Monotonic`, `Wall`, and `Logical` time capabilities,
  `MonotonicInstant`, `LogicalTestClock` for deterministic tests, and a
  non-blocking hashed timer wheel
- `EruObserver` event system: program, fiber, structured-concurrency, and
  tracing events; `unsafeRunSyncWith`, `forkWithObserver`
- `EruTrace`: spans, trace IDs, and context propagation across `fork` / `race` /
  `handleSuspend`
- Backend SPI: `ConcurrencyBackend` + `BackendProvider` with ServiceLoader
  discovery; the Virtual Thread backend is the default, with a sequential
  fallback backend for deterministic execution
- `RuntimeMetrics`: lock-free counters for effects, fibers, suspensions, and
  typed errors
- Retry and circuit-breaker support in `patterns.ErrorHandling`
- Deterministic test kit: `TestClock`, `LogicalTestClock`, `EruTest`,
  `IsolatedTestRunner` (`net.ghoula.eru.test`)
