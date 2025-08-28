package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

final class ConcurrencySpec extends FunSuite {
  test("zipPar combines independent effects") {
    val e = Eru.succeed(21).zipPar(Eru.succeed(2)).map(_ * _)
    assertEquals(e.runExit(), Exit.Success(42))
  }

  test("race returns first result") {
    val slow = Eru.blocking(Thread.sleep(50)).map(_ => "slow")
    val fast = Eru.succeed("fast")
    val raced = fast.race(slow)
    val exit = raced.runExit()
    exit match {
      case Exit.Success(Left(value)) => assertEquals(value, "fast")
      case Exit.Success(Right(value)) => assertEquals(value, "fast")
      case _ => fail("expected success")
    }
  }

  test("Deferred coordinates fibers and Ref holds state") {
    val program = for {
      d <- Eru.deferred[Int]
      f <- Eru.succeed(42).fork
      _ <- Eru.blocking(Thread.sleep(10))
      _ <- d.complete(99)
      v <- d.poll.map(_.getOrElse(-1))
      x <- f.await.flatMap {
        case Exit.Success(a) => Eru.succeed(a)
        case _ => Eru.succeed(-1)
      }
    } yield (v, x)
    val ex = program.runExit()
    ex match {
      case Exit.Success((v, x)) =>
        assertEquals(v, 99)
        assertEquals(x, 42)
      case other => fail(s"expected success, got $other")
    }

    val refProg = for {
      ref <- Eru.ref(0)
      _ <- ref.update(_ + 1).fork
      _ <- ref.update(_ + 1).fork
      _ <- Eru.blocking(Thread.sleep(10))
      v <- ref.get
    } yield v
    assertEquals(refProg.runExit(), Exit.Success(2))
  }
}
