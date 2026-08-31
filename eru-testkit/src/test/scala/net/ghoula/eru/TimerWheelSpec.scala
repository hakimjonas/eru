package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Deterministic tests for Eru.at / Eru.after and the At interpreter case.
  *
  * Uses a mock TimerService that records scheduled tasks and fires them on command, avoiding all
  * wall-clock timing dependencies.
  *
  * `after()` is monotonic: the mock receives the relative delay, not a wall-clock epoch.
  * `TimerService.get` resolves through a thread-local first and a write-once default-provider
  * (installed by EruRuntime.shared) second, so the `case None` interpreter branch is unreachable
  * from user-facing Eru.at in suites extending EruTestSuite; that branch is kept in the interpreter
  * to preserve core purity when no runtime is present.
  */
final class TimerWheelSpec extends EruTestSuite {

  /** Mock timer service that records scheduled tasks without any real timer.
    *
    * Fire-and-inspect only; cancellation is not exercised by existing specs.
    */
  private final class RecordingTimerService extends TimerService {
    import scala.jdk.CollectionConverters.*
    private val tasks = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Runnable)]()
    private val delayedTasks = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Runnable)]()

    def schedule(epochMillis: Long, task: Runnable): TimerHandle = {
      tasks.add((epochMillis, task))
      TimerHandle.NoOp
    }

    def scheduleAfter(delayMillis: Long, task: Runnable): TimerHandle = {
      delayedTasks.add((delayMillis, task))
      TimerHandle.NoOp
    }

    def shutdown(): Unit = ()

    def scheduledCount: Int = tasks.size()

    def scheduledTimes: List[Long] =
      tasks.asScala.map(_._1).toList

    def scheduledDelays: List[Long] =
      delayedTasks.asScala.map(_._1).toList

    /** Fire all recorded tasks synchronously. */
    def fireAll(): Unit = {
      var entry = Option(tasks.poll())
      while (entry.nonEmpty) {
        entry.foreach(_._2.run())
        entry = Option(tasks.poll())
      }
      entry = Option(delayedTasks.poll())
      while (entry.nonEmpty) {
        entry.foreach(_._2.run())
        entry = Option(delayedTasks.poll())
      }
    }
  }

  /** Run a block with a mock timer service bound to this thread's TimerService thread-local,
    * restoring the prior binding afterward. Mirrors the push/pop discipline fork / race use.
    */
  private def withMockTimer[A](mock: RecordingTimerService)(block: => A): A =
    TimerService.withTimer(mock)(block)

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
      Eru.at(0L)(Eru.unit).unsafeRunSync()

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

  test("Eru.after schedules a relative delay on the timer's duration path") {
    val mock = new RecordingTimerService()

    withMockTimer(mock) {
      Eru.after(java.time.Duration.ofSeconds(5))(Eru.unit).unsafeRunSync()
    }

    assertEquals(mock.scheduledDelays, List(5000L))
    assertEquals(mock.scheduledCount, 0, "after must not use the wall-clock schedule path")
  }

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
