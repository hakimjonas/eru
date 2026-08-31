# Chapter 3: The Eru Type

Now that you've written your first Eru programs, this chapter examines how `Eru[E, A]` works under the hood: its design decisions and the concepts that make it effective.

## The nature of Eru

At its heart, `Eru[E, A]` is a data structure that describes a computation. It is a blueprint: a description of what should happen when executed.

```scala mdoc
import net.ghoula.eru.prelude.*

// This creates a description, not a side effect
val program = Eru.effect {
  println("This won't print yet!")
  42
}

println("Program created, but effect hasn't run")

// Only when we execute does the effect occur
val result = program.unsafeRunSync()
println(s"Result: $result")
```

This separation between description and execution is fundamental to functional programming and is what gives composability, testability, and reasoning power.

## Understanding Eru[E, A]

The `Eru[E, A]` type is a program description that:
- When executed, may fail with an error of type `E`  
- When successful, produces a value of type `A`

```
                    ╭─────────────────╮
                    │   Eru[E, A]     │
                    │   (Program)     │
                    ╰─────────┬───────╯
                              │
                    ┌─────────▼─────────┐
                    │    Execute        │
                    │ .unsafeRunSync()  │
                    └─────────┬─────────┘
                              │
               ┌──────────────▼──────────────┐
               │                             │
               ▼                             ▼
        ╭───────────────╮               ╭──────────────╮
        │ Failure [E]   │               │ Success [A]  │
        │ Error Channel │               │Value Channel │
        ╰───────────────╯               ╰──────────────╯
```

This visual shows that an `Eru` program is a description with two potential outcomes: the error channel (type `E`) or the success channel (type `A`).

## Type Parameters: The Contract

The two type parameters describe a program's contract:

```scala mdoc
// A program that cannot fail, always produces an Int
val safe: Eru[Nothing, Int] = Eru.succeed(42)

// A program that might fail with String, might produce Int  
val risky: Eru[String, Int] = Eru.fail("Something went wrong")

// A program that might fail with Throwable, might produce String
val effectful: Eru[Throwable, String] = Eru.effect {
  "Hello from a side effect!"
}
```

### The error type E

- `Nothing` means "cannot fail" - the computation is total
- `String` means custom, typed errors with meaningful messages
- `Throwable` means integration with exception-based code
- Custom ADTs mean structured, pattern-matchable errors

### The success type A

- `Unit` means "I do something, don't care about result"
- `Int`, `String`, etc. mean specific value types
- Custom types mean domain-specific results
- `(A, B)` means tuple results, often from combining programs

## Construction: building programs

Eru provides several ways to construct programs:

### Pure values

```scala mdoc
// Immediate success - no computation needed
val immediate = Eru.succeed("Hello")

// Immediate failure - useful for error conditions
val failure = Eru.fail("Not found")

// Strict: the argument is evaluated once, right here, at construction
val computed = Eru.succeed {
  println("Computing...")
  21 * 2
}
```

`succeed` is strict: its argument is evaluated once, when the program is constructed. To evaluate a block on every run, use `Eru.effect` instead.

### Suspending effects

```scala mdoc
import scala.util.Random

// Suspend a side effect - captured for later execution
val suspended = Eru.effect {
  println("Random number generation")
  Random.nextInt(100)
}

// Each execution gets a fresh random number
val first = suspended.unsafeRunSync()
val second = suspended.unsafeRunSync()
println(s"First: $first, Second: $second")
```

### From other types

```scala mdoc
import scala.util.{Try, Success, Failure}

// From Try
val fromTry: Eru[Throwable, Int] = Eru.fromTry(Try(10 / 2))

// From Option  
val fromOption: Eru[String, Int] = Eru.fromOption(Some(42), "Not found")

// From Either
val fromEither: Eru[String, Int] = Eru.fromEither(Right(42))
```

## The GADT design

Eru uses a generalized algebraic data type (GADT) implemented with Scala 3 enums. Each constructor has its own type relationships, which allows compile-time optimizations and type safety.

Here's a simplified view of Eru's internal structure:

```scala
enum Eru[+E, +A]:
  case Succeed[+A](value: A) extends Eru[Nothing, A]
  case Fail[+E](error: E) extends Eru[E, Nothing]
  case Effect[+A](thunk: () => Either[Throwable, A]) extends Eru[Throwable, A]
  case Chain[E, A, B](source: Eru[E, A], cont: A => Eru[E, B]) extends Eru[E, B]
  case Zip[E1, E2, A, B](left: Eru[E1, A], right: Eru[E2, B]) extends Eru[E1 | E2, (A, B)]
  // ... and more
```

This design provides several useful features:

### Type-level guarantees

```scala
// The compiler knows this cannot fail
val guaranteed: Eru[Nothing, String] = Eru.succeed("Safe")

// A typed error cannot leak into a program whose error channel is Nothing:
// val impossible: Eru[Nothing, Int] = Eru.fail("no error channel to hold this") // does not compile

// recover on a Nothing error channel compiles but can never match anything;
// there are no error values for its partial function to see.
val pointless = guaranteed.recover { case _ => "fallback" }
```

### Performance optimizations

The GADT structure allows Eru to apply optimizations at construction time:

```scala mdoc
// These get optimized into a single operation
val optimized = Eru.succeed(10)
  .map(_ * 2)
  .map(_ + 5)
  .map(_.toString)

// Conceptually: 10 -> 20 -> 25 -> "25" in one step
```

### Stack safety

Deep `flatMap` chains are automatically trampolined, preventing stack overflow:

```scala mdoc
def deepChain(n: Int): Eru[Nothing, Int] = {
  // Build the chain iteratively to avoid Scala stack overflow
  (1 to n).foldLeft(Eru.succeed(0)) { (acc, _) =>
    acc.flatMap(current => Eru.succeed(current + 1))
  }
}

val deep = deepChain(100).unsafeRunSync()
println(s"Deep result: $deep")
```

Eru's `flatMap` chains are stack-safe; Scala function recursion is not. The fold above collapses to a single `Succeed` at construction, because `flatMap` on a `Succeed` applies the function immediately. Deep chains arise from suspended effects, and the interpreter executes those without growing the stack. A naive recursive implementation would overflow the Scala call stack first.

Common pitfall: avoid recursive construction

```scala
// ❌ DON'T DO THIS - Scala recursion will overflow:
def badChain(n: Int): Eru[Nothing, Int] =
  if (n <= 0) Eru.succeed(0)
  else Eru.succeed(n).flatMap(_ => badChain(n - 1))

// ✅ DO THIS - Use iterative builders:
def goodChain(n: Int): Eru[Nothing, Int] =
  Eru.iterate(0)(current => Eru.succeed(current + 1))(_ >= n)

// ✅ OR THIS - Build iteratively with foldLeft:
def alsoGoodChain(n: Int): Eru[Nothing, Int] =
  (1 to n).foldLeft(Eru.succeed(0)) { (acc, _) =>
    acc.flatMap(current => Eru.succeed(current + 1))
  }
```

Recursive functions create deep call stacks in Scala. Iterative construction builds a chain of suspended steps that Eru's interpreter runs without stack growth.

## Mental models

Here are three mental models that will help you work effectively with Eru:

### Model 1: the assembly line

Think of `Eru[E, A]` as a factory assembly line blueprint:

- Stations (operations like `map`, `flatMap`) transform the product
- Quality control (error handling) catches defective items
- Raw materials (input values) enter at the start
- The final product (result of type `A`) emerges at the end
- Defects (errors of type `E`) can be caught and handled

### Model 2: the recipe

`Eru[E, A]` is like a cooking recipe:

- Ingredients are your input values
- Steps are your transformations (`map`, `flatMap`, etc.)
- Possible failures are noted in the recipe (wrong temperature, missing ingredients)
- Following the recipe is execution (`unsafeRunSync`)
- The dish is your final result

### Model 3: the promise

`Eru[E, A]` is a promise about future computation:

- "I promise to give you an `A`": the success type
- "But I might fail with an `E`": the error type
- "Here's how I'll try": the program structure
- "Run me when you're ready": lazy execution

## Performance characteristics

Understanding Eru's performance helps you write efficient programs:

### Construction is cheap

```scala mdoc
// Creating programs is very fast - just building data structures
val quickBuild = (1 to 1000).map(i => Eru.succeed(i * 2))
```

### Composition is optimized

```scala mdoc
// Long chains get flattened and optimized
val longChain = (1 to 100).foldLeft(Eru.succeed(0)) { (acc, i) =>
  acc.flatMap(current => Eru.succeed(current + i))
}
```

### Execution is controlled

Only `unsafeRunSync()` and similar methods execute the program. The remaining operations are pure composition.

## Key takeaways

Understanding `Eru[E, A]` as a data structure rather than a computation matters:

Foundational correctness: the GADT design prevents invalid states at compile time.

Direct construction: building and composing effects is data construction.

Guided correctness: the type system pushes you toward handling each case.

Transparent runtime: the separation between description and execution makes programs debuggable and testable.

## What's next

Chapter 4 shows how to combine and sequence Eru programs with `map`, `flatMap`, `zip`, and other combinators: when to use each operator and how Eru optimizes the composition.