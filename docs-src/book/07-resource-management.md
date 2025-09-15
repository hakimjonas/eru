# Chapter 7: Resource Management

*"Good programs clean up after themselves, even when things go wrong."*

Resource management is one of the most error-prone aspects of programming. Files that stay open, connections that aren't closed, memory that isn't freed—these issues can bring down production systems. This chapter shows how Eru's `ensure` combinator provides reliable resource cleanup.

## The Resource Problem

Traditional resource management relies on try/finally blocks or try-with-resources constructs. While these work for simple cases, they become unwieldy with complex control flow:

```scala mdoc
import net.ghoula.eru.prelude.*

// Traditional approach - verbose and error-prone
def traditionalFileProcessing(filename: String): String = {
  val source = scala.io.Source.fromFile(filename)
  try {
    val content = source.mkString
    // More processing here...
    content.toUpperCase
  } finally {
    source.close()
  }
}

// This approach has problems:
// - No composition with other effect operations
// - Exception handling is separate from resource management  
// - Difficult to combine multiple resources
```

## Eru's `ensure` Combinator

Eru provides the `ensure` combinator for guaranteed resource cleanup. It ensures that cleanup code runs regardless of whether the main computation succeeds or fails:

```scala mdoc
case class FileResource(filename: String) {
  val source = scala.io.Source.fromFile(filename)
  def content: String = source.mkString
  def close(): Unit = source.close()
}

def openFile(filename: String): Eru[String, FileResource] = 
  Eru.effect(FileResource(filename)).mapError(_.getMessage)

def processContent(resource: FileResource): Eru[String, String] = 
  Eru.effect(resource.content.toUpperCase).mapError(_.getMessage)

// Guaranteed cleanup with ensure
def safeFileProcessing(filename: String): Eru[String, String] = {
  openFile(filename).flatMap { resource =>
    processContent(resource).ensure(Eru.effect(resource.close()))
  }
}

// Test with a file that exists (create a simple test file)
val testFile = "test-file.txt"
scala.util.Using(java.io.PrintWriter(testFile)) { writer =>
  writer.println("hello world")
}

val result = safeFileProcessing(testFile).attempt.unsafeRunSync()
println(s"Processing result: $result")

// Cleanup test file
java.io.File(testFile).delete()
```

The `ensure` combinator guarantees that `resource.close()` will be called whether `processContent` succeeds or fails.

## Composing Multiple Resources

Real applications often need to manage multiple resources. Eru's combinators make this straightforward:

```scala mdoc
case class DatabaseConnection(name: String) {
  def query(sql: String): String = s"$name: Results for $sql"
  def close(): Unit = println(s"Closing $name")
}

case class CacheConnection(name: String) {
  def get(key: String): Option[String] = Some(s"cached-$key")
  def close(): Unit = println(s"Closing $name")
}

def openDatabase(): Eru[String, DatabaseConnection] = 
  Eru.succeed(DatabaseConnection("primary-db"))

def openCache(): Eru[String, CacheConnection] = 
  Eru.succeed(CacheConnection("redis-cache"))

// Compose multiple resources with proper cleanup
def businessLogic(): Eru[String, String] = {
  openDatabase().flatMap { db =>
    openCache().flatMap { cache =>
      // Use both resources
      val operation = for {
        cached <- Eru.succeed(cache.get("user:123"))
        result <- cached match {
          case Some(value) => Eru.succeed(s"Cache hit: $value")
          case None => Eru.succeed(db.query("SELECT * FROM users WHERE id = 123"))
        }
      } yield result

      // Ensure both resources are cleaned up
      operation
        .ensure(Eru.effect(cache.close()))
        .ensure(Eru.effect(db.close()))
    }
  }
}

val businessResult = businessLogic().unsafeRunSync()
println(businessResult)
```

## Error Scenarios and Cleanup

The power of `ensure` becomes clear when errors occur. Let's see how cleanup happens even during failures:

```scala mdoc
def unreliableResource(): Eru[String, String] = {
  val resource = "important-resource"
  println(s"Acquired: $resource")
  
  // Simulate an operation that might fail
  val operation = if (scala.util.Random.nextBoolean()) {
    Eru.succeed("Operation completed")
  } else {
    Eru.fail("Operation failed")
  }
  
  // Cleanup happens regardless of success/failure
  operation.ensure(Eru.effect {
    println(s"Releasing: $resource")
  })
}

// Run multiple times to see both success and failure cases
println("=== Resource Management Test ===")
(1 to 3).foreach { i =>
  println(s"\nAttempt $i:")
  val result = unreliableResource().attempt.unsafeRunSync()
  println(s"Result: $result")
}
```

Notice how "Releasing: important-resource" is printed whether the operation succeeds or fails.

## Nested Resource Management

Complex applications often have nested resource dependencies. Eru handles these naturally:

```scala mdoc
case class OuterResource(name: String) {
  def createInner(): InnerResource = InnerResource(s"$name-inner")
  def close(): Unit = println(s"Closing outer: $name")
}

case class InnerResource(name: String) {
  def doWork(): String = s"Work done by $name"
  def close(): Unit = println(s"Closing inner: $name")
}

def nestedResourceExample(): Eru[String, String] = {
  val outerResource = OuterResource("outer")
  
  Eru.effect(outerResource.createInner()).mapError(_.getMessage).flatMap { innerResource =>
    val work = Eru.effect(innerResource.doWork()).mapError(_.getMessage)
    
    // Nested cleanup - inner resource cleaned up first, then outer
    work
      .ensure(Eru.effect(innerResource.close()))
      .ensure(Eru.effect(outerResource.close()))
  }
}

val nestedResult = nestedResourceExample().unsafeRunSync()
println(s"Nested result: $nestedResult")
```

The cleanup order is important: inner resources are cleaned up before outer resources, following the natural stack-like behavior you'd expect.

## Resource Patterns

### Pattern 1: Simple Resource Wrapper

```scala mdoc
def withResource[R, A](
  acquire: Eru[String, R],
  use: R => Eru[String, A],
  release: R => Eru[String, Unit]
): Eru[String, A] = {
  acquire.flatMap { resource =>
    use(resource).ensure(release(resource))
  }
}

// Usage
def fileExample(): Eru[String, String] = {
  withResource(
    acquire = Eru.effect(scala.io.Source.fromFile("example.txt")).mapError(_.getMessage),
    use = source => Eru.effect(source.mkString.take(100)).mapError(_.getMessage),
    release = source => Eru.effect(source.close()).mapError(_.getMessage)
  )
}
```

### Pattern 2: Resource Pool Management

```scala mdoc
case class ConnectionPool(size: Int) {
  private var active = 0
  
  def acquire(): Eru[String, Connection] = {
    if (active < size) {
      active += 1
      Eru.succeed(Connection(s"conn-$active"))
    } else {
      Eru.fail("Pool exhausted")
    }
  }
  
  def release(conn: Connection): Unit = {
    println(s"Returned ${conn.id} to pool")
    active -= 1
  }
}

case class Connection(id: String) {
  def executeQuery(sql: String): String = s"$id executed: $sql"
}

val pool = ConnectionPool(2)

def pooledOperation(sql: String): Eru[String, String] = {
  pool.acquire().flatMap { connection =>
    val query = Eru.effect(connection.executeQuery(sql)).mapError(_.getMessage)
    query.ensure(Eru.effect(pool.release(connection)).mapError(_.getMessage))
  }
}

// Multiple operations sharing the pool
val poolResults = for {
  result1 <- pooledOperation("SELECT * FROM users")
  result2 <- pooledOperation("SELECT * FROM orders") 
} yield List(result1, result2)

val poolTest = poolResults.unsafeRunSync()
poolTest.foreach(println)
```

### Pattern 3: Conditional Resource Management

```scala mdoc
def conditionalResource(useCache: Boolean): Eru[String, String] = {
  if (useCache) {
    // Use cache resource
    val cache = "cache-connection"
    println(s"Opening $cache")
    
    val operation = Eru.succeed("cached data")
    operation.ensure(Eru.effect(println(s"Closing $cache")))
  } else {
    // Use database resource  
    val database = "db-connection"
    println(s"Opening $database")
    
    val operation = Eru.succeed("fresh data from database")
    operation.ensure(Eru.effect(println(s"Closing $database")))
  }
}

val cacheResult = conditionalResource(useCache = true).unsafeRunSync()
val dbResult = conditionalResource(useCache = false).unsafeRunSync()
println(s"Cache: $cacheResult")
println(s"Database: $dbResult")
```

## The Bracket Pattern

The `bracket` method provides the gold standard for resource safety. It offers a more elegant approach where the three distinct parts of resource management are visually clear:

```scala mdoc
// Simple mock resource for demonstration
case class MockResource(name: String) {
  def getData(): String = s"Data from $name"
  def close(): Unit = println(s"Closed $name")
}

def acquireMockResource(): Eru[String, MockResource] = 
  Eru.succeed(MockResource("config-resource"))

// The bracket pattern makes resource management explicit and safe
def bracketExample(): Eru[String, String] = {
  acquireMockResource()  // <-- ACQUIRE: Get the resource
    .bracket(              // <-- The bracket operation
      release = resource => Eru.effect(resource.close())  // <-- RELEASE: Guaranteed cleanup
    ) {
      resource => Eru.succeed(resource.getData())  // <-- USE: Work with the resource
    }
}

val bracketResult = bracketExample().unsafeRunSync()
println(s"Bracket result: $bracketResult")
```

The three components are clearly separated:

- **ACQUIRE**: The initial effect that obtains the resource (`acquireMockResource`)
- **RELEASE**: The cleanup function that's guaranteed to run (`resource => close(resource)`)  
- **USE**: The work to be done with the resource (`resource => getData(resource)`)

This pattern ensures that no matter what happens in the USE phase—success, failure, or interruption—the RELEASE function will execute. Importantly, if the ACQUIRE step fails, the RELEASE function is not called (as there's nothing to release), which is the correct and safe behavior.

### Bracket vs Ensure

While `ensure` is great for simple cleanup, `bracket` provides stronger guarantees:

```scala mdoc
// Comparison of patterns using mock resources
def ensurePattern(): Eru[String, String] = {
  acquireMockResource().flatMap { resource =>
    Eru.succeed(resource.getData()).ensure(Eru.effect(resource.close()))
  }
}

def bracketPattern(): Eru[String, String] = {
  acquireMockResource().bracket(
    release = resource => Eru.effect(resource.close())
  ) {
    resource => Eru.succeed(resource.getData())
  }
}

// Both work the same way, but bracket is more explicit about the pattern
val ensureResult = ensurePattern().unsafeRunSync()
val bracketResult2 = bracketPattern().unsafeRunSync()

println(s"Ensure: $ensureResult")
println(s"Bracket: $bracketResult2")
```

Use `bracket` when you have the classic acquire-use-release pattern. Use `ensure` for simpler cleanup scenarios.

## Key Takeaways

Understanding resource management with Eru provides several benefits:

**Guaranteed Cleanup**: The `ensure` combinator runs cleanup code regardless of success or failure.

**Composable**: Resource management composes naturally with other Eru operations using `flatMap` and `ensure`.

**Predictable Ordering**: Cleanup happens in reverse order of acquisition, following stack semantics.

**Error Safe**: Resource cleanup occurs even when errors happen during resource usage.

**Testable**: Resource management logic can be tested like any other Eru program.

## What's Next

In Chapter 8, we'll explore cross-platform development with Eru, covering the differences between JVM and Native execution models and how to write code that works consistently across both platforms.

---

*"The mark of good code is not just what it does, but what it cleans up."*