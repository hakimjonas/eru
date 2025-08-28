# Eru 1.0 Release Plan (Living Document)

Mission: Deliver a pristine, principled 1.0 of Eru that exemplifies the Four Pillars — Correctness, Radical Ergonomics, Guided Correctness, and Exceptional Observability — with a joyful developer experience and an uncompromising public API.

Last updated: 2025-08-28 15:25 (local)
Target version: 1.0.0

---

## Scope and Principles

- Public API must be stable, elegant, and free of any `internal` leakage.
- Zero casting and lawfulness by construction; property-based validation for algebraic guarantees.
- Single canonical user import: `import net.ghoula.eru.prelude.*`.
- Cross-platform parity (JVM, Scala Native where applicable).
- Observability first-class via EruObserver.

Reference: docs-src/WORKING_PLAN.md (execution detail) and MANIFESTO.md (vision).

---

## Acceptance Criteria (Go/No-Go)

- [ ] AC1: Canonical prelude works across modules; no `internal` in public surface.
- [ ] AC2: `sbt check` green (mdoc, scalafix, scalafmt).
- [ ] AC3: Core and Runtime tests green on JVM and Native (where configured).
- [ ] AC4: Integration tests pass using only `net.ghoula.eru.prelude.*`.
- [ ] AC5: Full Scaladoc coverage for public APIs; guides compile via mdoc.
- [ ] AC6: Lawfulness verified via ScalaCheck where applicable.
- [ ] AC7: Observability documented and validated from userland.
- [ ] AC8: Performance baseline recorded; no significant regressions.
- [ ] AC9: CI and signed publishing pipeline green; 1.0.0 tagged and published.

---

## Phase Checklist

Phase 0 — Boundary Hardening and Prelude Correctness
- [ ] P0.1 Replace any public `export ..internal..` with public facades (e.g., api.PreludeApi, api.RuntimePreludeApi).
- [ ] P0.2 Single authoritative `object prelude` export (prefer shared) with:
  - [ ] CorePrelude.*
  - [ ] RuntimeExtensions.* (runner conveniences re-exported via public facade)
  - [ ] Public runtime type aliases: Ref, Deferred, Semaphore, Fiber
- [ ] P0.3 Remove duplicate/competing prelude files across source sets (shared/jvm/native).
- [ ] P0.4 Integration guard: attempts to import `net.ghoula.eru.internal.*` do not typecheck (tests/docs).
- [ ] P0.5 Runner conveniences (runExit, runWith) only via public facade; no internal leakage.
- [ ] P0.6 Build sanity: sbt compile green after each structural step.

Phase 1 — Documentation and Scaladoc Completeness
- [ ] P1.1 Scaladoc on every public symbol (purpose, params, returns, examples).
- [ ] P1.2 mdoc guides: Quickstart, Concurrency & Coordination, Resource Discipline, Reliability, Observability.
- [ ] P1.3 `sbt check` green for docs.

Phase 2 — Lawfulness and Property Testing
- [ ] P2.1 Result: functor and monad laws.
- [ ] P2.2 Eru: map/flatMap laws at observable boundary.
- [ ] P2.3 Resource: bracket finalizers exactly-once across paths.
- [ ] P2.4 Retry policies: attempt bounds and deterministic backoff.
- [ ] P2.5 Parity on JVM and Native.

Phase 3 — Observability Polish
- [ ] P3.1 Document event taxonomy and minimal guarantees.
- [ ] P3.2 Integration tests to assert expected event sequences.
- [ ] P3.3 Observer helpers (`noop`, `console`) surfaced and documented.

Phase 4 — Performance Guardrails
- [ ] P4.1 Curate JMH scenarios; establish baseline.
- [ ] P4.2 Record baseline results in repo with environment info.
- [ ] P4.3 Define variance thresholds.
- [ ] P4.4 Optional: publish results as CI artifacts.

Phase 5 — CI and Release Hygiene (Final)
- [ ] P5.1 CI matrix: `sbt check`, core/runtime Jvm+Native tests, integration tests, `sbt prepare`.
- [ ] P5.2 Signed publishing to Sonatype (Central); staging verified.
- [ ] P5.3 Version freeze 1.0.0; release tag and publish.
- [ ] P5.4 Publish docs site; README updated with canonical import and links.

---

## Current Status and Execution Log

- 2025-08-28 15:25 — Initiation
  - Created RELEASE_PLAN.md with comprehensive checklist and acceptance criteria.
  - Aligned plan with docs-src/WORKING_PLAN.md.
  - Marked Phase 0 as the immediate focus.
  - Logged known structural issues (see below) to address in early steps.

- 2025-08-28 15:25 — Phase 0 Start
  - Status: In Progress
  - Actions queued:
    - Audit public exports for any `..internal..` leakage (P0.1).
    - Consolidate `prelude` definition to a single authoritative location; remove duplicates (P0.2, P0.3).
    - Ensure runner conveniences are only re-exported via a public facade (P0.5).

- 2025-08-28 15:26 — Execution Kickoff
  - Began executing Phase 0 tasks. NA-1 marked In Progress: preparing to remove unmanaged source root injections in build.sbt to eliminate duplicate compilation (no code change applied in this step).
  - Added this update and will continue to log after each incremental, green build step.

---

## Known Issues / Blockers (to be triaged and resolved in Phase 0)

1) Duplicate source roots and files in eru-runtime
- unmanagedSourceDirectories in build.sbt for eruRuntime (JVM/Native) includes `src/main/scala`, mixing with `shared/src/main/scala` and causing duplicate symbol definitions.
- Duplicated files observed: Ref.scala, Deferred.scala, Semaphore.scala, and competing Prelude/RuntimeExtensions across `src/main`, `shared`, and `jvm`.

2) Competing prelude and RuntimeExtensions placement
- Multiple Prelude.scala files across source sets; inconsistent exports leading to failures like "value RuntimeExtensions is not a member of net.ghoula.eru".

3) Internal leakage risk
- Public objects must not export from `net.ghoula.eru.internal.*`. Must flow through public facades consistently.

4) Tests and testkit scope
- A `testkit` under main imports `munit` (test-scope dependency), leading to compilation errors when included in main. Should be placed under test scope or in a separate testkit module with appropriate dependencies.

5) Sealed Eru extension errors and missing type params in runtime fast-path
- Symptoms observed during the misconfigured build; expected to resolve once duplicate sources and mismatched views are eliminated.

---

## Next Actions (Short-Term)

- [*] NA-1: Remove unmanaged source root injections from eruRuntime in build.sbt to prevent duplicate compilation (P0.6). Owner: maintainers. ETA: ASAP. Status: In Progress.
- [ ] NA-2: Keep only one `prelude` (prefer shared) and one `RuntimeExtensions` in a consistent location; delete duplicates (P0.2, P0.3).
- [ ] NA-3: Ensure CorePrelude exports via `api.PreludeApi` and Runtime exports via `api.RuntimePreludeApi` (P0.1, P0.5).
- [ ] NA-4: Move `testkit` code that imports `munit` to test scope or a dedicated testkit module (P0.6 hygiene).
- [ ] NA-5: Rebuild incrementally and update this plan after each green step (ideal workflow: build and tests pass after every step).

---

## Communication and Traceability

- Update this document with timestamped entries after each step.
- Reference item codes (e.g., [P0.1], [NA-1]) in commit messages.
- Keep WORKING_PLAN.md in sync for deeper technical detail; RELEASE_PLAN.md remains the authoritative release-facing plan.

---

## Sign-off

This plan is living; we will keep it current and accurate as we progress. CI setup is reserved as the final step to avoid churn mid-flight.
