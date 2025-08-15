# Eru Development Playbook — Point‑by‑Point Plan

Status: Living document (kept under version control)

This playbook turns our Manifesto and guidelines into a concrete, step‑by‑step plan. It is the single source of truth for what we will do next, how we will do it, and how we will know we are done.

Reference pillars:
- Pillar I: Correctness as the Unseen Foundation
- Pillar II: Radical Ergonomics
- Pillar III: A Pit of Success
- Pillar IV: Exceptional Observability

Related docs:
- MANIFESTO.md
- ROADMAP.md

---

## 0.1.0 — Core Synchronous Kernel (Target: 2025‑09)

Objective: Finalize the minimal synchronous API, align interpreter behavior with docs/tests, and provide an ergonomic, safe “pit of success” for everyday usage.

### Scope and Deliverables
1) Interpreter behavior alignment (Correctness)
- Re-throw raw Throwables on unsafeRunSync; wrap only typed, non‑Throwable errors in EruException. 
- Clarify unsafeRunSync and EruException Scaladoc to describe exact behavior and mixed error channels (E | Throwable).

2) Safe interpretation & ergonomic helpers (Pit of Success, Ergonomics)
- Eru#attempt: interpret to Result[E, A] without throwing, preserving laziness.
- Eru#fromOption(opt, onNone), Eru#unit.
- Consider Eru#toResult as a pure wrapper (lazy variant preferred: Eru[Nothing, Result[E, A]]).

3) Tests (Correctness)
- Tests for unsafeRunSync behavior: Throwable pass‑through vs EruException for typed errors.
- Tests for attempt: laziness, single evaluation, correct Success/Failure mapping.
- Tests for fromOption/unit; edge cases.

4) Documentation (Ergonomics)
- Quickstart: build small programs using map/flatMap/recover, interpret via attempt; only use unsafeRunSync at the very edge.
- API pages for Eru and Result with examples, including error unions.

5) Quality gates
- sbt check (mdoc, scalafix, scalafmt).
- sbt eruCoreJVM/test and sbt eruCoreNative/test.
- sbt prepare.

### Definition of Done (0.1.0)
- [ ] unsafeRunSync re-throws Throwable, wraps typed non‑Throwable in EruException.
- [ ] Updated Scaladoc for unsafeRunSync and EruException.
- [ ] attempt, fromOption, unit are implemented with comprehensive tests.
- [ ] Quickstart and API docs added in docs-src and pass mdoc.
- [ ] All gates (check, tests, prepare) pass locally.

---

## 0.2.0 — Resource Safety and Observability Foundations (Target: 2025‑12)

Objective: Introduce principled resource safety and an observability footprint that enables diagnostics without compromising ergonomics or purity.

### Scope and Deliverables
1) Resource safety (Correctness, Pit of Success)
- Eru.Resource with acquisition/use/release semantics (acquire, use, release).
- ensure/bracket patterns and an explicit Scope (prepare opaque types for identity like ScopeId).
- Laws and property tests for Resource behavior.

2) Observability footprint (Observability)
- EruObserver interface and minimal event model (start/stop, success/failure, interruption, fiber events when applicable later).
- .debug combinator backed by observer.
- Structured diagnostics for interpreter failures.

3) Documentation (Ergonomics, Observability)
- Resource usage guide with examples.
- Observer/diagnostics guide; best practices.

4) Quality gates
- sbt check; JVM/Native tests; prepare.

### Definition of Done (0.2.0)
- [ ] Resource algebra and interpreter semantics with tests and laws.
- [ ] EruObserver + .debug combinator exist with event shapes documented.
- [ ] Diagnostics documented; examples compile in mdoc.
- [ ] All gates pass locally and in CI.

---

## 0.3.0 — Concurrency and Interop Beta (Target: 2026‑03)

Objective: Introduce a minimal concurrency runtime and high‑level combinators that feel natural and safe.

### Scope and Deliverables
1) Runtime (Pit of Success)
- Minimal fiber scheduler and safe blocking region (Eru.blocking).

2) Combinators (Ergonomics)
- zipPar, race; timeouts; retries/policies.

3) Interop
- Future <-> Eru conversions (JVM focus; document Native status).

4) Documentation and examples
- Concurrency quickstart; patterns and anti‑patterns.

5) Quality gates
- sbt check; JVM/Native tests; prepare.

### Definition of Done (0.3.0)
- [ ] Scheduler + blocking region semantics validated by tests.
- [ ] Parallel/racing combinators implemented and tested.
- [ ] Future interop with tests and docs.
- [ ] Docs/examples compile in mdoc; gates pass.

---

## Cross‑Cutting Concerns and Practices

1) Scala 3 principles
- enum for ADTs; opaque types for identity/domain integrity.
- extension methods for fluent APIs.
- given/using for contextual abstractions where typeclasses make sense.
- Use intersection/union types to model requirements and outcomes with precision.

2) Documentation discipline
- Exhaustive Scaladoc on every public API member; no inline code comments — prefer clarity via structure and naming.
- All user docs live in docs-src and are validated by mdoc.

3) Testing discipline
- Unit tests for every public function or feature; aim for full logical coverage.
- Property‑based tests for algebraic laws (Result/Eru) starting 0.2.0.
- Keep tests expressive and explicit; use vars in tests where they aid in asserting laziness and single‑execution semantics.

4) Quality gates (Unbreakable workflow)
- sbt check
- sbt eruCoreJVM/test
- sbt eruCoreNative/test
- sbt prepare

5) Performance posture
- Favor clarity first; micro‑optimize with evidence.
- Consider dedicated Map node for Eru to reduce allocation/chain depth once perf warrants it; back with microbenchmarks before/after.

---

## Immediate Backlog (actionable next)

These are the top items to start now for 0.1.0.

- [ ] Interpreter: adjust unsafeRunSync to re‑throw Throwable and wrap non‑Throwable in EruException; update Scaladoc.
- [ ] Add Eru#attempt, Eru#fromOption, Eru#unit (+ tests).
- [ ] Expand tests for interpreter behavior; add mixed error channels (E | Throwable) scenarios.
- [ ] Write docs-src/quickstart.md and docs-src/api pages for Eru/Result; show attempt‑first usage.
- [ ] Run gates locally: check, JVM/Native tests, prepare.

---

## Acceptance Criteria (Global)

A feature is “done” when:
- It is implemented with Scala 3 idioms as per the guidelines.
- It has comprehensive unit tests; where applicable, property‑based tests.
- Public APIs have complete Scaladoc with examples where helpful.
- Documentation exists in docs-src and passes mdoc.
- All quality gates pass locally (and in CI once defined).

---

## Notes on Alignment with Manifesto

- Correctness: Pure core with total interpreter semantics and typed error channels.
- Ergonomics: Fluent APIs via extension and minimal ceremony; safe interpretation helpers.
- Pit of Success: The easiest way is the safe way (attempt over unsafeRunSync inside business logic).
- Observability: Event shapes and diagnostics planned early to avoid churn.
