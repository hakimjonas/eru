# Performance — Eru 1.0 Snapshot (2025‑08‑29)

This document summarizes what we measured, how we measured it, and what the numbers say about Eru’s performance today.
It is intentionally conservative. The goal is not to boast, but to verify we are not being foolish and to document
honest, reproducible results.

All data below comes from running the code in this repository. Raw outputs and environment metadata are checked in.

- Non‑GC full baseline: benchmarks/raw/2025-08-29-143751-bench.txt
- Environment: benchmarks/raw/env-2025-08-29-143751.txt
- Parity smoke (Eru vs Cats Effect vs ZIO): benchmarks/raw/2025-08-29-121819-ParityBenches.txt
- GC‑profiled baseline: pending (we aborted the first attempt early; see “What’s next”)

## Fairness and scope
We compare only scenarios with equivalent semantics across libraries (per benchmarks/FAIRNESS.md):
- Composition chains (map/flatMap) — success and short‑circuit failure
- Error handling — recover/fallback on success/failure
- Resource discipline — bracket acquire/use/release, success and typed‑failure
- Retry — bounded attempts with ZERO backoff (no real sleeps)
- Runner overhead — construction + boundary cost for small/medium programs

Deferred for cross‑library comparison: true parallel zip/race, structured interruption, and async I/O. We keep Eru‑only
micros for those but do not compare across libraries yet.

JMH settings for the non‑GC full baseline: -wi 5 -i 10 -f1 -t1. The environment (CPU/OS/JDK/Scala/SBT/SHAs) is recorded
in the env file above.

## Throughput summary tables (ops/ms)
Numbers are taken from the cited raw outputs. For detailed variance and full listings, see the raw files.

- Composition — success path (higher is better)

| Depth | Eru | ZIO | Cats Effect |
|------:|----:|----:|------------:|
| 8     | 32,0k | 4,3k | 59 |
| 32    | 32,6k | 1,5k | 59 |
| 64    | 32,2k | 0,82k | 57 |
| 128   | 32,1k | 0,40k | 51 |

- Composition — short‑circuit at half depth (higher is better)

| Depth | Eru | ZIO | Cats Effect |
|------:|----:|----:|------------:|
| 8     | 12,3k | 2,73k | 37 |
| 32    | 5,1k  | 1,13k | 33 |
| 64    | 2,38k | 0,57k | 33 |
| 128   | 1,53k | 0,32k | 29 |

- Error handling — recover (higher is better)

| Path    | Eru   | ZIO   | Cats Effect |
|---------|------:|------:|------------:|
| success | 26,0k | 9,0k  | 63 |
| failure | 20,7k | 5,6k  | 61 |

- Resource discipline — bracket (higher is better)

| Outcome | Eru   | ZIO   | Cats Effect |
|---------|------:|------:|------------:|
| success | 3,3k  | 3,4k  | 61 |
| failure | 2,9k  | 2,8k  | 38 |

Note: In the bracket success path, ZIO is sometimes faster than Eru in our runs (e.g., 5.1k vs 4.3k ops/ms in the
non‑GC baseline). This does not change the overall picture: Eru and ZIO are broadly comparable on this scenario,
while Cats Effect is far slower in these micros.

- Retry — bounded attempts, ZERO base (attempt‑bounded; higher is better)

| (maxRetries, successIndex) | Eru  | ZIO  | Cats Effect |
|----------------------------|-----:|-----:|------------:|
| (0, 1)                     | 8,4k | 6,9k | 62 |
| (1, 2)                     | 8,1k | 4,0k | 62 |
| (3, 3)                     | 8,2k | 2,9k | 42 |
| (5, 6)                     | 8,0k | 1,6k | 21 |
| (10, 12)                   | 8,0k | 0,75k | 9 |

- Runner overhead — construction + unsafe boundary (higher is better)

| Size   | Eru (unsafe) | ZIO (unsafe) | Cats Effect (unsafe) |
|--------|-------------:|-------------:|---------------------:|
| small  | 102,5k       | 8,9k         | 63 |
| medium | 100,1k       | 0,78k        | 55 |

Fallback path (recover/handleError): Eru ≈ 24.2–24.6k ops/ms; ZIO ≈ 5.1–5.7k; Cats Effect ≈ 43–59.

## Methodology details
- Settings: JMH 1.37; -wi 5 -i 10 -f1 -t1. CPU governor and background load kept typical; see env file for hardware/OS.
- Parity rules: equivalence of semantics only; scenarios have identical chain depths, failure positions, retry bounds,
  and finalizer counts. No hidden tunings.
- Determinism: retry/backoff uses ZERO base to avoid measuring sleeps; results are attempt‑bounded.
- Reporting: we cite only data from raw files in this repository. When a competitor wins a scenario (e.g., ZIO bracket
  success), we call it out explicitly.

## What this does and does not claim
- These are microbenchmarks. They answer “how fast can a particular path be?” not “how will my app behave?”
- The scope is restricted to equivalent semantics. We purposely avoid cross‑library comparisons for true parallelism or
  async I/O until parity exists.
- The results do not imply that any one library is “faster overall.” They show that, within a fair scope, Eru’s baseline
  is excellent in composition, error handling, runner overhead, and retry; and competitive with ZIO on bracket, with ZIO
  sometimes faster in bracket success.

## Environment and reproducibility
- Environment metadata (hardware/OS/JDK/Scala/SBT/commit SHA): benchmarks/raw/env-2025-08-29-143751.txt
- Command examples (from repo root):
  - Full suite (non‑GC + GC): sh tools/run-benches.sh
  - Parity only: sh tools/run-benches.sh --mode=parity
  - With GC profile only: sh tools/run-benches.sh --gc
- All raw outputs are stored under benchmarks/raw with timestamps. See also benchmarks/FAIRNESS.md for scope and rules.

## What’s next (GC and memory profile)
- We will add GC‑profiled tables (bytes/op, GC time) from runs using -prof gc (parity‑only and full) and update this
  document. The primary cross‑library signal will be bytes/op (gc.alloc.rate.norm). We expect:
  - Pure success chains: flat, very low bytes/op; smooth scaling across depths.
  - Mixed/effect boundaries: modest increases; no step‑cliffs.
  - Resources/Retry: roughly linear with K/attempts; no unexpected promotion.
- If GC profiles surface issues, we will address them and update this page with before/after data.

## Bottom line
- Eru delivers excellent throughput on fused composition, very fast error paths, efficient bounded retries, and extremely
  low boundary overhead; it is competitive with ZIO on bracket, while Cats Effect is consistently slower in these
  micro‑scenarios.
- We explicitly acknowledge scenarios where ZIO leads (bracket success). We will continue to publish raw outputs,
  environment metadata, and GC profiles to keep the picture honest and complete.
