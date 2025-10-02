# Chapter 6: API Reference & Patterns

This chapter serves as a comprehensive reference to Eru's API patterns. Rather than listing every method, we focus on the ergonomic patterns that make Eru programs elegant and maintainable.

## Core Construction Patterns

### Creating Programs

```scala mdoc
import net.ghoula.eru.prelude.*

// Pure success - no effects
val success = Eru.succeed(42)

// Pure failure - no effects  
val failure = Eru.fail("Something went wrong")

// Suspend side effects safely
val effect = Eru.effect {
  println("This runs when executed")
  scala.util.Random.nextInt(100)
}

// From Option
val fromOption = Eru.fromOption(Some(42), "Value missing")

// From Try
import scala.util.Try
val fromTry = Eru.fromTry(Try(10 / 2))

// From Either
val fromEither = Eru.fromEither(Right(42): Either[String, Int])
```

### Conditional Construction

```scala mdoc
// Conditional success/failure
def validateAge(age: Int): Eru[String, Int] = 
  if (age >= 18) Eru.succeed(age) else Eru.fail("Must be 18 or older")

// Conditional effects
def processFile(path: String): Eru[String, String] = 
  if (java.nio.file.Files.exists(java.nio.file.Paths.get(path))) {
    Eru.effect(scala.io.Source.fromFile(path).mkString).mapError(_.getMessage)
  } else {
    Eru.fail("File not found")
  }
```

## Error Handling Patterns

### The Ergonomic `.fold()` Pattern

**Best Practice**: Use `.attempt.map(_.fold())` for explicit error handling:

```scala mdoc
val riskyOperation: Eru[String, Int] = Eru.fail("Database error")

// ELEGANT - the ergonomic way
val handled = riskyOperation.attempt.map(_.fold(
  ifFailure = error => s"Error: $error",
  ifSuccess = value => s"Success: $value"
))

val result = handled.unsafeRunSync()
println(result)
```

### Recovery Patterns

```scala mdoc
val unreliableService: Eru[String, String] = Eru.fail("Service down")

// Pattern 1: Simple fallback with specific error matching
val withFallback = unreliableService.fallback {
  case "Service down" => "Using cached data"
  case "Timeout" => "Using backup service"
}

// Pattern 2: Recover to pure values
val recovered = unreliableService.recover {
  case "Service down" => "Default value"
}

// Pattern 3: Recover with new Eru programs
val recoveredWith = unreliableService.recoverWith {
  case "Service down" => Eru.succeed("Backup data")
  case "Timeout" => Eru.effect { loadFromCache() }
}

def loadFromCache(): String = "Cached value"

val fallbackResult = withFallback.unsafeRunSync()
println(fallbackResult)
```

### Alternative Strategies with `orElse`

```scala mdoc
val primary: Eru[String, String] = Eru.fail("Primary failed")
val secondary: Eru[String, String] = Eru.fail("Secondary failed")  
val tertiary: Eru[String, String] = Eru.succeed("Tertiary works")

// Try alternatives in sequence
val firstSuccess = primary
  .orElse(secondary)
  .orElse(tertiary)

val orElseResult = firstSuccess.unsafeRunSync()
println(orElseResult)
```

### Error Type Transformation

```scala mdoc
enum NetworkError:
  case Timeout, ConnectionFailed, InvalidResponse

enum BusinessError:
  case ServiceUnavailable, InvalidData, ProcessingFailed

val networkCall: Eru[NetworkError, String] = Eru.fail(NetworkError.Timeout)

// Transform error types
val businessCall = networkCall.mapError {
  case NetworkError.Timeout => BusinessError.ServiceUnavailable
  case NetworkError.ConnectionFailed => BusinessError.ServiceUnavailable
  case NetworkError.InvalidResponse => BusinessError.InvalidData
}

val transformedResult = businessCall.attempt.unsafeRunSync()
println(transformedResult)
```

## Composition Patterns

### Sequential Composition with For-Comprehensions

```scala mdoc
def fetchUser(id: Int): Eru[String, String] = 
  if (id > 0) Eru.succeed(s"User-$id") else Eru.fail("Invalid ID")

def fetchProfile(user: String): Eru[String, String] = 
  Eru.succeed(s"Profile for $user")

def sendEmail(profile: String): Eru[String, String] = 
  Eru.succeed(s"Email sent to $profile")

// Sequential operations with dependency
val pipeline = for {
  user    <- fetchUser(123)
  profile <- fetchProfile(user)
  result  <- sendEmail(profile)
} yield result

val pipelineResult = pipeline.unsafeRunSync()
println(pipelineResult)
```

### Parallel Composition with `zip`

```scala mdoc
val config = Eru.succeed("production")
val version = Eru.succeed("1.0.0")
val features = Eru.succeed(List("auth", "api"))

// Combine independent computations
val appInfo = config.zip(version).zip(features).map {
  case ((env, ver), feats) => s"App $ver in $env with ${feats.mkString(",")}"
}

val appResult = appInfo.unsafeRunSync()
println(appResult)

// Zip with custom combination
val customZip = config.zip(version).map { (env, ver) =>
  s"Environment: $env, Version: $ver"
}

val customResult = customZip.unsafeRunSync()
println(customResult)
```

### Transformation Patterns

```scala mdoc
val numbers = Eru.succeed(List(1, 2, 3, 4, 5))

// Transform success values
val doubled = numbers.map(_.map(_ * 2))
val filtered = doubled.map(_.filter(_ > 5))
val summed = filtered.map(_.sum)

val transformResult = summed.unsafeRunSync()
println(transformResult)

// Chain transformations
val chained = Eru.succeed(42)
  .map(_ + 8)
  .map(_.toString)
  .map(s => s"Result: $s")

val chainedResult = chained.unsafeRunSync()
println(chainedResult)
```

## Resource Management Patterns

### Manual Resource Management Pattern

```scala mdoc
case class Resource(name: String) {
  def use(): String = s"Using $name"
  def close(): Unit = println(s"Closing $name")
}

def openResource(): Eru[String, Resource] = 
  Eru.succeed(Resource("Database"))

def useResource(resource: Resource): Eru[String, String] = 
  Eru.succeed(resource.use())

// Manual resource management with try/finally pattern
def safeResourceUse: Eru[String, String] = 
  openResource().flatMap { resource =>
    useResource(resource).ensure(Eru.effect(resource.close()))
  }

val resourceResult = safeResourceUse.unsafeRunSync()
println(resourceResult)
```

### The `ensure` Pattern

```scala mdoc
val computation = Eru.effect {
  println("Doing important work...")
  "Work complete"
}

// Guaranteed cleanup regardless of success/failure
val withCleanup = computation.ensure(Eru.effect {
  println("Cleanup performed")
})

val ensureResult = withCleanup.unsafeRunSync()
```

## Debugging and Observability Patterns

### Tapping into Program Flow

```scala mdoc
val debuggedProgram = Eru.succeed(42)
  .tap(value => Eru.effect(println(s"Debug: Processing $value")))
  .map(_ * 2)
  .tap(value => Eru.effect(println(s"Debug: Result is $value")))

val debugResult = debuggedProgram.unsafeRunSync()
println(s"Final: $debugResult")
```

### Timing Operations

```scala mdoc
def simulateWork(): Eru[Throwable, String] = Eru.effect {
  Thread.sleep(100) // Don't do this in real code - use TestClock for tests
  "Work done"
}

// Simple timing with manual measurement
val timedOperation = for {
  start  <- Eru.effect(System.currentTimeMillis())
  result <- simulateWork()
  end    <- Eru.effect(System.currentTimeMillis())
} yield s"Operation took ${end - start}ms: $result"

// Note: This example uses Thread.sleep only for demonstration
// In real applications, use proper async operations
```

### Error Context with `mapError`

```scala mdoc
def parseNumber(s: String): Eru[String, Int] = 
  Eru.fromTry(scala.util.Try(s.toInt))
    .mapError(ex => s"Failed to parse '$s' as number: ${ex.getMessage}")

val parseResult = parseNumber("not-a-number").attempt.unsafeRunSync()
println(parseResult)
```

## Advanced Patterns

### Advanced Retry Pattern

```scala mdoc
def unreliableOperation(): Eru[String, String] = {
  if (scala.util.Random.nextDouble() > 0.7) 
    Eru.succeed("Success")
  else 
    Eru.fail("Temporary failure")
}

// A more sophisticated retry combinator that can be customized
def retryWithPolicy[E, A](
  operation: Eru[E, A],
  maxAttempts: Int,
  shouldRetry: E => Boolean = (_: E) => true
): Eru[E, A] = {
  def attempt(remaining: Int): Eru[E, A] = {
    if (remaining <= 0) {
      operation // Final attempt
    } else {
      operation.recoverWith { error =>
        if (shouldRetry(error)) {
          attempt(remaining - 1)
        } else {
          Eru.fail(error) // Don't retry this error
        }
      }
    }
  }
  attempt(maxAttempts - 1)
}

// Usage with different retry policies
val basicRetry = retryWithPolicy(unreliableOperation(), maxAttempts = 3)

val selectiveRetry = retryWithPolicy(
  unreliableOperation(), 
  maxAttempts = 5,
  shouldRetry = {
    case "Temporary failure" => true
    case "Permanent error" => false
    case _ => true
  }
)

val retryResult = basicRetry.attempt.unsafeRunSync()
println(s"Retry result: $retryResult")
```

This pattern shows how you can build powerful, reusable combinators on top of Eru's primitives.

### Validation Accumulation Pattern

```scala mdoc
// For accumulating multiple validation errors, stay within the Eru effect system

def validateEmail(email: String): Eru[String, String] =
  if (email.contains("@")) Eru.succeed(email) else Eru.fail("Invalid email")

def validateUserAge(age: Int): Eru[String, Int] =
  if (age >= 18) Eru.succeed(age) else Eru.fail("Must be 18+")

def validateName(name: String): Eru[String, String] =
  if (name.nonEmpty) Eru.succeed(name) else Eru.fail("Name required")

// Collect all validation results, staying purely functional
def validateAll(email: String, age: Int, name: String): Eru[Nothing, List[String]] = {
  val validations: List[Eru[String, Any]] = List(
    validateEmail(email),
    validateUserAge(age),
    validateName(name)
  )

  // Convert all to attempts and collect results
  val allAttempts = validations.map(_.attempt)
  
  // Use collectAll to run all validations and collect only failures
  Eru.collectAll(allAttempts).map { results =>
    results.collect { 
      case net.ghoula.eru.Result.Failure(error) => error 
    }
  }
}

// Now the entire operation stays within Eru
val allErrors = validateAll("invalid-email", 16, "").unsafeRunSync()
println(s"All validation errors: $allErrors")
```

### Conditional Execution Patterns

```scala mdoc
def processNumber(n: Int): Eru[String, String] = {
  if (n > 100) {
    // Large number processing
    Eru.succeed(n).map(x => s"Large: $x")
  } else if (n > 0) {
    // Small positive number
    Eru.succeed(n).map(x => s"Small positive: $x")
  } else {
    // Handle negative or zero
    Eru.fail("Non-positive numbers not supported")
  }
}

val conditionalResults = List(150, 50, -10).map { n =>
  processNumber(n).attempt.unsafeRunSync()
}
conditionalResults.foreach(println)
```

## Testing Patterns

### Creating Deterministic Tests

```scala mdoc
// Create programs that can be tested deterministically
def testableService(shouldSucceed: Boolean): Eru[String, Int] =
  if (shouldSucceed) Eru.succeed(42) else Eru.fail("Service error")

// Test success path
val successTest = testableService(true).unsafeRunSync()
assert(successTest == 42)

// Test failure path  
val failureTest = testableService(false).attempt.unsafeRunSync()
failureTest match {
  case net.ghoula.eru.Result.Failure("Service error") => println("✓ Failure test passed")
  case other => println(s"✗ Unexpected result: $other")
}
```

### Mocking External Dependencies

```scala mdoc
trait DatabaseService {
  def findUser(id: Int): Eru[String, String]
}

// Real implementation
class RealDatabaseService extends DatabaseService {
  def findUser(id: Int): Eru[String, String] = 
    Eru.effect(s"User-$id from database").mapError(_.getMessage)
}

// Test implementation  
class MockDatabaseService(responses: Map[Int, Either[String, String]]) extends DatabaseService {
  def findUser(id: Int): Eru[String, String] = 
    responses.get(id) match {
      case Some(Right(user)) => Eru.succeed(user)
      case Some(Left(error)) => Eru.fail(error)
      case None => Eru.fail("User not found")
    }
}

// Usage in tests
val mockDb = MockDatabaseService(Map(
  1 -> Right("Alice"),
  2 -> Left("Database error")
))

val mockResult1 = mockDb.findUser(1).unsafeRunSync()
val mockResult2 = mockDb.findUser(2).attempt.unsafeRunSync()
println(s"Mock success: $mockResult1")
println(s"Mock failure: $mockResult2")
```

## Performance Patterns

### Map Fusion Optimization

```scala mdoc
// Multiple maps are automatically fused into a single transformation
val optimizedChain = Eru.succeed(10)
  .map(_ * 2)      // These three maps become
  .map(_ + 5)      // a single function:
  .map(_.toString) // x => ((x * 2) + 5).toString

val optimizedResult = optimizedChain.unsafeRunSync()
println(s"Optimized: $optimizedResult")
```

### Stack-Safe Recursion

```scala mdoc
// Build large chains without stack overflow
def buildLargeChain(n: Int): Eru[Nothing, Int] = {
  (1 to n).foldLeft(Eru.succeed(0)) { (acc, i) =>
    acc.flatMap(current => Eru.succeed(current + i))
  }
}

val largeChainResult = buildLargeChain(1000).unsafeRunSync()
println(s"Large chain sum: $largeChainResult")
```

## Key Takeaways

This reference demonstrates the patterns that make Eru programs elegant:

**Ergonomic Error Handling**: Use `.attempt.map(_.fold())` and `.fallback{}` instead of verbose pattern matching.

**Composable Recovery**: Chain alternatives with `orElse` and handle specific errors with `recover`.

**Safe Resource Management**: Use `bracket` and `ensure` for guaranteed cleanup.

**Performance by Default**: Map fusion and stack safety are automatic optimizations.

**Testable by Design**: Create deterministic programs with dependency injection and controlled effects.

## What's Next

With these patterns understood, you can build production systems with Eru. Chapter 7 explores resource management in depth, covering guaranteed cleanup and safe resource handling patterns.