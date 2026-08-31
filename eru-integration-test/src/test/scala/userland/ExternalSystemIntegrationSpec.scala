package userland

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import net.ghoula.eru.prelude.*

/** Integration test suite for external system interaction patterns.
  *
  * Tests real-world scenarios where Eru effects interact with external systems including databases,
  * web services, message queues, and other asynchronous resources. Validates proper resource
  * management, error handling, timeout behavior, and cancellation semantics in production-like
  * environments.
  */
class ExternalSystemIntegrationSpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

  test("database connection pool simulation with resource limits") {
    val connectionPool = new java.util.concurrent.ArrayBlockingQueue[String](2)
    connectionPool.put("conn1")
    connectionPool.put("conn2")

    val completedQueries = new AtomicInteger(0)

    def acquireConnection(): Eru[String, String] = {
      Eru.effect {
        Option(connectionPool.poll(100, TimeUnit.MILLISECONDS)) match {
          case Some(conn) => conn
          case None => throw new RuntimeException("Connection timeout")
        }
      }.mapError(_.getMessage)
    }

    def releaseConnection(conn: String): Eru[String, Unit] = {
      Eru.effect { connectionPool.put(conn) }.mapError(_.getMessage)
    }

    def executeQuery(conn: String, query: String): Eru[String, String] = {
      Eru.effect {
        completedQueries.incrementAndGet()
        s"Result for $query on $conn"
      }.mapError(_.getMessage)
    }

    val query1 = acquireConnection()
      .bracket(releaseConnection)(conn => executeQuery(conn, "SELECT 1"))

    val query2 = acquireConnection()
      .bracket(releaseConnection)(conn => executeQuery(conn, "SELECT 2"))

    val program = query1.zipPar(query2).map { case (r1, r2) => List(r1, r2) }

    val result = program.runExit()

    result match {
      case Exit.Success(_) => assert(completedQueries.get() == 2)
      case _ => fail("Expected successful database queries")
    }
  }

  test("web service call with retry simulation") {
    val callCount = new AtomicInteger(0)

    def webServiceCall(endpoint: String): Eru[String, String] = {
      Eru.effect {
        val count = callCount.incrementAndGet()

        if (count <= 2) {
          throw new RuntimeException(s"Service unavailable (attempt $count)")
        }

        s"Success response from $endpoint"
      }.mapError(_.getMessage)
    }

    val retryProgram = webServiceCall("/api/data")
      .orElse(webServiceCall("/api/data"))
      .orElse(webServiceCall("/api/data"))

    val result = retryProgram.runExit()

    result match {
      case Exit.Success(_) => assertEquals(callCount.get(), 3)
      case _ => fail("Expected successful retry")
    }
  }

  test("message queue producer-consumer simulation") {
    val queue = new java.util.concurrent.ArrayBlockingQueue[String](3)

    def producer(message: String): Eru[String, Boolean] = {
      Eru.effect {
        queue.offer(message, 50, TimeUnit.MILLISECONDS)
      }.mapError(_.getMessage)
    }

    def consumer(): Eru[String, Option[String]] = {
      Eru.effect {
        Option(queue.poll(100, TimeUnit.MILLISECONDS))
      }.mapError(_.getMessage)
    }

    val program = for {
      _ <- producer("message-1")
      _ <- producer("message-2")
      msg1 <- consumer()
      msg2 <- consumer()
    } yield (msg1, msg2)

    val result = program.runExit()

    result match {
      case Exit.Success((Some(m1), Some(m2))) =>
        assert(Set(m1, m2) == Set("message-1", "message-2"))
      case _ => fail("Expected both messages to be consumed")
    }
  }

  test("file system operations with resource cleanup") {
    val tempDir = java.nio.file.Files.createTempDirectory("eru-test")

    def createFile(name: String): Eru[String, java.nio.file.Path] = {
      Eru.effect {
        val path = tempDir.resolve(name)
        java.nio.file.Files.write(path, s"Content for $name".getBytes())
        path
      }.mapError(_.getMessage)
    }

    def deleteFile(path: java.nio.file.Path): Eru[String, Unit] = {
      Eru.effect {
        if (java.nio.file.Files.exists(path)) {
          java.nio.file.Files.delete(path)
        }
      }.mapError(_.getMessage)
    }

    def processFile(path: java.nio.file.Path): Eru[String, String] = {
      Eru.effect {
        val content = java.nio.file.Files.readString(path)
        content.toUpperCase()
      }.mapError(_.getMessage)
    }

    val program = createFile("test-file.txt")
      .bracket(deleteFile) { path =>
        processFile(path)
      }
      .ensure {
        Eru.effect {
          if (java.nio.file.Files.exists(tempDir)) {
            java.nio.file.Files.deleteIfExists(tempDir)
          }
        }.mapError(_.getMessage)
      }

    val result = program.runExit()

    result match {
      case Exit.Success(content) =>
        assert(content.startsWith("CONTENT FOR"))
      case _ => fail("Expected successful file processing")
    }
  }

  test("external process execution") {
    def executeCommand(command: String*): Eru[String, String] = {
      Eru.effect {
        val process = new ProcessBuilder(command*).start()

        try {
          val completed = process.waitFor(2, TimeUnit.SECONDS)
          if (!completed) {
            process.destroyForcibly()
            throw new RuntimeException("Process timeout")
          }
        } catch {
          case _: InterruptedException =>
            process.destroyForcibly()
            throw new RuntimeException("Process interrupted")
        }

        val output = new String(process.getInputStream.readAllBytes())
        if (process.exitValue() != 0) {
          throw new RuntimeException(s"Process failed with exit code: ${process.exitValue()}")
        }

        output.trim()
      }.mapError(_.getMessage)
    }

    val program = executeCommand("echo", "Hello World")

    val result = program.runExit()

    result match {
      case Exit.Success(output) =>
        assertEquals(output, "Hello World")
      case _ => fail("Expected successful command execution")
    }
  }
}
