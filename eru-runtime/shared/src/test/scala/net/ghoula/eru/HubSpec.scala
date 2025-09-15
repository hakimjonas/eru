package net.ghoula.eru

import net.ghoula.eru.prelude.*

class HubSpec extends TestWithSharedRuntime {

  // =============================================================================
  // Bounded Hub Tests
  // =============================================================================

  test("bounded hub creation succeeds") {
    val hub = Eru.hub[Int](3).unsafeRunSync()
    assertEquals(hub.subscriberCount.unsafeRunSync(), 0)
    assertEquals(hub.hasSubscribers.unsafeRunSync(), false)
    assertEquals(hub.capacity.unsafeRunSync(), 3)
  }

  test("bounded hub capacity validation") {
    intercept[IllegalArgumentException] {
      Eru.hub[Int](0).unsafeRunSync()
    }
    intercept[IllegalArgumentException] {
      Eru.hub[Int](-1).unsafeRunSync()
    }
  }

  test("bounded hub subscription creates queue") {
    val hub = Eru.hub[String](2).unsafeRunSync()

    val subscriber = hub.subscribe.unsafeRunSync()
    assertEquals(hub.subscriberCount.unsafeRunSync(), 1)
    assertEquals(hub.hasSubscribers.unsafeRunSync(), true)

    // Check that subscriber queue is properly configured
    assertEquals(subscriber.size.unsafeRunSync(), 0)
    assertEquals(subscriber.remainingCapacity.unsafeRunSync(), 2)
  }

  test("bounded hub publish to no subscribers drops message") {
    val hub = Eru.hub[String](2).unsafeRunSync()

    hub.publish("test").unsafeRunSync() // Should not error, just drop
    assertEquals(hub.subscriberCount.unsafeRunSync(), 0)
  }

  test("bounded hub publish to single subscriber") {
    val hub = Eru.hub[String](2).unsafeRunSync()
    val subscriber = hub.subscribe.unsafeRunSync()

    hub.publish("message1").unsafeRunSync()
    hub.publish("message2").unsafeRunSync()

    assertEquals(subscriber.take.unsafeRunSync(), "message1")
    assertEquals(subscriber.take.unsafeRunSync(), "message2")
    assertEquals(subscriber.poll.unsafeRunSync(), None)
  }

  test("bounded hub publish to multiple subscribers") {
    val hub = Eru.hub[Int](3).unsafeRunSync()
    val subscriber1 = hub.subscribe.unsafeRunSync()
    val subscriber2 = hub.subscribe.unsafeRunSync()
    val subscriber3 = hub.subscribe.unsafeRunSync()

    assertEquals(hub.subscriberCount.unsafeRunSync(), 3)

    hub.publish(42).unsafeRunSync()
    hub.publish(100).unsafeRunSync()

    // All subscribers should receive all messages
    assertEquals(subscriber1.take.unsafeRunSync(), 42)
    assertEquals(subscriber1.take.unsafeRunSync(), 100)

    assertEquals(subscriber2.take.unsafeRunSync(), 42)
    assertEquals(subscriber2.take.unsafeRunSync(), 100)

    assertEquals(subscriber3.take.unsafeRunSync(), 42)
    assertEquals(subscriber3.take.unsafeRunSync(), 100)
  }

  test("bounded hub subscribers only receive messages after subscription") {
    val hub = Eru.hub[String](2).unsafeRunSync()

    hub.publish("before").unsafeRunSync() // Dropped

    val subscriber = hub.subscribe.unsafeRunSync()

    hub.publish("after").unsafeRunSync()

    assertEquals(subscriber.take.unsafeRunSync(), "after")
    assertEquals(subscriber.poll.unsafeRunSync(), None)
  }

  // =============================================================================
  // Unbounded Hub Tests
  // =============================================================================

  test("unbounded hub creation succeeds") {
    val hub = Eru.unboundedHub[Int].unsafeRunSync()
    assertEquals(hub.subscriberCount.unsafeRunSync(), 0)
    assertEquals(hub.hasSubscribers.unsafeRunSync(), false)
    assertEquals(hub.capacity.unsafeRunSync(), Int.MaxValue)
  }

  test("unbounded hub subscription and publishing") {
    val hub = Eru.unboundedHub[String].unsafeRunSync()
    val subscriber = hub.subscribe.unsafeRunSync()

    hub.publish("test").unsafeRunSync()
    assertEquals(subscriber.take.unsafeRunSync(), "test")
  }

  test("unbounded hub handles large volumes") {
    val hub = Eru.unboundedHub[Int].unsafeRunSync()
    val subscriber = hub.subscribe.unsafeRunSync()

    val messages = 1 to 1000
    messages.foreach(msg => hub.publish(msg).unsafeRunSync())

    val received = (1 to 1000).map(_ => subscriber.take.unsafeRunSync()).toList
    assertEquals(received, messages.toList)
  }

  test("unbounded hub multiple subscribers") {
    val hub = Eru.unboundedHub[String].unsafeRunSync()
    val subscriber1 = hub.subscribe.unsafeRunSync()
    val subscriber2 = hub.subscribe.unsafeRunSync()

    hub.publish("broadcast").unsafeRunSync()

    assertEquals(subscriber1.take.unsafeRunSync(), "broadcast")
    assertEquals(subscriber2.take.unsafeRunSync(), "broadcast")
  }

  // =============================================================================
  // Cross-Platform Edge Cases
  // =============================================================================

  test("hub publish ordering is maintained") {
    val hub = Eru.hub[Int](10).unsafeRunSync()
    val subscriber = hub.subscribe.unsafeRunSync()

    val messages = List(1, 2, 3, 4, 5)
    messages.foreach(msg => hub.publish(msg).unsafeRunSync())

    val received = messages.map(_ => subscriber.take.unsafeRunSync())
    assertEquals(received, messages)
  }

  test("hub sequential publishing") {
    val hub = Eru.hub[Int](10).unsafeRunSync()
    val subscriber = hub.subscribe.unsafeRunSync()

    // Publish messages sequentially (Native-compatible)
    Eru.foreach(1 to 10)(hub.publish).unsafeRunSync()

    val received = (1 to 10).map(_ => subscriber.take.unsafeRunSync()).toList
    assertEquals(received, (1 to 10).toList)
  }

  // =============================================================================
  // Integration with RuntimeExtensions
  // =============================================================================

  test("hub constructors available via Eru companion") {
    val bounded = Eru.hub[String](5).unsafeRunSync()
    val unbounded = Eru.unboundedHub[String].unsafeRunSync()

    val sub1 = bounded.subscribe.unsafeRunSync()
    val sub2 = unbounded.subscribe.unsafeRunSync()

    bounded.publish("bounded").unsafeRunSync()
    unbounded.publish("unbounded").unsafeRunSync()

    assertEquals(sub1.take.unsafeRunSync(), "bounded")
    assertEquals(sub2.take.unsafeRunSync(), "unbounded")
  }

  test("hub operations compose with other Eru effects") {
    val hub = Eru.hub[String](3).unsafeRunSync()

    val program = for {
      subscriber <- hub.subscribe
      _ <- hub.publish("first")
      _ <- hub.publish("second")
      count <- hub.subscriberCount
      _ <- Eru.when(count > 0)(hub.publish("third"))
      messages <- Eru.collectAll(List(subscriber.take, subscriber.take, subscriber.take))
    } yield messages

    val results = program.unsafeRunSync()
    assertEquals(results, List("first", "second", "third"))
  }
}
