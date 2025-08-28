# Eru Quickstart — Synchronous Core

This guide shows how to model and run effectful programs with Eru's synchronous core using pure, composable building blocks.

## Key Ideas

- `Eru[E, A]` is a pure description of a program that may fail with a typed error E or succeed with A.

- Construction is pure and lazy. Evaluation happens only when you call an unsafe interpreter (e.g., `unsafeRunSync`).

- Composition is via `map`, `flatMap`, `zip`, and error handling methods.

## Hello, Eru

```scala
import net.ghoula.eru.prelude.*

val program: Eru[Nothing, String] =
  Eru.succeed("hello, eru")

val result: String = program.unsafeRunSync()
```

## Laziness and Effects

Use `effect` to suspend side-effects. The provided thunk is evaluated only when the program is run.

```scala
var counter = 0
val prog: Eru[Throwable, Int] = Eru.effect {
  counter += 1
  42
}

// counter is still 0
val value = prog.unsafeRunSync()
// counter is now 1
```

## Sequencing with map and flatMap

`flatMap` and `map` are used to chain operations. For chains of pure computations using `Eru.succeed`, Eru applies construction-time optimizations to reduce overhead, making pure functional composition highly efficient.

```scala
val pureComputation: Eru[Nothing, Int] =
  Eru.succeed(10)
    .flatMap(x => Eru.succeed(x * 2)) // This chain is fused at construction
    .map(_ + 2)

val result = pureComputation.unsafeRunSync() // 22
```

## Error Handling

Use methods like `recover` and `attempt` to handle potential failures in a composable way.

```scala
val failed = Eru.fail("boom")

val recovered = failed.recover {
  case "boom" => "recovered!"
}

recovered.unsafeRunSync() // "recovered!"
```
