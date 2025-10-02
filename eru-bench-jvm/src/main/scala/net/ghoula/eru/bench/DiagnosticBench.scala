package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import java.util.concurrent.{CompletableFuture, CountDownLatch}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

import net.ghoula.eru.prelude.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
class DiagnosticBench {

  private val runtime: EruRuntime = EruRuntime.shared

  // Just create and start 5 virtual threads with minimal work
  @Benchmark
  def rawVirtualThreads(): Unit = {
    val latch = new CountDownLatch(5)
    for (_ <- 1 to 5) {
      Thread.startVirtualThread { () =>
        Thread.sleep(1)
        latch.countDown()
      }
    }
    latch.await()
  }

  // Eru's fork with minimal work
  @Benchmark
  def eruFork(): List[Int] = {
    val effects = List(
      runtime.fork(Eru.effect { Thread.sleep(1); 1 }),
      runtime.fork(Eru.effect { Thread.sleep(1); 2 }),
      runtime.fork(Eru.effect { Thread.sleep(1); 3 }),
      runtime.fork(Eru.effect { Thread.sleep(1); 4 }),
      runtime.fork(Eru.effect { Thread.sleep(1); 5 })
    )

    val fibers = Eru.sequence(effects).unsafeRunSync()
    fibers.map(_.await.unsafeRunSync() match {
      case Exit.Success(v) => v
      case _ => 0
    })
  }

  // Eru's parSequence
  @Benchmark
  def eruParSequence(): List[Int] = {
    val effects = List(
      Eru.effect { Thread.sleep(1); 1 },
      Eru.effect { Thread.sleep(1); 2 },
      Eru.effect { Thread.sleep(1); 3 },
      Eru.effect { Thread.sleep(1); 4 },
      Eru.effect { Thread.sleep(1); 5 }
    )
    runtime.parSequence(effects).unsafeRunSync()
  }

  // CompletableFuture with virtual threads
  @Benchmark
  def completableFutureVT(): List[Int] = {
    import java.util.concurrent.Executors
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    try {
      val futures = (1 to 5).map { i =>
        CompletableFuture.supplyAsync(
          () => {
            Thread.sleep(1)
            Integer.valueOf(i)
          },
          executor
        )
      }.toList

      futures.map(_.get().intValue())
    } finally {
      executor.shutdown()
    }
  }

  // Scala Future with virtual thread executor
  @Benchmark
  def scalaFutureVT(): List[Int] = {
    implicit val ec: ExecutionContext = new ExecutionContext {
      def execute(runnable: Runnable): Unit =
        Thread.startVirtualThread(runnable)
      def reportFailure(cause: Throwable): Unit =
        cause.printStackTrace()
    }

    val futures = (1 to 5).map { i =>
      Future {
        Thread.sleep(1)
        i
      }
    }.toList

    Await.result(Future.sequence(futures), 10.seconds)
  }
}
