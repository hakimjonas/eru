# Comparative Benchmarks: Eru vs Cats Effect vs ZIO (Design Doc)

Status: Plan only. Do not implement/execute until Phase 4 per RELEASE_PLAN.md and docs-src/WORKING_PLAN.md.

Objective
- Provide a fair, reproducible, and meaningful comparison between Eru, Cats Effect (CE3), and ZIO 2.
- Focus on scenarios users actually care about: pure/effectful/mixed chains, memory pressure, concurrency/coordination, resource discipline, reliability primitives, and observability overhead.

Fairness rules
- Same hardware, OS, JDK, and Scala version (pin in build.sbt).
- Same JVM flags and JMH settings: warmups, iterations, forks, time units.
- Use each library’s idioms to express identical semantics (not identical code). No hidden shortcuts.
- Default schedulers/executors unless explicitly called out; document any deviations.
- For each benchmark, record: commit SHAs, environment (CPU, cores, RAM, OS/JDK), JMH settings.

Environment & tooling
- Module: eru-bench-jvm
- Dependencies:
  - dev.zio: zio_2.13 (2.x current stable)
  - org.typelevel: cats-effect_2.13 (3.x current stable)
- Profilers:
  - -prof gc for GC stats
  - optional -prof stack for selected cases

Scenario matrix
1) Computation Chains
- Pure chains: map/flatMap at depths {10, 100, 1000}
- Effectful chains: CE(IO delay), ZIO(effect), Eru.effect per step
- Mixed chains: ratios {10%, 50%, 90%} effectful steps
- Memory pressure variants: small vs. larger payload allocations

2) Concurrency Primitives
- zipPar for pairs and n-way (e.g., 2, 8, 32) aggregation
- race with deterministic fast/slow winners
- fork/join throughput: spawn N trivial effects and await completion
- Coordination:
  - Ref get/set/update
  - Deferred complete/await
  - Semaphore withPermit/withPermits (or close idioms in CE/ZIO)

3) Resource Management
- ensure/bracket cost across success, typed failure, and defect paths
- nested finalizers (FILO) depth sensitivity (e.g., 4, 16, 64)

4) Reliability
- timeout / timeoutTo style patterns; success, failure, timeout paths
- retryN and exponential backoff (latency and CPU cost)

5) Observability
- Cost of minimal observer/tracing hook vs. baseline (where applicable)

Metrics & reporting
- Throughput (ops/ms) and/or average time (ns/op) depending on case
- p50/p95/p99 as available; error margins
- GC allocations and pauses from -prof gc
- Store raw JMH outputs for all libraries in complete_benchmark_results.txt
- Provide summary tables in README-like report for each category

Process
- Phase gate: Only run once Phases 0–3 are green per release plan
- Audit existing ~350 tests and existing benches to avoid duplicates; fill coverage gaps only
- Keep benches simple, idiomatic, and comparable; review each benchmark’s semantics across libraries
- Tag and freeze versions/SHAs before running; record everything for reproduction

Acceptance criteria
- All comparative scenarios implemented per matrix
- Results recorded with full environment metadata
- Documentation updated (PERFORMANCE.md summary + link to raw results)
- No internal API usage or changes in any library under test — public surface only
