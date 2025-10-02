# Chapter 13: Integration Patterns

Real-world adoption of Eru requires seamless integration with existing codebases, legacy systems, blocking operations, and third-party libraries. This chapter provides comprehensive patterns and strategies for integrating Eru into existing systems while maintaining safety, performance, and composability.

## Integration Philosophy

Eru's integration approach follows these principles:

- **Gradual Adoption**: Enable incremental migration without big-bang rewrites
- **Safety Preservation**: Maintain Eru's safety guarantees even when integrating unsafe code
- **Performance Awareness**: Minimize overhead while providing safety and observability
- **Ecosystem Compatibility**: Work well with existing Scala and Java libraries

## Wrapping Legacy Code

The most common integration scenario is wrapping existing synchronous code in Eru effects:

```scala mdoc
import net.ghoula.eru.prelude.*
import scala.util.{Try, Success, Failure}
import scala.concurrent.{Future, ExecutionContext}
import java.io.File

// Define typed errors for integration scenarios
enum IntegrationError:
  case InvalidInput(field: String, value: String)
  case ServiceUnavailable(service: String, reason: String)
  case NetworkTimeout(durationMs: Long)
  case ParseError(input: String, expected: String)
  case FileNotFound(path: String)
  case DatabaseError(operation: String, details: String)

// Example legacy code that we need to integrate
class LegacyUserService {
  def getUserById(id: String): String = {
    if (id.nonEmpty) {
      // Simulate database access
      Thread.sleep(10) // Blocking I/O simulation
      s"User data for $id"
    } else {
      throw new IllegalArgumentException("User ID cannot be empty")
    }
  }

  def updateUser(id: String, data: String): Boolean = {
    if (id.nonEmpty && data.nonEmpty) {
      Thread.sleep(5) // Blocking I/O simulation
      println(s"Updated user $id with data: $data")
      true
    } else {
      throw new IllegalArgumentException("Invalid user data")
    }
  }
}

// Pattern 1: Basic wrapping with Eru.effect and typed errors
class EruUserService(legacy: LegacyUserService) {

  def getUser(id: String): Eru[IntegrationError, String] = {
    Eru.effect(legacy.getUserById(id)).mapError {
      case _: IllegalArgumentException => IntegrationError.InvalidInput("userId", id)
      case _: java.sql.SQLException => IntegrationError.DatabaseError("getUser", s"Failed to fetch user $id")
      case other => IntegrationError.ServiceUnavailable("UserService", other.getMessage)
    }
  }

  def updateUser(id: String, data: String): Eru[IntegrationError, Boolean] = {
    Eru.effect(legacy.updateUser(id, data)).mapError {
      case _: IllegalArgumentException =>
        if (id.isEmpty) IntegrationError.InvalidInput("userId", id)
        else IntegrationError.InvalidInput("userData", data)
      case _: java.sql.SQLException => IntegrationError.DatabaseError("updateUser", s"Failed to update user $id")
      case other => IntegrationError.ServiceUnavailable("UserService", other.getMessage)
    }
  }
}

// Usage example
val legacyService = LegacyUserService()
val eruService = EruUserService(legacyService)

def basicIntegrationExample(): Eru[IntegrationError, String] = {
  for {
    user <- eruService.getUser("user123")
    _ <- eruService.updateUser("user123", "updated data")
    result <- Eru.succeed(s"Successfully processed: $user")
  } yield result
}

val integrationResult = basicIntegrationExample().attempt.unsafeRunSync()
integrationResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"Integration success: $result")
  case net.ghoula.eru.Result.Failure(error) => error match {
    case IntegrationError.InvalidInput(field, value) => println(s"Invalid $field: $value")
    case IntegrationError.ServiceUnavailable(service, reason) => println(s"$service unavailable: $reason")
    case IntegrationError.DatabaseError(op, details) => println(s"Database error in $op: $details")
    case other => println(s"Integration error: $other")
  }
}
```

## Blocking Operations Integration

Handle blocking I/O operations safely within Eru's execution model:

```scala mdoc
import java.io.{File, FileReader, BufferedReader}
import java.net.{URI, HttpURLConnection}
import scala.io.Source

// Pattern 2: Blocking I/O with proper resource management
object BlockingIntegration {

  // File operations with resource safety
  def readFileBlocking(filename: String): Eru[IntegrationError, String] = {
    def openFile(): Eru[IntegrationError, BufferedReader] = {
      Eru.effect {
        new BufferedReader(new FileReader(filename))
      }.mapError {
        case _: java.io.FileNotFoundException => IntegrationError.FileNotFound(filename)
        case _: java.io.IOException => IntegrationError.ServiceUnavailable("FileSystem", s"Cannot access file $filename")
        case other => IntegrationError.ServiceUnavailable("FileSystem", other.getMessage)
      }
    }

    def readContent(reader: BufferedReader): Eru[IntegrationError, String] = {
      Eru.effect {
        val content = new StringBuilder
        var line = reader.readLine() // intentional var for I/O iteration
        while (line != null) {
          content.append(line).append("\n")
          line = reader.readLine()
        }
        content.toString
      }.mapError {
        case _: java.io.IOException => IntegrationError.ServiceUnavailable("FileSystem", "Error reading file content")
        case other => IntegrationError.ServiceUnavailable("FileSystem", other.getMessage)
      }
    }

    def closeReader(reader: BufferedReader): Eru[IntegrationError, Unit] = {
      Eru.effect(reader.close()).mapError {
        case _: java.io.IOException => IntegrationError.ServiceUnavailable("FileSystem", "Error closing file")
        case other => IntegrationError.ServiceUnavailable("FileSystem", other.getMessage)
      }
    }

    // Use bracket for guaranteed resource cleanup
    openFile().bracket(release = closeReader)(readContent)
  }

  // HTTP requests with timeout and error handling
  def httpRequestBlocking(url: String, timeoutMs: Int = 5000): Eru[String, String] = {
    def openConnection(): Eru[String, HttpURLConnection] = {
      Eru.effect {
        val connection = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
        connection.setConnectTimeout(timeoutMs)
        connection.setReadTimeout(timeoutMs)
        connection.setRequestMethod("GET")
        connection
      }.mapError(_.getMessage)
    }

    def readResponse(connection: HttpURLConnection): Eru[String, String] = {
      Eru.effect {
        val responseCode = connection.getResponseCode
        if (responseCode == 200) {
          val source = Source.fromInputStream(connection.getInputStream)
          try {
            source.mkString
          } finally {
            source.close()
          }
        } else {
          throw new RuntimeException(s"HTTP error: $responseCode")
        }
      }.mapError(_.getMessage)
    }

    def closeConnection(connection: HttpURLConnection): Eru[String, Unit] = {
      Eru.effect(connection.disconnect()).mapError(_.getMessage)
    }

    openConnection().bracket(release = closeConnection)(readResponse)
  }

  // Database connection simulation with connection pooling
  class DatabaseConnection {
    def executeQuery(sql: String): String = {
      Thread.sleep(20) // Simulate DB query time
      s"Query result for: $sql"
    }
    def close(): Unit = println("Database connection closed")
  }

  class ConnectionPool(maxSize: Int) {
    private var availableConnections = (1 to maxSize).map(i => new DatabaseConnection()).toList
    private var activeConnections = 0

    def acquireConnection(): Eru[String, DatabaseConnection] = {
      Eru.effect {
        synchronized { // Note: In new code, prefer Eru's Semaphore or Queue for coordination
          if (availableConnections.nonEmpty) {
            val connection = availableConnections.head
            availableConnections = availableConnections.tail
            activeConnections += 1
            connection
          } else {
            throw new RuntimeException("Connection pool exhausted")
          }
        }
      }.mapError(_.getMessage)
    }

    def releaseConnection(connection: DatabaseConnection): Eru[String, Unit] = {
      Eru.effect {
        synchronized {
          availableConnections = connection :: availableConnections
          activeConnections -= 1
        }
      }.mapError(_.getMessage)
    }

    def withConnection[A](operation: DatabaseConnection => Eru[String, A]): Eru[String, A] = {
      acquireConnection().bracket(release = releaseConnection)(operation)
    }
  }
}

// Usage examples
def blockingIntegrationExample(): Eru[IntegrationError, String] = {
  // Create a test file for demonstration
  val testContent = "Hello, Eru Integration!\nThis is a test file.\nEnd of content."
  val testFile = "integration-test.txt"

  for {
    // Write test file
    _ <- Eru.effect {
      val writer = new java.io.PrintWriter(testFile)
      try {
        writer.print(testContent)
      } finally {
        writer.close()
      }
    }.mapError {
      case _: java.io.IOException => IntegrationError.ServiceUnavailable("FileSystem", s"Cannot create file $testFile")
      case other => IntegrationError.ServiceUnavailable("FileSystem", other.getMessage)
    }

    // Read the file using blocking integration
    content <- BlockingIntegration.readFileBlocking(testFile)

    // Clean up test file
    _ <- Eru.effect(new File(testFile).delete()).mapError {
      case _: java.io.IOException => IntegrationError.ServiceUnavailable("FileSystem", s"Cannot delete file $testFile")
      case other => IntegrationError.ServiceUnavailable("FileSystem", other.getMessage)
    }

    result <- Eru.succeed(s"File content (${content.length} chars): ${content.take(50)}...")
  } yield result
}

val blockingResult = blockingIntegrationExample().attempt.unsafeRunSync()
blockingResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"Blocking integration: $result")
  case net.ghoula.eru.Result.Failure(error) => error match {
    case IntegrationError.FileNotFound(path) => println(s"File not found: $path")
    case IntegrationError.ServiceUnavailable(service, reason) => println(s"$service unavailable: $reason")
    case other => println(s"Blocking integration error: $other")
  }
}

// Database pool example
val connectionPool = BlockingIntegration.ConnectionPool(3)

def databaseExample(): Eru[String, List[String]] = {
  val queries = List("SELECT * FROM users", "SELECT * FROM orders", "SELECT * FROM products")

  // Execute queries in parallel with connection pooling
  Eru.traverse(queries) { query =>
    connectionPool.withConnection { connection =>
      Eru.effect(connection.executeQuery(query)).mapError(_.getMessage)
    }
  }
}

val dbResult = databaseExample().unsafeRunSync()
println(s"Database queries completed: ${dbResult.size} results")
dbResult.foreach(result => println(s"  $result"))
```

## Third-Party Library Integration

Common patterns for integrating with popular Scala and Java libraries:

```scala mdoc
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Try, Success, Failure}

// Pattern 3: Future integration
object FutureIntegration {

  // Convert Future to Eru
  def fromFuture[A](future: Future[A])(using ExecutionContext): Eru[Throwable, A] = {
    Eru.effect {
      // In production, you'd use proper async integration
      // This is a simplified blocking version for demonstration
      import scala.concurrent.duration._
      scala.concurrent.Await.result(future, 10.seconds)
    }
  }

  // Convert Eru to Future
  def toFuture[E, A](eru: Eru[E, A])(using ExecutionContext): Future[A] = {
    Future {
      eru.unsafeRunSync() match {
        case value => value
      }
    }.recover {
      case ex => throw ex
    }
  }

  // Example service that returns Futures
  class AsyncUserService {
    def fetchUserAsync(id: String)(using ExecutionContext): Future[String] = {
      Future {
        Thread.sleep(15) // Simulate async work
        if (id.nonEmpty) s"Async user data for $id" else throw new Exception("Invalid ID")
      }
    }
  }

  def integrateAsyncService(): Eru[Throwable, String] = {
    given ExecutionContext = ExecutionContext.global
    val service = new AsyncUserService()

    for {
      user1 <- fromFuture(service.fetchUserAsync("user1"))
      user2 <- fromFuture(service.fetchUserAsync("user2"))
      combined <- Eru.succeed(s"Combined: $user1 & $user2")
    } yield combined
  }
}

val futureResult = FutureIntegration.integrateAsyncService().attempt.unsafeRunSync()
futureResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"Future integration: $result")
  case net.ghoula.eru.Result.Failure(error) => println(s"Future integration error: $error")
}

// Pattern 4: Try integration
object TryIntegration {

  def fromTry[A](tryValue: Try[A]): Eru[Throwable, A] = {
    tryValue match {
      case Success(value) => Eru.succeed(value)
      case Failure(exception) => Eru.fail(exception)
    }
  }

  def toTry[E <: Throwable, A](eru: Eru[E, A]): Try[A] = {
    eru.attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Success(value) => Success(value)
      case net.ghoula.eru.Result.Failure(error) => Failure(error)
    }
  }

  // Example service that returns Try
  def parseNumberTry(str: String): Try[Int] = {
    Try(str.toInt)
  }

  def integrateTryOperations(): Eru[Throwable, Int] = {
    for {
      num1 <- fromTry(parseNumberTry("42"))
      num2 <- fromTry(parseNumberTry("58"))
      sum <- Eru.succeed(num1 + num2)
    } yield sum
  }
}

val tryResult = TryIntegration.integrateTryOperations().attempt.unsafeRunSync()
tryResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"Try integration: $result")
  case net.ghoula.eru.Result.Failure(error) => println(s"Try integration error: $error")
}
```

## JSON Library Integration

Integrate with popular JSON libraries safely:

```scala mdoc
// Simulate JSON library integration (simplified version)
object JsonIntegration {

  // Simulated JSON library
  case class JsonValue(value: String)
  case class JsonParseException(message: String) extends Exception(message)

  object FakeJsonLib {
    def parse(jsonString: String): JsonValue = {
      if (jsonString.startsWith("{") && jsonString.endsWith("}")) {
        JsonValue(jsonString)
      } else {
        throw JsonParseException(s"Invalid JSON: $jsonString")
      }
    }

    def stringify(value: Map[String, Any]): String = {
      "{" + value.map { case (k, v) => s"\"$k\":\"$v\"" }.mkString(",") + "}"
    }
  }

  // Safe JSON operations with Eru
  def parseJson(jsonString: String): Eru[String, JsonValue] = {
    Eru.effect(FakeJsonLib.parse(jsonString)).mapError {
      case JsonParseException(msg) => s"JSON parsing failed: $msg"
      case other => s"Unexpected error: ${other.getMessage}"
    }
  }

  def stringifyJson(data: Map[String, Any]): Eru[String, String] = {
    Eru.effect(FakeJsonLib.stringify(data)).mapError(_.getMessage)
  }

  // HTTP API simulation with JSON
  case class User(id: String, name: String, email: String)

  def fetchUserJson(userId: String): Eru[String, User] = {
    val mockJsonResponse = s"""{"id":"$userId","name":"User $userId","email":"$userId@example.com"}"""

    for {
      json <- parseJson(mockJsonResponse)
      user <- Eru.effect {
        // Simulate JSON deserialization
        User(userId, s"User $userId", s"$userId@example.com")
      }.mapError(_.getMessage)
    } yield user
  }

  def createUserJson(user: User): Eru[String, String] = {
    val userData = Map(
      "id" -> user.id,
      "name" -> user.name,
      "email" -> user.email
    )

    stringifyJson(userData)
  }
}

// JSON integration example
def jsonIntegrationExample(): Eru[String, String] = {
  for {
    user <- JsonIntegration.fetchUserJson("12345")
    updatedUser = user.copy(name = "Updated " + user.name)
    jsonString <- JsonIntegration.createUserJson(updatedUser)
    result <- Eru.succeed(s"User processed: $jsonString")
  } yield result
}

val jsonResult = jsonIntegrationExample().attempt.unsafeRunSync()
jsonResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"JSON integration: $result")
  case net.ghoula.eru.Result.Failure(error) => println(s"JSON integration error: $error")
}
```

## Configuration and Environment Integration

Integrate with configuration libraries and environment variables:

```scala mdoc
import scala.util.Properties

// Pattern 5: Configuration integration
object ConfigIntegration {

  case class DatabaseConfig(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String
  )

  case class AppConfig(
    database: DatabaseConfig,
    httpPort: Int,
    logLevel: String
  )

  // Safe environment variable access
  def getEnvVar(name: String): Eru[String, String] = {
    Eru.effect {
      Option(System.getenv(name))
        .getOrElse(throw new RuntimeException(s"Environment variable $name not found"))
    }.mapError(_.getMessage)
  }

  def getEnvVarWithDefault(name: String, default: String): Eru[Nothing, String] = {
    Eru.succeed(Option(System.getenv(name)).getOrElse(default))
  }

  def getIntEnvVar(name: String): Eru[String, Int] = {
    getEnvVar(name).flatMap { value =>
      Eru.effect(value.toInt).mapError(_ => s"Invalid integer value for $name: $value")
    }
  }

  // Configuration loading with validation
  def loadDatabaseConfig(): Eru[String, DatabaseConfig] = {
    for {
      host <- getEnvVarWithDefault("DB_HOST", "localhost")
      port <- getEnvVarWithDefault("DB_PORT", "5432").flatMap { portStr =>
        Eru.effect(portStr.toInt).mapError(_ => s"Invalid port number: $portStr")
      }
      database <- getEnvVarWithDefault("DB_NAME", "myapp")
      username <- getEnvVarWithDefault("DB_USER", "user")
      password <- getEnvVarWithDefault("DB_PASS", "password")

      config <- Eru.succeed(DatabaseConfig(host, port, database, username, password))

      // Validate configuration
      _ <- if (config.host.nonEmpty && config.database.nonEmpty) {
        Eru.succeed(())
      } else {
        Eru.fail("Invalid database configuration: host and database cannot be empty")
      }

    } yield config
  }

  def loadAppConfig(): Eru[String, AppConfig] = {
    for {
      dbConfig <- loadDatabaseConfig()
      httpPort <- getEnvVarWithDefault("HTTP_PORT", "8080").flatMap { portStr =>
        Eru.effect(portStr.toInt).mapError(_ => s"Invalid HTTP port: $portStr")
      }
      logLevel <- getEnvVarWithDefault("LOG_LEVEL", "INFO")

      // Validate log level
      _ <- if (List("DEBUG", "INFO", "WARN", "ERROR").contains(logLevel)) {
        Eru.succeed(())
      } else {
        Eru.fail(s"Invalid log level: $logLevel")
      }

      appConfig <- Eru.succeed(AppConfig(dbConfig, httpPort, logLevel))
    } yield appConfig
  }
}

// Configuration example
def configExample(): Eru[String, String] = {
  for {
    config <- ConfigIntegration.loadAppConfig()
    summary <- Eru.succeed {
      s"App configured: DB=${config.database.host}:${config.database.port}/${config.database.database}, " +
      s"HTTP=${config.httpPort}, LogLevel=${config.logLevel}"
    }
  } yield summary
}

val configResult = configExample().attempt.unsafeRunSync()
configResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"Config integration: $result")
  case net.ghoula.eru.Result.Failure(error) => println(s"Config integration error: $error")
}
```

## Testing Integration

Integrate Eru programs with testing frameworks:

```scala mdoc
// Pattern 6: Testing integration patterns
object TestingIntegration {

  // Test utilities for Eru programs
  def shouldSucceedWith[E, A](program: Eru[E, A], expected: A): Boolean = {
    program.attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Success(actual) => actual == expected
      case _ => false
    }
  }

  def shouldFailWith[E, A](program: Eru[E, A], expectedError: E): Boolean = {
    program.attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Failure(actual) => actual == expectedError
      case _ => false
    }
  }

  def shouldComplete[E, A](program: Eru[E, A]): Boolean = {
    try {
      program.unsafeRunSync()
      true
    } catch {
      case _ => false
    }
  }

  // Mock service for testing
  trait UserRepository {
    def findUser(id: String): Eru[String, Option[String]]
    def saveUser(id: String, data: String): Eru[String, Unit]
  }

  class MockUserRepository extends UserRepository {
    private var users = Map[String, String]()

    def findUser(id: String): Eru[String, Option[String]] = {
      if (id.isEmpty) {
        Eru.fail("Invalid user ID")
      } else {
        Eru.succeed(users.get(id))
      }
    }

    def saveUser(id: String, data: String): Eru[String, Unit] = {
      if (id.isEmpty || data.isEmpty) {
        Eru.fail("Invalid user data")
      } else {
        Eru.effect {
          users = users + (id -> data)
        }.mapError(_.getMessage)
      }
    }
  }

  // Service under test
  class UserService(repository: UserRepository) {
    def getOrCreateUser(id: String): Eru[String, String] = {
      repository.findUser(id).flatMap {
        case Some(userData) => Eru.succeed(userData)
        case None =>
          val defaultData = s"Default user data for $id"
          repository.saveUser(id, defaultData).map(_ => defaultData)
      }
    }
  }

  // Test suite simulation
  def runTests(): Unit = {
    println("=== ERU TESTING INTEGRATION ===")

    val mockRepo = new MockUserRepository()
    val userService = new UserService(mockRepo)

    // Test 1: Get existing user
    val getExistingTest = for {
      _ <- mockRepo.saveUser("user1", "existing data")
      result <- userService.getOrCreateUser("user1")
    } yield result

    val test1Result = shouldSucceedWith(getExistingTest, "existing data")
    println(s"Test 1 (get existing user): ${if (test1Result) "PASS" else "FAIL"}")

    // Test 2: Create new user
    val createNewTest = userService.getOrCreateUser("user2")
    val expectedNewData = "Default user data for user2"
    val test2Result = shouldSucceedWith(createNewTest, expectedNewData)
    println(s"Test 2 (create new user): ${if (test2Result) "PASS" else "FAIL"}")

    // Test 3: Handle invalid input
    val invalidInputTest = userService.getOrCreateUser("")
    val test3Result = shouldFailWith(invalidInputTest, "Invalid user ID")
    println(s"Test 3 (invalid input): ${if (test3Result) "PASS" else "FAIL"}")

    // Test 4: Complex scenario
    val complexTest = for {
      user1 <- userService.getOrCreateUser("complex1")
      user2 <- userService.getOrCreateUser("complex2")
      combined <- Eru.succeed(s"$user1 + $user2")
    } yield combined

    val test4Result = shouldComplete(complexTest)
    println(s"Test 4 (complex scenario): ${if (test4Result) "PASS" else "FAIL"}")
  }
}

TestingIntegration.runTests()
```

## Migration Strategies

Strategies for gradually migrating existing codebases to Eru:

```scala mdoc
// Pattern 7: Gradual migration patterns
object MigrationStrategies {

  // Existing legacy service
  class LegacyOrderService {
    def processOrder(orderId: String): String = {
      if (orderId.startsWith("INVALID")) {
        throw new RuntimeException("Invalid order")
      }
      Thread.sleep(5) // Simulate processing time
      s"Processed order $orderId"
    }

    def sendNotification(message: String): Boolean = {
      println(s"Notification: $message")
      true
    }
  }

  // Step 1: Wrapper layer - Start by wrapping legacy calls
  class WrappedOrderService(legacy: LegacyOrderService) {
    def processOrderSafely(orderId: String): Eru[String, String] = {
      Eru.effect(legacy.processOrder(orderId)).mapError(_.getMessage)
    }

    def sendNotificationSafely(message: String): Eru[String, Boolean] = {
      Eru.effect(legacy.sendNotification(message)).mapError(_.getMessage)
    }
  }

  // Step 2: New Eru-native implementations alongside legacy
  class ModernOrderService(legacy: LegacyOrderService) extends WrappedOrderService(legacy) {

    // New validation logic in Eru
    def validateOrder(orderId: String): Eru[String, String] = {
      if (orderId.isEmpty) {
        Eru.fail("Order ID cannot be empty")
      } else if (orderId.length < 3) {
        Eru.fail("Order ID too short")
      } else if (orderId.startsWith("INVALID")) {
        Eru.fail("Invalid order ID format")
      } else {
        Eru.succeed(orderId)
      }
    }

    // Enhanced processing with validation
    def processOrderWithValidation(orderId: String): Eru[String, String] = {
      for {
        validOrderId <- validateOrder(orderId)
        result <- processOrderSafely(validOrderId)
        _ <- sendNotificationSafely(s"Order $validOrderId completed")
      } yield result
    }

    // New audit logging functionality
    def auditLog(action: String, orderId: String): Eru[String, Unit] = {
      Eru.effect {
        println(s"AUDIT: $action for order $orderId at ${System.currentTimeMillis()}")
      }.mapError(_.getMessage)
    }

    // Complete modern workflow
    def processOrderModern(orderId: String): Eru[String, String] = {
      for {
        _ <- auditLog("PROCESS_START", orderId)
        result <- processOrderWithValidation(orderId)
        _ <- auditLog("PROCESS_COMPLETE", orderId)
      } yield result
    }
  }

  // Step 3: Batch migration pattern
  def migrateOrdersBatch(orderIds: List[String]): Eru[String, List[Either[String, String]]] = {
    val legacy = new LegacyOrderService()
    val modern = new ModernOrderService(legacy)

    // Process orders in parallel, collecting both successes and failures
    Eru.traverse(orderIds) { orderId =>
      modern.processOrderModern(orderId).attempt.map {
        case net.ghoula.eru.Result.Success(result) => Right(result)
        case net.ghoula.eru.Result.Failure(error) => Left(error)
      }
    }
  }

  // Integration bridge pattern
  trait OrderProcessor {
    def process(orderId: String): String
  }

  // Legacy implementation
  class LegacyProcessor(service: LegacyOrderService) extends OrderProcessor {
    def process(orderId: String): String = service.processOrder(orderId)
  }

  // Eru implementation
  class EruProcessor(service: ModernOrderService) extends OrderProcessor {
    def process(orderId: String): String = {
      service.processOrderModern(orderId).attempt.unsafeRunSync() match {
        case net.ghoula.eru.Result.Success(result) => result
        case net.ghoula.eru.Result.Failure(error) => throw new RuntimeException(error)
      }
    }
  }
}

// Migration example
def migrationExample(): Eru[String, String] = {
  val testOrders = List("ORDER001", "ORDER002", "INVALID003", "ORDER004")

  MigrationStrategies.migrateOrdersBatch(testOrders).map { results =>
    val successes = results.collect { case Right(result) => result }
    val failures = results.collect { case Left(error) => error }

    s"Migration completed: ${successes.size} successes, ${failures.size} failures"
  }
}

val migrationResult = migrationExample().unsafeRunSync()
println(s"Migration result: $migrationResult")
```

## Performance Considerations for Integration

Optimize integration points for production performance:

```scala mdoc
// Pattern 8: High-performance integration patterns
object PerformanceIntegration {

  // Connection pooling for expensive resources
  case class ExpensiveResource(id: String) {
    def process(data: String): String = {
      Thread.sleep(1) // Simulate expensive operation
      s"$id processed: $data"
    }
    def close(): Unit = println(s"Closed resource $id")
  }

  class ResourcePool[R](factory: () => R, maxSize: Int) {
    private val available = scala.collection.mutable.Queue[R]()
    private var created = 0

    def withResource[A](operation: R => Eru[String, A]): Eru[String, A] = {
      acquireResource().bracket(release = releaseResource)(operation)
    }

    private def acquireResource(): Eru[String, R] = {
      Eru.effect {
        synchronized {
          if (available.nonEmpty) {
            available.dequeue()
          } else if (created < maxSize) {
            created += 1
            factory()
          } else {
            throw new RuntimeException("Resource pool exhausted")
          }
        }
      }.mapError(_.getMessage)
    }

    private def releaseResource(resource: R): Eru[String, Unit] = {
      Eru.effect {
        synchronized {
          available.enqueue(resource)
          () // Return Unit explicitly
        }
      }.mapError(_.getMessage)
    }
  }

  // Batching pattern for bulk operations
  def batchProcess[A, B](
    items: List[A],
    batchSize: Int
  )(processor: List[A] => Eru[String, List[B]]): Eru[String, List[B]] = {
    val batches = items.grouped(batchSize).toList

    Eru.traverse(batches)(processor).map(_.flatten)
  }

  // Caching pattern for expensive computations
  class CachingService[K, V] {
    private val cache = scala.collection.mutable.Map[K, V]()

    def getOrCompute(key: K)(computation: K => Eru[String, V]): Eru[String, V] = {
      Eru.effect {
        cache.get(key)
      }.mapError(_.getMessage).flatMap {
        case Some(cached) => Eru.succeed(cached)
        case None => computation(key).tap { value =>
          Eru.effect {
            cache(key) = value
          }.mapError(_.getMessage)
        }
      }
    }
  }

  // Example usage of performance patterns
  def performanceExample(): Eru[String, String] = {
    // Resource pool
    val resourcePool = new ResourcePool(
      () => ExpensiveResource(s"resource-${System.currentTimeMillis() % 1000}"),
      maxSize = 5
    )

    // Caching service
    val cache = new CachingService[String, String]()

    def expensiveComputation(key: String): Eru[String, String] = {
      Eru.effect {
        Thread.sleep(10) // Simulate expensive computation
        s"Computed value for $key"
      }.mapError(_.getMessage)
    }

    for {
      // Use resource pool
      poolResult <- resourcePool.withResource { resource =>
        Eru.succeed(resource.process("test data"))
      }

      // Use caching (first call computes, second call hits cache)
      cachedResult1 <- cache.getOrCompute("key1")(expensiveComputation)
      cachedResult2 <- cache.getOrCompute("key1")(expensiveComputation) // Cache hit

      // Batch processing
      items = (1 to 100).map(i => s"item-$i").toList
      batchResults <- batchProcess(items, batchSize = 10) { batch =>
        Eru.succeed(batch.map(item => s"processed-$item"))
      }

      summary <- Eru.succeed {
        s"Performance patterns: pool=$poolResult, cache=$cachedResult1==$cachedResult2, batches=${batchResults.size}"
      }

    } yield summary
  }
}

val perfResult = PerformanceIntegration.performanceExample().unsafeRunSync()
println(s"Performance integration: $perfResult")
```

## Key Takeaways

Eru's integration patterns enable seamless adoption in existing systems:

**Gradual Migration**: Start by wrapping legacy code, then gradually introduce Eru-native implementations alongside existing code.

**Safety Preservation**: Use `Eru.effect` and proper error handling to maintain safety guarantees even when integrating unsafe operations.

**Resource Management**: Apply `bracket` and `ensure` patterns consistently for resource cleanup, even in integration scenarios.

**Performance Optimization**: Use connection pooling, caching, and batching patterns to optimize integration points.

**Testing Integration**: Create mock implementations and use Eru's deterministic execution for reliable testing.

**Ecosystem Compatibility**: Integrate smoothly with Future, Try, JSON libraries, and configuration systems.

**Production Ready**: Apply monitoring, observability, and error handling patterns to integration code.

## What's Next

Chapter 14 explores the Eru ecosystem, including Valar integration, community patterns, migration strategies from other effect systems, and the broader vision for Eru's place in the Scala ecosystem.