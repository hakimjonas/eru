package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

final class RefMapSpec extends EruTestSuite {

  test("get returns None for missing key") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      v <- rm.get("missing")
    } yield v).unsafeRunSync()
    assertEquals(result, None)
  }

  test("put and get round-trip") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      old <- rm.put("a", 1)
      v <- rm.get("a")
    } yield (old, v)).unsafeRunSync()
    assertEquals(result, (None, Some(1)))
  }

  test("put returns previous value") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      _ <- rm.put("a", 1)
      prev <- rm.put("a", 2)
      v <- rm.get("a")
    } yield (prev, v)).unsafeRunSync()
    assertEquals(result, (Some(1), Some(2)))
  }

  test("update modifies existing key") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      _ <- rm.put("a", 10)
      upd <- rm.update("a")(_ + 5)
      v <- rm.get("a")
    } yield (upd, v)).unsafeRunSync()
    assertEquals(result, (Some(15), Some(15)))
  }

  test("update returns None for missing key") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      upd <- rm.update("missing")(_ + 1)
    } yield upd).unsafeRunSync()
    assertEquals(result, None)
  }

  test("modify returns auxiliary value") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      _ <- rm.put("a", 10)
      b <- rm.modify("a")(v => (v * 2, s"was $v"))
      v <- rm.get("a")
    } yield (b, v)).unsafeRunSync()
    assertEquals(result, (Some("was 10"), Some(20)))
  }

  test("remove deletes key and returns previous") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      _ <- rm.put("a", 42)
      prev <- rm.remove("a")
      v <- rm.get("a")
    } yield (prev, v)).unsafeRunSync()
    assertEquals(result, (Some(42), None))
  }

  test("remove returns None for missing key") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      prev <- rm.remove("missing")
    } yield prev).unsafeRunSync()
    assertEquals(result, None)
  }

  test("getOrElse returns default for missing key") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      v <- rm.getOrElse("missing", 99)
    } yield v).unsafeRunSync()
    assertEquals(result, 99)
  }

  test("getOrElse returns existing value") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      _ <- rm.put("a", 42)
      v <- rm.getOrElse("a", 99)
    } yield v).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("updateOrCreate creates when absent") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      v <- rm.updateOrCreate("a", 10)(_ + 1)
    } yield v).unsafeRunSync()
    assertEquals(result, 10)
  }

  test("updateOrCreate updates when present") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      _ <- rm.put("a", 10)
      v <- rm.updateOrCreate("a", 0)(_ + 5)
    } yield v).unsafeRunSync()
    assertEquals(result, 15)
  }

  test("keys returns all keys") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      _ <- rm.put("a", 1)
      _ <- rm.put("b", 2)
      _ <- rm.put("c", 3)
      ks <- rm.keys
    } yield ks).unsafeRunSync()
    assertEquals(result, Set("a", "b", "c"))
  }

  test("size tracks entries") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      s0 <- rm.size
      _ <- rm.put("a", 1)
      _ <- rm.put("b", 2)
      s2 <- rm.size
      _ <- rm.remove("a")
      s1 <- rm.size
    } yield (s0, s2, s1)).unsafeRunSync()
    assertEquals(result, (0, 2, 1))
  }

  test("toMap returns snapshot") {
    val result = (for {
      rm <- RefMap.make[String, Int]
      _ <- rm.put("x", 10)
      _ <- rm.put("y", 20)
      m <- rm.toMap
    } yield m).unsafeRunSync()
    assertEquals(result, Map("x" -> 10, "y" -> 20))
  }

  test("from creates pre-populated map") {
    val result = (for {
      rm <- RefMap.from(List("a" -> 1, "b" -> 2, "c" -> 3))
      m <- rm.toMap
    } yield m).unsafeRunSync()
    assertEquals(result, Map("a" -> 1, "b" -> 2, "c" -> 3))
  }

  test("concurrent updates to different keys don't interfere") {
    val n = 1000
    val result = (for {
      rm <- RefMap.make[Int, Int]
      _ <- Eru.foreachDiscard(0 until n)(i => rm.put(i, 0))
      fibers <- Eru.foreach(0 until n) { i =>
        (for {
          _ <- rm.update(i)(_ + 1)
          _ <- rm.update(i)(_ + 1)
          _ <- rm.update(i)(_ + 1)
        } yield ()).fork
      }
      _ <- Eru.foreachDiscard(fibers)(_.await)
      m <- rm.toMap
    } yield m).unsafeRunSync()
    // Each key should have been incremented exactly 3 times
    assertEquals(result.size, n)
    result.values.foreach(v => assertEquals(v, 3))
  }
}
