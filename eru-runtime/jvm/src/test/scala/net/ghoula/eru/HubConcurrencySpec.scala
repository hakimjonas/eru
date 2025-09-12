package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** Async concurrency tests for Hub operations using proper coordination primitives.
  *
  * These tests demonstrate correct async behavior without relying on Thread.sleep or polling,
  * instead using Promise and CountDownLatch for deterministic coordination.
  */
class HubConcurrencySpec extends TestWithRuntime {

  test("hub concurrent publishing maintains message delivery") {
    val hub = Eru.hub[String](10).unsafeRunSync() // Large capacity to avoid blocking
    val subscriber = hub.subscribe.unsafeRunSync()

    val messageCount = 5
    val allPublished = Eru.countDownLatch(messageCount).unsafeRunSync()

    // Publisher
    val publisher = runtime.fork {
      Eru.foreach(1 to messageCount) { i =>
        for {
          _ <- hub.publish(s"msg$i")
          _ <- allPublished.countDown
        } yield ()
      }
    }.unsafeRunSync()

    // Consumer
    val consumer = runtime.fork {
      for {
        _ <- allPublished.await // Wait for all messages to be published
        messages <- Eru.collectAll((1 to messageCount).map(_ => subscriber.take))
      } yield messages
    }.unsafeRunSync()

    publisher.await.unsafeRunSync()
    val result = consumer.await.unsafeRunSync()

    result match {
      case Exit.Success(messages) =>
        // Messages might not be in strict order due to concurrency, use Set comparison
        assertEquals(messages.toSet, (1 to messageCount).map(i => s"msg$i").toSet)
      case other => fail(s"Expected success but got: $other")
    }
  }

  test("hub multiple subscribers receive all messages independently") {
    val hub = Eru.hub[Int](10).unsafeRunSync()
    val subscriber1 = hub.subscribe.unsafeRunSync()
    val subscriber2 = hub.subscribe.unsafeRunSync()

    val messageCount = 3 // Small number for fast test
    val publishComplete = Eru.promise[Nothing, Unit].unsafeRunSync()

    // Publisher
    val publisher = runtime.fork {
      for {
        _ <- Eru.foreach(1 to messageCount)(hub.publish)
        _ <- publishComplete.succeed(())
      } yield ()
    }.unsafeRunSync()

    // Consumers
    val consumer1 = runtime.fork {
      for {
        _ <- publishComplete.await
        messages <- Eru.collectAll((1 to messageCount).map(_ => subscriber1.take))
      } yield messages
    }.unsafeRunSync()

    val consumer2 = runtime.fork {
      for {
        _ <- publishComplete.await
        messages <- Eru.collectAll((1 to messageCount).map(_ => subscriber2.take))
      } yield messages
    }.unsafeRunSync()

    publisher.await.unsafeRunSync()

    val (messages1, messages2) = (
      consumer1.await.unsafeRunSync(),
      consumer2.await.unsafeRunSync()
    )

    messages1 match {
      case Exit.Success(msgs) => assertEquals(msgs.toSet, (1 to messageCount).toSet)
      case other => fail(s"Consumer1 expected success but got: $other")
    }

    messages2 match {
      case Exit.Success(msgs) => assertEquals(msgs.toSet, (1 to messageCount).toSet)
      case other => fail(s"Consumer2 expected success but got: $other")
    }
  }
}
