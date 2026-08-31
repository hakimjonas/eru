package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Async concurrency tests for Hub operations using proper coordination primitives.
  *
  * These tests demonstrate correct async behavior without relying on Thread.sleep or polling,
  * instead using Promise and CountDownLatch for deterministic coordination.
  *
  * Hub capacity is sized generously to avoid blocking publishers. Message ordering is not
  * guaranteed under concurrency, so assertions compare message sets.
  */
class HubConcurrencySpec extends EruTestSuite {

  test("hub concurrent publishing maintains message delivery") {
    val hub = Eru.hub[String](10).unsafeRunSync()
    val subscriber = hub.subscribe.eru.unsafeRunSync()

    val messageCount = 5
    val allPublished = Eru.countDownLatch(messageCount).unsafeRunSync()

    val publisher = (
      Eru
        .foreach(1 to messageCount) { i =>
          for {
            _ <- hub.publish(s"msg$i").eru
            _ <- allPublished.countDown.eru
          } yield ()
        }
      )
      .fork
      .unsafeRunSync()

    val consumer = (for {
      _ <- allPublished.await.eru
      messages <- Eru.collectAll((1 to messageCount).map(_ => subscriber.take.eru))
    } yield messages).fork.unsafeRunSync()

    publisher.await.unsafeRunSync()
    val result = consumer.await.unsafeRunSync()

    result match {
      case Exit.Success(messages) =>
        assertEquals(messages.toSet, (1 to messageCount).map(i => s"msg$i").toSet)
      case other => fail(s"Expected success but got: $other")
    }
  }

  test("hub multiple subscribers receive all messages independently") {
    val hub = Eru.hub[Int](10).unsafeRunSync()
    val subscriber1 = hub.subscribe.eru.unsafeRunSync()
    val subscriber2 = hub.subscribe.eru.unsafeRunSync()

    val messageCount = 3
    val publishComplete = Eru.promise[Nothing, Unit].unsafeRunSync()

    val publisher = (for {
      _ <- Eru.foreach(1 to messageCount)(i => hub.publish(i).eru)
      _ <- publishComplete.succeed(()).eru
    } yield ()).fork.unsafeRunSync()

    val consumer1 = (for {
      _ <- publishComplete.await.eru
      messages <- Eru.collectAll((1 to messageCount).map(_ => subscriber1.take.eru))
    } yield messages).fork.unsafeRunSync()

    val consumer2 = (for {
      _ <- publishComplete.await.eru
      messages <- Eru.collectAll((1 to messageCount).map(_ => subscriber2.take.eru))
    } yield messages).fork.unsafeRunSync()

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
