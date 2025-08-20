### `valar.md` (Updated)

# Valar Integration Plan

**Status**: Deferred — Refactor to be undertaken post Eru 0.3.0.

## Purpose

To make Valar the first major integration target for Eru by refactoring its validation execution model to be powered by
the `Eru` effect system. This will provide immediate real-world feedback to Eru and unlock a more principled and
ergonomic foundation for Valar.

**Update (2025-08-20)**:
With the completion of the Eru 0.3.0 milestone (including the zero-casting runtime and full concurrency features), the
path is now clear to begin this integration. The work is scheduled to begin in the 0.4.0 development window.

-----

## Objectives

* Establish a clean, typed, effectful core for Valar validations based on `Eru[E, A]`.
* Preserve and improve Valar’s ergonomics via Scala 3 features.
* Define a typed error model that integrates naturally with Eru’s error channel.
* Provide a clear and testable migration path.

-----

## Phased Strategy

### Phase 0 — Alignment and Scoping

* [ ] Catalog Valar’s current public APIs involved in validation definition and execution.
* [ ] Identify key semantics: fail-fast vs. error accumulation, short-circuiting, and composition patterns.
* [ ] Define an initial `ValidationError` ADT for typed errors.

### Phase 1 — Core Refactoring

* [ ] Abstract Valar's core evaluation logic into a trait that can be implemented with an Eru backend.
* [ ] Replace direct, eager evaluation with `Eru.effect` or pure `Eru.succeed` where appropriate.
* [ ] Use `map`/`flatMap`/`recover` to express control flow instead of imperative branching.

-----

## References

* **Valar Repository**: [https://github.com/hakimjonas/valar](https://github.com/hakimjonas/valar)
* **Availability**: Valar is published on Maven Central. See the project’s README for coordinates.