# Eru Roadmap

Mission: Build the definitive effect system for the discerning Scala 3 developer, grounded in the pillars of Correctness, Radical Ergonomics, a Pit of Success, and Exceptional Observability.

Last updated: 2025-08-15

---

Current status snapshot

- Implemented
  - Core data type: enum Eru[E, A] with Succeed/Fail/Effect.
  - Core combinators: map, flatMap, mapError, recover/recoverWith, orElse, zip.
  - Constructors and interop: succeed, fail, effect, fromEither, fromTry, fromOption, unit.
  - Safe interpreter helper: attempt (lazy, non-throwing) returning Result[E, A].
  - Synchronous interpreter: unsafeRunSync using a stack-safe TailRec interpreter; rethrows Throwable failures and wraps non-Throwable typed failures in EruException[E].
  - Foundational data type: enum Result[E, A] with map/flatMap/fold and tests.
  - Resource safety foundations: ensure/bracket with FILO finalizer ordering; finalizer error suppression; tests.
  - Observability foundations: EruObserver primitives (ScopeId, Outcome, EruEvent), .debug, observer-aware interpreter emitting ProgramStart/ProgramEnd after finalizers.
  - Documentation: quickstart (attempt-first guidance), resources and observer guides; async direction notes; edge semantics clarified (NonFatal policy; unsafeRunSync behavior).
  - Tests: comprehensive unit tests for Eru, Resource, Observer; all green on JVM; Native parity planned.
  - Property-based tests for Resource laws added (0.2.0 scope).
- Not yet implemented
  - Concurrency runtime (fibers/scheduler), blocking region, and concurrency primitives (Ref, Deferred, Semaphore).
  - Diagnostics enhancements and structured reporting for suppressed finalizer errors.
  - Integration: Valar (first integration target; major refactor deferred post 0.3.0); other modules (e.g., Future interop, fs2/http4s).

---

Milestones and deliverables

0.1.0 — Core synchronous kernel (Target: 2025-09) — Completed (internal): 2025-08-15

- Status-driven goals
  - Finalize the minimal API surface for synchronous programs. [done]
  - Ensure exhaustive Scaladoc for all public APIs (Eru, Result, EruException). [done]
  - Strengthen unit tests to cover edge cases (nested recover, error mapping, left-biasing). [done]
- Deliverables
  - Eru: attempt (to capture errors in Result), fromOption, unit helpers; zip for sequential composition.
  - Result: convenience constructors and syntax parity with Eru where appropriate.
  - Documentation: README status update; quickstart examples using only synchronous APIs; edge semantics clarified (NonFatal policy; unsafeRunSync Throwable rethrow vs EruException). Links: docs-src/quickstart.md, docs-src/design/async.md.
  - Quality gates: sbt check and eruCoreJVM/test pass on CI.

0.2.0 — Resource safety and observability foundations (Target: 2025-12) — Foundations implemented (internal): 2025-08-15

- Pillars: Correctness, Pit of Success, Exceptional Observability
- Deliverables
  - Eru.Resource with acquisition/use/release semantics; ensure and bracket patterns; opaque types for Scope/FiberId prepared.
  - EruObserver interface and minimal event model; .debug combinator backed by observer.
  - Typed error model guidance and structured diagnostics for interpreter failures.
  - Property-based tests for Resource laws; documentation and guides in docs-src.

0.3.0 — Concurrency and interop beta (Target: 2026-03)

- Pillars: Pit of Success, Radical Ergonomics
- Deliverables
  - Minimal fiber scheduler and safe blocking region (Eru.blocking).
  - Concurrency primitives: Ref, Deferred, Semaphore; parallel combinators (zipPar, race).
  - Time and resilience: timeout, retry policies.
  - Interop: Future <-> Eru conversions.

0.4.0 — Ecosystem integrations and DX (Target: 2026-05)

- Deliverables
  - Modules: eru-fs2, eru-http4s and example apps.
  - Valar: refactor validation runtime on Eru (post 0.3.0), migration docs, CI verification.
  - Documentation site with guides, API docs, and deep-dive articles; Valar-based onboarding path.
  - Benchmarks and performance regression checks.

1.0.0 — Stability and stewardship (Target: 2026-08)

- Deliverables
  - API stabilization policy and semantic versioning guarantees.
  - Lawfulness suite published and automated; migration guides.
  - Final docs and examples; long-term maintenance plan.

---

Development workflow (unbreakable)

- Understand the task by referencing the Manifesto pillars.
- Implement with modern Scala 3 features (enum ADTs, opaque types, extension methods, given/using) to uphold domain integrity and ergonomics.
- Tests: every public API change must be covered with comprehensive unit and, where applicable, property-based tests.
- Local checks before pushing
  - sbt check
  - sbt eruCoreJVM/test and sbt eruCoreNative/test
  - sbt prepare

Notes on design directives

- Prefer enum for ADTs and opaque types for identities like FiberId and Scope.
- Favor extension methods for fluent APIs on Eru[A].
- Embrace intersection/union types to precisely encode requirements and outcomes.