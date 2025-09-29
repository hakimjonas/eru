package net.ghoula.eru.bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.*
import zio.{Unsafe, ZIO}

import net.ghoula.eru.prelude.*

object TimingTest {
  def main(args: Array[String]): Unit = {
    val runtime = EruRuntime.shared

    def testSleepDuration(sleepMs: Long): Unit = {
      println(s"\nTesting with ${sleepMs}ms sleeps (5 parallel tasks):")
      println(s"Ideal parallel time: ~${sleepMs}ms, Sequential would be: ~${sleepMs * 5}ms")

      // Eru
      val eruStart = System.currentTimeMillis()
      val eruEffects = List.fill(5)(Eru.effect { Thread.sleep(sleepMs); 1 })
      runtime.parSequence(eruEffects).unsafeRunSync()
      val eruTime = System.currentTimeMillis() - eruStart

      // ZIO
      val zioStart = System.currentTimeMillis()
      val zioEffects = List.fill(5)(ZIO.attempt { Thread.sleep(sleepMs); 1 })
      Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(ZIO.collectAllPar(zioEffects)).getOrThrowFiberFailure()
      }
      val zioTime = System.currentTimeMillis() - zioStart

      // Cats Effect
      val catsStart = System.currentTimeMillis()
      val catsEffects = List.fill(5)(IO { Thread.sleep(sleepMs); 1 })
      catsEffects.parSequence.unsafeRunSync()
      val catsTime = System.currentTimeMillis() - catsStart

      println(f"Eru:  ${eruTime}%4dms (${eruTime.toDouble / sleepMs}%.1fx ideal)")
      println(f"ZIO:  ${zioTime}%4dms (${zioTime.toDouble / sleepMs}%.1fx ideal)")
      println(f"Cats: ${catsTime}%4dms (${catsTime.toDouble / sleepMs}%.1fx ideal)")
    }

    println("Testing parallelism at different work granularities...")
    testSleepDuration(1)
    testSleepDuration(10)
    testSleepDuration(50)
    testSleepDuration(100)
  }
}
