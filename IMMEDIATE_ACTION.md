# IMMEDIATE ACTION PLAN — Alpha Hardening and True Concurrency Roadmap

Last updated: 2025-08-29 21:12 (local)

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
- Change: Update README and guides (concurrency, runtime status, CompletedFiber semantics, deterministic race, sequential zipPar).
- Acceptance: No overclaims; clear user expectations.

E. Reduce interpreter duplication (Correctness, Observability, Dev Ergonomics)
- Intent: One core run loop with parameterized observer hooks.
- Change: Introduce Hooks (onProgramStart/onProgramEnd/onStep) and feed NoopHooks vs. ObserverHooks.
- Acceptance: Single loop; zero casts preserved; TailRec safety maintained; observed sequences unchanged.
- Tests: Full core/integration suites; observer event sequences stable.

F. Performance guardrails (Correctness, Confidence)
- Intent: Ensure no regressions after A–E.
- Actions: Re‑run bench smoke; keep scope honest (no cross‑library parallel claims yet).

G. QA and workflow (Discipline)
- sbt check green; full JVM/Native core+runtime tests; integration tests via public prelude only; prepare/bench scripts validated; release plan kept in sync.

---

H. Implementing true concurrency (Scheduler, Fibers, Interruption, Observability)

Intent: Deliver a real, portable, fiber‑based asynchronous runtime with cooperative scheduling and structured concurrency, while preserving the pure, cast‑free core. This will transition from the current sequential “concurrency‑lite” façade to genuine parallelism and asynchronous I/O.

Scope and guiding principles
- Correctness first: resource safety, finalizers FILO/once, lawfulness of bracket/ensure under concurrency.
- Guided correctness: interruption is cooperative and structured; easiest APIs are the safe ones.
- Exceptional observability: fiber lifecycle and spans emitted without altering semantics.
- Zero casts and stack safety preserved; core ADT remains sealed and pure.

H1. Architecture and primitives
- Scheduler model: work‑stealing pool with lightweight fibers; cooperative yielding at effect boundaries.
- Execution representation: use Eru.Internals.view to step effects without exposing internal constructors.
- Fiber state machine: Running/Suspended/Completed/Interrupted; exit stored once; await returns Exit.
- Mailboxes/queues: lock‑free queues per worker where possible; fallback to synchronized structures on Native if needed.
- Time: wheel or priority queue for timers; sleep/timeout integrate with scheduler, not Thread.sleep.

H2. Suspend integration (async boundary)
- Replace handleSuspend with scheduler registration:
  - register installs callback that enqueues resumption onto a worker.
  - Finalizers thread propagated; exactly‑once resume guaranteed.
- Back‑pressure and fairness: bounded queues with cooperative polling; avoid starvation.

H3. Interruption and structured concurrency
- Cancellation tokens: per‑fiber interrupt flags with cause; mask regions for finalizers/critical sections.
- Parent/child linkage: parent termination propagates InterruptCause.ParentTerminated to descendants.
- Resource safety: finalizers always run; interruption observes ensure semantics.

H4. Concurrency operators
- fork: create running fiber; emit FiberStarted; return handle.
- zipPar: run both fibers; combine when both succeed; propagate first typed failure/defect; cancel loser on failure.
- race: return first winner; safely interrupt the loser; handle all exit cases.
- sleep/timeout: scheduler timers; timeout interrupts target with Timeout cause.

H5. Observability
- Events: FiberStarted, FiberCompleted(exit), FiberInterrupted(cause), plus existing ProgramStart/End and Step.
- Span hooks: optional time measurements around key operators (zipPar, race, sleep) via TraceSpan.

H6. Portability strategy
- Phase JVM first: implement scheduler using Java concurrency primitives (ForkJoinPool or custom work‑stealing on Virtual/Platform threads based on perf findings).
- Phase Native: start with fixed thread pool and synchronized queues; iterate toward lock‑free when feasible.
- Single public API across platforms; semantic parity tests enforced.

H7. Acceptance criteria
- Correctness:
  - Finalizers run exactly once in FILO under success, typed failure, defect, and interruption.
  - zipPar/race semantics: correct short‑circuit and cancellation; no resource leaks.
  - Suspend correctness: callback resumes exactly once; no deadlocks in standard patterns.
- Observability:
  - Fiber events emitted with correct ordering and stable IDs.
  - ProgramStart/End and Step unchanged except for added fiber events.
- Performance (initial budgets to be refined):
  - zipPar speedup on CPU‑bound tasks vs sequential baseline for independent work.
  - sleep/timeout do not block threads; timers scale to thousands of scheduled tasks.
- Determinism where documented (e.g., race is no longer forced‑left; document non‑determinism and provide raceFirst/raceLatest variants if needed in follow‑ups).

H8. Test plan
- Laws/Properties:
  - Finalizer laws under concurrency and interruption.
  - Parity properties observed vs. non‑observed semantics (events add visibility only).
  - Interruption idempotence and masking guarantees.
- Integration:
  - fork/await/join with many fibers; tree of parent/child interruption.
  - zipPar/race cancellation behavior; timeout correctness; sleep wakeup ordering.
  - Suspend interop: Deferred/Ref/Semaphore built atop scheduler where applicable.
- Stress/Soak:
  - Thousands of fibers; timer storms; mixed successes/failures; randomized schedules.

H9. Incremental delivery (milestones)
- H9.1: Scheduler skeleton + fibers + await; fork runs effect to completion (no timers, no interruption). JVM only.
- H9.2: zipPar/race with proper combination and cancellation; basic interruption (user cancellation). JVM.
- H9.3: Timers: sleep/timeout over scheduler; remove Thread.sleep usage; integrate with interruption. JVM.
- H9.4: Suspend integration: non‑blocking callback resumption; ensure finalizer safety. JVM.
- H9.5: Observability events for fibers; spans optional. JVM.
- H9.6: Native parity: bring features over with synchronized primitives; pass parity suites.
- H9.7: Performance tuning: fairness, work‑stealing tweaks; baseline benches for parallel operators.

H10. Documentation updates
- README/Guides: update runtime status to “true concurrency (JVM), Native parity in progress” during milestones; finalize once parity achieved.
- Scaladoc: Fiber, Exit, InterruptCause expanded with concurrent semantics and examples.
- Observability guide: document fiber events and minimal guarantees.

---

Execution notes
- Keep each step small and green; prefer internal feature flags if needed to land the scheduler incrementally.
- Maintain zero‑cast discipline and tail‑recursive safety in interpreter paths.
- Update RELEASE_PLAN.md with timestamps and checkmarks as items land; reference this file for immediate priorities.
