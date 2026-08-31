package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

class HubSpec extends EruTestSuite {

  test("bounded hub creation succeeds") {
    val hub = Eru.hub[Int](3).unsafeRunSync()
    assertEquals(hub.subscriberCount.eru.unsafeRunSync(), 0)
    assertEquals(hub.hasSubscribers.eru.unsafeRunSync(), false)
    assertEquals(hub.capacity.eru.unsafeRunSync(), 3)
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

    val subscriber = hub.subscribe.eru.unsafeRunSync()
    assertEquals(hub.subscriberCount.eru.unsafeRunSync(), 1)
    assertEquals(hub.hasSubscribers.eru.unsafeRunSync(), true)

    assertEquals(subscriber.size.eru.unsafeRunSync(), 0)
    assertEquals(subscriber.remainingCapacity.eru.unsafeRunSync(), 2)
  }

  test("bounded hub publish to no subscribers drops message") {
    val hub = Eru.hub[String](2).unsafeRunSync()

    hub.publish("test").eru.unsafeRunSync()
    assertEquals(hub.subscriberCount.eru.unsafeRunSync(), 0)
  }

  test("bounded hub publish to single subscriber") {
    val hub = Eru.hub[String](2).unsafeRunSync()
    val subscriber = hub.subscribe.eru.unsafeRunSync()

    hub.publish("message1").eru.unsafeRunSync()
    hub.publish("message2").eru.unsafeRunSync()

    assertEquals(subscriber.take.eru.unsafeRunSync(), "message1")
    assertEquals(subscriber.take.eru.unsafeRunSync(), "message2")
    assertEquals(subscriber.poll.eru.unsafeRunSync(), None)
  }

  test("bounded hub publish to multiple subscribers") {
    val hub = Eru.hub[Int](3).unsafeRunSync()
    val subscriber1 = hub.subscribe.eru.unsafeRunSync()
    val subscriber2 = hub.subscribe.eru.unsafeRunSync()
    val subscriber3 = hub.subscribe.eru.unsafeRunSync()

    assertEquals(hub.subscriberCount.eru.unsafeRunSync(), 3)

    hub.publish(42).eru.unsafeRunSync()
    hub.publish(100).eru.unsafeRunSync()

    assertEquals(subscriber1.take.eru.unsafeRunSync(), 42)
    assertEquals(subscriber1.take.eru.unsafeRunSync(), 100)

    assertEquals(subscriber2.take.eru.unsafeRunSync(), 42)
    assertEquals(subscriber2.take.eru.unsafeRunSync(), 100)

    assertEquals(subscriber3.take.eru.unsafeRunSync(), 42)
    assertEquals(subscriber3.take.eru.unsafeRunSync(), 100)
  }

  test("bounded hub subscribers only receive messages after subscription") {
    val hub = Eru.hub[String](2).unsafeRunSync()

    hub.publish("before").eru.unsafeRunSync()

    val subscriber = hub.subscribe.eru.unsafeRunSync()

    hub.publish("after").eru.unsafeRunSync()

    assertEquals(subscriber.take.eru.unsafeRunSync(), "after")
    assertEquals(subscriber.poll.eru.unsafeRunSync(), None)
  }

  test("unbounded hub creation succeeds") {
    val hub = Eru.unboundedHub[Int].unsafeRunSync()
    assertEquals(hub.subscriberCount.eru.unsafeRunSync(), 0)
    assertEquals(hub.hasSubscribers.eru.unsafeRunSync(), false)
    assertEquals(hub.capacity.eru.unsafeRunSync(), Int.MaxValue)
  }

  test("unbounded hub subscription and publishing") {
    val hub = Eru.unboundedHub[String].unsafeRunSync()
    val subscriber = hub.subscribe.eru.unsafeRunSync()

    hub.publish("test").eru.unsafeRunSync()
    assertEquals(subscriber.take.eru.unsafeRunSync(), "test")
  }

  test("unbounded hub handles large volumes") {
    val hub = Eru.unboundedHub[Int].unsafeRunSync()
    val subscriber = hub.subscribe.eru.unsafeRunSync()

    val messages = 1 to 1000
    messages.foreach(msg => hub.publish(msg).eru.unsafeRunSync())

    val received = (1 to 1000).map(_ => subscriber.take.eru.unsafeRunSync()).toList
    assertEquals(received, messages.toList)
  }

  test("unbounded hub multiple subscribers") {
    val hub = Eru.unboundedHub[String].unsafeRunSync()
    val subscriber1 = hub.subscribe.eru.unsafeRunSync()
    val subscriber2 = hub.subscribe.eru.unsafeRunSync()

    hub.publish("broadcast").eru.unsafeRunSync()

    assertEquals(subscriber1.take.eru.unsafeRunSync(), "broadcast")
    assertEquals(subscriber2.take.eru.unsafeRunSync(), "broadcast")
  }

  test("hub publish ordering is maintained") {
    val hub = Eru.hub[Int](10).unsafeRunSync()
    val subscriber = hub.subscribe.eru.unsafeRunSync()

    val messages = List(1, 2, 3, 4, 5)
    messages.foreach(msg => hub.publish(msg).eru.unsafeRunSync())

    val received = messages.map(_ => subscriber.take.eru.unsafeRunSync())
    assertEquals(received, messages)
  }

  test("hub sequential publishing") {
    val hub = Eru.hub[Int](10).unsafeRunSync()
    val subscriber = hub.subscribe.eru.unsafeRunSync()

    Eru.foreach(1 to 10)(msg => hub.publish(msg).eru).unsafeRunSync()

    val received = (1 to 10).map(_ => subscriber.take.eru.unsafeRunSync()).toList
    assertEquals(received, (1 to 10).toList)
  }

  test("hub constructors available via Eru companion") {
    val bounded = Eru.hub[String](5).unsafeRunSync()
    val unbounded = Eru.unboundedHub[String].unsafeRunSync()

    val sub1 = bounded.subscribe.eru.unsafeRunSync()
    val sub2 = unbounded.subscribe.eru.unsafeRunSync()

    bounded.publish("bounded").eru.unsafeRunSync()
    unbounded.publish("unbounded").eru.unsafeRunSync()

    assertEquals(sub1.take.eru.unsafeRunSync(), "bounded")
    assertEquals(sub2.take.eru.unsafeRunSync(), "unbounded")
  }

  test("hub operations compose with other Eru effects") {
    val hub = Eru.hub[String](3).unsafeRunSync()

    val program = for {
      subscriber <- hub.subscribe.eru
      _ <- hub.publish("first").eru
      _ <- hub.publish("second").eru
      count <- hub.subscriberCount.eru
      _ <- Eru.when(count > 0)(hub.publish("third").eru)
      messages <- Eru.collectAll(List(subscriber.take.eru, subscriber.take.eru, subscriber.take.eru))
    } yield messages

    val results = program.unsafeRunSync()
    assertEquals(results, List("first", "second", "third"))
  }
}
