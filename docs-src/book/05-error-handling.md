# Chapter 5: Error Handling

This chapter covers error handling patterns, from simple string errors to structured ADTs and advanced recovery strategies.

## The philosophy of typed errors

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

## Error types: from simple to sophisticated

### String errors: quick and simple

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

### ADT errors: structured and pattern-matchable

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

## Recovery patterns

### Basic recovery with recover

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

### Advanced recovery with recoverWith

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

### The orElse pattern

Chain alternative approaches with `orElse`:

```scala mdoc
val approach1: Eru[String, Int] = Eru.fail("Approach 1 failed")
val approach2: Eru[String, Int] = Eru.succeed(42)

val firstSuccess = approach1.orElse(approach2)

val orElseResult = firstSuccess.unsafeRunSync()
println(s"First success: $orElseResult")
```

## Error transformation and mapping

### Changing error types with mapError

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

### Flattening nested results

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

## Accumulating vs fail-fast errors

### Fail-fast: stop at first error

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

### Error accumulation: collect all errors

Some validations should report every failure, not just the first. Eru ships combinators for this. `accumulateErrors` combines two effects and, if both fail, carries both errors:

```scala mdoc
// Both succeed: you get the pair
val bothGood = Eru.succeed(1).accumulateErrors(Eru.succeed(2))
println(s"Both good: ${bothGood.unsafeRunSync()}")

// Both fail: the errors accumulate
val bothBad = Eru.fail("first error").accumulateErrors(Eru.fail("second error"))
println(s"Both bad: ${bothBad.attempt.unsafeRunSync()}")
```

`validate` runs any number of checks against a value and accumulates their failures:

```scala mdoc
def checkEmail(email: String): Eru[String, Unit] =
  if (email.contains("@")) Eru.succeed(()) else Eru.fail("Invalid email")

def checkLength(text: String): Eru[String, Unit] =
  if (text.length > 3) Eru.succeed(()) else Eru.fail("Too short")

val allChecks = Eru.succeed("not-an-email")
  .validate(checkEmail, checkLength)
  .attempt
  .unsafeRunSync()

println(s"Validation: $allChecks")
```

`EruRuntime.zipParAll` and `parSequenceAll` (Chapter 10) run such lists of checks in parallel with the same accumulate-all semantics.

## Real-world error handling patterns

### The circuit breaker pattern

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

Eru ships this pattern: `withCircuitBreaker` on `Eru` takes a `patterns.ErrorHandling.CircuitBreaker`, which tracks failures and opens after a configured threshold.

### Retries with backoff

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

Eru also ships `retry` with `EruRuntime.Policy.NoDelay(n)` or `EruRuntime.Policy.Exponential(...)` (retry counts are retries after the initial attempt); the hand-rolled version above is for custom schedules.

## Testing error conditions


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

## Key takeaways


Foundational correctness: typed errors make failure modes explicit.

Ergonomic design: pattern matching and recovery combinators keep error handling concise.

Guided correctness: an unhandled error stays in the type signature until a combinator removes it.

Transparent runtime: error flow is visible in the program structure.

## What's next

Chapter 6 is a tour of the remaining API patterns. Chapter 7 covers resource management with `bracket` and `ensure`.