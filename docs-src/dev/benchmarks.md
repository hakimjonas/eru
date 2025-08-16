# Benchmarks — Measuring Eru Core (JVM)

Status: Experimental (developer tool; not part of CI)

Purpose
- Provide directional performance baselines for the synchronous interpreter before 0.3.0 (fibers).
- Guide optimizations (e.g., a potential dedicated Map node) with data.
- Detect regressions when changing interpreter internals.

Scope
- JVM-only using JMH. Scala Native benchmarks are out of scope for now.
- Microbenchmarks target hot paths in the synchronous kernel.

How to run
- From the project root:

```
sbt bench
```

This alias runs the JMH suite in the `eru-bench-jvm` subproject with sensible defaults:
- 5 warmup iterations
- 10 measurement iterations
- 2 forks
- 1 thread, single forked JVM (`-f1 -t1`)

Advanced usage
- Run a specific benchmark class or regex:

```
sbt "project eruBenchJVM" "jmh:run -i 10 -wi 5 -f1 -t1 .*EruMapFlatMapBench.*"
```

Current benchmarks
- EruMapFlatMapBench
  - Measures throughput of map vs flatMap chains at depths 10/100/1000
  - Runs the programs with unsafeRunSync to capture interpreter overhead

Interpreting results
- Prefer relative comparisons across commits/branches rather than absolute numbers.
- Watch allocation rates and GC behavior with external profilers (e.g., JFR, async-profiler) when investigating.

Policy
- Benchmarks are not run in CI.
- Include a short summary of before/after results in PRs that change interpreter internals.

Notes
- Keep benchmarks simple and representative.
- If a significant regression or improvement appears, consider adding or adjusting benchmarks accordingly.
