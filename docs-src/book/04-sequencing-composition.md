# Chapter 4: Sequencing & Composition

*"The art of programming lies not in individual operations, but in how they combine."*

You've learned to create individual Eru programs. Now comes the real power: combining them into larger, more sophisticated computations. This chapter explores Eru's composition operators and when to use each one.

## The Composition Toolbox

Eru provides several operators for combining programs, each optimized for different patterns:

> **A Note on Imports**  
> All the core functions and extension methods you see, like `.flatMap`, `.map`, and `Eru.succeed`, are made available through a single, convenient import:
> ```scala
> import net.ghoula.eru.prelude.*
> ```
> This object is your main entry point to the entire Eru API.

### `map` - Transforming Success Values

Use `map` when you want to transform the successful result without introducing new effects or failures:

```scala mdoc
import net.ghoula.eru.prelude.*

val number = Eru.succeed(42)

// Transform the value inside
val doubled = number.map(_ * 2)
val stringified = doubled.map(_.toString)
val decorated = stringified.map(s => s"Result: $s")

val final1 = decorated.unsafeRunSync()
println(final1)

// Chain them together  
val chained = Eru.succeed(42)
  .map(_ * 2)
  .map(_.toString)
  .map(s => s"Result: $s")

val final2 = chained.unsafeRunSync()
println(final2)
```

**Key insight**: `map` never changes the error type. If the original program fails, `map` operations are skipped.

### `flatMap` - Chaining Dependent Computations

Use `flatMap` when the next computation depends on the result of the current one, or when you need to introduce new effects:

```scala mdoc
def fetchUser(id: Int): Eru[String, String] = 
  if (id > 0) Eru.succeed(s"User-$id") else Eru.fail("Invalid ID")

def fetchUserProfile(userName: String): Eru[String, String] =
  Eru.succeed(s"Profile for $userName")

// Chain dependent operations
val userProfile = fetchUser(123).flatMap { user =>
  fetchUserProfile(user)
}

val profile = userProfile.unsafeRunSync()
println(profile)

// Error propagation with flatMap
val invalidProfile = fetchUser(-1).flatMap { user =>
  fetchUserProfile(user)
}

val errorResult = invalidProfile.attempt.unsafeRunSync()
println(s"Error result: $errorResult")
```

**Key insight**: `flatMap` allows errors from any step to propagate through the entire chain.

### For-Comprehensions: Sequential Composition

For-comprehensions are syntactic sugar over `flatMap` and make sequential operations readable:

```scala mdoc
def validateEmail(email: String): Eru[String, String] =
  if (email.contains("@")) Eru.succeed(email) else Eru.fail("Invalid email")

def validateAge(age: Int): Eru[String, Int] =
  if (age >= 0 && age <= 150) Eru.succeed(age) else Eru.fail("Invalid age")

def createUser(email: String, age: Int): Eru[String, String] = for {
  validEmail <- validateEmail(email)
  validAge   <- validateAge(age)
  user       <- Eru.succeed(s"User($validEmail, $validAge)")
} yield user

val validUser = createUser("alice@example.com", 25).unsafeRunSync()
println(validUser)

val invalidUser = createUser("not-an-email", 25).attempt.unsafeRunSync()
println(s"Invalid: $invalidUser")
```

### `zip` - Parallel Combination

Use `zip` when you want to combine the results of two independent computations:

```scala mdoc
val leftSide = Eru.succeed("Hello")
val rightSide = Eru.succeed(42)

val combined = leftSide.zip(rightSide)
val tuple = combined.unsafeRunSync()
println(s"Combined: $tuple")

// zip and then map for custom combination
val customCombined = leftSide.zip(rightSide).map { (str, num) =>
  s"$str + $num = ${str.length + num}"
}

val custom = customCombined.unsafeRunSync()
println(custom)
```

**Key insight**: `zip` fails if either side fails, but the computations are conceptually independent.

## Performance Optimizations

Eru applies several optimizations to make composition fast:

### Map Fusion

Multiple `map` operations are fused into a single transformation:

```scala mdoc
// This becomes a single function: x => ((x * 2) + 1).toString
val fused = Eru.succeed(10)
  .map(_ * 2)  // Step 1
  .map(_ + 1)  // Step 2  
  .map(_.toString) // Step 3

val fusedResult = fused.unsafeRunSync()
println(s"Fused result: $fusedResult")
```

### Right Association

FlatMap chains are automatically right-associated for stack safety:

```scala mdoc
def buildChain(n: Int): Eru[Nothing, Int] = {
  // This creates a right-associated chain internally
  (1 to n).foldLeft(Eru.succeed(0)) { (acc, i) =>
    acc.flatMap(current => Eru.succeed(current + i))
  }
}

val bigChain = buildChain(100).unsafeRunSync()
println(s"Chain result: $bigChain")
```

### Short-Circuit Evaluation  

Operations stop as soon as a failure is encountered:

```scala mdoc
val earlyFailure = Eru.fail("Early error")
  .map(_ => println("This won't print"))
  .map(_ => println("Neither will this"))
  .flatMap(_ => Eru.succeed("Never reached"))

val earlyResult = earlyFailure.attempt.unsafeRunSync()
println(s"Early failure: $earlyResult")
```

## When to Use Each Combinator

Here's a decision tree for choosing the right combinator:

### Use `map` when:
- Transforming values without adding effects
- The transformation cannot fail
- You want optimized performance for pure transformations

```scala mdoc
// Good uses of map
val calculations = Eru.succeed(5)
  .map(_ * 2)           // Pure transformation
  .map(Math.sqrt(_))    // Pure function
  .map(_.toInt)         // Type conversion
```

### Use `flatMap` when:  
- The next operation depends on the previous result
- You need to introduce new effects or error conditions
- Chaining operations that can fail

```scala mdoc
def divide(a: Int, b: Int): Eru[String, Double] =
  if (b != 0) Eru.succeed(a.toDouble / b) else Eru.fail("Division by zero")

// Good use of flatMap - dependent operations
val calculation = Eru.succeed(10).flatMap { x =>
  divide(x, 2).flatMap { result =>
    if (result > 2) Eru.succeed(result) else Eru.fail("Too small")
  }
}
```

### Use `zip` when:
- Combining independent computations  
- Both results are needed for the final result
- Operations can potentially be parallelized

```scala mdoc  
// Independent operations that can be combined
val config = Eru.succeed("production")
val version = Eru.succeed("1.0.0")

val appInfo = config.zip(version).map { (env, ver) =>
  s"App running in $env environment, version $ver"
}
```

## Advanced Composition Patterns

### Conditional Execution

```scala mdoc
def processBasedOnCondition(x: Int): Eru[String, String] = {
  if (x > 10) {
    Eru.succeed(x).map(n => s"Large number: $n")
  } else {
    Eru.succeed(x).flatMap(n => 
      if (n < 0) Eru.fail("Negative not allowed") 
      else Eru.succeed(s"Small number: $n")
    )
  }
}

val largeResult = processBasedOnCondition(15).unsafeRunSync()
val smallResult = processBasedOnCondition(5).unsafeRunSync()
println(s"Large: $largeResult")
println(s"Small: $smallResult")
```

### Nested Computations

```scala mdoc
case class Config(dbUrl: String, timeout: Int)
case class Connection(url: String)

def loadConfig(): Eru[String, Config] = 
  Eru.succeed(Config("jdbc:postgresql://localhost", 30))

def connect(config: Config): Eru[String, Connection] =
  Eru.succeed(Connection(config.dbUrl))

def query(conn: Connection): Eru[String, List[String]] =
  Eru.succeed(List("user1", "user2", "user3"))

// Nested composition with for-comprehension
val result = for {
  config     <- loadConfig()
  connection <- connect(config)
  users      <- query(connection)
} yield users

val users = result.unsafeRunSync()
println(s"Users: $users")
```

## Error Propagation in Composition

Understanding how errors flow through composition is crucial:

```scala mdoc
val step1 = Eru.succeed(10)
val step2 = Eru.fail("Something went wrong") 
val step3 = Eru.succeed("Final step")

// Error in the middle stops the chain
val errorChain = for {
  a <- step1
  b <- step2  // This fails
  c <- step3  // Never reached
} yield (a, b, c)

val errorChainResult = errorChain.attempt.unsafeRunSync()
println(s"Chain with error: $errorChainResult")
```

## Key Takeaways

Understanding composition is important for effective Eru programming:

**Foundational Correctness**: Composition preserves safety - if any step fails, the whole chain fails safely.

**Ergonomic Design**: For-comprehensions make complex sequential logic read like imperative code.

**Guided Correctness**: The type system prevents composing incompatible operations.

**Transparent Runtime**: The structure of your composition directly reflects execution order.

## What's Next

In Chapter 5, we'll explore error handling - the `E` in `Eru[E, A]`. You'll see techniques for recovering from failures, accumulating errors, and building applications that handle error conditions gracefully.

---

*"From simple parts, complex systems emerge. From thoughtful composition, robust programs follow."*