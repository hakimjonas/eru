# Resource Safety in Eru — ensure and bracket

Status: 0.2.0 foundations

Eru provides principled resource safety in the synchronous kernel with two core combinators:
- ensure: attach a finalizer that always runs after the program, regardless of success or failure.
- bracket: acquire–use–release in one fluent operation.

Key guarantees
- Finalizers always run: on success, typed failure (E), and defects (Throwable captured by effect).
- FILO ordering: nested ensure finalizers run in last-in, first-out order.
- Error suppression: finalizer failures are suppressed and do not change the main outcome.
- Laziness: finalizers are evaluated lazily and executed exactly once when the program runs.
- Idempotence advice: write finalizers to be idempotent; although each is invoked once, defensive idempotence is recommended for real systems.

Examples

Ensure runs on success:
```scala
import net.ghoula.eru.Eru

var cleaned = 0
val program = Eru.succeed(42).ensure(Eru.effect { cleaned += 1; () })
val out = program.unsafeRunSync()
// out == 42, cleaned == 1
```

Ensure runs on typed failure and on defects:
```scala
import net.ghoula.eru.{Eru, EruException}

var cleaned = 0
val failTyped: Eru[String, Int] = Eru.fail("boom").ensure(Eru.effect { cleaned += 1; () })
try failTyped.unsafeRunSync() catch { case e: EruException[String] => () }
// cleaned == 1

cleaned = 0
val ex = new RuntimeException("x")
val failDefect: Eru[Throwable, Int] = Eru.effect[Int](throw ex).ensure(Eru.effect { cleaned += 1 })
try failDefect.unsafeRunSync() catch { case _: RuntimeException => () }
// cleaned == 1
```

FILO ordering when nested:
```scala
import net.ghoula.eru.Eru
import scala.collection.mutable.ListBuffer

val order = ListBuffer.empty[String]
val f1 = Eru.effect { order += "f1"; () }
val f2 = Eru.effect { order += "f2"; () }
Eru.succeed(1).ensure(f1).ensure(f2).unsafeRunSync()
// order.toList == List("f2", "f1")
```

Bracket (acquire–use–release):
```scala
import net.ghoula.eru.Eru

var acquired = 0
var released = 0
val acquire: Eru[Throwable, Int] = Eru.effect { acquired += 1; 7 }
val release: Int => Eru[Throwable, Unit] = _ => Eru.effect { released += 1; () }

val success = acquire.bracket(release) { a => Eru.succeed(a * 2) }
// success.unsafeRunSync() == 14; acquired == 1; released == 1

val failure = acquire.bracket(release) { _ => Eru.fail("nope") }
// throws EruException("nope"); acquired == 2; released == 2
```

Design notes
- ensure is represented by a dedicated Ensure node. The interpreter collects finalizers on a stack and drains them after the program finishes, guaranteeing FILO ordering.
- Finalizer failures are normalized via attempt and ignored to preserve the main outcome. Future versions may surface suppressed finalizer errors in structured diagnostics.
- The synchronous interpreter remains stack‑safe via scala.util.control.TailCalls.


---

## Additional notes

- Finalizers can themselves register further finalizers. Because Eru drains finalizers using a LIFO stack, any finalizers registered by a finalizer will run before earlier ones.
- Finalizer/release errors are intentionally suppressed in 0.2.x to preserve the main program outcome. Future versions may surface suppressed finalizer errors as structured diagnostics via an Exit/Cause model.
