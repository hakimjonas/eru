# Valar Integration Plan — Refactoring Valar on Eru

Status: Deferred — Refactor post Eru 0.3.0 (earliest 2026-05)

Purpose: Make Valar the first integration target for Eru by refactoring its validation execution model to be powered by Eru. This delivers immediate real‑world feedback to Eru and unlocks a principled, ergonomic foundation for Valar.

Update — Deferral (2025-08-15)

Given Valar 0.5.x maturity and ongoing changes, the deep refactor onto Eru is deferred until after Eru 0.3.0 (concurrency and interop). Until then, we will:
- Maintain alignment docs and mapping only.
- Avoid API or runtime coupling until Eru Resource (0.2.0) and concurrency (0.3.0) are available.

---

## Objectives

- Establish a clean, typed, effectful core for Valar validations based on `Eru[E, A]`.
- Preserve and improve Valar’s ergonomics via Scala 3 features (enum, opaque type, extension, given/using).
- Define a typed error model that integrates naturally with Eru’s error channel.
- Provide a migration path that is incremental, low‑risk, and testable in CI.

---

## Phased Strategy

### Phase 0 — Alignment and Scoping
- Catalog Valar’s current public APIs involved in validation definition and execution.
- Identify semantics that matter to users: fail‑fast vs. error accumulation, short‑circuiting rules, composition patterns, and reporting.
- Define an initial `ValidationError` ADT for typed errors. Keep binary compatibility concerns in mind if needed.

### Phase 1 — Eru‑backed synchronous kernel (Post 0.3.0)
- Represent validation execution as `Eru[ValidationError, A]` for individual rules and composed validators.
- Express composition via Eru’s core combinators:
  - `map`/`flatMap` for dependent sequencing.
  - `zip` (available) for sequential combination of independent computations (evaluates left then right, fail-fast).
  - `mapError` and `recover/ recoverWith` for error shaping and recovery.
- Provide adapters and shims so existing Valar APIs call into the Eru layer without breaking user code.
- Ship PoC branch in Valar and run sample suites in CI using Eru’s synchronous interpreter.

### Phase 2 — Resource safety and observability
- Adopt `Eru.Resource` (once available in Eru 0.2.0) for validators that open resources (e.g., DB lookups, file reads).
- Wire basic observability using `EruObserver` and a `.debug` style surface for troubleshooting validation flows.

### Phase 3 — Concurrency and performance
- Introduce parallel composition for independent validations using Eru’s fiber scheduler (post 0.3.0).
- Add timeouts and retries for I/O‑bound validations via Eru’s resilience combinators.

---

## Conceptual Mapping

- Rule/Predicate evaluation: `Eru[ValidationError, Unit]` or `Eru[ValidationError, A]` if yielding transformed values.
- Validator composition: expressed through `map`, `flatMap`, and `zip`/sequential combinators.
- Error channel: `ValidationError` (an enum ADT) carried in Eru’s `E` type parameter.
- Reporting: collect results by interpreting `Eru` into `Result[ValidationError, A]` when needed for aggregation.

Notes on error accumulation:
- The Eru kernel is fail‑fast by default. For accumulating multiple errors, model validators as `Eru[Nothing, Accumulating[A]]` or interpret rule results into a data structure like `Result[NonEmptyList[ValidationError], A]` and combine at the Valar layer. Keep the Eru core pure and minimal; accumulation lives above as a dedicated combinator set.

---

## Minimal API Sketch (Valar layer on top of Eru)

```scala
import net.ghoula.eru.Eru

enum ValidationError:
  case Required(field: String)
  case MinLength(field: String, min: Int)
  case Custom(message: String)

object V:
  def pass[A](a: A): Eru[Nothing, A] = Eru.succeed(a)
  def fail(err: ValidationError): Eru[ValidationError, Nothing] = Eru.fail(err)
  def ensure[A](a: A)(predicate: A => Boolean, onFail: => ValidationError): Eru[ValidationError, A] =
    if predicate(a) then Eru.succeed(a) else Eru.fail(onFail)

  extension [A](self: Eru[ValidationError, A])
    def andThen[B](f: A => Eru[ValidationError, B]): Eru[ValidationError, B] = self.flatMap(f)
    def label(ctx: String): Eru[ValidationError, A] = self.mapError:
      case e => ValidationError.Custom(s"$ctx: $e")
```

This is illustrative only; the actual Valar API should maintain its existing user‑facing shapes, delegating to Eru under the hood.

---

## Acceptance Criteria (Alpha)

- A PoC branch of Valar executes a representative subset of validations on `Eru` (synchronous kernel).
- A typed `ValidationError` ADT is adopted where applicable and flows through `mapError`/`recover` combinators.
- A migration doc exists with concrete before/after examples and guidance.
- CI jobs run Valar’s example validations via `unsafeRunSync` and assert identical outcomes compared to pre‑Eru behavior.

---

## Migration Guide (Outline)

- Identify existing validators and rules and map them to Eru‑backed implementations.
- Replace direct eager evaluation with `Eru.effect` or pure `Eru.succeed` where appropriate.
- Use `map`/`flatMap`/`recover` to express control flow instead of imperative branching.
- For accumulation, use Valar‑level combinators that interpret results into accumulating structures; keep the Eru core fail‑fast in this phase.

---

## Tracking and Timeline

- Target: Post 0.3.0 — 0.4.0 window (earliest 2026-05)
- Deliverables mirror ROADMAP: this document (updated), PoC branch in Valar post-0.3.0, aligned error model, migration guide, CI example suite.
