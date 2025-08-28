# Observability in Eru

Eru includes a minimal observability footprint for program execution. You can attach an observer to receive lifecycle and step events emitted by an observer-aware interpreter.

## Primitives

- **ScopeId**: A stable identifier for a single synchronous run.
- **Outcome**: A structured result (Success, TypedFailure, Defect).
- **EruEvent**: ADT for events like ProgramStart, ProgramEnd, and Step.
- **EruObserver**: A simple trait to implement for consuming events.

## Running with an Observer

To use an observer, pass it to the unsafeRunSyncWith interpreter.

```scala
import net.ghoula.eru.prelude.*

class PrintingObserver extends EruObserver {
  def onEvent(e: EruEvent): Unit = println(e)
}

val obs = new PrintingObserver
val out = Eru.succeed(123).unsafeRunSyncWith(obs)
// Prints: ProgramStart(ScopeId(1))
// Prints: ProgramEnd(ScopeId(1),Success)
```

## Debug Steps

Use `.debug` to annotate computations with a lazily evaluated label. When an observer is present, a Step event is emitted before the annotated computation executes.

```scala
val program = Eru.succeed(1).debug("pre-step").map(_ + 1)
val obs = new PrintingObserver
val value = program.unsafeRunSyncWith(obs)
// Prints: ProgramStart(ScopeId(2))
// Prints: Step(ScopeId(2),pre-step)
// Prints: ProgramEnd(ScopeId(2),Success)
```
