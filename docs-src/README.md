# Eru

Eru is an effect system for Scala 3 that runs on Java virtual threads. Every program is a value of type `Eru[E, A]`; `E` is the error channel, and Scala 3 union types keep each possible failure visible at compile time. Operations that may block indefinitely return `Suspending[E, A]`, a type with no synchronous run method, so code that can deadlock does not compile. `bracket` and `ensure` tie resource cleanup to a resource's scope.

Read the [Eru Manifesto](MANIFESTO.md) for the design principles and goals.

## Installation

Eru is published to Maven Central. Add the modules to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-core" % "@VERSION@",
  "net.ghoula" %% "eru-runtime" % "@VERSION@"
)
```

Eru builds against Scala @SCALA_VERSION@ on JDK 25.

## Quick Start

```scala
import net.ghoula.eru.prelude.*
import java.nio.file.{Files, Path}

// A program is a value. Nothing runs until it is run.
val program: Eru[Nothing, Int] = Eru.succeed(21).map(_ * 2)
val answer: Int = program.unsafeRunSync() // 42

// Typed errors: the error channel is part of the type.
val recoverable: Eru[String, Int] =
  Eru.fail("bad input").recover { case "bad input" => 0 }

// Resources: bracket ties cleanup to the resource's scope.
val firstLine: Eru[Throwable, String] =
  Eru.effect(Files.newBufferedReader(Path.of("notes.txt")))
    .bracket(reader => Eru.effect(reader.close()))(reader => Eru.effect(reader.readLine()))
```

## Design

A computation has type `Eru[E, A]`: it fails with an `E` or succeeds with an `A`. Union types accumulate the errors a program can produce, so every failure a program declares is visible in its signature.

Operations that may block indefinitely, such as `queue.take`, return `Suspending[E, A]` instead. This type has no synchronous run method; it runs through `timeout`, `fork`, or `race`. Code that would block forever cannot be run synchronously by accident.

`bracket` and `ensure` attach cleanup to a resource's scope, and finalizers run in acquisition-reverse order. A resource acquired inside a computation cannot outlive the computation that uses it.

The interpreter is cast-free. Concurrency primitives such as `Queue` and `Semaphore` are built from `Ref` and `Promise`, not from `java.util.concurrent`, and fibers run directly on virtual threads rather than a custom scheduler.

## Documentation

- [Manifesto](MANIFESTO.md)
- [Quick Start](QUICKSTART.md)
- [API reference](API.md)
- [Resource management](RESOURCES.md)
- [Observability](OBSERVER.md)
- [The Eru Book](book/00-table-of-contents.md)
- [Contributing](CONTRIBUTING.md)

## Status

Eru is at version @VERSION@. The core API is stable; breaking changes may still occur before 1.0.0.

## Contributing

Eru is designed and developed by Hakim Jonas Ghoula and licensed under the GNU General Public License v3.0 or later. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow, quality standards, and build commands.
