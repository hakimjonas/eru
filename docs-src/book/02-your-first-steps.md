# Chapter 2: Your First Steps

This chapter demonstrates Eru's core patterns through working code. If you're already comfortable with `Either` and `Try` in Scala, you'll find the transition straightforward.

## Setting Up

Add Eru to your build:

```scala
// build.sbt  
libraryDependencies += "net.ghoula" %% "eru-core" % "@VERSION@"
libraryDependencies += "net.ghoula" %% "eru-runtime" % "@VERSION@" // For concurrency
```

Then in your Scala files:

```scala mdoc
import net.ghoula.eru.prelude.*
```

## Hello, Eru

Let's start with the simplest possible program:

```scala mdoc
val greeting: Eru[Nothing, String] = Eru.succeed("Hello, Eru!")

// The program is just a description until we run it
val result: String = greeting.unsafeRunSync()
println(result)
```

Two key points:
1. `Eru.succeed` creates a program description, not an immediate result
2. Nothing happens until we call `unsafeRunSync()`

Programs are data that can be transformed and combined before execution.

## Understanding the Type Signature

The type `Eru[Nothing, String]` tells us:
- **Error Type** (`Nothing`): This program cannot fail
- **Success Type** (`String`): When successful, it produces a String

Let's see a program that can fail:

```scala mdoc
def divide(a: Int, b: Int): Eru[String, Int] = 
  if (b == 0) 
    Eru.fail("Division by zero!")
  else 
    Eru.succeed(a / b)

// This succeeds
val success = divide(10, 2).unsafeRunSync()
println(s"10 / 2 = $success")

// This fails (but safely!)
try {
  divide(10, 0).unsafeRunSync()
} catch {
  case ex: Exception => println(s"Caught: ${ex.getMessage}")
}
```

The type system makes failure explicit and trackable.

## Suspending Side Effects

Real programs need to interact with the outside world. Use `Eru.effect` to safely suspend side effects:

```scala mdoc
import scala.util.Random

// Suspend a side effect - it won't run until the program executes
val randomProgram: Eru[Throwable, Int] = Eru.effect {
  println("Generating random number...")
  Random.nextInt(100)
}

println("Program created (no side effect yet)")

val number = randomProgram.unsafeRunSync()
println(s"Generated: $number")
```

The `println` inside `Eru.effect` only happens when the program runs, not when it's created.

## Composing Programs

Eru programs can be combined. Let's build a program step by step:

```scala mdoc
def getUserInput(prompt: String): Eru[Throwable, String] = Eru.effect {
  println(prompt)
  "Eru User" // Simulating user input
}

def greetUser(name: String): Eru[Nothing, String] = 
  Eru.succeed(s"Welcome, $name! Ready to learn Eru?")

def logMessage(message: String): Eru[Throwable, Unit] = Eru.effect {
  println(s"LOG: $message")
}

// Compose these programs using for-comprehension
val welcomeProgram: Eru[Throwable, String] = for {
  name     <- getUserInput("What's your name?")
  greeting <- greetUser(name)
  _        <- logMessage(greeting)
} yield greeting

val finalMessage = welcomeProgram.unsafeRunSync()
println(s"Result: $finalMessage")
```

Notice how we combined three different programs into one, with different error types automatically unified.

## Working with Results

Error handling works similarly to `Either` - you can handle errors explicitly rather than letting them propagate:

```scala mdoc
val riskyOperation: Eru[String, Int] = Eru.fail("Something went wrong!")

// Convert to a safe program that can't fail
val safeProgram: Eru[Nothing, String] = riskyOperation.attempt.map(_.fold(
  ifFailure = error => s"Error: $error",
  ifSuccess = value => s"Success: $value"
))

val outcome = safeProgram.unsafeRunSync()
println(outcome)
```

The `.attempt.fold()` pattern works exactly like `either.fold(handleError, handleSuccess)` - clean and familiar.

## Error Recovery

You can also recover from specific errors with simple fallback values:

```scala mdoc
val unreliableService: Eru[String, String] = Eru.fail("Service unavailable")

val withFallback = unreliableService.fallback {
  case "Service unavailable" => "Using cached data"
  case "Timeout" => "Using default value"
}

val fallbackResult = withFallback.unsafeRunSync()
println(fallbackResult)
```

The `fallback` method provides clean error recovery - just like `option.getOrElse()` but for specific error cases.

## A Practical Example

Let's build something more realistic using modern Scala 3 patterns. Notice how the domain modeling and business logic remain the same - only the effect handling changes:

```scala mdoc
// Domain modeling with enums and opaque types
opaque type UserId = Int
object UserId {
  def apply(value: Int): Option[UserId] = if (value > 0) Some(value) else None
}

opaque type Email = String  
object Email {
  def apply(value: String): Option[Email] = if (value.contains("@")) Some(value) else None
}

enum UserError {
  case InvalidId(id: Int)
  case InvalidEmail(email: String) 
  case DatabaseError(message: String)
}

case class User(id: UserId, name: String, email: Email)

// Pipeline functions with proper error types
def fetchUser(id: Int): Eru[UserError, User] = {
  UserId(id) match {
    case Some(userId) => Eru.succeed(User(userId, "Alice", Email("alice@example.com").get))
    case None => Eru.fail(UserError.InvalidId(id))
  }
}

def validateUser(user: User): Eru[UserError, User] = {
  // In this example, user is already valid due to opaque type constraints
  Eru.succeed(user)
}

def saveUser(user: User): Eru[UserError, String] = Eru.effect {
  // Simulate database save
  s"User ${user.name} saved successfully"
}.mapError(ex => UserError.DatabaseError(ex.getMessage))

// Compose the pipeline
def processUser(id: Int): Eru[UserError, String] = for {
  user   <- fetchUser(id)
  valid  <- validateUser(user)  
  result <- saveUser(valid)
} yield result

// Test with valid ID
val processSuccess = processUser(123).unsafeRunSync()
println(processSuccess)

// Test error handling with pattern matching
val errorResult = processUser(-1).attempt.unsafeRunSync()
errorResult match {
  case net.ghoula.eru.Result.Success(msg) => println(s"Success: $msg")
  case net.ghoula.eru.Result.Failure(UserError.InvalidId(id)) => 
    println(s"Invalid user ID: $id")
  case net.ghoula.eru.Result.Failure(error) => println(s"Other error: $error")
}
```

## Complete Working Example

Here's a complete, runnable program that demonstrates everything we've covered. Copy this into a new sbt project to get started immediately:

```scala
// file: src/main/scala/Main.scala
import net.ghoula.eru.prelude.*

@main def eruExample(): Unit = {
  // Simple greeting program
  val greeting = Eru.succeed("Hello, Eru!")
  println(greeting.unsafeRunSync())

  // Program with potential failure
  def divide(a: Int, b: Int): Eru[String, Int] = 
    if (b == 0) 
      Eru.fail("Division by zero!")
    else 
      Eru.succeed(a / b)

  // Handle success
  val success = divide(10, 2).unsafeRunSync()
  println(s"10 / 2 = $success")

  // Handle failure gracefully
  val failure = divide(10, 0).attempt.unsafeRunSync()
  failure match {
    case net.ghoula.eru.Result.Success(value) => println(s"Success: $value")
    case net.ghoula.eru.Result.Failure(error) => println(s"Error: $error")
  }

  // Better: Use the ergonomic pattern
  val handled = divide(10, 0).attempt.map(_.fold(
    ifFailure = error => s"Error: $error",
    ifSuccess = value => s"Success: $value"
  )).unsafeRunSync()
  println(handled)

  // Composing programs
  val pipeline = for {
    x <- divide(20, 4)
    y <- divide(x, 2) 
    z <- Eru.succeed(y * 3)
  } yield z

  println(s"Pipeline result: ${pipeline.unsafeRunSync()}")

  // Error recovery
  val withFallback = divide(10, 0).fallback {
    case "Division by zero!" => 0
  }.unsafeRunSync()
  println(s"With fallback: $withFallback")
}
```

And the corresponding `build.sbt`:

```scala
// file: build.sbt
ThisBuild / scalaVersion := "3.8.2"

libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-core" % "@VERSION@"
)
```

Run with `sbt run` to see Eru in action!

## Summary

These examples demonstrate several key aspects of Eru:

- Types make failure modes explicit
- Pure descriptions separate logic from execution  
- `for` comprehensions make sequential logic natural
- The type system guides you toward handling errors
- You control exactly when effects execute

## What's Next

In Chapter 3, we'll examine the `Eru` type in detail, exploring how its design enables both safety and performance.