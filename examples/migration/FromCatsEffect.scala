/** Migration Guide: From Cats Effect to Eru
  *
  * This example demonstrates how to migrate common Cats Effect patterns to Eru,
  * highlighting similarities and differences between the two effect systems.
  */
package examples.migration

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import scala.util.Random
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

// Runtime context required for concurrency operations
given runtime: EruRuntime = EruRuntime.default

object FromCatsEffect extends App {

  println("=== Migration Guide: Cats Effect to Eru ===\n")

  // ====== BASIC EFFECTS ======

  def basicEffectsExample(): Unit = {
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
    val fail = Eru.fail("error") // Eru prefers typed errors over exceptions
    val effect = Eru.effect(println("Hello"))
    val blocking = Eru.blocking { Thread.sleep(10); "blocking result" }

    println(s"Succeed result: ${succeed.unsafeRunSync()}")
    println(s"Blocking result: ${blocking.unsafeRunSync()}")
    println("Effect executed successfully")
    println()
  }

  // ====== ERROR HANDLING ======

  def errorHandlingExample(): Unit = {
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

    // Redeem equivalent - handle both success and failure
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

  // ====== CONCURRENCY ======

  def concurrencyExample(): Unit = {
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
      // Fork (start) and join
      fiber <- task.fork
      result <- fiber.await.flatMap(exit => Eru.fromExit(exit).recover(_ => "failed"))

      // Parallel tuple (parTupled equivalent)
      parallel <- task1.zipPar(task2).recover(_ => ("error1", "error2"))

      // Race
      raceResult <- task1.race(task2).recover(_ => Left("timeout"))

      // Timeout
      timeoutResult <- task.timeout(Duration.ofMillis(100)).recover(_ => "timed out")

    } yield (result, parallel, raceResult, timeoutResult)

    val (result, (r1, r2), raceResult, timeoutResult) = concurrencyDemo.unsafeRunSync()
    println(s"Fiber result: $result")
    println(s"Parallel tuple: ($r1, $r2)")
    println(s"Race result: $raceResult")
    println(s"Timeout result: $timeoutResult")
    println()
  }

  // ====== COLLECTION OPERATIONS ======

  def collectionsExample(): Unit = {
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
      // Sequential traverse
      traversed <- Eru.traverse(items)(processItem).recover(_ => List("error"))

      // Parallel traverse
      parallel <- EruRuntime.parTraverse(items)(processItem).recover(_ => List("error"))

      // Sequential sequence
      sequenced <- Eru.sequence(effects).recover(_ => List("error"))

      // Parallel sequence
      parSequenced <- EruRuntime.parSequence(effects).recover(_ => List("error"))

    } yield (traversed, parallel, sequenced, parSequenced)

    val (traversed, parallel, sequenced, parSequenced) = results.unsafeRunSync()
    println(s"Traverse: $traversed")
    println(s"ParTraverse: $parallel")
    println(s"Sequence: $sequenced")
    println(s"ParSequence: $parSequenced")
    println()
  }

  // ====== RESOURCE MANAGEMENT ======

  def resourceManagementExample(): Unit = {
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

    // Resource bracket pattern (equivalent to Resource.use)
    val resourceUsage = for {
      resource <- acquire
      result <- useResource(resource).ensure(Eru.effect(resource.close()).mapError(_ => ()))
    } yield result

    // Guarantee equivalent (ensure)
    val cleanup = Eru.effect(println("Cleanup guaranteed")).mapError(_ => ())
    val ensuring = Eru.succeed("main work").ensure(cleanup)

    // onCancel equivalent (interruption handling)
    val cancellableTask = Eru.effect {
      Thread.sleep(100)
      "completed"
    }.mapError(_.getMessage)

    val withCancelHandler = cancellableTask.onExit { exit =>
      exit match {
        case Exit.Interrupt(_, cause) =>
          Eru.effect(println(s"Task was cancelled: $cause")).mapError(_ => ())
        case _ =>
          Eru.succeed(())
      }
    }

    val results = for {
      resourceResult <- resourceUsage.recover(error => s"Resource error: $error")
      ensuringResult <- ensuring
      cancelResult <- withCancelHandler.recover(error => s"Cancelled: $error")
    } yield (resourceResult, ensuringResult, cancelResult)

    val (resourceResult, ensuringResult, cancelResult) = results.unsafeRunSync()
    println(s"Resource usage: $resourceResult")
    println(s"Ensuring result: $ensuringResult")
    println(s"Cancel handler result: $cancelResult")
    println()
  }

  // ====== REF AND STATE ======

  def stateExample(): Unit = {
    println("6. State Management")

    println("Cats Effect Style:")
    println("""
    |// Cats Effect (IO)
    |val ref = Ref[IO].of(0)
    |val updated = ref.flatMap(_.updateAndGet(_ + 1))
    |val deferred = Deferred[IO, String]
    |val completed = deferred.complete("done")
    """.stripMargin)

    println("Eru Equivalent (simulation with AtomicReference):")

    // Simulating Ref with AtomicReference (Eru is cross-platform, no built-in Ref)
    case class EruRef[A](private val ref: AtomicReference[A]) {
      def get: Eru[Nothing, A] = Eru.succeed(ref.get())
      def set(value: A): Eru[Nothing, Unit] = Eru.succeed(ref.set(value))
      def updateAndGet(f: A => A): Eru[Nothing, A] = Eru.succeed(ref.updateAndGet(f(_)))
      def modify[B](f: A => (A, B)): Eru[Nothing, B] = Eru.effect {
        val current = ref.get()
        val (newValue, result) = f(current)
        ref.set(newValue)
        result
      }.mapError(_ => ())
    }

    object EruRef {
      def of[A](initial: A): Eru[Nothing, EruRef[A]] =
        Eru.succeed(EruRef(new AtomicReference(initial)))
    }

    // Usage
    val stateDemo = for {
      ref <- EruRef.of(0)
      initial <- ref.get
      incremented <- ref.updateAndGet(_ + 1)
      doubled <- ref.updateAndGet(_ * 2)
      final <- ref.get
    } yield (initial, incremented, doubled, final)

    val (initial, incremented, doubled, finalValue) = stateDemo.unsafeRunSync()
    println(s"Initial: $initial")
    println(s"After increment: $incremented")
    println(s"After doubling: $doubled")
    println(s"Final: $finalValue")
    println()
  }

  // ====== STREAMING (CONCEPTUAL) ======

  def streamingExample(): Unit = {
    println("7. Streaming (Conceptual)")

    println("Cats Effect Style:")
    println("""
    |// Cats Effect + FS2
    |val stream = Stream.range(1, 10).evalMap(i => processItem(i))
    |val result = stream.compile.toList
    """.stripMargin)

    println("Eru Equivalent (manual streaming simulation):")

    // Simple stream-like processing with Eru
    def processStream(start: Int, end: Int): Eru[String, List[String]] = {
      val range = (start until end).toList
      Eru.traverse(range) { i =>
        Eru.effect(s"item-$i").mapError(_.getMessage)
      }
    }

    // Chunk-based processing
    def processInChunks[A, B](
      items: List[A],
      chunkSize: Int
    )(
      processor: A => Eru[String, B]
    ): Eru[String, List[B]] = {
      val chunks = items.grouped(chunkSize).toList
      Eru.traverse(chunks) { chunk =>
        Eru.traverse(chunk)(processor)
      }.map(_.flatten)
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

  // ====== MIGRATION SUMMARY ======

  def migrationSummary(): Unit = {
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
    |   Eru: .ensure, .onExit, resource bracket pattern
    |
    |5. Collections:
    |   Cats Effect: .traverse, .parTraverse, .sequence, .parSequence
    |   Eru: Eru.traverse, EruRuntime.parTraverse, Eru.sequence, EruRuntime.parSequence
    |
    |6. State:
    |   Cats Effect: Ref[IO], Deferred[IO]
    |   Eru: Manual with AtomicReference (cross-platform)
    |
    |7. Execution:
    |   Cats Effect: .unsafeRunSync() (with IORuntime)
    |   Eru: .unsafeRunSync()
    |
    |Key Advantages of Eru:
    |✅ Cross-platform (JVM + Native)
    |✅ Simpler runtime model
    |✅ Typed errors by default
    |✅ Zero-reflection design
    |✅ Excellent performance characteristics
    |✅ Stack-safe by design
    """.stripMargin)
  }

  // Run all examples
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