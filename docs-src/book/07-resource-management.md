# Chapter 7: Resource Management

Resource management is one of the most error-prone aspects of programming. Files that stay open, connections that aren't closed, memory that isn't freed: these issues can bring down production systems. This chapter shows how Eru's `ensure` and `bracket` combinators provide reliable resource cleanup.

## The resource problem

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

## Eru's ensure combinator

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

## Composing multiple resources

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

      // Finalizers run last-registered first, so register them in
      // acquisition-reverse order: cache (acquired last) closes first.
      operation
        .ensure(Eru.effect(db.close()))
        .ensure(Eru.effect(cache.close()))
    }
  }
}

val businessResult = businessLogic().unsafeRunSync()
println(businessResult)
```

## Error scenarios and cleanup

The next example shows cleanup when the operation fails:

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

## Nested resource management

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
    
    // Finalizers run last-registered first: register outer's close first
    // so that the inner resource (acquired last) is closed first.
    work
      .ensure(Eru.effect(outerResource.close()))
      .ensure(Eru.effect(innerResource.close()))
  }
}

val nestedResult = nestedResourceExample().unsafeRunSync()
println(s"Nested result: $nestedResult")
```

The cleanup order matters: the inner resource closes before the outer resource. Finalizers run last-registered first, so register them in acquisition-reverse order.

## Resource patterns

### Pattern 1: simple resource wrapper

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

### Pattern 2: resource pool management

```scala mdoc
case class ConnectionPool(size: Int) {
  private var active = 0

  def acquire(): Eru[String, Connection] = Eru.effect {
    // The mutation runs inside the effect: calling acquire() only DESCRIBES the program.
    // Incrementing at construction time would execute on every program build and corrupt
    // the pool's reference counts.
    if (active < size) {
      active += 1
      Connection(s"conn-$active")
    } else {
      throw new IllegalStateException("Pool exhausted")
    }
  }.mapError {
    case e: IllegalStateException => e.getMessage
    case t                         => t.getMessage
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

### Pattern 3: conditional resource management

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

## The bracket pattern

The `bracket` method is the primary resource-safety combinator. Its three parts are visible at the call site:

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

- ACQUIRE: the initial effect that obtains the resource (`acquireMockResource`)
- RELEASE: the cleanup function that's guaranteed to run (`resource => close(resource)`)
- USE: the work to be done with the resource (`resource => getData(resource)`)

Whatever happens in the USE phase, RELEASE runs. If ACQUIRE fails, RELEASE does not run: there is nothing to release.

### Bracket vs ensure

`bracket` is `ensure`, rearranged: `acquire.bracket(release)(use)` is equivalent to `acquire.flatMap(a => use(a).ensure(release(a)))`. `bracket` states the acquire-use-release shape at the call site; `ensure` attaches a finalizer to an existing computation:

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

Use `bracket` for the classic acquire-use-release pattern. Use `ensure` to attach a finalizer to a computation you already have.

## Key takeaways


Guaranteed cleanup: `ensure` runs the finalizer whether the computation succeeds or fails.

Composable: resource management composes with other Eru operations through `flatMap` and `ensure`.

Predictable ordering: finalizers run last-registered first. Register them so the last-acquired resource closes first.

Error safe: cleanup occurs even when the computation fails.

Testable: resource management logic can be tested like any other Eru program.

## What's next

Chapter 8 covers Eru's execution backends: the JVM virtual-thread backend and the sequential fallback.