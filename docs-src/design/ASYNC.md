### `async.md` (Updated)

# Async Runtime Design

**Status**: Implemented and complete as of version 0.3.0.

The asynchronous runtime lives in the dedicated module `eru-runtime` (supporting both JVM and Native) and provides a
complete, fiber-based execution model. The pure, synchronous kernel remains in `eru-core`.

This document records the foundational choices for Eru’s asynchronous model, which aligns with the Manifesto pillars.

* **Pillar I — Correctness**: A clear separation of typed errors (`E`), defects (`Throwable`), and interrupts (
  `InterruptCause`). The runtime is a zero-casting implementation.
* **Pillar II — Radical Ergonomics**: Fibers and high-level combinators (`zipPar`, `race`, `timeout`) that feel natural
  in Scala 3.
* **Pillar III — Guided Correctness**: Cancellation and resource-safety are built-in, making the safe path the obvious one.
* **Pillar IV — Exceptional Observability**: Structured diagnostics via `Exit` and `InterruptCause`, and an
  `EruObserver` hook.

-----

## Core Semantic Distinctions

The runtime maintains a three-way distinction for the outcome of any computation:

* **Typed failure**: Domain errors of type `E`.
* **Defect**: Unexpected `Throwable`s (e.g., thrown exceptions).
* **Interrupt**: Cooperative cancellation initiated by the runtime or user.

These distinctions are captured in the `Exit[E, A]` data type and enable principled resource safety, cancellation, and
diagnostics.

## Key Features Implemented

* **Fibers**: The `Fiber[E, A]` trait represents a lightweight, interruptible thread of execution. The runtime manages
  the fiber lifecycle, including forking, joining, and interruption.
* **Structured Concurrency**: High-level combinators like `zipPar` (running two fibers in parallel and gathering their
  results) and `race` (running two fibers and taking the result of the first to complete) are provided.
* **Resource Safety**: The runtime's resource management is fully integrated with the fiber model, ensuring that
  `ensure` and `bracket` finalizers are correctly executed even in the presence of concurrency and interruption.
* **Observability**: The `EruObserver` interface emits fiber-level events, including `FiberStarted`, `FiberCompleted`,
  and `FiberInterrupted`, providing a clear view into the behavior of the concurrent system.

