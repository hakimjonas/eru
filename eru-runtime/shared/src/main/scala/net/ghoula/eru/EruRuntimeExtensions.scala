package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** Ergonomic extensions for common runtime patterns.
  *
  * These extensions make Eru even more pleasant to use by reducing boilerplate for common patterns
  * while maintaining type safety and clarity.
  */
extension [E, A](effect: Eru[E, A]) {

  /** Run this effect synchronously with the default runtime.
    *
    * Convenience method that creates a runtime and runs the effect. For production use, prefer
    * creating and reusing a runtime.
    */
  def runSync()(using runtime: EruRuntime): A = {
    // Runtime is available implicitly for unsafeRunSync
    val _ = runtime // Mark as used
    effect.unsafeRunSync()
  }

  /** Run this effect and print the result (useful for debugging/REPL).
    */
  def runPrint()(using runtime: EruRuntime): Unit = {
    val _ = runtime // Mark as used
    effect.attempt.map {
      case Result.Success(value) => println(s"Success: $value")
      case Result.Failure(error) => println(s"Failure: $error")
    }.unsafeRunSync()
  }

  /** Time this effect's execution.
    */
  def timed(using runtime: EruRuntime): Eru[E, (A, java.time.Duration)] = {
    val _ = runtime // Mark as used
    for {
      start <- Eru.effectTotal(System.nanoTime())
      result <- effect
      end <- Eru.effectTotal(System.nanoTime())
      duration = java.time.Duration.ofNanos(end - start)
    } yield (result, duration)
  }

  /** Retry this effect with exponential backoff.
    */
  def retryWithBackoff(
    maxAttempts: Int = 3,
    initialDelay: java.time.Duration = java.time.Duration.ofMillis(100)
  )(using runtime: EruRuntime): Eru[E, A] = {
    def loop(attempt: Int, delay: java.time.Duration): Eru[E, A] = {
      effect.attempt.flatMap {
        case Result.Success(value) => Eru.succeed(value)
        case Result.Failure(error) if attempt < maxAttempts =>
          runtime.sleep(delay).flatMap { _ =>
            loop(attempt + 1, delay.multipliedBy(2))
          }
        case Result.Failure(error) => Eru.fail(error)
      }
    }
    loop(1, initialDelay)
  }

  /** Log this effect's execution (before and after).
    */
  def logged(label: String): Eru[E, A] = {
    for {
      _ <- Eru.effectTotal(println(s"[$label] Starting..."))
      start <- Eru.effectTotal(System.nanoTime())
      result <- effect.attempt
      end <- Eru.effectTotal(System.nanoTime())
      duration = (end - start) / 1_000_000.0
      _ <- Eru.effectTotal {
        result match {
          case Result.Success(value) =>
            println(f"[$label] Completed in $duration%.2f ms: $value")
          case Result.Failure(error) =>
            println(f"[$label] Failed in $duration%.2f ms: $error")
        }
      }
      finalResult <- result match {
        case Result.Success(value) => Eru.succeed(value)
        case Result.Failure(error) => Eru.fail(error)
      }
    } yield finalResult
  }
}

/** Ergonomic runtime creation patterns. */
object EruRuntimeExtensions {

  /** Create and use a runtime for a single effect.
    *
    * This is convenient for scripts and testing but creates a new runtime each time. For
    * production, create and reuse a runtime.
    */
  def run[E, A](effect: Eru[E, A]): A = {
    effect.unsafeRunSync()
  }

  /** Create a runtime and run a block of code with it.
    */
  def withRuntime[A](block: EruRuntime ?=> A): A = {
    given runtime: EruRuntime = EruRuntime.create()
    block
  }

  /** Global default runtime for convenience (use with caution in production).
    */
  lazy val global: EruRuntime = EruRuntime.create()
}

/** Ergonomic syntax for building effect chains. */
extension [E](companion: Eru.type) {

  /** Build an effect from multiple conditions.
    */
  def whenCondition[A](condition: Boolean)(effect: => Eru[E, A]): Eru[E, Option[A]] = {
    if (condition) effect.map(Some(_))
    else Eru.succeed(None)
  }

  /** Build an effect from an Option.
    */
  def fromOption[A](option: Option[A], ifNone: => E): Eru[E, A] = {
    option match {
      case Some(value) => Eru.succeed(value)
      case None => Eru.fail(ifNone)
    }
  }

  /** Build an effect from an Either.
    */
  def fromEither[A](either: Either[E, A]): Eru[E, A] = {
    either match {
      case Right(value) => Eru.succeed(value)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Build an effect from a Try.
    */
  def fromTry[A](tryValue: scala.util.Try[A]): Eru[Throwable, A] = {
    tryValue match {
      case scala.util.Success(value) => Eru.succeed(value)
      case scala.util.Failure(error) => Eru.fail(error)
    }
  }

  /** Bracket pattern for resource management with better ergonomics.
    */
  def bracket[R, E1, A](
    acquire: Eru[E1, R]
  )(
    release: R => Eru[Nothing, Unit]
  )(
    use: R => Eru[E1, A]
  ): Eru[E1, A] = {
    acquire.bracket(release)(use)
  }
}

/** Ergonomic concurrent operations. */
extension (runtime: EruRuntime) {

  /** Run effects in parallel and return the first to complete.
    */
  def raceFirst[E, A](effects: Eru[E, A]*): Eru[E | Throwable, A] = {
    effects.toList match {
      case Nil => Eru.fail(new IllegalArgumentException("raceFirst requires at least one effect"))
      case single :: Nil =>
        // Single effect doesn't need racing, just ensure type alignment
        single.map(identity)
      case multiple => runtime.raceAll(multiple).map { case (value, _) => value }
    }
  }

  /** Run effects in parallel and collect all results.
    */
  def parAll[E, A](effects: Eru[E, A]*): Eru[E | Throwable, List[A]] = {
    runtime.parSequence(effects.toList)
  }

  /** Run effects with a timeout.
    */
  def withTimeout[E, A](
    duration: java.time.Duration
  )(effect: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    runtime.timeout(duration)(effect)
  }
}
