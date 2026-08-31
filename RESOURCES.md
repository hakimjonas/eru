# Resource management in Eru

Eru provides principled resource safety with guaranteed cleanup semantics. The system ensures that resources are properly acquired, used, and released regardless of how the program terminates - whether through success, typed failure, defect, or interruption.

## Core principles

- **Guaranteed Execution**: Finalizers always run on any termination path
- **FILO Ordering**: Nested finalizers execute in last-in, first-out order
- **Laziness**: Resource operations are suspended until program execution
- **JVM**: Resource safety guarantees hold on the JVM, the only supported platform

## Basic resource operations

### `ensure` - guaranteed cleanup

Attach a finalizer that runs after the main effect, regardless of outcome:

```scala
import net.ghoula.eru.prelude.*

val fileOperation = Eru.effect {
  val file = new FileWriter("output.txt")
  file.write("Hello, World!")
  file
}.ensure(
  Eru.effect(println("Cleanup: File operation completed"))
)

val result = fileOperation.unsafeRunSync()
// Cleanup message always prints
```

### `bracket` - acquire-use-release pattern

The classic resource pattern for acquire/use/release:

```scala
import java.nio.file.*

val safeFileRead: Eru[Throwable, String] = 
  Eru.effect {
    Files.newBufferedReader(Paths.get("data.txt"))
  }.bracket { reader =>
    // Release: Always called
    Eru.effect(reader.close())
  } { reader =>
    // Use: Only called if acquire succeeds
    Eru.effect(reader.readLine())
  }

val content = safeFileRead.unsafeRunSync()
// Reader is guaranteed to be closed
```

### Multiple resources with `ensureAll`

Manage multiple cleanup operations:

```scala
val complexOperation = Eru.effect {
  val resource1 = acquireDatabase()
  val resource2 = acquireNetworkConnection()
  processData(resource1, resource2)
}.ensureAll(
  Eru.effect(database.close()),
  Eru.effect(connection.close()),
  Eru.effect(cleanupTempFiles())
)
```

## FILO finalizer ordering

Finalizers execute in last-in, first-out order:

```scala
var executionOrder = List.empty[String]

val nested = Eru.succeed("result")
  .ensure(Eru.effect { executionOrder = "outer" :: executionOrder })
  .ensure(Eru.effect { executionOrder = "middle" :: executionOrder })
  .ensure(Eru.effect { executionOrder = "inner" :: executionOrder })

nested.unsafeRunSync()
// executionOrder == List("outer", "middle", "inner")
```

## Resource safety guarantees

### Success path

```scala
var cleaned = false
val successfulProgram = Eru.succeed(42)
  .ensure(Eru.effect { cleaned = true })

val result = successfulProgram.unsafeRunSync() // 42
assert(cleaned == true)
```

### Failure path

```scala
var cleaned = false
val failedProgram = Eru.fail("boom")
  .ensure(Eru.effect { cleaned = true })

val result = failedProgram.attempt.unsafeRunSync()
// result == Result.Failure("boom")
assert(cleaned == true)
```

### Defect path

```scala
var cleaned = false
val defectProgram = Eru.effect { throw new RuntimeException("crash") }
  .ensure(Eru.effect { cleaned = true })

try {
  defectProgram.unsafeRunSync()
} catch {
  case _: RuntimeException => ()
}
assert(cleaned == true)
```

## Advanced resource patterns

### Conditional resource cleanup

```scala
def conditionalCleanup(shouldCleanup: Boolean): Eru[Nothing, String] = {
  val resource = Eru.succeed("acquired")
  
  if (shouldCleanup) {
    resource.ensure(Eru.effect(println("Resource cleaned up")))
  } else {
    resource
  }
}
```

### Resource pooling pattern

```scala
class ResourcePool[R] {
  private val available = new java.util.concurrent.ConcurrentLinkedQueue[R]()
  
  def acquire: Eru[String, R] = Eru.effect {
    Option(available.poll()).getOrElse(throw new RuntimeException("No resources available"))
  }
  
  def release(resource: R): Eru[Nothing, Unit] = Eru.effect {
    available.offer(resource)
  }
  
  def withResource[A](use: R => Eru[String, A]): Eru[String, A] = {
    acquire.bracket(release)(use)
  }
}
```

### Nested resource management

```scala
def processWithNestedResources: Eru[Throwable, String] = {
  // Outer resource
  Eru.effect(openDatabase()).bracket(db => Eru.effect(db.close())) { db =>
    // Inner resource  
    Eru.effect(openConnection()).bracket(conn => Eru.effect(conn.close())) { conn =>
      // Use both resources
      Eru.effect {
        val data = db.query("SELECT * FROM users")
        conn.send(data)
        "Processing complete"
      }
    }
  }
  // Connection closes first, then database (FILO order)
}
```

## Resource safety in concurrent context

Resources are properly managed even in concurrent programs:

```scala
def concurrentResourceProcessing: Eru[Throwable, List[String]] = {
  val files = List("file1.txt", "file2.txt", "file3.txt")
  
  val processFile = (filename: String) =>
    Eru.effect(Files.newBufferedReader(Paths.get(filename)))
      .bracket(reader => Eru.effect(reader.close())) { reader =>
        EruRuntime.fork {
          Eru.effect(reader.readLine())
        }.flatMap(_.await).map {
          case Exit.Success(content) => content
          case other => throw new RuntimeException(s"Failed to read $filename: $other")
        }
      }
  
  EruRuntime.parTraverse(files)(processFile)
  // All file readers guaranteed to be closed, even if some operations fail
}
```

## Error recovery with resources

```scala
def resilientFileProcessing(filename: String): Eru[String, String] = {
  Eru.effect(Files.newBufferedReader(Paths.get(filename)))
    .bracket(reader => Eru.effect(reader.close())) { reader =>
      Eru.effect(reader.readLine())
        .recover {
          case _: IOException => "File could not be read"
        }
    }
    .recoverWith {
      case _: NoSuchFileException => 
        Eru.succeed("File does not exist")
    }
}
```

## Resource behavior

### JVM platform
- Full support for all resource management patterns
- Concurrent finalizer execution with Virtual Threads  
- Integration with Java's resource management APIs
- Proper handling of interrupted threads

## Best practices

1. **Always Use Resource Management**: Never acquire resources without proper cleanup
2. **Prefer `bracket` for Complex Resources**: Use bracket when you need both acquire and release logic
3. **Use `ensure` for Simple Cleanup**: Use ensure for straightforward cleanup operations
4. **Test Resource Cleanup**: Verify that resources are properly cleaned up in failure scenarios
5. **Mind the Order**: Remember that finalizers execute in FILO order
6. **Handle Finalizer Errors**: Ensure finalizer code itself doesn't throw exceptions

## Integration with existing libraries

Eru resource management integrates well with existing Java and Scala resource patterns:

```scala
// Java AutoCloseable integration
def withAutoCloseable[R <: AutoCloseable, A](
  resource: => R
)(use: R => Eru[Throwable, A]): Eru[Throwable, A] = {
  Eru.effect(resource).bracket(r => Eru.effect(r.close()))(use)
}

// Usage
val result = withAutoCloseable(Files.newBufferedReader(path)) { reader =>
  Eru.effect(reader.readLine())
}
```

Resource management in Eru lets you build reliable, leak-free applications that handle cleanup regardless of how program execution terminates.