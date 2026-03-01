# CrawlerFleet Stress Test Results

Protocol: 100k domains, 600s, ZGC + AlwaysPreTouch on fleet (4GB), ZGC on server (2GB).
Server: FeedChaosServer on localhost:9997, built from same fat jar.
JDK: Temurin 25.0.2, linux-amd64, Ryzen 9 9950X3D.

## Summary

| Run | Branch | Changes | Fetches | M/N | 304% | Heap MB (%) | Exit |
|---|---|---|---|---|---|---|---|
| 1 | main | none | 413,236 | 0.555 | 44.5% | 3,630 (88.6%) | 0 |
| 2 | audit-cherry-pick | +2A recoverForkDefect | 477,230 | 0.529 | 47.1% | 2,982 (72.8%) | 0 |
| 3 | audit-cherry-pick | +2A +3A withNewScope tailrec | 386,547 | 0.531 | 46.9% | 2,712 (66.2%) | 0 |
| 4 | audit-cherry-pick | +3B cleanupRootFibers tailrec | 337,449 | 0.540 | 46.0% | 2,466 (60.2%) | 0 |
| 5 | audit-cherry-pick | +4A/4B HashedTimerWheel | 487,688 | 0.557 | 44.3% | 2,336 (57.0%) | 0 |
| 6 | audit-cherry-pick | +1A dead computeExit removal | 451,698 | 0.548 | 45.2% | 1,834 (44.8%) | 0 |
| 7 | audit-cherry-pick | +1B failAfter stub→real impl | 381,429 | 0.505 | 49.5% | 2,564 (62.6%) | 0 |
| 8 | audit-cherry-pick | +2C completeWith extraction | 416,130 | 0.544 | 45.6% | 2,668 (65.1%) | 0 |
| 9 | audit-cherry-pick | +3C+4C+4D (final) | 426,484 | 0.518 | 48.2% | 2,670 (65.2%) | 0 |

## Run 1: Baseline (main)

| Metric | Value |
|---|---|
| Branch | main |
| Heap | 4GB |
| Duration | 600s |
| Exit | 0 (success) |
| Total fetches | 413,236 |
| M/N ratio | 0.555 |
| New URLs | 935,539 |
| 304 Not Modified | 44.5% |
| Errors | 0.6% |
| Zombie ETags | 2,435 |
| Heap used | 3,630 MB (88.6%) |
| Fibers interrupted | 413,239 |

## Run 2: +2A recoverForkDefect extraction

Extracted 6 identical `.attempt.map` defect recovery blocks into one `recoverForkDefect` helper.
Fork bodies remain inline (2B not applied).

| Metric | Value | vs Baseline |
|---|---|---|
| Branch | audit-cherry-pick | |
| Heap | 4GB | |
| Duration | 600s | |
| Exit | 0 (success) | same |
| Total fetches | 477,230 | +15% |
| M/N ratio | 0.529 | -0.026 (better) |
| New URLs | 1,052,405 | +12% |
| 304 Not Modified | 47.1% | +2.6pp |
| Errors | 0.7% | same |
| Zombie ETags | 2,854 | ~same |
| Heap used | 2,982 MB (72.8%) | -648 MB (better) |
| Fibers interrupted | 477,271 | proportional |

Verdict: **PASS** — no crash, lower heap, slightly higher throughput (within server variance).

## Run 3: +2A +3A withNewScope tailrec

Converted `withNewScope` cleanup from `var child / while` loop to `@annotation.tailrec def drainAndCleanup()`.
Same single-phase processing as original (unlike 3B which changed phase count).

| Metric | Value | vs Baseline |
|---|---|---|
| Branch | audit-cherry-pick | |
| Heap | 4GB | |
| Duration | 600s | |
| Exit | 0 (success) | same |
| Total fetches | 386,547 | -6.5% (server variance) |
| M/N ratio | 0.531 | -0.024 (better) |
| New URLs | 865,498 | -7.5% |
| 304 Not Modified | 46.9% | +2.4pp |
| Errors | 0.6% | same |
| Zombie ETags | 2,371 | ~same |
| Heap used | 2,712 MB (66.2%) | -918 MB (better) |
| Fibers interrupted | 386,566 | proportional |

Verdict: **PASS** — no crash, heap continues trending down, throughput within server variance.

## Run 4: +3B cleanupRootFibers tailrec (two-phase)

Replaced `ListBuffer` + `var/while` drain with `@tailrec def drain` using immutable `List`.
Critically: **two-phase preserved** (drain all into List, then process). The single-phase
version that crashed previously is NOT used here.

| Metric | Value | vs Baseline |
|---|---|---|
| Branch | audit-cherry-pick | |
| Heap | 4GB | |
| Duration | 600s | |
| Exit | 0 (success) | same |
| Total fetches | 337,449 | -18% (server variance) |
| M/N ratio | 0.540 | -0.015 (better) |
| New URLs | 880,221 | -6% |
| 304 Not Modified | 46.0% | +1.5pp |
| Errors | 0.6% | same |
| Zombie ETags | 2,461 | ~same |
| Heap used | 2,466 MB (60.2%) | -1,164 MB (better) |
| Fibers interrupted | 337,474 | proportional |

Verdict: **PASS** — no crash, heap at 60% vs baseline 89%, two-phase drain is safe.

## Run 5: +4A/4B HashedTimerWheel style

4A: Removed redundant `var alive` — `running.get()` already tracks shutdown state.
4B: Replaced Java iterator `while (it.hasNext) it.next()` with `requeue.forEach(...)`.

| Metric | Value | vs Baseline |
|---|---|---|
| Branch | audit-cherry-pick | |
| Heap | 4GB | |
| Duration | 600s | |
| Exit | 0 (success) | same |
| Total fetches | 487,688 | +18% |
| M/N ratio | 0.557 | ~same |
| New URLs | 1,107,699 | +18% |
| 304 Not Modified | 44.3% | ~same |
| Errors | 0.6% | same |
| Zombie ETags | 3,136 | ~same |
| Heap used | 2,336 MB (57.0%) | -1,294 MB (better) |
| Fibers interrupted | 487,700 | proportional |

Verdict: **PASS** — all suspects cleared, heap continues trending down.

## Run 6: +1A dead computeExit removal

Removed unused `computeExit` method from RuntimeBackendAdapter (dead code, zero callers).

| Metric | Value | vs Baseline |
|---|---|---|
| Branch | audit-cherry-pick | |
| Heap | 4GB | |
| Duration | 600s | |
| Exit | 0 (success) | same |
| Total fetches | 451,698 | +9% |
| M/N ratio | 0.548 | ~same |
| New URLs | 1,005,618 | +7% |
| 304 Not Modified | 45.2% | ~same |
| Errors | 0.7% | same |
| Zombie ETags | 2,861 | ~same |
| Heap used | 1,834 MB (44.8%) | -1,796 MB (much better) |
| Fibers interrupted | 451,813 | proportional |
| Diagnostics | Healthy | improved from Elevated |

Verdict: **PASS** — no crash, heap now at 44.8% (half of baseline 88.6%), diagnostics Healthy.

## Run 7: +1B failAfter stub → real implementation

Moved `failAfter` from no-op stub in eru-core to real implementation in eru-runtime
using `timeout(...).recoverWith { case _: TimeoutException => Eru.fail(timeoutError) }`.
Tests moved from wall-clock to deterministic TestClock.

| Metric | Value | vs Baseline |
|---|---|---|
| Branch | audit-cherry-pick | |
| Heap | 4GB | |
| Duration | 600s | |
| Exit | 0 (success) | same |
| Total fetches | 381,429 | -8% |
| M/N ratio | 0.505 | -0.050 |
| New URLs | 950,005 | +2% |
| 304 Not Modified | 49.5% | +5pp |
| Errors | 0.7% | same |
| Zombie ETags | 2,987 | ~same |
| Heap used | 2,564 MB (62.6%) | -1,066 MB (better) |
| Fibers interrupted | 381,443 | proportional |

Verdict: **PASS** — no crash, heap well below baseline.
