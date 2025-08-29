# FAIRNESS — Comparative Benchmarks for Eru vs Cats Effect vs ZIO

This document defines the scope and rules for fair, reproducible benchmarks.

## Scope (what we compare now)
- Pure composition: map/flatMap chains (all-success; short-circuit on failure).
- Error handling overhead: recover/mapError; first-error short-circuit.
- Resource discipline: ensure/bracket overhead (success and failure), finalizer counts.
- Retry policies: retryN/backoff with base = ZERO (attempt-count bounded; no real sleeps).
- Runner overhead: construction + run/attempt boundary for small/medium programs.

These scenarios have equivalent semantics in all libraries and are portable.

## Deferred (not compared cross-library yet)
- True parallel combinators (zipPar, race), structured interruption, async I/O.
  - We will include Eru-only baselines for regression tracking, but not publish cross-library comparisons until Eru’s async/fiber runtime lands to ensure apples-to-apples.

## Environment & Reproducibility
- Pin toolchain: Scala 3.7.2, SBT, JDK (Temurin 21.x), library versions (CE 3.6.3, ZIO 2.1.20).
- Record hardware: CPU model, core count, RAM, OS/kernel.
- JMH settings (default, unless otherwise noted): `-wi 5 -i 10 -f1 -t1`.
- No one-off tuning unique to a library unless identically applied and documented.
- Run on an otherwise idle machine; disable turbo/thermal variability where possible.

## Scenario Design Rules
- Determinism: avoid wall-clock sleeps; for retry/backoff use `Duration.ZERO` and count attempts.
- Equivalence: identical chain lengths, positions of failure, and finalizer counts across libraries.
- Simplicity: prefer minimal, idiomatic APIs with no hidden caches/pools unless equivalent across libraries.
- Isolation: each benchmark method constructs its program to avoid cross-test state bleed.

## Reporting
- Raw JMH outputs stored under `benchmarks/raw/YYYY-MM-DD-run*.txt`.
- Summary with environment and SHAs under `benchmarks/Baseline-YYYY-MM-DD.md`.
- Interpret conservatively; highlight only robust differences.

## Library Coordinates (pinned)
- Eru: this repository (SHA recorded in summary)
- Cats Effect: `org.typelevel:cats-effect:3.6.3`
- ZIO: `dev.zio:zio:2.1.20`

## Command Aliases
- `sbt bench` — default suite run: `-wi 5 -i 10 -f1 -t1`.
- `sbt benchCore` — focused runs (Map/FlatMap and Runtime benches).
- For smoke testing locally: `project eruBenchJVM; jmh:run -i 1 -wi 1 -f1 -t1 ".*EruMapFlatMapBench.*"`.
