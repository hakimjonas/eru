/** Simple Web Service Example with Eru
  *
  * This example demonstrates how to build a simple HTTP server using Eru for
  * effect management. It showcases concurrent request handling, resource management,
  * and error handling patterns.
  *
  * Note: This is a conceptual example showing Eru patterns. For production use,
  * integrate with your preferred HTTP library (http4s, Akka HTTP, etc.)
  */
package examples.webservice

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.util.Random

// Runtime context required for concurrency operations
given runtime: EruRuntime = EruRuntime.default

// Simple domain models
case class User(id: Long, name: String, email: String)
case class CreateUserRequest(name: String, email: String)
case class HttpRequest(method: String, path: String, body: String)
case class HttpResponse(status: Int, body: String, headers: Map[String, String] = Map.empty)

// Service errors
enum ServiceError:
  case UserNotFound(id: Long)
  case InvalidInput(message: String)
  case DatabaseError(cause: String)
  case InternalError(message: String)

object SimpleServer extends App {

  // Simulated in-memory database
  private val users = scala.collection.mutable.Map[Long, User]()
  private val userIdCounter = AtomicLong(1)

  // Initialize with some test data
  users.put(1L, User(1L, "Alice Johnson", "alice@example.com"))
  users.put(2L, User(2L, "Bob Smith", "bob@example.com"))

  // Database layer with Eru effects
  object UserDatabase {

    def findUser(id: Long): Eru[ServiceError, User] = {
      Eru.effect {
        Thread.sleep(Random.nextInt(50)) // Simulate DB latency
        users.get(id)
      }.flatMap {
        case Some(user) => Eru.succeed(user)
        case None => Eru.fail(ServiceError.UserNotFound(id))
      }.mapError(t => ServiceError.DatabaseError(t.getMessage))
    }

    def createUser(request: CreateUserRequest): Eru[ServiceError, User] = {
      Eru.effect {
        val id = userIdCounter.incrementAndGet()
        val user = User(id, request.name, request.email)
        Thread.sleep(Random.nextInt(100)) // Simulate DB write latency
        users.put(id, user)
        user
      }.mapError(t => ServiceError.DatabaseError(t.getMessage))
    }

    def listUsers(): Eru[ServiceError, List[User]] = {
      Eru.effect {
        Thread.sleep(Random.nextInt(30)) // Simulate DB query latency
        users.values.toList.sortBy(_.id)
      }.mapError(t => ServiceError.DatabaseError(t.getMessage))
    }

    def deleteUser(id: Long): Eru[ServiceError, Unit] = {
      Eru.effect {
        Thread.sleep(Random.nextInt(40)) // Simulate DB delete latency
        users.remove(id) match {
          case Some(_) => ()
          case None => throw new RuntimeException(s"User $id not found")
        }
      }.mapError(t => ServiceError.DatabaseError(t.getMessage))
    }
  }

  // HTTP handlers using Eru
  object UserHandlers {

    def getUser(id: Long): Eru[ServiceError, HttpResponse] = {
      for {
        user <- UserDatabase.findUser(id)
        response = HttpResponse(
          status = 200,
          body = s"""{"id": ${user.id}, "name": "${user.name}", "email": "${user.email}"}""",
          headers = Map("Content-Type" -> "application/json")
        )
      } yield response
    }

    def createUser(body: String): Eru[ServiceError, HttpResponse] = {
      for {
        request <- parseCreateUserRequest(body)
        user <- UserDatabase.createUser(request)
        response = HttpResponse(
          status = 201,
          body = s"""{"id": ${user.id}, "name": "${user.name}", "email": "${user.email}"}""",
          headers = Map("Content-Type" -> "application/json", "Location" -> s"/users/${user.id}")
        )
      } yield response
    }

    def listUsers(): Eru[ServiceError, HttpResponse] = {
      for {
        users <- UserDatabase.listUsers()
        usersJson = users.map(u => s"""{"id": ${u.id}, "name": "${u.name}", "email": "${u.email}"}""").mkString("[", ",", "]")
        response = HttpResponse(
          status = 200,
          body = usersJson,
          headers = Map("Content-Type" -> "application/json")
        )
      } yield response
    }

    def deleteUser(id: Long): Eru[ServiceError, HttpResponse] = {
      for {
        _ <- UserDatabase.deleteUser(id)
        response = HttpResponse(status = 204, body = "")
      } yield response
    }

    private def parseCreateUserRequest(body: String): Eru[ServiceError, CreateUserRequest] = {
      Eru.effect {
        // Simple JSON parsing simulation
        if (body.contains("name") && body.contains("email")) {
          val name = extractJsonField(body, "name")
          val email = extractJsonField(body, "email")
          CreateUserRequest(name, email)
        } else {
          throw new IllegalArgumentException("Missing required fields: name, email")
        }
      }.mapError(t => ServiceError.InvalidInput(t.getMessage))
    }

    private def extractJsonField(json: String, field: String): String = {
      val pattern = s""""$field"\\s*:\\s*"([^"]+)"""".r
      pattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("")
    }
  }

  // Request router
  def routeRequest(request: HttpRequest): Eru[ServiceError, HttpResponse] = {
    (request.method, request.path) match {
      case ("GET", path) if path.startsWith("/users/") =>
        val idStr = path.substring("/users/".length)
        Eru.effect(idStr.toLong)
          .mapError(t => ServiceError.InvalidInput(s"Invalid user ID: $idStr"))
          .flatMap(UserHandlers.getUser)

      case ("GET", "/users") =>
        UserHandlers.listUsers()

      case ("POST", "/users") =>
        UserHandlers.createUser(request.body)

      case ("DELETE", path) if path.startsWith("/users/") =>
        val idStr = path.substring("/users/".length)
        Eru.effect(idStr.toLong)
          .mapError(t => ServiceError.InvalidInput(s"Invalid user ID: $idStr"))
          .flatMap(UserHandlers.deleteUser)

      case _ =>
        Eru.succeed(HttpResponse(
          status = 404,
          body = """{"error": "Not Found"}""",
          headers = Map("Content-Type" -> "application/json")
        ))
    }
  }

  // Error handling
  def handleError(error: ServiceError): HttpResponse = error match {
    case ServiceError.UserNotFound(id) =>
      HttpResponse(
        status = 404,
        body = s"""{"error": "User not found", "userId": $id}""",
        headers = Map("Content-Type" -> "application/json")
      )
    case ServiceError.InvalidInput(message) =>
      HttpResponse(
        status = 400,
        body = s"""{"error": "Invalid input", "message": "$message"}""",
        headers = Map("Content-Type" -> "application/json")
      )
    case ServiceError.DatabaseError(cause) =>
      HttpResponse(
        status = 500,
        body = s"""{"error": "Database error", "cause": "$cause"}""",
        headers = Map("Content-Type" -> "application/json")
      )
    case ServiceError.InternalError(message) =>
      HttpResponse(
        status = 500,
        body = s"""{"error": "Internal server error", "message": "$message"}""",
        headers = Map("Content-Type" -> "application/json")
      )
  }

  // Request processor with timeout and error handling
  def processRequest(request: HttpRequest): Eru[Nothing, HttpResponse] = {
    val requestId = Random.nextLong().abs

    println(s"[$requestId] Processing: ${request.method} ${request.path}")

    val startTime = System.currentTimeMillis()

    val processing = routeRequest(request)
      .timeout(java.time.Duration.ofSeconds(5)) // 5 second timeout
      .recover { case error: ServiceError => handleError(error) }
      .recover { case _: java.util.concurrent.TimeoutException =>
        HttpResponse(
          status = 408,
          body = """{"error": "Request timeout"}""",
          headers = Map("Content-Type" -> "application/json")
        )
      }
      .recover { case t: Throwable =>
        HttpResponse(
          status = 500,
          body = s"""{"error": "Unexpected error", "message": "${t.getMessage}"}""",
          headers = Map("Content-Type" -> "application/json")
        )
      }

    processing.map { response =>
      val duration = System.currentTimeMillis() - startTime
      println(s"[$requestId] Response: ${response.status} (${duration}ms)")
      response
    }
  }

  // Simulate concurrent request handling
  def simulateServer(): Eru[Nothing, Unit] = {
    println("=== Simple Web Service with Eru ===\n")

    val requests = List(
      HttpRequest("GET", "/users", ""),
      HttpRequest("GET", "/users/1", ""),
      HttpRequest("POST", "/users", """{"name": "Charlie Brown", "email": "charlie@example.com"}"""),
      HttpRequest("GET", "/users/3", ""),
      HttpRequest("DELETE", "/users/2", ""),
      HttpRequest("GET", "/users", ""),
      HttpRequest("GET", "/users/999", ""), // Should return 404
      HttpRequest("POST", "/users", """{"name": "Diana Prince"}"""), // Missing email, should return 400
      HttpRequest("GET", "/invalid-path", ""), // Should return 404
    )

    println("Processing requests concurrently...\n")

    // Process all requests in parallel
    val parallelProcessing = requests.map(processRequest).map(_.fork)

    Eru.traverse(parallelProcessing) { fiberEru =>
      fiberEru.flatMap(_.await.flatMap(exit => Eru.fromExit(exit).recover(_ =>
        HttpResponse(500, """{"error": "Fiber failed"}""")
      )))
    }.map { responses =>
      println("\n=== All Requests Completed ===")
      responses.zipWithIndex.foreach { case (response, i) =>
        val request = requests(i)
        println(s"${request.method} ${request.path} -> ${response.status}")
      }
      println(s"\nProcessed ${responses.length} requests successfully!")
      ()
    }
  }

  // Demonstrate resource management
  def demonstrateResourceManagement(): Eru[Nothing, Unit] = {
    println("\n=== Resource Management Example ===")

    // Simulate a database connection resource
    case class DatabaseConnection(id: String) {
      def close(): Unit = println(s"Closing database connection: $id")
    }

    def acquireConnection(): Eru[Throwable, DatabaseConnection] = {
      Eru.effect {
        val id = s"conn-${Random.nextInt(1000)}"
        println(s"Acquiring database connection: $id")
        DatabaseConnection(id)
      }
    }

    def useConnection(conn: DatabaseConnection): Eru[ServiceError, List[User]] = {
      println(s"Using connection ${conn.id} to fetch users")
      UserDatabase.listUsers()
    }

    // Resource bracket pattern ensures cleanup even on errors
    val resourceOperation = for {
      conn <- acquireConnection().mapError(t => ServiceError.InternalError(t.getMessage))
      users <- useConnection(conn).ensure(Eru.effect(conn.close()).mapError(_ => ()))
    } yield users

    resourceOperation
      .recover { case error =>
        println(s"Operation failed: $error")
        List.empty[User]
      }
      .map { users =>
        println(s"Successfully fetched ${users.length} users with proper resource cleanup")
        ()
      }
  }

  // Run the demo
  val demo = for {
    _ <- simulateServer()
    _ <- demonstrateResourceManagement()
  } yield ()

  demo.unsafeRunSync()

  println("\n=== Server Demo Completed Successfully! ===")
  println("Key patterns demonstrated:")
  println("- Concurrent request processing with fibers")
  println("- Structured error handling with custom error types")
  println("- Timeouts and resource management")
  println("- Type-safe effect composition")
}