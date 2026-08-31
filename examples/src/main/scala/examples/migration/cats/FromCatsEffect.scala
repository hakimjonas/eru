/** Migration Guide: From Cats Effect to Eru
  *
  * This example demonstrates how to migrate common Cats Effect patterns to Eru, highlighting
  * similarities and differences between the two effect systems.
  */
package examples.migration.cats

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import java.time.Duration

/** Runtime context required for concurrency operations. */
given runtime: EruRuntime = EruRuntime.shared

object FromCatsEffect {

  println("=== Migration Guide: Cats Effect to Eru ===\n")

  /** Basic effect construction: Eru prefers typed errors over exceptions. */
  private def basicEffectsExample(): Unit = {
    println("1. Basic Effects")

    println("Cats Effect Style:")
    println("""
      |// Cats Effect (IO)
      |val succeed = IO.pure(42)
      |val fail = IO.raiseError(new Exception("error"))
      |val effect = IO(println("Hello"))
      |val blocking = IO.blocking(blockingOperation())
    """.stripMargin)

    println("Eru Equivalent:")
    val succeed = Eru.succeed(42)
    val fail = Eru.fail("error")
    val effect = Eru.effect(println("Hello"))
    val blocking = Eru.blocking { Thread.sleep(10); "blocking result" }

    println(s"Succeed result: ${succeed.unsafeRunSync()}")
    println(s"Blocking result: ${blocking.unsafeRunSync()}")
    println("Effect executed successfully")
    println()
  }

  private def errorHandlingExample(): Unit = {
    println("2. Error Handling")

    println("Cats Effect Style:")
    println("""
      |// Cats Effect (IO)
      |val handled = task.handleError(error => "recovered")
      |val handleWith = task.handleErrorWith(error => IO.pure("handled"))
      |val attempt = task.attempt
      |val redeem = task.redeem(error => "failed", success => success)
    """.stripMargin)

    println("Eru Equivalent:")
    val failingTask: Eru[String, Int] = Eru.fail("something went wrong")
    val succeedingTask: Eru[String, Int] = Eru.succeed(42)

    val handled = failingTask.recover { case error => s"recovered from: $error" }
    val handleWith = failingTask.recoverWith { case error => Eru.succeed(s"handled: $error") }
    val attempt = failingTask.attempt

    /** Redeem equivalent: handles both success and failure in one step. */
    def redeem[E, A, B](eru: Eru[E, A])(onError: E => B, onSuccess: A => B): Eru[Nothing, B] =
      eru.attempt.map {
        case Result.Success(value) => onSuccess(value)
        case Result.Failure(error) => onError(error)
      }

    val redeemed = redeem(succeedingTask)(
      error => s"failed with: $error",
      success => s"succeeded with: $success"
    )

    val results = for {
      h <- handled
      hw <- handleWith
      att <- attempt
      red <- redeemed
    } yield (h, hw, att, red)

    val (h, hw, att, red) = results.unsafeRunSync()
    println(s"Handled: $h")
    println(s"Handle with: $hw")
    println(s"Attempt: $att")
    println(s"Redeemed: $red")
    println()
  }

  /** Concurrency: Eru.zipPar is the parallel tuple (parTupled) equivalent. */
  private def concurrencyExample(): Unit = {
    println("3. Concurrency")

    println("Cats Effect Style:")
    println("""
      |// Cats Effect (IO)
      |val forked = task.start
      |val parallel = (task1, task2).parTupled
      |val raced = IO.race(task1, task2)
      |val timeout = task.timeout(5.seconds)
    """.stripMargin)

    println("Eru Equivalent:")
    val task = Eru.effect { Thread.sleep(50); "completed" }
    val task1 = Eru.effect { Thread.sleep(30); "first" }
    val task2 = Eru.effect { Thread.sleep(40); "second" }

    val concurrencyDemo = for {
      fiber <- task.fork
      result <- fiber.await.flatMap(exit => Eru.fromExit(exit).recover(_ => "failed"))

      parallel <- task1.zipPar(task2).recover(_ => ("error1", "error2"))

      raceResult <- task1.race(task2).recover(_ => Left("timeout"))

      timeoutResult <- task.timeout(Duration.ofMillis(100)).recover(_ => "timed out")

    } yield (result, parallel, raceResult, timeoutResult)

    val (result, (r1, r2), raceResult, timeoutResult) = concurrencyDemo.unsafeRunSync()
    println(s"Fiber result: $result")
    println(s"Parallel tuple: ($r1, $r2)")
    println(s"Race result: $raceResult")
    println(s"Timeout result: $timeoutResult")
    println()
  }

  private def collectionsExample(): Unit = {
    println("4. Collection Operations")

    println("Cats Effect Style:")
    println("""
      |// Cats Effect (IO)
      |val traversed = list.traverse(item => processItem(item))
      |val parallel = list.parTraverse(item => processItem(item))
      |val sequence = effects.sequence
      |val parSequence = effects.parSequence
    """.stripMargin)

    println("Eru Equivalent:")
    val items = List(1, 2, 3, 4, 5)
    def processItem(item: Int): Eru[String, String] =
      Eru.effect(s"processed-$item").mapError(_.getMessage)

    val effects = items.map(processItem)

    val results = for {
      traversed <- Eru.traverse(items)(processItem).recover(_ => List("error"))

      parallel <- runtime.parTraverse(items)(processItem).recover(_ => List("error"))

      sequenced <- Eru.sequence(effects).recover(_ => List("error"))

      parSequenced <- runtime.parSequence(effects).recover(_ => List("error"))

    } yield (traversed, parallel, sequenced, parSequenced)

    val (traversed, parallel, sequenced, parSequenced) = results.unsafeRunSync()
    println(s"Traverse: $traversed")
    println(s"ParTraverse: $parallel")
    println(s"Sequence: $sequenced")
    println(s"ParSequence: $parSequenced")
    println()
  }

  /** Resource management patterns: ensure/guarantee equivalents for Resource.use and cleanup, and
    * the fork + await + Exit pattern for cancellation.
    */
  private def resourceManagementExample(): Unit = {
    println("5. Resource Management")

    println("Cats Effect Style:")
    println("""
      |// Cats Effect (IO)
      |val resource = Resource.make(acquire)(release)
      |val usage = resource.use(res => useResource(res))
      |val ensuring = task.guarantee(cleanup)
      |val onCancel = task.onCancel(cancelHandler)
    """.stripMargin)

    println("Eru Equivalent:")
    case class Resource(name: String) {
      def close(): Unit = println(s"Closing resource: $name")
    }

    def acquire: Eru[String, Resource] =
      Eru.effect(Resource("file-handle")).mapError(_.getMessage)

    def useResource(r: Resource): Eru[String, String] =
      Eru.effect(s"Read data from ${r.name}").mapError(_.getMessage)

    val resourceUsage = for {
      resource <- acquire
      result <- useResource(resource).ensure(Eru.effect(resource.close()).mapError(_ => ()))
    } yield result

    val cleanup = Eru.effect(println("Cleanup guaranteed")).mapError(_ => ())
    val ensuring = Eru.succeed("main work").ensure(cleanup)

    val cancellableTask = Eru.effect {
      Thread.sleep(100)
      "completed"
    }.mapError(_.getMessage)

    val fiber = cancellableTask.fork.unsafeRunSync()
    val cancelResult = fiber.await.unsafeRunSync() match {
      case Exit.Interrupt(_, cause) => s"Cancelled: $cause"
      case Exit.Success(value) => s"Completed: $value"
      case other => s"Other: $other"
    }

    val results = for {
      resourceResult <- resourceUsage.recover(error => s"Resource error: $error")
      ensuringResult <- ensuring
    } yield (resourceResult, ensuringResult, cancelResult)

    val (resourceResult, ensuringResult, cancelResultValue) = results.unsafeRunSync()
    println(s"Resource usage: $resourceResult")
    println(s"Ensuring result: $ensuringResult")
    println(s"Cancel handler result: $cancelResultValue")
    println()
  }

  private def stateExample(): Unit = {
    println("6. State Management")

    println("Cats Effect Style:")
    println("""
      |// Cats Effect (IO)
      |val ref = Ref[IO].of(0)
      |val updated = ref.flatMap(_.updateAndGet(_ + 1))
      |val deferred = Deferred[IO, String]
      |val completed = deferred.complete("done")
    """.stripMargin)

    println("Eru Ref:")

    val stateDemo = for {
      ref <- Eru.ref(0)
      initial <- ref.get
      incremented <- ref.update(_ + 1)
      doubled <- ref.update(_ * 2)
      finalValue <- ref.get
    } yield (initial, incremented, doubled, finalValue)

    val (initial, incremented, doubled, finalVal) = stateDemo.unsafeRunSync()

    println(s"Initial: $initial")
    println(s"After increment: $incremented")
    println(s"After doubling: $doubled")
    println(s"Final: $finalVal")
    println()
  }

  private def streamingExample(): Unit = {
    println("7. Streaming (Conceptual)")

    println("Cats Effect Style:")
    println("""
      |// Cats Effect + FS2
      |val stream = Stream.range(1, 10).evalMap(i => processItem(i))
      |val result = stream.compile.toList
    """.stripMargin)

    println("Eru Equivalent (manual streaming simulation):")

    def processStream(start: Int, end: Int): Eru[String, List[String]] = {
      val range = (start until end).toList
      Eru.traverse(range) { i =>
        Eru.effect(s"item-$i").mapError(_.getMessage)
      }
    }

    def processInChunks[A, B](
      items: List[A],
      chunkSize: Int
    )(
      processor: A => Eru[String, B]
    ): Eru[String, List[B]] = {
      val chunks = items.grouped(chunkSize).toList
      Eru
        .traverse(chunks) { chunk =>
          Eru.traverse(chunk)(processor)
        }
        .map(_.flatten)
    }

    val results = for {
      stream <- processStream(1, 10)
      chunked <- processInChunks(List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 3) { i =>
        Eru.effect(s"chunked-$i").mapError(_.getMessage)
      }
    } yield (stream, chunked)

    val (streamResult, chunkedResult) = results.unsafeRunSync()
    println(s"Stream processing: $streamResult")
    println(s"Chunked processing: $chunkedResult")
    println()
  }

  private def migrationSummary(): Unit = {
    println("=== Migration Summary ===")
    println("""
      |Key Differences:
      |
      |1. Construction:
      |   Cats Effect: IO.pure, IO.raiseError, IO(effect), IO.blocking
      |   Eru: Eru.succeed, Eru.fail, Eru.effect, Eru.blocking
      |
      |2. Error Handling:
      |   Cats Effect: .handleError, .handleErrorWith, .attempt, .redeem
      |   Eru: .recover, .recoverWith, .attempt, custom redeem function
      |
      |3. Concurrency:
      |   Cats Effect: .start, .parTupled, IO.race, .timeout
      |   Eru: .fork, .zipPar, .race, .timeout
      |
      |4. Resources:
      |   Cats Effect: Resource.make/use, .guarantee, .onCancel
      |   Eru: .bracket, .ensure (guarantee)
      |
      |5. Collections:
      |   Cats Effect: .traverse, .parTraverse, .sequence, .parSequence
      |   Eru: Eru.traverse, runtime.parTraverse, Eru.sequence, runtime.parSequence
      |
      |6. State:
      |   Cats Effect: Ref[IO], Deferred[IO]
      |   Eru: Eru.ref, Eru.deferred
      |
      |7. Execution:
      |   Cats Effect: .unsafeRunSync() (with IORuntime)
      |   Eru: .unsafeRunSync()
      |
      |Key Advantages of Eru:
      |✅ Virtual Thread-native concurrency
      |✅ Typed errors by default
      |✅ Stack-safe by design
    """.stripMargin)
  }

  basicEffectsExample()
  errorHandlingExample()
  concurrencyExample()
  collectionsExample()
  resourceManagementExample()
  stateExample()
  streamingExample()
  migrationSummary()

  println("=== Cats Effect to Eru Migration Guide Complete ===")
}
