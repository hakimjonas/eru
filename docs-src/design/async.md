# Async Runtime Direction — Fibers, Exit/Cause, and Observability

Status: Core types available; runtime in progress (0.3.0)

Module layout: The asynchronous runtime lives in the dedicated module `eru-runtime` (JVM and Native).
The pure kernel remains in `eru-core`. Use the runtime module for fork/await and upcoming parallel/time combinators.

Available now in core: Exit[E, A], FiberId, InterruptCause, and the Fiber[E, A] interface.
The minimal scheduler/runtime and parallel combinators will land during 0.3.0.

This document records foundational choices for Eru’s asynchronous evolution while preserving the small, pure synchronous core. It aligns with the Manifesto pillars and informs API stability to minimize churn.

- Pillar I — Correctness: Purity-first; clear separation of typed errors vs defects vs interrupts.
- Pillar II — Radical Ergonomics: Fibers and high-level combinators (zipPar, race, timeout) that feel natural in Scala 3.
- Pillar III — Pit of Success: Cancellation and resource-safety by design; the safe path is the obvious one.
- Pillar IV — Exceptional Observability: Structured diagnostics via Exit/Cause and an observer hook.

---

## Core semantic distinctions

We maintain a three-way distinction at the edge:
- Typed failure: domain errors of type E (values).
- Defect: unexpected Throwable (e.g., thrown exceptions) — non-domain failures.
- Interrupt: cooperative cancellation initiated by the runtime.

These distinctions enable principled resource safety, cancellation, and diagnostics.

### Exit and Cause (runtime-facing)

Sketch (names may evolve):

```scala
package net.ghoula.eru

enum Exit[+E, +A]:
  case Success(value: A)
  case Failure(error: E)           // typed error (domain)
  case Die(throwable: Throwable)   // defect (untyped)
  case Interrupt(fiberId: FiberId, cause: InterruptCause)

opaque type FiberId = Long // example; concrete representation TBD

enum InterruptCause:
  case Cancelled
  case Timeout
```

Exit provides the structured outcome model for fibers and async joins. The pure kernel continues to carry Eru[E, A] as the program description; Exit is the result shape used by the runtime and by safe joins.

---

## Runtime model: Fibers and scheduler

- Fiber: A lightweight, user-space thread of execution with:
  - id: FiberId (opaque)
  - await: Eru[Nothing, Exit[E, A]] — join without throwing
  - interrupt: Eru[Nothing, Unit] — cooperative cancellation

- Scheduler: A minimal event loop dispatching runnable steps; safe blocking modeled via Eru.blocking to avoid stalling the loop.

- Cancellation: Cooperative with masking regions. Critical sections and finalizers run to completion; interrupts are observed via Exit.Interrupt and observer events.

- Native/JVM parity: Keep the API portable. JVM gets Future interop; Native documents limitations and prefers the fiber primitives directly.

---

## API surface (directional)

Keep the pure Eru ADT minimal and synchronous. Provide async in a runtime module layered on top:

- Option A: Extend Eru with a minimal Async/Register node for callback registration; interpreter trampolines continuations and supports cancellation.
- Option B (preferred initially): Keep Eru’s ADT purely synchronous; supply async via a runtime interpreter and combinators defined in a separate module. This preserves kernel simplicity and allows independent iteration.

High-level combinators (0.3.0):
- zipPar, race
- timeout, sleep, schedule-based retry policies (exponential backoff with jitter)
- blocking region: `Eru.blocking[A](thunk)`

---

## Error policy and NonFatal

- In the synchronous kernel, Eru.effect captures scala.util.control.NonFatal into Eru[Throwable, A]. Fatal errors (e.g., VirtualMachineError) escape.
- At the edge, the 0.1.0 interpreter will:
  - Rethrow raw Throwable failures.
  - Wrap non-Throwable typed failures in EruException.
- In async, defects become Exit.Die(t), not typed failures. This consistency ensures clarity across sync and async.

---

## Observability: EruObserver footprint

- Event shapes: FiberStarted, FiberCompleted(exit), FiberInterrupted(cause), Step/Suspend/Resume.
- Hook: A standardized observer interface pluggable at runtime with low overhead; can forward to logging/metrics/tracing.
- Diagnostics: Capture structured Exit/Cause, include breadcrumbs of combinator frames where practical.

---

## Laws and testing

- Extend property-based tests to async:
  - flatMap associativity up to observational equivalence
  - bracket/ensure laws (release always runs; idempotence of finalizers)
  - interruption laws (masking semantics)
  - fairness: no starvation under cooperative scheduling assumptions

---

## Milestone mapping

- 0.2.0 — Resource & Observability foundations
  - Introduce Resource algebra; prepare opaque identities (FiberId, ScopeId)
  - Define Exit/Cause and EruObserver interfaces
  - .debug combinator backed by observer

- 0.3.0 — Concurrency & Interop beta
  - Minimal scheduler and Fiber
  - zipPar, race, timeout, retry
  - Future interop on JVM

References:
- Quickstart (sync core): https://github.com/hakimjonas/eru/blob/main/quickstart.md
- Roadmap: https://github.com/hakimjonas/eru/blob/main/ROADMAP.md
- Manifesto: https://github.com/hakimjonas/eru/blob/main/MANIFESTO.md


---

## Milestone A (0.3.0) — Minimal synchronous fork stub

A minimal runtime surface is now available to establish the async-facing API without introducing true concurrency yet:

- EruRuntime.fork evaluates the computation synchronously and returns a completed Fiber capturing Exit (Success | Failure | Die).
- EruRuntime.forkWithObserver emits FiberStarted and FiberCompleted events.
- yieldNow, uninterruptible, and mask exist as placeholders to be refined with the upcoming scheduler.
- interrupt on the returned Fiber records an InterruptCause; await yields Exit.Interrupt if the fiber was interrupted after completion.

These stubs unblock tests, documentation, and integration work while we implement the cooperative scheduler and masking semantics in subsequent milestones.
