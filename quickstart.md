# Eru Quickstart — Synchronous Core

Status: 0.1.0 internal waypoint — core synchronous kernel complete (not yet released)

This guide shows how to model and run effectful programs with Eru’s synchronous core using pure, composable building blocks.

Key ideas:
- Eru[E, A] is a pure description of a program that may fail with a typed error E or succeed with A.
- Construction is pure. Evaluation happens only when you call an unsafe interpreter at the edge (here: unsafeRunSync).
- Composition is via map, flatMap, zip, recover, recoverWith, and mapError.

Recommendation: Prefer .attempt to interpret safely into Result without throwing; use unsafeRunSync only at the outer edge (e.g., main or tests).

---

## Hello, Eru

```scala
import net.ghoula.eru.Eru

val program: Eru[Nothing, String] =
  Eru.succeed("hello, eru")

val result: String = program.unsafeRunSync() // edge
```

## Laziness and Effects

Use effect to suspend side-effects and exceptions. The function is evaluated only when the program is run.

```scala
import net.ghoula.eru.Eru

var counter = 0
val prog: Eru[Throwable, Int] = Eru.effect {
  counter += 1
  40 + 2
}

// counter is still 0 here
val value = prog.unsafeRunSync() // 42
// counter is now 1
```

## Sequencing with map and flatMap

```scala
import net.ghoula.eru.Eru

val prog: Eru[Nothing, Int] =
  Eru.succeed(10)
    .map(_ * 2)            // 20
    .flatMap(x => Eru.succeed(x + 5)) // 25

val out = prog.unsafeRunSync() // 25
```

## Typed errors, recovery, and shaping

- fail constructs a typed failure.
- recover and recoverWith allow selective recovery.
- mapError transforms the error type/value.

```scala
import net.ghoula.eru.Eru

val failed: Eru[String, Nothing] = Eru.fail("not found")

val recovered: Eru[String, String] =
  failed.recover {
    case "not found" => "default"
  }

val ok = recovered.unsafeRunSync() // "default"

val shaped: Eru[Int, Int] =
  Eru.fail("abc").mapError(_.length)

// shaped.unsafeRunSync() would throw EruException(3) at the edge
```

recoverWith can replace an error with an alternative computation:

```scala
import net.ghoula.eru.Eru

val prog: Eru[String | Int, String] =
  Eru.fail("oops").recoverWith {
    case "oops" => Eru.fail(404) // change error type to Int
  }
```

## Combining independent computations with zip

zip evaluates left first, then right, and short-circuits on the first failure.

```scala
import net.ghoula.eru.Eru

val left  = Eru.succeed(1)
val right = Eru.succeed("a")

val both: Eru[Nothing, (Int, String)] = left.zip(right)
val tuple = both.unsafeRunSync() // (1, "a")
```

## Interop: Either and Try

```scala
import net.ghoula.eru.Eru

val e1: Either[String, Int] = Right(42)
val fromEither: Eru[String, Int] = Eru.fromEither(e1)

import scala.util.{Try, Success, Failure}
val t: Try[Int] = Success(42)
val fromTry: Eru[Throwable, Int] = Eru.fromTry(t)
```

## Safe interpretation with attempt

```scala
import net.ghoula.eru.{Eru, Result}

val program: Eru[String, Int] =
  Eru.succeed(21).map(_ * 2)

val asResult: Eru[Nothing, Result[String, Int]] =
  program.attempt

val message: String =
  asResult.map(_.fold(e => s"error: $e", a => s"ok: $a")).unsafeRunSync()
// "ok: 42"
```

## Edge semantics

- unsafeRunSync is the synchronous interpreter and may throw at the edge.
- If a computation fails with a Throwable, that Throwable is rethrown as-is (e.g., from Eru.effect or Eru.fail(t: Throwable)).
- If a computation fails with a non-Throwable typed error E, an EruException(E) is thrown.
- Eru.effect catches scala.util.control.NonFatal exceptions into the error channel; fatal errors escape.

## Suggested usage pattern

- Build programs as Eru values throughout your application and libraries.
- Use unsafeRunSync only at the outermost boundary (e.g., main), or in tests.
- Once attempt ships, prefer .attempt and handle results in a pure style within your domain logic.
