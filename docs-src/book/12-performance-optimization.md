# Chapter 12: Performance

This chapter describes how Eru executes effects and what follows from those choices for code that needs to be fast. It does not list benchmark numbers; measure on your own workloads.

## How the interpreter works

- `Eru[E, A]` is a GADT encoded as a Scala 3 enum. Matching on the enum cases is a plain switch, and the interpreter is a stack-safe state machine.
- Adjacent `map` calls fuse at construction time, so `effect.map(f).map(g)` allocates one continuation instead of two.
- The runtime runs fibers on Java virtual threads. Blocking work runs through `Eru.blocking`, which is exactly `Eru.effect` (same node, no special marking); use `Eru.interruptibleBlocking` when the blocking call must observe interruption, because only that constructor converts `InterruptedException` into fiber interruption.

These are implementation facts. Use them as a mental model, not as a coding rule.

## Guidelines

- Measure before changing anything. The JIT and allocation behavior make intuition unreliable at this scale.
- Prefer `Eru.traverse` over `map` plus `sequence` for collections; it makes one pass.
- Use the iterative builders (`Eru.iterate`, `Eru.iterateN`, `Eru.foldLeft`, `Eru.traverse`) instead of Scala recursion for repetition. These builders are construction-time stack-safe: pure prefixes peel with a tail-recursive loop and fuse to a single `Succeed` (no per-step allocation), while effectful steps build deferred chains the interpreter walks without growing the stack.
- Still, never build chains with your own recursion: `def loop = Eru.succeed(x).flatMap(_ => loop)` overflows the JVM stack at construction, because `flatMap` on a `Succeed` applies its function immediately. Recursive *construction* is the one stack-unsafe pattern left — the library's builders exist to replace it.
- Use `parTraverse` and `foreachParN` (from the prelude) for independent work that should run concurrently.
- Keep error handling typed (`Eru.fail`) rather than throwing, so failures travel the ordinary effect path.
- Place error handling where the error is understood, not at every boundary.

## JVM settings

Eru development uses ZGC:

```bash
-XX:+UseZGC -Xms4g -Xmx4g
```

Eru's default JVM runtime is built on virtual threads, and every program run on that runtime depends on them — so use ZGC as the default for Eru deployments. G1GC also works with virtual threads; pick the GC for the workload, not for the library.

The `-Xms`/`-Xmx` values matter more than the collector choice: size the heap to the workload and give sbt its own generous settings.

## Measuring

For microbenchmarks use JMH. A wall-clock loop around `unsafeRunSync` measures the JIT warmup as much as the operation, so treat such numbers as rough indications only. In production, measure what users experience: end-to-end latency and error rates, via `EruObserver` hooks rather than in-line timers.

## Key takeaways

- The interpreter is a fused, stack-safe state machine. Forked fibers run on virtual threads.
- Optimization choices are ordinary Scala choices: traverse, iterative builders, typed errors.
- Measure your own workload with JMH before trusting any number.

## What's next

Chapter 13 covers integration with blocking code, legacy libraries, and external systems.
