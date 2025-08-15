# Observability in Eru — EruObserver, Events, and .debug

Status: 0.2.0 foundations

Eru includes a minimal observability footprint for synchronous runs. You can attach an observer to receive lifecycle and step events emitted by an observer‑aware interpreter.

Primitives
- ScopeId: a stable identifier for a single synchronous run (opaque type).
- Outcome: Success | TypedFailure(error: Any) | Defect(throwable: Throwable).
- EruEvent: ProgramStart(scopeId), ProgramEnd(scopeId, outcome), Step(scopeId, label).
- EruObserver: trait with onEvent(event: EruEvent): Unit.

Running with an observer
```scala
import net.ghoula.eru.*

class CollectingObserver extends EruObserver {
  val buf = scala.collection.mutable.ListBuffer.empty[EruEvent]
  def onEvent(e: EruEvent): Unit = buf += e
}

val obs = new CollectingObserver
val out = Eru.succeed(123).unsafeRunSyncWith(obs)
// obs.buf contains: ProgramStart(scope), ProgramEnd(scope, Outcome.Success)
```

Debug steps
Use .debug to annotate computations with a lazily evaluated label. When an observer is present, a Step event is emitted before the annotated computation executes.
```scala
import net.ghoula.eru.*

val program = Eru.succeed(1).debug("pre-step").map(_ + 1)
val obs = new CollectingObserver
val value = program.unsafeRunSyncWith(obs)
// Events: Start(scope), Step(scope, "pre-step"), End(scope, Success)
```

Lifecycle and timing
- ProgramStart is emitted before the program begins evaluation.
- Finalizers registered via ensure/bracket are executed before ProgramEnd is emitted.
- ProgramEnd carries the final Outcome of the program (Success, TypedFailure, or Defect).
- After ProgramEnd, the same edge semantics apply as unsafeRunSync: a Throwable is rethrown; a non‑Throwable typed error is wrapped in EruException.

Edge semantics and NonFatal
- Eru.effect captures scala.util.control.NonFatal exceptions into the error channel. Fatal errors escape.
- At the edge, unsafeRunSync and unsafeRunSyncWith rethrow Throwable failures and wrap non‑Throwable typed failures in EruException.

Future direction (0.3.0)
- Fibers introduce per‑fiber identities (FiberId) and richer events: FiberStarted, FiberCompleted(exit), Interrupt(cause), Suspend/Resume.
- Outcome generalizes to an Exit[E, A] model for joins and diagnostics.


---

## Notes

- ScopeId is a simple process-local incrementer sufficient for synchronous runs in 0.2.x. In 0.3.0, fibers introduce per-fiber identities (FiberId), and events will carry FiberId instead.
