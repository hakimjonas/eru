package net.ghoula.eru.bench.fair

import org.openjdk.jmh.annotations.*
import zio.{Runtime as ZRuntime, Unsafe, ZIO}

import java.util.concurrent.TimeUnit

import net.ghoula.eru.*

/** Benchmarks comparing different approaches for common patterns. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class PatternBench extends FairBenchmarkBase {

  private given eruRuntime: EruRuntime = EruRuntime.create()

  import scala.compiletime.uninitialized
  private var eruQueue: Queue[Int] = uninitialized
  private var zioQueue: zio.Queue[Int] = uninitialized

  @Setup
  def setup(): Unit = {
    eruQueue = Queue.bounded[Int](100).unsafeRunSync()
    zioQueue = Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(zio.Queue.bounded[Int](100)).getOrThrowFiberFailure()
    }
  }

  // ===== Offer-Then-Take Pattern =====

  @Benchmark
  def eruOfferThenTake(): Int = {
    val program = for {
      _ <- eruQueue.put(42).eru
      result <- eruQueue.take.eru
    } yield result
    program.unsafeRunSync()
  }

  @Benchmark
  def zioOfferThenTake(): Int = {
    val program = for {
      _ <- zioQueue.offer(42)
      result <- zioQueue.take
    } yield result
    Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  // ===== Drain Available Pattern =====

  @Benchmark
  def eruDrainLoop(): List[Int] = {
    // Pre-fill queue
    (1 to 10)
      .foldLeft(Eru.unit) { (acc, i) =>
        acc.flatMap(_ => eruQueue.tryPut(i).eru.map(_ => ()))
      }
      .unsafeRunSync()

    def loop(acc: List[Int]): Eru[Nothing, List[Int]] = {
      eruQueue.tryTake.eru.flatMap {
        case Some(elem) => loop(elem :: acc)
        case None => Eru.succeed(acc.reverse)
      }
    }

    loop(Nil).unsafeRunSync()
  }

  @Benchmark
  def zioDrain(): List[Int] = {
    // Pre-fill queue
    Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe
        .run(
          ZIO.foreach(1 to 10)(i => zioQueue.offer(i))
        )
        .getOrThrowFiberFailure()
    }

    def loop(acc: List[Int]): ZIO[Any, Nothing, List[Int]] = {
      zioQueue.poll.flatMap {
        case Some(elem) => loop(elem :: acc)
        case None => ZIO.succeed(acc.reverse)
      }
    }

    Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(loop(Nil)).getOrThrowFiberFailure()
    }
  }

  // ===== Take-Or-Else Pattern =====

  @Benchmark
  def eruTakeWithDefault(): Int = {
    val program = eruQueue.tryTake.eru.map(_.getOrElse(99))
    program.unsafeRunSync()
  }

  @Benchmark
  def zioTakeWithDefault(): Int = {
    val program = zioQueue.poll.map(_.getOrElse(99))
    Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  // ===== Batch Offer-Take Pattern =====

  @Benchmark
  def eruBatchOfferTake(): List[Int] = {
    val values = List(1, 2, 3, 4, 5)
    val program = for {
      _ <- eruQueue.putAll(values).eru
      result <- eruQueue.takeUpTo(values.length).eru
    } yield result
    program.unsafeRunSync()
  }

  @Benchmark
  def zioBatchOfferTake(): List[Int] = {
    val values = List(1, 2, 3, 4, 5)
    val program = for {
      _ <- ZIO.foreach(values)(zioQueue.offer)
      result <- ZIO.collectAll(List.fill(values.length)(zioQueue.take))
    } yield result
    Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  // ===== Ref Batch Update Pattern =====

  private var eruRef: Ref[Int] = uninitialized
  private var zioRef: zio.Ref[Int] = uninitialized

  @Setup(Level.Trial)
  def setupRefs(): Unit = {
    eruRef = Ref.make(0).unsafeRunSync()
    zioRef = Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(zio.Ref.make(0)).getOrThrowFiberFailure()
    }
  }

  @Benchmark
  def eruRefBatchUpdate(): Int = {
    val updates = List[Int => Int](
      _ + 1,
      _ * 2,
      _ + 3,
      _ / 2,
      _ + 5
    )
    eruRef.updateMany(updates*).unsafeRunSync()
  }

  @Benchmark
  def eruRefChainedUpdate(): Int = {
    val updates = List[Int => Int](
      _ + 1,
      _ * 2,
      _ + 3,
      _ / 2,
      _ + 5
    )
    // Chain individual updates for comparison
    val program = updates.foldLeft(Eru.succeed(0)) { (acc, f) =>
      acc.flatMap(_ => eruRef.update(f))
    }
    program.unsafeRunSync()
  }

  @Benchmark
  def zioRefBatchUpdate(): Int = {
    val updates = List[Int => Int](
      _ + 1,
      _ * 2,
      _ + 3,
      _ / 2,
      _ + 5
    )
    val program = updates.foldLeft(ZIO.succeed(0): ZIO[Any, Nothing, Int]) { (acc, f) =>
      acc.flatMap(_ => zioRef.updateAndGet(f))
    }
    Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  // ===== Promise Fast Path =====

  private var eruPromise: Promise[Nothing, Int] = uninitialized
  private var zioPromise: zio.Promise[Nothing, Int] = uninitialized

  @Setup(Level.Invocation)
  def setupPromises(): Unit = {
    eruPromise = Promise.make[Nothing, Int].unsafeRunSync()
    zioPromise = Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(zio.Promise.make[Nothing, Int]).getOrThrowFiberFailure()
    }
  }

  @Benchmark
  def eruPromiseCompletePoll(): Option[Int] = {
    eruPromise.succeed(42).eru.unsafeRunSync()
    eruPromise.poll.eru.unsafeRunSync().map {
      case Exit.Success(value) => value
      case _ => 0
    }
  }

  @Benchmark
  def zioPromiseCompletePoll(): Option[Int] = {
    Unsafe.unsafe { implicit u =>
      ZRuntime.default.unsafe.run(zioPromise.succeed(42)).getOrThrowFiberFailure()
      val maybePromise = ZRuntime.default.unsafe.run(zioPromise.poll).getOrThrowFiberFailure()
      maybePromise.map { promise =>
        ZRuntime.default.unsafe.run(promise).getOrThrowFiberFailure()
      }
    }
  }
}
