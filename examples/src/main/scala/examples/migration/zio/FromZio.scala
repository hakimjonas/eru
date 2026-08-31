/** Migration Guide: From ZIO to Eru
  *
  * This example demonstrates how to migrate common ZIO patterns to Eru, highlighting similarities
  * and differences between the two effect systems.
  */
package examples.migration.zio

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import java.time.Duration

/** Runtime context required for concurrency operations. */
given runtime: EruRuntime = EruRuntime.shared

object FromZio {

  println("=== Migration Guide: ZIO to Eru ===\n")

  private def basicEffectsExample(): Unit = {
    println("1. Basic Effects")

    println("ZIO Style:")
    println("""
      |// ZIO
      |val succeed = ZIO.succeed(42)
      |val fail = ZIO.fail("error")
      |val effect = ZIO.attempt(println("Hello"))
    """.stripMargin)

    println("Eru Equivalent:")
    val succeed = Eru.succeed(42)
    val fail = Eru.fail("error")
    val effect = Eru.effect(println("Hello"))

    println(s"Succeed result: ${succeed.unsafeRunSync()}")
    println(s"Fail attempt: ${fail.attempt.unsafeRunSync()}")
    effect.unsafeRunSync()
    println()
  }

  /** Error handling: Eru.recover is catchSome-style, so only matching errors are recovered and a
    * non-match leaves the error intact.
    */
  private def errorHandlingExample(): Unit = {
    println("2. Error Handling")

    println("ZIO Style:")
    println("""
      |// ZIO
      |val recovery = failingTask.catchAll(error => ZIO.succeed(s"Recovered: $error"))
      |val partial = failingTask.catchSome { case "specific" => ZIO.succeed("handled") }
      |val either = failingTask.either
    """.stripMargin)

    println("Eru Equivalent:")
    val failingTask: Eru[String, Int] = Eru.fail("something went wrong")

    val recovery = failingTask.recover { case error => s"Recovered: $error" }
    val partial = failingTask.recover { case "specific" => "handled" }
    val either = failingTask.attempt

    println(s"Recovery: ${recovery.unsafeRunSync()}")
    println(s"Partial (no match, error preserved): ${partial.attempt.unsafeRunSync()}")
    println(s"Either: ${either.unsafeRunSync()}")
    println()
  }

  private def transformationsExample(): Unit = {
    println("3. Transformations")

    println("ZIO Style:")
    println("""
      |// ZIO
      |val mapped = task.map(_ * 2)
      |val flatMapped = task.flatMap(x => ZIO.succeed(x.toString))
      |val zipped = task1.zip(task2)
    """.stripMargin)

    println("Eru Equivalent:")
    val task = Eru.succeed(21)
    val task1 = Eru.succeed("hello")
    val task2 = Eru.succeed(42)

    val mapped = task.map(_ * 2)
    val flatMapped = task.flatMap(x => Eru.succeed(x.toString))
    val zipped = task1.zip(task2)

    println(s"Mapped: ${mapped.unsafeRunSync()}")
    println(s"FlatMapped: ${flatMapped.unsafeRunSync()}")
    println(s"Zipped: ${zipped.unsafeRunSync()}")
    println()
  }

  private def concurrencyExample(): Unit = {
    println("4. Concurrency")

    println("ZIO Style:")
    println("""
      |// ZIO
      |val forked = task.fork
      |val parallel = task1.zipPar(task2)
      |val raced = task1.race(task2)
      |val timeout = task.timeout(Duration.ofSeconds(5))
    """.stripMargin)

    println("Eru Equivalent:")
    val task = Eru.effect { Thread.sleep(100); "completed" }
    val task1 = Eru.effect { Thread.sleep(50); "first" }
    val task2 = Eru.effect { Thread.sleep(75); "second" }

    val parallelDemo = for {
      fiber <- task.fork
      result <- fiber.await.flatMap(exit => Eru.fromExit(exit).recover(_ => "failed"))

      (r1, r2) <- task1.zipPar(task2).recover(_ => ("error", "error"))

      raceResult <- task1.race(task2).recover(_ => Left("timeout"))

      timeoutResult <- task.timeout(Duration.ofMillis(200)).recover(_ => "timed out")

    } yield (result, r1, r2, raceResult, timeoutResult)

    val results = parallelDemo.unsafeRunSync()
    println(s"Fiber result: ${results._1}")
    println(s"Parallel: (${results._2}, ${results._3})")
    println(s"Race winner: ${results._4}")
    println(s"Timeout result: ${results._5}")
    println()
  }

  private def collectionsExample(): Unit = {
    println("5. Collection Operations")

    println("ZIO Style:")
    println("""
      |// ZIO
      |val traversed = ZIO.foreach(list)(item => processItem(item))
      |val parallel = ZIO.foreachPar(list)(item => processItem(item))
      |val parallelN = ZIO.foreachParN(4)(list)(item => processItem(item))
    """.stripMargin)

    println("Eru Equivalent:")
    val items = List(1, 2, 3, 4, 5)
    def processItem(item: Int): Eru[String, String] =
      Eru.effect(s"processed-$item").mapError(_.getMessage)

    val traversed = Eru.traverse(items)(processItem)
    val parallel = runtime.parTraverse(items)(processItem)
    val parallelN = runtime.foreachParN(2, items)(processItem)

    val results = for {
      seq <- traversed.recover(_ => List("error"))
      par <- parallel.recover(_ => List("error"))
      parN <- parallelN.recover(_ => List("error"))
    } yield (seq, par, parN)

    val (seq, par, parN) = results.unsafeRunSync()
    println(s"Sequential: $seq")
    println(s"Parallel: $par")
    println(s"ParallelN(2): $parN")
    println()
  }

  private def resourceManagementExample(): Unit = {
    println("6. Resource Management")

    println("ZIO Style:")
    println("""
      |// ZIO
      |val managed = ZManaged.make(acquire)(release)
      |val scoped = ZIO.scoped(managed.use(resource => useResource(resource)))
      |val ensuring = task.ensuring(cleanup)
    """.stripMargin)

    println("Eru Equivalent:")
    case class Resource(name: String) {
      def close(): Unit = println(s"Closing resource: $name")
    }

    def acquire: Eru[String, Resource] =
      Eru.effect(Resource("database")).mapError(_.getMessage)

    def useResource(r: Resource): Eru[String, String] =
      Eru.effect(s"Used ${r.name}").mapError(_.getMessage)

    val resourceUsage = for {
      resource <- acquire
      result <- useResource(resource).ensure(Eru.effect(resource.close()).mapError(_ => ()))
    } yield result

    val cleanup = Eru.effect(println("Cleanup executed")).mapError(_ => ())
    val ensuring = Eru.succeed("main task").ensure(cleanup)

    val results = for {
      resourceResult <- resourceUsage.recover(error => s"Resource error: $error")
      ensuringResult <- ensuring
    } yield (resourceResult, ensuringResult)

    val (resourceResult, ensuringResult) = results.unsafeRunSync()
    println(s"Resource usage: $resourceResult")
    println(s"Ensuring result: $ensuringResult")
    println()
  }

  private def iterationExample(): Unit = {
    println("7. Iteration and Loops")

    println("ZIO Style:")
    println("""
      |// ZIO
      |val iterated = ZIO.iterate(0)(_ < 5)(i => ZIO.succeed(i + 1))
      |val repeated = ZIO.repeat(task)(Schedule.recurs(3))
      |val whileLoop = ZIO.whileM(condition)(body)
    """.stripMargin)

    println("Eru Equivalent:")
    val iterated = Eru.iterate(0)(i => Eru.succeed(i + 1))(_ >= 5)
    val repeated = Eru.iterateN(0, 3)(i => Eru.effect { println(s"Iteration $i"); i + 1 })

    var counter = 0
    def condition: Eru[Throwable, Boolean] = Eru.effect(counter < 3)
    def body: Eru[Throwable, Unit] = Eru.effect { counter += 1; println(s"Body executed: $counter") }

    def whileLoop: Eru[Throwable, Unit] = {
      condition.flatMap { cond =>
        if (cond) body.flatMap(_ => whileLoop)
        else Eru.succeed(())
      }
    }

    val results = for {
      iter <- iterated
      rep <- repeated
      _ <- whileLoop
    } yield (iter, rep)

    val (iterResult, repResult) = results.unsafeRunSync()
    println(s"Iteration result: $iterResult")
    println(s"Repetition result: $repResult")
    println("While loop completed")
    println()
  }

  private def migrationSummary(): Unit = {
    println("=== Migration Summary ===")
    println("""
      |Key Differences:
      |
      |1. Construction:
      |   ZIO: ZIO.succeed, ZIO.fail, ZIO.attempt
      |   Eru: Eru.succeed, Eru.fail, Eru.effect
      |
      |2. Error Handling:
      |   ZIO: .catchAll, .catchSome, .either
      |   Eru: .recover, .recoverWith, .attempt
      |
      |3. Concurrency:
      |   ZIO: .fork, .zipPar, .race, implicit runtime
      |   Eru: .fork, .zipPar, .race, runtime.* methods
      |
      |4. Resources:
      |   ZIO: ZManaged/ZIO.scoped
      |   Eru: .ensure, resource bracket pattern
      |
      |5. Collections:
      |   ZIO: ZIO.foreach*, ZIO.foreachPar*
      |   Eru: Eru.traverse, runtime.parTraverse
      |
      |6. Execution:
      |   ZIO: Unsafe.unsafe(_.run(effect))
      |   Eru: effect.unsafeRunSync()
      |
      |Common Patterns Work Similarly:
      |✅ map, flatMap, zip combinators
      |✅ Error handling with typed errors
      |✅ Concurrent execution with fibers
      |✅ Resource safety and cleanup
      |✅ Stack-safe iteration
    """.stripMargin)
  }

  basicEffectsExample()
  errorHandlingExample()
  transformationsExample()
  concurrencyExample()
  collectionsExample()
  resourceManagementExample()
  iterationExample()
  migrationSummary()

  println("=== ZIO to Eru Migration Guide Complete ===")
}
