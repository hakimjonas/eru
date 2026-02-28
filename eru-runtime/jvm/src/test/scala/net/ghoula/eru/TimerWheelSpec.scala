package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Deterministic tests for Eru.at / Eru.after and the At interpreter case.
  *
  * Uses a mock TimerService that records scheduled tasks and fires them on command, avoiding all
  * wall-clock timing dependencies.
  */
final class TimerWheelSpec extends EruTestSuite {

  /** Mock timer service that records scheduled tasks without any real timer. */
  private final class RecordingTimerService extends TimerService {
    import scala.jdk.CollectionConverters.*
    private val tasks = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Runnable)]()

    def schedule(epochMillis: Long, task: Runnable): Unit =
      tasks.add((epochMillis, task))

    def shutdown(): Unit = ()

    def scheduledCount: Int = tasks.size()

    def scheduledTimes: List[Long] =
      tasks.asScala.map(_._1).toList

    /** Fire all recorded tasks synchronously. */
    def fireAll(): Unit = {
      var entry = Option(tasks.poll())
      while (entry.nonEmpty) {
        entry.foreach(_._2.run())
        entry = Option(tasks.poll())
      }
    }
  }

  /** Run a block with a mock timer service installed, restoring the original afterward. */
  private def withMockTimer[A](mock: RecordingTimerService)(block: => A): A = {
    val saved = TimerService.swap(Some(mock))
    try block
    finally TimerService.swap(saved)
  }

  /** Run a block with no timer service, restoring the original afterward. */
  private def withNoTimer[A](block: => A): A = {
    val saved = TimerService.swap(None)
    try block
    finally TimerService.swap(saved)
  }

  // --- Eru.at contract tests (mock timer) ---

  test("Eru.at returns unit immediately without firing the effect") {
    val mock = new RecordingTimerService()
    var effectRan = false
    val futureMs = System.currentTimeMillis() + 60000L

    withMockTimer(mock) {
      val result = Eru.at(futureMs)(Eru.effectTotal { effectRan = true }).unsafeRunSync()
      assertEquals(result, ())
      assert(!effectRan, "Effect should not run until timer fires it")
      assertEquals(mock.scheduledCount, 1)
    }
  }

  test("Eru.at schedules with the correct epoch millis") {
    val mock = new RecordingTimerService()
    val futureMs = System.currentTimeMillis() + 60000L

    withMockTimer(mock) {
      Eru.at(futureMs)(Eru.unit).unsafeRunSync()
      assertEquals(mock.scheduledTimes, List(futureMs))
    }
  }

  test("Eru.at past-due time clamps to current time") {
    val mock = new RecordingTimerService()
    val beforeMs = System.currentTimeMillis()

    withMockTimer(mock) {
      // Schedule with epoch 0 (far in the past)
      Eru.at(0L)(Eru.unit).unsafeRunSync()

      // Should have been clamped to ~now (not 0)
      val scheduledTime = mock.scheduledTimes.head
      assert(scheduledTime >= beforeMs, s"Past-due should be clamped to now, got $scheduledTime")
    }
  }

  test("Eru.at effect runs when timer fires it") {
    val mock = new RecordingTimerService()
    var effectRan = false
    val futureMs = System.currentTimeMillis() + 60000L

    withMockTimer(mock) {
      Eru.at(futureMs)(Eru.effectTotal { effectRan = true }).unsafeRunSync()
      assert(!effectRan, "Should not run before fire")

      mock.fireAll()
      assert(effectRan, "Should run after timer fires")
    }
  }

  test("Eru.at effect captures computation lazily") {
    val mock = new RecordingTimerService()
    var counter = 0
    val futureMs = System.currentTimeMillis() + 60000L

    withMockTimer(mock) {
      // The computation thunk should be captured, not evaluated
      Eru.at(futureMs)(Eru.effectTotal { counter += 1 }).unsafeRunSync()
      assertEquals(counter, 0)

      mock.fireAll()
      assertEquals(counter, 1)
    }
  }

  test("multiple Eru.at calls schedule independently") {
    val mock = new RecordingTimerService()
    val base = System.currentTimeMillis() + 60000L

    withMockTimer(mock) {
      Eru.at(base + 100L)(Eru.unit).unsafeRunSync()
      Eru.at(base + 200L)(Eru.unit).unsafeRunSync()
      Eru.at(base + 300L)(Eru.unit).unsafeRunSync()

      assertEquals(mock.scheduledCount, 3)
      assertEquals(mock.scheduledTimes, List(base + 100L, base + 200L, base + 300L))
    }
  }

  // --- Eru.after contract tests (mock timer) ---

  test("Eru.after computes target time from delay") {
    val mock = new RecordingTimerService()
    val beforeMs = System.currentTimeMillis()

    withMockTimer(mock) {
      Eru.after(java.time.Duration.ofSeconds(5))(Eru.unit).unsafeRunSync()
    }

    val afterMs = System.currentTimeMillis()
    val scheduled = mock.scheduledTimes.head
    // Target should be ~5000ms in the future from when it ran
    assert(scheduled >= beforeMs + 5000, s"Target $scheduled should be >= ${beforeMs + 5000}")
    assert(scheduled <= afterMs + 5000, s"Target $scheduled should be <= ${afterMs + 5000}")
  }

  // --- Sync kernel fallback tests (no timer service) ---

  test("Eru.at runs inline when no timer service is available") {
    var effectRan = false
    val futureMs = System.currentTimeMillis() + 60000L

    withNoTimer {
      Eru.at(futureMs)(Eru.effectTotal { effectRan = true }).unsafeRunSync()
    }
    assert(effectRan, "Sync fallback should run effect inline")
  }

  test("Eru.at sync fallback returns unit") {
    val futureMs = System.currentTimeMillis() + 60000L
    val result = withNoTimer {
      Eru.at(futureMs)(Eru.succeed(42)).unsafeRunSync()
    }
    assertEquals(result, ())
  }

  // --- Composition tests ---

  test("Eru.at composes with flatMap") {
    val mock = new RecordingTimerService()
    var ran1 = false
    var ran2 = false
    val base = System.currentTimeMillis() + 60000L

    withMockTimer(mock) {
      val prog = for {
        _ <- Eru.at(base + 100L)(Eru.effectTotal { ran1 = true })
        _ <- Eru.at(base + 200L)(Eru.effectTotal { ran2 = true })
      } yield "done"

      val result = prog.unsafeRunSync()
      assertEquals(result, "done")
      assertEquals(mock.scheduledCount, 2)
      assert(!ran1 && !ran2, "Effects should not run before timer fires")

      mock.fireAll()
      assert(ran1 && ran2, "Both effects should run after fire")
    }
  }
}
