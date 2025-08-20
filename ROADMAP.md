# Eru Roadmap

**Mission**: Build a definitive effect system for the discerning Scala 3 developer, grounded in the pillars of Correctness, Radical Ergonomics, a Guided Correctness, and Exceptional Observability.

**Last updated**: 2025-08-20

## Current Status Snapshot

The Eru project has successfully completed its initial major development phases, delivering a correct, performant, and ergonomic effect system for Scala 3.

## Implemented & Verified ✅

- **Core Data Type & Combinators**: A complete synchronous kernel including Eru[E, A], all standard monad and bifunctor operations (map, flatMap, recover, zip, etc.), and rich constructors (effect, fromEither, etc.).

- **Zero-Casting Runtime**: A fully type-safe, fiber-based concurrent runtime that is free of asInstanceOf calls, a guarantee enforced by the build linter.

- **Construction-Time Fusion**: A key performance optimization that evaluates pure flatMap chains at construction time, making their execution overhead virtually zero.

- **Resource Safety**: Principled resource management with ensure and bracket, guaranteeing FILO finalizer ordering and correct execution on success, failure, or defect.

- **Concurrency Primitives**: A full suite of non-blocking, fiber-safe concurrency primitives including Ref, Deferred, and Semaphore.

- **Structured Concurrency**: High-level, safe concurrency combinators like zipPar and race.

- **Observability**: A built-in EruObserver model for consuming structured lifecycle and debug events.

- **Correctness Guarantees**: Adherence to Monad laws is verified through property-based testing.

- **Platform Support**: First-class, parallel support for both JVM and Scala Native.

- **Comprehensive Tests**: Full test coverage for all features on both JVM and Native platforms.

- **Documentation**: Core guides for quickstart, resources, concurrency, and observer are complete.

## Future Direction

- **Ecosystem Integrations**: Thoughtfully expand the ecosystem with a small number of high-quality, principled integrations.

- **Interop**: Improve interoperability with standard library types and other effect systems.

- **Advanced Concurrency**: Investigate higher-level concurrency patterns and data structures based on community feedback.

## Milestones and Deliverables

### 0.3.0 — The Synthesis Release — ✅ Completed

This milestone represents the culmination of the core vision for Eru, delivering the full synthesis of correctness and performance. All original goals for 0.1.0, 0.2.0, and 0.3.0 have been met or exceeded.

**Pillars Achieved**: Correctness, Radical Ergonomics, Guided Correctness, Exceptional Observability.

**Key Deliverables**:

- [COMPLETED] Finalized synchronous and asynchronous API surface.
- [COMPLETED] Implemented and verified the zero-casting runtime.
- [COMPLETED] Implemented and benchmarked construction-time flatMap fusion.
- [COMPLETED] Delivered the full suite of concurrency primitives (Ref, Deferred, Semaphore) and combinators (zipPar, race).
- [COMPLETED] Implemented time and resilience features (timeout, retry).
- [COMPLETED] Implemented Future <-> Eru conversions for JVM interop.
- [COMPLETED] Wrote and verified property-based tests for Monad and Resource laws.
- [COMPLETED] Ensured all tests pass on both JVM and Scala Native.

### 0.4.0 — Ecosystem & Community

This phase will focus on growing the Eru ecosystem and responding to the needs of early adopters.

**Deliverables**:

- **Modules**: eru-http (a minimal, non-blocking HTTP client/server), eru-json.
- **Valar Integration**: Complete the refactoring of the Valar validation library to run on Eru, serving as a flagship example.
- **Documentation**: Create a full documentation website with tutorials, deep-dive articles, and clear, honest performance analyses.
- **Benchmarks**: Add a comparative benchmark suite to provide fair performance data against other major effect systems.

### 1.0.0 — Stability and Stewardship

This release will mark the long-term stabilization of the API and a commitment to stewardship of the project.

**Deliverables**:

- API stabilization policy and semantic versioning guarantees.
- Published migration guides for any breaking changes from the 0.x series.
- A long-term maintenance and community contribution plan.
