# Chapter 5: Error Handling

*"In the face of failure, the wise program does not panic—it recovers."*

This chapter covers error handling patterns, from simple string errors to structured ADTs and advanced recovery strategies.

## The Philosophy of Typed Errors

Traditional exception-based error handling suffers from several problems:
- Errors are invisible in function signatures
- No compile-time guarantee that errors are handled
- Stack traces often obscure the real problem
- Recovery logic is scattered and ad-hoc

With typed errors, function signatures declare their failure modes:

```scala mdoc
import net.ghoula.eru.prelude.*

// The error type is part of the contract
def divide(a: Int, b: Int): Eru[String, Double] =
  if (b != 0) 
    Eru.succeed(a.toDouble / b)
  else 
    Eru.fail("Division by zero")

// You cannot ignore the possibility of failure
val calculation = divide(10, 2).unsafeRunSync()
println(s"Result: $calculation")

// Errors are values, not exceptions
val errorCase = divide(10, 0).attempt.unsafeRunSync()
println(s"Error case: $errorCase")
```

## Error Types: From Simple to Sophisticated

### String Errors - Quick and Simple

```scala mdoc
def validatePassword(password: String): Eru[String, String] = {
  if (password.length < 8) 
    Eru.fail("Password too short")
  else if (!password.exists(_.isDigit))
    Eru.fail("Password must contain a digit")
  else
    Eru.succeed(password)
}

val validPassword = validatePassword("mysecret123").unsafeRunSync()
val invalidPassword = validatePassword("short").attempt.unsafeRunSync()
println(s"Valid: $validPassword")
println(s"Invalid: $invalidPassword")
```

### ADT Errors - Structured and Pattern-Matchable

```scala mdoc
enum ValidationError:
  case TooShort(minLength: Int)
  case MissingDigit
  case MissingSpecial
  case TooCommon(password: String)

def validatePasswordADT(password: String): Eru[ValidationError, String] = {
  if (password.length < 8) 
    Eru.fail(ValidationError.TooShort(8))
  else if (!password.exists(_.isDigit))
    Eru.fail(ValidationError.MissingDigit)
  else if (password == "password123")
    Eru.fail(ValidationError.TooCommon(password))
  else
    Eru.succeed(password)
}

// Pattern match on structured errors
def handlePasswordError(error: ValidationError): String = error match {
  case ValidationError.TooShort(min) => s"Password must be at least $min characters"
  case ValidationError.MissingDigit => "Password must contain at least one digit"  
  case ValidationError.MissingSpecial => "Password must contain a special character"
  case ValidationError.TooCommon(pwd) => s"Password '$pwd' is too common"
}

val structuredError = validatePasswordADT("short").attempt.unsafeRunSync()
structuredError match {
  case net.ghoula.eru.Result.Success(pwd) => println(s"Valid password: $pwd")
  case net.ghoula.eru.Result.Failure(error) => println(handlePasswordError(error))
}
```

## Recovery Patterns

### Basic Recovery with `recover`

Use `recover` to handle specific error cases:

```scala mdoc
val unreliableService: Eru[String, String] = Eru.fail("Service unavailable")

val withFallback = unreliableService.recover {
  case "Service unavailable" => "Using cached data"
  case "Timeout" => "Using default value"
}

val recoveredResult = withFallback.unsafeRunSync()
println(s"Recovered: $recoveredResult")
```

### Advanced Recovery with `recoverWith`

Use `recoverWith` when recovery itself might fail:

```scala mdoc
def primaryService(): Eru[String, String] = Eru.fail("Primary failed")
def backupService(): Eru[String, String] = Eru.succeed("Backup data")
def cacheService(): Eru[String, String] = Eru.succeed("Cached data")

val resilientService = primaryService().recoverWith {
  case "Primary failed" => backupService().recoverWith {
    case "Backup failed" => cacheService()
  }
}

val resilientResult = resilientService.unsafeRunSync()
println(s"Resilient: $resilientResult")
```

### The `orElse` Pattern

Chain alternative approaches with `orElse`:

```scala mdoc
val approach1: Eru[String, Int] = Eru.fail("Approach 1 failed")
val approach2: Eru[String, Int] = Eru.fail("Approach 2 failed")  
val approach3: Eru[String, Int] = Eru.succeed(42)

val firstSuccess = approach1
  .orElse(approach2)
  .orElse(approach3)

val orElseResult = firstSuccess.unsafeRunSync()
println(s"First success: $orElseResult")
```

## Error Transformation and Mapping

### Changing Error Types with `mapError`

```scala mdoc
enum NetworkError:
  case Timeout, Disconnected, InvalidResponse

enum BusinessError:
  case ValidationFailed, ProcessingError, ExternalServiceError

def networkCall(): Eru[NetworkError, String] = 
  Eru.fail(NetworkError.Timeout)

// Transform network errors into business errors
val businessOperation = networkCall().mapError {
  case NetworkError.Timeout => BusinessError.ExternalServiceError
  case NetworkError.Disconnected => BusinessError.ExternalServiceError  
  case NetworkError.InvalidResponse => BusinessError.ProcessingError
}

val transformedError = businessOperation.attempt.unsafeRunSync()
println(s"Transformed: $transformedError")
```

### Flattening Nested Results

```scala mdoc
// Sometimes you get nested Results from external APIs
val nestedResult: Eru[String, net.ghoula.eru.Result[String, Int]] = 
  Eru.succeed(net.ghoula.eru.Result.Success(42))

// Flatten them with flatMap
val flattened = nestedResult.flatMap {
  case net.ghoula.eru.Result.Success(value) => Eru.succeed(value)
  case net.ghoula.eru.Result.Failure(error) => Eru.fail(error)
}

val flattenedResult = flattened.unsafeRunSync()
println(s"Flattened: $flattenedResult")
```

## Accumulating vs Fail-Fast Errors

### Fail-Fast: Stop at First Error

This is the default behavior with `flatMap` and for-comprehensions:

```scala mdoc
def validateEmail(email: String): Eru[String, String] =
  if (email.contains("@")) Eru.succeed(email) else Eru.fail("Invalid email")

def validateAge(age: Int): Eru[String, Int] =
  if (age >= 18) Eru.succeed(age) else Eru.fail("Must be 18+")

def validateName(name: String): Eru[String, String] =
  if (name.nonEmpty) Eru.succeed(name) else Eru.fail("Name required")

// Fail-fast: stops at first error
val failFast = for {
  email <- validateEmail("not-email")  // This fails
  age   <- validateAge(16)             // Never executed
  name  <- validateName("")            // Never executed  
} yield (email, age, name)

val failFastResult = failFast.attempt.unsafeRunSync()
println(s"Fail-fast: $failFastResult")
```

### Error Accumulation: Collect All Errors

Sometimes you want to collect all validation errors. Here's a conceptual approach:

```scala mdoc
case class ValidationErrors(errors: List[String])

// For error accumulation, you'd typically use a specialized validation library
// or implement custom combinators. Here's the concept:

def gatherAllErrors(email: String, age: Int, name: String): List[String] = {
  val emailErrors = validateEmail(email).attempt.unsafeRunSync() match {
    case net.ghoula.eru.Result.Failure(e) => List(e)
    case _ => List.empty
  }
  
  val ageErrors = validateAge(age).attempt.unsafeRunSync() match {
    case net.ghoula.eru.Result.Failure(e) => List(e)  
    case _ => List.empty
  }
  
  val nameErrors = validateName(name).attempt.unsafeRunSync() match {
    case net.ghoula.eru.Result.Failure(e) => List(e)
    case _ => List.empty
  }
  
  emailErrors ++ ageErrors ++ nameErrors
}

val allErrors = gatherAllErrors("not-email", 16, "")
println(s"All errors: $allErrors")

// In practice, you'd build this into a proper validation framework
```

## Real-World Error Handling Patterns

### The Circuit Breaker Pattern

```scala mdoc
case class CircuitBreakerState(failures: Int, isOpen: Boolean)

def callExternalService(): Eru[String, String] = {
  // Simulate unreliable service
  if (scala.util.Random.nextBoolean()) 
    Eru.succeed("Service response")
  else 
    Eru.fail("Service down")
}

def withCircuitBreaker(state: CircuitBreakerState): Eru[String, String] = {
  if (state.isOpen && state.failures > 3) {
    Eru.fail("Circuit breaker open")
  } else {
    callExternalService().recoverWith { error =>
      val newState = state.copy(failures = state.failures + 1)
      if (newState.failures > 3) {
        Eru.fail("Circuit breaker opened")
      } else {
        Eru.fail(error)
      }
    }
  }
}

val circuitResult = withCircuitBreaker(CircuitBreakerState(0, false)).attempt.unsafeRunSync()
println(s"Circuit breaker: $circuitResult")
```

### Retries with Backoff

```scala mdoc
def flakyOperation(): Eru[String, String] = {
  if (scala.util.Random.nextDouble() > 0.7) 
    Eru.succeed("Success!")
  else 
    Eru.fail("Temporary failure")
}

def retry[E, A](operation: Eru[E, A], attempts: Int): Eru[E, A] = {
  if (attempts <= 1) {
    operation
  } else {
    operation.recoverWith { error =>
      retry(operation, attempts - 1)
    }
  }
}

val retriedOperation = retry(flakyOperation(), 3)
val retryResult = retriedOperation.attempt.unsafeRunSync()
println(s"Retry result: $retryResult")
```

## Testing Error Conditions

One of Eru's greatest strengths is making error conditions testable:

```scala mdoc
// Create deterministic failure for testing
def testableOperation(shouldFail: Boolean): Eru[String, Int] =
  if (shouldFail) Eru.fail("Test failure") else Eru.succeed(42)

// Test success case
val successTest = testableOperation(false).unsafeRunSync()
assert(successTest == 42)

// Test failure case  
val failureTest = testableOperation(true).attempt.unsafeRunSync()
failureTest match {
  case net.ghoula.eru.Result.Failure("Test failure") => println("Failure test passed")
  case other => println(s"Unexpected result: $other")
}
```

## Key Takeaways

Understanding error handling with Eru changes how you build resilient applications:

**Foundational Correctness**: Typed errors make failure modes explicit and force proper handling.

**Ergonomic Design**: Pattern matching and recovery combinators make error handling expressive.

**Guided Correctness**: The compiler ensures you handle all error cases or explicitly ignore them.

**Transparent Runtime**: Error flow is visible in program structure, making debugging straightforward.

## What's Next

In Chapter 6, we'll explore resource management—one of the areas where effect systems truly excel. You'll learn about `bracket`, `Resource`, and other patterns that ensure your programs properly acquire, use, and release resources even in the presence of failures.

---

*"Good programs anticipate failure and prepare for it. Well-designed code recovers gracefully."*