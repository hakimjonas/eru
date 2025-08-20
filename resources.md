# Resource Safety in Eru

Eru provides principled resource safety in the synchronous kernel with two core combinators:

- **ensure**: Attaches a finalizer that is guaranteed to run after the main effect, regardless of its outcome.

- **bracket**: A convenient pattern for acquiring, using, and releasing a resource.

## Key Guarantees

- **Guaranteed Execution**: Finalizers always run on success, typed failure (E), and defects (Throwable).

- **FILO Ordering**: Nested ensure finalizers run in last-in, first-out order.

- **Laziness**: Finalizers are suspended as effects and executed exactly once when the program is run.

## Examples

### ensure runs on both success and failure:

```scala
import net.ghoula.eru.Eru

var cleaned = false
val successfulProgram = Eru.succeed(42).ensure(Eru.effect { cleaned = true })
successfulProgram.unsafeRunSync()
// cleaned is now true

cleaned = false
val failedProgram = Eru.fail("boom").ensure(Eru.effect { cleaned = true })
try failedProgram.unsafeRunSync() catch { case _ => () }
// cleaned is now true
```

### bracket for acquire-use-release:

```scala
var acquired = false
var released = false
val acquire = Eru.effect { acquired = true; "resource" }
val release = (res: String) => Eru.effect { released = true }

val program = acquire.bracket(release) { resource =>
  Eru.succeed(s"Using $resource")
}

program.unsafeRunSync()
// acquired and released are both true
```
