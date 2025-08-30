# Observability in Eru

Eru provides structured, low-overhead observability for program execution. Attach an `EruObserver` to receive lifecycle and step events emitted by the observer-aware interpreter.

## Event Taxonomy (Minimal, Structured)

- Program events
  - `ProgramStart(scopeId)` — emitted once at the beginning of a run.
  - `ProgramEnd(scopeId, outcome)` — emitted once at the end with an `Outcome`:
    - `Outcome.Success`
    - `Outcome.TypedFailure(error: Any)`
    - `Outcome.Defect(throwable: Throwable)`
- Step events
  - `Step(scopeId, label)` — emitted just before executing a `.debug(label)`-annotated computation.
- Fiber events (present for fork operations; compatible with future async runtime)
  - `FiberStarted(fiberId)`
  - `FiberCompleted(fiberId, exit)`
  - `FiberInterrupted(fiberId, cause)`

## Minimal Guarantees

- For any `unsafeRunSyncWith(observer)` invocation:
  - Exactly one `ProgramStart` precedes exactly one `ProgramEnd` for the same `scopeId`.
  - If `.debug(label)` is used, a matching `Step(scopeId, label)` is emitted before the annotated step executes.
- For `forkWithObserver(observer)`:
  - `FiberStarted` is emitted before the forked computation runs.
  - `FiberCompleted` is emitted when the forked computation completes with its `Exit` value.
  - The effect returned by `forkWithObserver` can be awaited via `fiber.await` to observe the same `Exit`.
- Observers must be fast and exception-safe; exceptions must not escape `onEvent`.

## Built-in Helpers

```scala
import net.ghoula.eru.prelude.*

val noop: EruObserver = EruObserver.noop           // discard all events
val console: EruObserver = EruObserver.console     // print events to stdout
```

## Running with an Observer

```scala
import net.ghoula.eru.prelude.*

class PrintingObserver extends EruObserver {
  def onEvent(e: EruEvent): Unit = println(e)
}

val obs = new PrintingObserver
val out = Eru.succeed(123).unsafeRunSyncWith(obs)
// ProgramStart(scopeId)
// ProgramEnd(scopeId, Success)
```

## Debug Steps

```scala
import net.ghoula.eru.prelude.*

val program = Eru.succeed(1).debug("pre-step").map(_ + 1)
val obs = EruObserver.console
val value = program.unsafeRunSyncWith(obs)
// ProgramStart(scopeId)
// Step(scopeId, pre-step)
// ProgramEnd(scopeId, Success)
```

## Fork/Join Lifecycle (sync-friendly semantics)

```scala
import net.ghoula.eru.prelude.*

val obs = EruObserver.console
val fiber = Eru.succeed(42).forkWithObserver(obs).unsafeRunSync()
val exit  = fiber.await.unsafeRunSync()
// FiberStarted(fiberId)
// FiberCompleted(fiberId, Exit.Success(42))
```