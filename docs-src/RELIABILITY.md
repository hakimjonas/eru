# Reliability in Eru

Eru provides ergonomic, principled tools for building reliable programs: bounded retries with optional backoff, and timeouts with safe cancellation. The unified prelude exposes these as fluent extension methods on `Eru[E, A]`.

## Canonical Import

```scala
import net.ghoula.eru.prelude.*
```

## Bounded Retries

Retries are applied only to typed failures (the `E` channel). Defects (`Throwable`) are not retried and are surfaced for diagnosis.

```scala
import net.ghoula.eru.prelude.*

var attempts = 0
val flaky: Eru[String, Int] =
  Eru.effect {
    attempts += 1
    attempts
  }.flatMap { n =>
    if n < 3 then Eru.fail("try again") else Eru.succeed(42)
  }

val resilient: Eru[String, Int] = flaky.retryN(5)
val value: Int = resilient.unsafeRunSync() // 42 after a few attempts
```

## Exponential Backoff

Use exponential backoff to add delays between retries. The policy is deterministic and bounded.

```scala
import net.ghoula.eru.prelude.*
import java.time.Duration

var hit = 0
val service: Eru[String, String] =
  Eru.effect { hit += 1; hit }
    .flatMap(n => if n < 4 then Eru.fail("unavailable") else Eru.succeed("ok"))

val withBackoff = service.retryWithBackoff(Duration.ofMillis(10), maxRetries = 5)
val ok: String = withBackoff.unsafeRunSync() // "ok"
```

## Timeouts

A timeout converts long-running computations into either a typed failure (via `timeoutTo`) or a defect path containing a `TimeoutException`. The losing branch is interrupted and finalizers are honored.

```scala
import net.ghoula.eru.prelude.*
import java.time.Duration

val slow: Eru[Throwable, Int] = Eru.blocking { Thread.sleep(1000); 42 }

// Fail fast on timeout with a TimeoutException in the defect channel
val fastFail: Eru[Throwable | java.util.concurrent.TimeoutException | Throwable, Int] =
  slow.timeout(Duration.ofMillis(50))

// Provide a fallback value instead of failing on timeout
val withFallback: Eru[Throwable, Int] = slow.timeoutTo(Duration.ofMillis(50), fallback = -1)

val v1: Int = withFallback.unsafeRunSync() // -1
```

## Observability-Friendly Execution

You can always execute programs at the observable boundary and inspect structured outcomes.

```scala
import net.ghoula.eru.prelude.*

val exit: Exit[String, Int] = flaky.retryN(3).runExit()
exit match {
  case Exit.Success(v) => println(s"value=$v")
  case Exit.Failure(e) => println(s"typed error=$e")
  case Exit.Die(t)     => println(s"defect=${t.getMessage}")
  case Exit.Interrupt(_, cause) => println(s"interrupted: $cause")
}
```

## Testing and Quality Assurance

Eru maintains exceptional reliability through comprehensive testing infrastructure:

**Test Coverage:**
- **576+ tests** across JVM and Native platforms
- **Zero-cast runtime** enforcement with build-time linting
- **Complete logical coverage** for all core operations including retries, timeouts, and error handling

**Cross-Platform Validation:**
- **JVM tests** verify concurrent execution with Virtual Threads
- **Native tests** validate synchronous execution with identical API surface
- **Integration tests** ensure end-to-end reliability scenarios

**CI Optimization:**
```bash
# Targeted test commands for efficient validation
sbt testJVM           # Run all JVM tests (core + runtime)
sbt testNative        # Run all Native tests (core + runtime)
sbt testQuick         # Fast feedback loop excluding slow tests
sbt testIntegration   # End-to-end integration scenarios
```

The testing infrastructure ensures that reliability patterns work correctly across all platforms while maintaining the performance characteristics that make Eru exceptional.
