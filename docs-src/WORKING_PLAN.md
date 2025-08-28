# Eru 1.0 Execution Plan and Working Checklist

This is the living implementation plan for delivering a pristine 1.0 of Eru. It is designed to be updated as we make progress. It follows the Four Pillars and the project’s unbreakable workflow.

- Source of truth for progress and scope
- Strictly about public API surface, runtime ergonomics, docs, tests, and release hygiene
- CI setup and hardening are explicitly the final step of this plan

Last updated: TODO(maintainers)

---

## How to use this plan

- Update checkboxes and dates as you complete items. Prefer small, frequent updates.
- Use the Progress column (if any) and keep commit messages referencing the item code (e.g., `[P0.1]`).
- Run the workflow locally before marking a phase complete:
  - `sbt check`
  - `sbt eruCoreJVM/test`
  - `sbt eruCoreNative/test`
  - `sbt eruRuntimeJVM/test`
  - `sbt eruRuntimeNative/test`
  - `sbt eruIntegrationTest/test`
  - `sbt prepare`

---

## Phase 0 — Boundary hardening and prelude correctness

Goal: A single canonical import for users and zero leakage of `internal` symbols through the public surface.

- [x] P0.1 Replace any `export net.ghoula.eru.internal.*` from public objects with public facades (e.g., `api.PreludeApi`, `api.RuntimePreludeApi`).
- [x] P0.2 Ensure a single authoritative `object prelude` at `eru-runtime/shared` that re-exports:
  - [x] Core prelude (`CorePrelude.*`)
  - [x] Runtime extensions and runner conveniences (`RuntimeExtensions.*` which itself exports via public facade)
  - [x] Public runtime types via type aliases: `Ref`, `Deferred`, `Semaphore`, `Fiber`
- [x] P0.3 Remove duplicate/competing prelude files across platforms (JVM/Native) to avoid drift.
- [x] P0.4 Extend integration tests to assert that importing `net.ghoula.eru.internal.*` does not typecheck.
- [ ] P0.5 Confirm all runtime convenience methods (`runExit`, `runWith`) are exported only via public facade.
- [x] P0.6 Success criteria: `sbt check` + runtime and integration tests green; grepping the tree shows no public `export ..internal..` from public entry points.

---

## Phase 1 — Documentation and Scaladoc completeness

Goal: Every public API has authoritative Scaladoc and mdoc-backed guides compile from examples using the canonical import.

- [ ] P1.1 Ensure every public symbol has complete Scaladoc (parameters, return, semantics, examples).
- [ ] P1.2 Author mdoc guides in `docs-src`:
  - [ ] Quickstart (canonical prelude import, basic program, `runExit`, `runWith`)
  - [ ] Concurrency & coordination (fork/join, zipPar, race, Ref, Deferred, Semaphore)
  - [ ] Resource discipline (ensure, bracket, ensureAll)
  - [ ] Reliability (timeout/timeoutTo, retry variants, backoff semantics)
  - [ ] Observability (EruObserver taxonomy and minimal guarantees)
- [ ] P1.3 All examples compile via `sbt check`.

---

## Phase 2 — Lawfulness and property-based tests

Goal: Prove the algebra and core patterns are lawful with ScalaCheck-based tests.

- [ ] P2.1 Result laws: functor and monad laws.
- [ ] P2.2 Eru laws: map/flatMap identity and associativity at observable boundary (via `runExit`).
- [ ] P2.3 Resource laws: bracket finalizer executes exactly once across success/failure/defect paths.
- [ ] P2.4 Retry policy properties: attempt bounds and deterministic backoff schedule.
- [ ] P2.5 JVM and Native parity: run law tests on both where applicable.

---

## Phase 3 — Observability polish

Goal: A clear, stable event taxonomy with tests verifying visibility from userland.

- [ ] P3.1 Document EruObserver event categories and minimal guarantees (start/stop, failure/defect, finalizer run, etc.).
- [ ] P3.2 Add integration tests that assert expected events sequence for simple programs (success and failure paths) using a collector observer.
- [ ] P3.3 Ensure observer helpers (`noop`, `console`) are part of the public surface and showcased in docs.

---

## Phase 4 — Performance guardrails

Goal: Establish a baseline and monitor for regressions in critical scenarios.

- [ ] P4.1 Curate key JMH benchmarks (map/flatMap, heavy finalization, zipPar/race micro-cases, retry/backoff path).
- [ ] P4.2 Record baseline results (commit artifact or store in repo as text with date and environment).
- [ ] P4.3 Define acceptable variance thresholds (document in PERFORMANCE.md or this plan).
- [ ] P4.4 Optional: publish JMH results as CI artifacts.
- [ ] P4.5 Comparative plan (Eru vs Cats Effect vs ZIO):
  - [ ] P4.5.a Audit existing ~350 tests/benches to avoid duplication and identify gaps.
  - [ ] P4.5.b Define fairness rules, shared scenario matrix, and uniform JMH settings.
  - [ ] P4.5.c Create dev/BENCHMARKS_COMPARISON.md with methodology and matrix.
  - [ ] P4.5.d Execute comparative benches only after Phases 0–3 are green; store results in complete_benchmark_results.txt with environment and SHAs.

---

## Phase 5 — CI and release hygiene (final step)

Goal: Locked quality gates and reproducible releases. This phase must be done last to reflect the finished state.

- [ ] P5.1 CI matrix:
  - [ ] `sbt check`
  - [ ] `sbt eruCoreJVM/test` and `sbt eruCoreNative/test`
  - [ ] `sbt eruRuntimeJVM/test` and `sbt eruRuntimeNative/test`
  - [ ] `sbt eruIntegrationTest/test`
  - [ ] `sbt prepare`
- [ ] P5.2 Signed publishing to Sonatype (Central), staging workflow verified (use `publishSigned`).
- [ ] P5.3 Freeze API, set version to `1.0.0`, tag release, publish artifacts.
- [ ] P5.4 Generate and publish documentation site (mdoc), update README with canonical import and docs links.

Note: CI configuration (workflows, secrets, publishing) is intentionally reserved for this final step so we don’t churn CI while upstream tasks evolve.

---

## Acceptance criteria summary

- A single canonical import: `import net.ghoula.eru.prelude.*`
- No `internal` package exposure at the public surface (enforced by integration tests and code review)
- Full Scaladoc coverage and mdoc guides compiling
- Lawfulness validated on JVM and Native (where applicable)
- Observability documented and tested from userland
- Benchmarks baseline recorded; no significant regressions
- CI and release pipeline green; 1.0.0 tagged and published

---

## Change log (append entries as you progress)

- yyyy-mm-dd: [author] [P#.#] Short description of change and links to PRs/commits
