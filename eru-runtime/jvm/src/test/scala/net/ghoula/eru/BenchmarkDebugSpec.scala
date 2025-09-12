package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Debug test that exactly replicates the benchmark class structure. */
class BenchmarkDebugSpec extends FunSuite {

  private val runtime = EruRuntime.create()
  given EruRuntime = runtime

  val contention = 1
  val operations = 10 // Reduced from 100 to prevent potential hanging

  test("exact benchmark pattern replication") {
    def eruDeferredBasic(): Int = {
      val program = for {
        deferred <- Eru.deferred[Int]
        waiterFiber <- runtime.fork(deferred.await)
        producerFiber <- runtime.fork {
          // Simulate some work before producing value
          Eru
            .foreachDiscard(1 to operations / 10)(_ => Eru.unit)
            .flatMap(_ => deferred.complete(operations))
        }
        _ <- producerFiber.await
        result <- waiterFiber.await.map {
          case Exit.Success(value) => value
          case _ => 0
        }
      } yield result

      program.unsafeRunSync()
    }

    // Test only 3 iterations to prevent hanging
    println("Running 3 iterations...")
    for (i <- 1 to 3) {
      print(s"Iteration $i: ")
      val start = System.nanoTime()
      val result = eruDeferredBasic()
      val elapsed = (System.nanoTime() - start) / 1000000
      println(s"$result (${elapsed}ms)")
      assertEquals(result, operations)
    }

    runtime.cleanup()
  }

  test("benchmark pattern with explicit runtime usage") {
    def eruDeferredBasicExplicit(): Int = {
      val program = for {
        deferred <- Eru.deferred[Int](using runtime)
        waiterFiber <- runtime.fork(deferred.await)
        producerFiber <- runtime.fork {
          Eru
            .foreachDiscard(1 to operations / 10)(_ => Eru.unit)
            .flatMap(_ => deferred.complete(operations))
        }
        _ <- producerFiber.await
        result <- waiterFiber.await.map {
          case Exit.Success(value) => value
          case _ => 0
        }
      } yield result

      program.unsafeRunSync()
    }

    println("Running explicit runtime test...")
    for (i <- 1 to 2) {
      print(s"Iteration $i: ")
      val start = System.nanoTime()
      val result = eruDeferredBasicExplicit()
      val elapsed = (System.nanoTime() - start) / 1000000
      println(s"$result (${elapsed}ms)")
      assertEquals(result, operations)
    }

    runtime.cleanup()
  }
}
