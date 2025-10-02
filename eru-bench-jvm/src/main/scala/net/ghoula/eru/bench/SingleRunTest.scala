package net.ghoula.eru.bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.*
import zio.{Unsafe, ZIO}

import net.ghoula.eru.prelude.*

object SingleRunTest {
  def main(args: Array[String]): Unit = {
    val runtime = EruRuntime.shared
    val iterations = 1000 // Similar to JMH

    println(s"Running $iterations iterations with 1ms sleeps...")

    // Warm up
    println("\nWarmup...")
    for (_ <- 1 to 100) {
      runtime.parSequence(List.fill(5)(Eru.effect { Thread.sleep(1); 1 })).unsafeRunSync()
    }

    // Eru
    println("\nEru parSequence:")
    val eruStart = System.nanoTime()
    for (_ <- 1 to iterations) {
      val effects = List.fill(5)(Eru.effect { Thread.sleep(1); 1 })
      runtime.parSequence(effects).unsafeRunSync()
    }
    val eruTime = (System.nanoTime() - eruStart) / 1_000_000
    val eruOpsPerMs = iterations * 1000.0 / eruTime
    println(f"  Total: ${eruTime}ms, Throughput: ${eruOpsPerMs}%.2f ops/ms")

    // ZIO
    println("\nZIO collectAllPar:")
    val zioStart = System.nanoTime()
    for (_ <- 1 to iterations) {
      val effects = List.fill(5)(ZIO.attempt { Thread.sleep(1); 1 })
      Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(ZIO.collectAllPar(effects)).getOrThrowFiberFailure()
      }
    }
    val zioTime = (System.nanoTime() - zioStart) / 1_000_000
    val zioOpsPerMs = iterations * 1000.0 / zioTime
    println(f"  Total: ${zioTime}ms, Throughput: ${zioOpsPerMs}%.2f ops/ms")

    // Cats
    println("\nCats parSequence:")
    val catsStart = System.nanoTime()
    for (_ <- 1 to iterations) {
      val effects = List.fill(5)(IO { Thread.sleep(1); 1 })
      effects.parSequence.unsafeRunSync()
    }
    val catsTime = (System.nanoTime() - catsStart) / 1_000_000
    val catsOpsPerMs = iterations * 1000.0 / catsTime
    println(f"  Total: ${catsTime}ms, Throughput: ${catsOpsPerMs}%.2f ops/ms")

    println("\nNote: These should match JMH benchmark results")
  }
}
