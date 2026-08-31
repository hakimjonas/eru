# Chapter 8: Backends and execution models

Eru separates pure effect descriptions from the runtime that executes them. The `Eru[E, A]` type has no opinion about *how* effects run: the runtime decides, through a backend SPI. This chapter explains the execution models Eru ships with and how the runtime selects a backend.

## The backend SPI

The runtime module defines a `ConcurrencyBackend` contract behind a `BackendProvider` discovery mechanism. Every concurrency operation the runtime offers (`fork`, `race`, `timeout`, `sleep`, `retry`, `handleSuspend`, structured cleanup) delegates to the selected backend. The public API stays identical no matter which backend is running.

```scala mdoc
import net.ghoula.eru.prelude.*

// A pure program: no execution strategy attached.
val program = Eru.succeed(21).map(_ * 2)
```

Which backend runs `program` is chosen when the program is executed.

## The JVM backend

The JVM backend is built on Java Virtual Threads:

- Forking maps each `fork` to a virtual thread.
- Blocking inside a forked fiber parks that virtual thread; `sleep` and blocking I/O in forked fibers do not occupy carrier threads.
- Timers use a hashed timer wheel, giving `sleep`, `timeout`, and `Eru.at` non-blocking scheduling; wheel-based sleeps fire at-or-after their requested duration (one-tick pad).
- Forked fibers are contained by structured scopes: a fiber forked inside a scope is interrupted with the real `ParentTerminated` cause and awaited when the scope unwinds. At the root, fibers are tracked in the runtime's root collection and released only by an explicit `runtime.cleanup()` or `shutdownRootFibers` — there is no automatic handling at program exit.

### Capabilities

Every backend reports its capabilities so tooling and tests can adapt. The JVM backend reports virtual threads, structured scopes, and non-blocking timers; the sequential backend reports none of them.

## The sequential backend

Eru also ships a synchronous fallback backend. It runs every effect inline on the calling thread: `fork` computes the child to completion, `race` prefers the left side and falls back to the right when the left fails, and `sleep` blocks with an at-least-duration `Thread.sleep`. Its `timeout` cannot preempt a running effect, so it runs the effect and reports a `TimeoutException` when the effect finished past the deadline — honest, not interrupting.

This backend exists for two reasons:

1. Determinism: sequential execution removes scheduling nondeterminism entirely — useful for reasoning, not for timing. Timing-sensitive tests belong on the TestClock backend (Chapter 11), whose logical time is driven entirely by the test.
2. Fallback safety: if backend discovery ever fails (for example, when running in an environment without a registered provider), Eru degrades to a complete, correct synchronous implementation rather than crashing.

## Backend discovery

The runtime discovers its backend through `java.util.ServiceLoader`, reading `META-INF/services/net.ghoula.eru.internal.BackendProvider` from the classpath. The JVM backend registers itself this way; no user configuration is required.

```scala mdoc
import net.ghoula.eru.prelude.*

// EruRuntime.create() picks up the registered backend automatically:
val runtime = EruRuntime.create()

// And the shared runtime does the same at first use.
val shared = EruRuntime.shared
```

If no provider is registered, the sequential fallback is used. This fail-safe design means `EruRuntime.create()` always returns a working runtime.

## Choosing a backend

Applications rarely need to think about backends: the JVM backend is the default and is selected automatically. Tests that need determinism add the `eru-testkit` artifact and use `net.ghoula.eru.test`; the sequential backend is an internal fallback, not a selectable option. The boundary is clean: execution strategy is selected at runtime, not encoded in the program.

## Key takeaways

- Effect descriptions are backend-agnostic: `Eru[E, A]` values never mention execution strategy.
- The JVM backend is the default: virtual threads and non-blocking timers.
- A sequential fallback guarantees correctness: discovery failure degrades to a complete synchronous runtime.
- The SPI is an extension point: future backends can register themselves without touching the core.

## What's next

With the execution model clear, the next chapter covers fibers: how `fork`, `await`, and interruption behave in detail.
