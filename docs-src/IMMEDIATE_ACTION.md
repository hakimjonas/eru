# IMMEDIATE ACTION PLAN — Alpha Hardening and True Concurrency Roadmap

Last updated: 2025-08-30 02:06 (local)

Objective: Land a concise, high‑leverage plan that hardens the synchronous core for an alpha and defines a clear, staged path to implementing true concurrency. This plan maps to the Four Pillars (Correctness, Ergonomics, Guided Correctness, Observability) and is intended to be executed in small, always‑green increments.

---

A. Unify Ensure semantics (Correctness, Observability)
- Intent: Remove the structural asymmetry of Ensure between observed and non‑observed interpreters.
- Change: Make both paths add the finalizer in the same step order (evaluate source, then cons finalizer, or extract a shared branch used by both).
- Acceptance:
  - Identical results and finalizer ordering across success, typed failure, and defect paths with and without observer.
  - Finalizers run exactly once in FILO order under nested ensure/bracket.
- Tests: Parity suite (observed vs. non‑observed), nested ensure/bracket for success/failure/defect.
- Docs: Ensure/bracket Scaladoc clarifies FILO and nesting.

B. Replace busy‑wait in Suspend (Correctness, Guided Correctness)
- Intent: Eliminate spin‑waiting in handleSuspend.
- Options (choose one):
  - B1: Synchronous‑only kernel: if register does not synchronously invoke callback, fail fast with a clear error (disallow async until scheduler lands).
  - B2: Latch‑based blocking: use CountDownLatch/parking to avoid busy spin (document as temporary for sync kernel).
- Acceptance: No spin loop remains; finalizers still drain correctly around Suspend.
- Tests: Sync register completes; failing/async‑style path behaves per chosen policy; finalizers run.
- Docs: Kernel notes explain 0.2.x limitation and future scheduler integration.

C. Remove runtime duplication (Correctness, Ergonomics)
- Intent: Single source of truth for EruRuntime across platforms.
- Change: Keep shared runtime; remove platform‑specific duplicates; ensure retry defect‑guard and forkWithObserver parity.
- Acceptance: Unified behavior on JVM/Native; Throwables never retried; forkWithObserver works uniformly.
- Tests: Platform parity spec for retry/forkWithObserver/zipPar/race.
- Docs: Retry Scaladoc explicitly states Throwables are never retried.

D. Align README and guides with reality (Guided Correctness, Ergonomics)
- Intent: Brand alpha as “synchronous core + concurrency‑lite.”
- Change: Update README and guides (concurrency, runtime status, CompletedFiber semantics, deterministic race, sequential zipPar). Also add JVM‑first messaging (Virtual Threads now; Native concurrency deferred) and platform matrix.
- Acceptance: No overclaims; clear user expectations.

E. Reduce interpreter duplication (Correctness, Observability, Dev Ergonomics)
- Intent: One core run loop with parameterized observer hooks.
- Change: Introduce Hooks (onProgramStart/onProgramEnd/onStep) and feed NoopHooks vs. ObserverHooks.
- Acceptance: Single loop; zero casts preserved; TailRec safety maintained; observed sequences unchanged.
- Tests: Full core/integration suites; observer event sequences stable.

F. Performance guardrails (Correctness, Confidence)
- Intent: Ensure no regressions after A–E.
- Actions: Re‑run bench smoke; keep scope honest (no cross‑library parallel claims yet). ✓

G. QA and workflow (Discipline)
- sbt check green; full JVM/Native core+runtime tests; integration tests via public prelude only; prepare/bench scripts validated; release plan kept in sync. ✓

---

H. Implementing true concurrency (JVM 21/25 strategy, Scheduler/Fibers/Interruption/Observability)

Intent: Deliver true concurrency on JVM immediately via Java Virtual Threads (JDK 21+), optionally integrating Structured Concurrency (JEP 505) on JDK 25 when preview is enabled — all behind a tiny internal backend abstraction. Keep Scala Native on the sequential “concurrency‑lite” runtime for now, with clear rationale and roadmap. Preserve the pure, cast‑free core.

Scope and guiding principles
- Correctness first: resource safety, finalizers FILO/once, lawfulness of bracket/ensure under concurrency.
- Guided correctness: interruption is cooperative and structured; easiest APIs are the safe ones.
- Exceptional observability: fiber lifecycle and spans emitted without altering semantics.
- Zero casts and stack safety preserved; core ADT remains sealed and pure.

H1. Backend abstraction and capabilities (preview‑free public API)
- Introduce a private[eru] ConcurrencyBackend SPI used by EruRuntime to implement: fork/await/interrupt, zipPar, race, sleep, timeout, and capabilities reporting.
- Default JVM backend: VTOnlyBackend (JDK 21+) using Virtual Threads and ScheduledExecutor timers; no preview dependency.
- Optional JVM backend: STSBackend (JDK 25 with --enable-preview) that uses StructuredTaskScope.open(...) via reflection or an opt‑in module; automatic fallback to VTOnlyBackend if unavailable.
- Capabilities flags: virtualThreads=true on JVM; structuredScopes=true only when STS is active; timersNonBlocking=true on JVM backends.

H2. Architecture and primitives
- Scheduler/execution model (JVM): per‑task VTs; cooperative cancellation via Thread.interrupt observed at effect boundaries; timers via ScheduledExecutor; no busy waits.
- Execution representation: use Eru.Internals.view to step effects without exposing internal constructors.
- Fiber state machine: Running/Suspended/Completed/Interrupted; exit stored once; await returns Exit.

H3. Suspend integration (async boundary)
- Replace handleSuspend’s busy‑wait with non‑blocking resumption on the JVM backend (enqueue callback onto VT executor). Until backend lands, use B1/B2 policy in the sync kernel and document.

H4. Interruption and structured concurrency
- Cooperative cancellation: VT interrupt; map to Exit.Interrupt at boundaries; ensure finalizers (FILO/once) run.
- Parent/child linkage preserved at the Eru layer; when using STS, leverage scope cancellation semantics internally while keeping public semantics identical.

H5. Concurrency operators (identical semantics across backends)
- fork: launch on backend; emit FiberStarted/FiberCompleted/FiberInterrupted events.
- zipPar: run both; on failure/defect, cancel the other and drain finalizers; combine results when both succeed.
- race: return winner; cancel loser; document non‑determinism.
- sleep/timeout: non‑blocking timers; timeout interrupts target with InterruptCause.Timeout.

H6. Portability strategy and platform position
- JVM 21+: true concurrency via VTOnlyBackend by default; no preview flags required.
- JVM 25+: optional integration with StructuredTaskScope when `--enable-preview` is enabled; automatic fallback otherwise.
- Scala Native: keep sequential runtime for now (multithreading exists but no VTs); document rationale (low demand; complexity; roadmap after JVM stabilizes).
- Single public API across platforms; semantic parity tests enforce identical behavior (except documented determinism differences).

H7. Acceptance criteria
- Correctness:
  - Finalizers run exactly once in FILO under success, typed failure, defect, and interruption.
  - zipPar/race semantics: correct short‑circuit and cancellation; no resource leaks.
  - Suspend correctness: callback resumes exactly once; no busy‑waits; no deadlocks in standard patterns.
- Observability:
  - Fiber events emitted with correct ordering and stable IDs.
  - ProgramStart/End and Step unchanged except for added fiber events.
- Performance (initial budgets to be refined):
  - zipPar speedup on CPU‑bound tasks vs sequential baseline for independent work.
  - sleep/timeout do not block threads; timers scale to thousands of scheduled tasks.
- Determinism where documented (race is non‑deterministic on JVM backends).

H8. Test plan
- Capability‑gated tests: enable parallel/concurrency suites when capabilities.virtualThreads is true; add small STS smokes when structuredScopes is true.
- Laws/Properties:
  - Finalizer laws under concurrency and interruption.
  - Observed vs non‑observed parity (events only add visibility).
  - Interruption idempotence and masking guarantees.
- Integration:
  - fork/await/join with many fibers; tree of parent/child interruption.
  - zipPar/race cancellation behavior; timeout correctness; sleep wakeup ordering.
  - Suspend interop: Deferred/Ref/Semaphore built atop backend where applicable.
- Stress/Soak:
  - Thousands of fibers; timer storms; mixed successes/failures; randomized schedules.

H9. Incremental delivery (milestones)
- H9.1: JVM VT baseline — implement ConcurrencyBackend and VTOnlyBackend; wire EruRuntime to delegate (behavior identical to current semantics initially); keep tests green.
- H9.2: fork/await on VT backend; CompletedFiber backed by promise/handle; emit fiber events.
- H9.3: zipPar/race parallelization with safe cancellation and finalizer laws; timers via ScheduledExecutor; timeout interrupts.
- H9.4: Suspend non‑blocking resumption; remove any latches from sync kernel on JVM path.
- H9.5: Optional STSBackend for JDK 25 preview behind reflection/ServiceLoader with automatic fallback.
- H9.6: Native remains sequential; parity guards and documentation.
- H9.7: Performance tuning; baseline benches for parallel operators.

H10. Documentation updates
- README/Guides: add platform matrix — JVM 21+ true concurrency (VTs), optional JDK 25 STS integration with preview, Native sequential for now with rationale. Update “Status” and concurrency guide accordingly.
- Scaladoc: Fiber, Exit, InterruptCause concurrent semantics and examples.
- Observability guide: fiber events and minimal guarantees.

---

Execution notes
- Keep each step small and green; prefer internal feature flags if needed to land changes incrementally.
- Maintain zero‑cast discipline and tail‑recursive safety in interpreter paths.
- Update RELEASE_PLAN.md with timestamps and checkmarks as items land; reference this file for immediate priorities.
