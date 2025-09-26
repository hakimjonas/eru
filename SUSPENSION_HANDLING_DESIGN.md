# Suspension Handling Design - FINAL SPECIFICATION

## ⚠️ CRITICAL: NO DEVIATIONS ALLOWED

This document specifies the ONLY acceptable solution for suspension handling in Eru. Any deviation from this specification will lead to the same destructive spiral we just escaped from.

**DO NOT**:
- Use `asInstanceOf` anywhere
- Create phantom types that need casting
- Try to "simplify" by removing the value classes
- Compromise on type safety
- Accept any solution that allows deadlocks

**THIS DESIGN IS FINAL AND NON-NEGOTIABLE.**

## The Problem

Eru needs to distinguish at compile-time between:
1. Operations that can suspend/block indefinitely
2. Operations that complete immediately
3. Operations with bounded wait times

Currently, all operations return `Eru[E, A]` with no compile-time distinction, allowing:
```scala
// This compiles but deadlocks:
queue.take.unsafeRunSync()  // 💀 DEADLOCK if queue is empty
```

## The Solution: Value Class Wrappers

### Core Types

```scala
package net.ghoula.eru

/** A computation that may suspend indefinitely.
  *
  * CRITICAL: This type has NO unsafeRunSync method to prevent deadlocks.
  * It can only be run safely via fork or race operations.
  *
  * This is a value class with ZERO runtime overhead - the wrapper is
  * completely erased by the compiler.
  */
final class Suspending[+E, +A](val eru: Eru[E, A]) extends AnyVal {

  /** Fork this suspending computation onto a new fiber. */
  def fork(using runtime: EruRuntime): Eru[Nothing, Fiber[E, A]] =
    eru.fork

  /** Race this against another suspending computation. */
  def race[E2, B](that: Suspending[E2, B])(using runtime: EruRuntime):
    Suspending[E | E2 | Throwable, Either[A, B]] =
    new Suspending(runtime.race(eru, that.eru))

  /** Race with a timeout. */
  def timeout(duration: Duration)(using runtime: EruRuntime):
    Immediate[E | TimeoutError, A] = {
    val timeoutEru = runtime.sleep(duration).map(_ =>
      throw TimeoutError(s"Operation timed out after $duration"))
    new Immediate(runtime.race(eru, timeoutEru).map(_.merge))
  }

  // DELIBERATELY NO unsafeRunSync - this prevents deadlocks at compile time
}

/** A computation that completes immediately without suspension.
  *
  * This type CAN be safely run synchronously via unsafeRunSync.
  *
  * This is a value class with ZERO runtime overhead - the wrapper is
  * completely erased by the compiler.
  */
final class Immediate[+E, +A](val eru: Eru[E, A]) extends AnyVal {

  /** Safely run this non-suspending computation synchronously. */
  def unsafeRunSync(): A = eru.unsafeRunSync()

  /** Fork this computation onto a new fiber. */
  def fork(using runtime: EruRuntime): Eru[Nothing, Fiber[E, A]] =
    eru.fork

  /** Convert to a suspending computation (always safe to widen). */
  def suspending: Suspending[E, A] = new Suspending(eru)
}

case class TimeoutError(message: String) extends Exception(message)
```

## Implementation Pattern for Queue

### The Interface

```scala
trait Queue[A] {
  // Suspending operations - MUST return Suspending type
  def put(a: A): Suspending[Nothing, Unit]
  def take: Suspending[Nothing, A]
  def putAll(as: Seq[A]): Suspending[Nothing, Unit]
  def takeUpTo(n: Int): Suspending[Nothing, List[A]]

  // Immediate operations - MUST return Immediate type
  def tryPut(a: A): Immediate[Nothing, Boolean]
  def tryTake: Immediate[Nothing, Option[A]]
  def tryPutAll(as: Seq[A]): Immediate[Nothing, Int]
  def tryTakeUpTo(n: Int): Immediate[Nothing, List[A]]
  def size: Immediate[Nothing, Int]
  def isEmpty: Immediate[Nothing, Boolean]
  def isFull: Immediate[Nothing, Boolean]
  def remainingCapacity: Immediate[Nothing, Int]
  def peek: Immediate[Nothing, Option[A]]
  def capacity: Immediate[Nothing, Option[Int]]

  // Bounded operations - complete immediately with timeout result
  def putWithin(a: A, timeout: Duration): Immediate[Throwable, Boolean]
  def takeWithin(timeout: Duration): Immediate[Throwable, Option[A]]
  def putAllWithin(as: Seq[A], timeout: Duration): Immediate[Throwable, Int]
  def takeUpToWithin(n: Int, timeout: Duration): Immediate[Throwable, List[A]]
}
```

### The Implementation

```scala
private[eru] final class QueueImpl[A](
  stateRef: Ref[QueueState[A]],
  capacityLimit: Option[Int],
  runtime: EruRuntime
) extends Queue[A] {

  // Suspending operations - wrap in Suspending
  override def put(a: A): Suspending[Nothing, Unit] =
    new Suspending(putImpl(a))

  override def take: Suspending[Nothing, A] =
    new Suspending(takeImpl())

  // Immediate operations - wrap in Immediate
  override def tryPut(a: A): Immediate[Nothing, Boolean] =
    new Immediate(tryPutImpl(a))

  override def tryTake: Immediate[Nothing, Option[A]] =
    new Immediate(tryTakeImpl())

  override def size: Immediate[Nothing, Int] =
    new Immediate(stateRef.get.map(_.size))

  // Private implementation methods return plain Eru
  private def putImpl(a: A): Eru[Nothing, Unit] = {
    // Current implementation
  }

  private def takeImpl(): Eru[Nothing, A] = {
    // Current implementation
  }

  private def tryPutImpl(a: A): Eru[Nothing, Boolean] = {
    // Current implementation
  }

  private def tryTakeImpl(): Eru[Nothing, Option[A]] = {
    // Current implementation
  }
}
```

## Extension to Other Primitives

### Promise
```scala
trait Promise[E, A] {
  def await: Suspending[E, A]
  def tryGet: Immediate[Nothing, Option[Exit[E, A]]]
  def succeed(a: A): Immediate[Nothing, Unit]
  def fail(e: E): Immediate[Nothing, Unit]
}
```

### Semaphore
```scala
trait Semaphore {
  def acquire: Suspending[Nothing, Unit]
  def acquireN(n: Long): Suspending[Nothing, Unit]
  def tryAcquire: Immediate[Nothing, Boolean]
  def tryAcquireN(n: Long): Immediate[Nothing, Boolean]
  def release: Immediate[Nothing, Unit]
  def releaseN(n: Long): Immediate[Nothing, Unit]
  def available: Immediate[Nothing, Long]
}
```

### CountDownLatch
```scala
trait CountDownLatch {
  def await: Suspending[Nothing, Unit]
  def countDown: Immediate[Nothing, Unit]
  def getCount: Immediate[Nothing, Int]
  def isZero: Immediate[Nothing, Boolean]
}
```

### CyclicBarrier
```scala
trait CyclicBarrier {
  def await: Suspending[Nothing, Unit]
  def getParties: Immediate[Nothing, Int]
  def getNumberWaiting: Immediate[Nothing, Int]
  def isBroken: Immediate[Nothing, Boolean]
}
```

### Deferred
```scala
trait Deferred[E, A] {
  def get: Suspending[E, A]
  def tryGet: Immediate[Nothing, Option[Exit[E, A]]]
  def complete(exit: Exit[E, A]): Immediate[Nothing, Boolean]
}
```

## Usage Examples

### Safe Usage
```scala
// Fork suspending operations
for {
  fiber <- queue.take.fork
  _ <- queue.put(42).fork
  result <- fiber.await
} yield result

// Race with timeout
queue.take.timeout(1.second).unsafeRunSync() match {
  case Right(value) => println(s"Got $value")
  case Left(TimeoutError(_)) => println("Timed out")
}

// Use immediate operations synchronously
val added = queue.tryPut(42).unsafeRunSync()
val size = queue.size.unsafeRunSync()
```

### Prevented Errors
```scala
// DOES NOT COMPILE - no unsafeRunSync on Suspending
queue.take.unsafeRunSync()  // ❌ Compile error!

// DOES NOT COMPILE - wrong type
val s: Immediate[Nothing, Unit] = queue.put(42)  // ❌ Type mismatch!

// Must handle suspension explicitly
val result = queue.take.fork.flatMap(_.await)  // ✅ Correct
```

## Implementation Checklist

- [ ] Create Suspending and Immediate value classes in eru-runtime
- [ ] Update Queue trait with new return types
- [ ] Update QueueImpl to wrap returns in appropriate types
- [ ] Update Promise with new return types
- [ ] Update Semaphore with new return types
- [ ] Update CountDownLatch with new return types
- [ ] Update CyclicBarrier with new return types
- [ ] Update Deferred with new return types
- [ ] Update all tests to use new API
- [ ] Verify NO asInstanceOf usage
- [ ] Verify NO unsafeRunSync on suspending operations

## Why This Works

1. **Zero Runtime Cost**: Value classes are completely erased by the compiler
2. **No Type Casting**: We construct the right type from the start
3. **Compile-Time Safety**: Can't call blocking operations synchronously
4. **Clear API**: Types tell you exactly what can happen
5. **Proven Pattern**: Matches Java's 20-year successful approach

## Final Warning

**DO NOT DEVIATE FROM THIS DESIGN.**

Java's `BlockingQueue` has worked for 20 years with clear distinction between blocking and non-blocking operations. We are following the same proven pattern with Scala's type system.

Any attempt to "improve" or "simplify" this design will result in:
- Type casting (asInstanceOf)
- Runtime deadlocks
- Lost type safety
- Violation of Eru's principles

**This design is correct, proven, and final.**