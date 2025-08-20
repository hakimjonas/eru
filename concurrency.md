# Concurrency Guide

This guide introduces Eru's core effect system and composition patterns. Eru provides a pure, composable foundation for building applications with strong correctness guarantees.

## Basic Effect Composition

The foundation of Eru is the ability to compose pure effects that describe computations without executing them immediately.

For chains of pure computations (using Eru.succeed), Eru employs a construction-time optimization to fuse operations and reduce overhead.

```scala
import net.ghoula.eru.*

// Compose pure effects using for-comprehension
val program = for {
  a <- Eru.succeed(42)
  b <- Eru.succeed("hello")
  result <- Eru.succeed(s"$a-$b")
} yield s"Composed: $result"

// The program is fused at construction to:
// program: Eru[Nothing, String] = Succeed(value = "Composed: 42-hello")

program.unsafeRunSync()
// res0: String = "Composed: 42-hello"
```

## Non-Blocking Concurrency Primitives

Eru's concurrency primitives are designed to be non-blocking to align with the library's asynchronous, fiber-based runtime. This encourages building responsive applications by default.

### Semaphore

Eru provides a non-blocking Semaphore. All acquisition methods like tryAcquire are effects that complete immediately with a boolean indicating success. A blocking acquire can be trivially built on top of tryAcquire if needed, but is not provided by default to encourage non-blocking design.

```scala
import net.ghoula.eru.*
import scala.concurrent.duration.*

val program = for {
  sem <- Semaphore.make(1)
  a <- sem.withPermit(Eru.succeed("critical section"))
  b <- sem.tryAcquire.map(acquired => s"Acquired second permit: $acquired")
} yield (a, b)

// program.unsafeRunSync() will yield ("critical section", "Acquired second permit: false")
```

## Error Handling and Recovery

Eru provides powerful error handling capabilities that work seamlessly with effect composition.

```scala
val errorProgram = for {
  result1 <- Eru.fail("Something went wrong").recover {
    case "Something went wrong" => "Recovered!"
  }
  result2 <- Eru.succeed(42).attempt
} yield (result1, result2)

errorProgram.unsafeRunSync()
// res1: (String, Result[Nothing, Int]) = ("Recovered!", Success(value = 42))
```
