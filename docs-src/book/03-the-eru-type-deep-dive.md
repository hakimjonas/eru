# Chapter 3: The Eru Type Deep Dive

*"Understanding the foundation helps with everything that follows."*

Now that you've written your first Eru programs, let's examine how `Eru[E, A]` works under the hood. This chapter explores the core type, its design decisions, and the concepts that will help you use it effectively.

## The Nature of Eru

At its heart, `Eru[E, A]` is a **data structure that describes a computation**. It's not the computation itself—it's a blueprint, a recipe, a description of what should happen when executed.

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

This separation between **description** and **execution** is fundamental to functional programming and is what gives us composability, testability, and reasoning power.

## Understanding `Eru[E, A]`

The `Eru[E, A]` type represents a **program description** that:
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

This visual shows that an `Eru` program is a description with two potential outcomes: it can follow the **error channel** (type `E`) or the **success channel** (type `A`).

## Type Parameters: The Contract

The two type parameters tell us everything about a program's contract:

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

### The Error Type `E`

- `Nothing` means "cannot fail" - the computation is total
- `String` means custom, typed errors with meaningful messages
- `Throwable` means integration with exception-based code
- Custom ADTs mean structured, pattern-matchable errors

### The Success Type `A`

- `Unit` means "I do something, don't care about result"
- `Int`, `String`, etc. mean specific value types
- Custom types mean domain-specific results
- `(A, B)` means tuple results, often from combining programs

## Construction: Building Programs

Eru provides several ways to construct programs, each serving specific purposes:

### Pure Values

```scala mdoc
// Immediate success - no computation needed
val immediate = Eru.succeed("Hello")

// Immediate failure - useful for error conditions
val failure = Eru.fail("Not found")

// Pure computation - evaluated every time the program runs
val computed = Eru.succeed {
  println("Computing...")
  21 * 2
}
```

Note the difference: `Eru.succeed("Hello")` is truly immediate, while `Eru.succeed { ... }` will re-evaluate the block each time.

### Suspending Effects

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

### From Other Types

```scala mdoc
import scala.util.{Try, Success, Failure}

// From Try
val fromTry: Eru[Throwable, Int] = Eru.fromTry(Try(10 / 2))

// From Option  
val fromOption: Eru[String, Int] = Eru.fromOption(Some(42), "Not found")

// From Either
val fromEither: Eru[String, Int] = Eru.fromEither(Right(42))
```

## The GADT Design

Eru uses a **Generalized Algebraic Data Type** (GADT) implemented with Scala 3 enums. This means each constructor can have different type relationships, enabling compile-time optimizations and enhanced type safety.

Here's a simplified view of Eru's internal structure:

```scala
enum Eru[+E, +A]:
  case Succeed[+A](value: A) extends Eru[Nothing, A]
  case Fail[+E](error: E) extends Eru[E, Nothing]  
  case Effect[+A](thunk: () => A) extends Eru[Throwable, A]
  case FlatMap[E, A, B](program: Eru[E, A], f: A => Eru[E, B]) extends Eru[E, B]
  // ... and more
```

This design enables several useful features:

### Type-Level Guarantees

```scala mdoc
// The compiler knows this can't fail
val guaranteed: Eru[Nothing, String] = Eru.succeed("Safe")

// So this code won't compile - no error to handle!
// val impossible = guaranteed.recover { case _ => "fallback" }
```

### Performance Optimizations

The GADT structure allows Eru to apply optimizations at construction time:

```scala mdoc
// These get optimized into a single operation
val optimized = Eru.succeed(10)
  .map(_ * 2)
  .map(_ + 5)
  .map(_.toString)

// Conceptually: 10 -> 20 -> 25 -> "25" in one step
```

### Stack Safety

Deep `flatMap` chains are automatically trampolined, preventing stack overflow:

```scala mdoc
def deepChain(n: Int): Eru[Nothing, Int] = {
  // Build the chain iteratively to avoid Scala stack overflow
  (1 to n).foldLeft(Eru.succeed(0)) { (acc, _) =>
    acc.flatMap(current => Eru.succeed(current + 1))
  }
}

// This demonstrates true stack safety - Eru handles deep chains
val deep = deepChain(10000).unsafeRunSync()
println(s"Deep result: $deep")
```

**Key insight**: Eru's `flatMap` chains are stack-safe, but Scala function recursion is not. The iterative approach above builds an Eru data structure with many `flatMap` operations, which Eru's runtime can execute using trampolining. A naive recursive implementation would overflow the Scala call stack before Eru could provide its stack safety guarantees.

## Mental Models

Here are three mental models that will help you work effectively with Eru:

### Model 1: The Assembly Line

Think of `Eru[E, A]` as a factory assembly line blueprint:

- **Stations** (operations like `map`, `flatMap`) transform the product
- **Quality control** (error handling) catches defective items  
- **Raw materials** (input values) enter at the start
- **Final product** (result of type `A`) emerges at the end
- **Defects** (errors of type `E`) can be caught and handled

### Model 2: The Recipe

`Eru[E, A]` is like a cooking recipe:

- **Ingredients** are your input values
- **Steps** are your transformations (`map`, `flatMap`, etc.)
- **Possible failures** are explicitly noted (wrong temperature, missing ingredients)
- **Following the recipe** is execution (`unsafeRunSync`)
- **The dish** is your final result

### Model 3: The Promise

`Eru[E, A]` is a promise about future computation:

- **"I promise to give you an `A`"** - the success type
- **"But I might fail with an `E`"** - the error type  
- **"Here's how I'll try"** - the program structure
- **"Run me when you're ready"** - lazy execution

## Performance Characteristics

Understanding Eru's performance helps you write efficient programs:

### Construction is Cheap

```scala mdoc
// Creating programs is very fast - just building data structures
val quickBuild = (1 to 1000).map(i => Eru.succeed(i * 2))
```

### Composition is Optimized

```scala mdoc
// Long chains get flattened and optimized
val longChain = (1 to 100).foldLeft(Eru.succeed(0)) { (acc, i) =>
  acc.flatMap(current => Eru.succeed(current + i))
}
```

### Execution is Controlled

Only `unsafeRunSync()` and similar methods actually execute the program. Everything else is pure composition.

## Key Takeaways

Understanding `Eru[E, A]` as a data structure rather than a computation changes everything:

**Foundational Correctness**: The GADT design prevents invalid states at compile time.

**Ergonomic Design**: Construction and composition are intuitive because you're just building data.

**Guided Correctness**: The type system guides you toward handling all cases correctly.  

**Transparent Runtime**: The separation between description and execution makes programs debuggable and testable.

## What's Next

In Chapter 4, we'll explore how to combine and sequence Eru programs using `map`, `flatMap`, `zip`, and other combinators. You'll learn when to use each operator and how Eru optimizes complex compositions for maximum performance.

---

*"The foundation is laid. Now we build upon it."*