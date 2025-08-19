# Concurrency Quickstart

This guide introduces you to Eru's core effect system and composition patterns. Eru provides a pure, composable foundation for building applications with strong correctness guarantees.

## Basic Effect Composition

The foundation of Eru is the ability to compose pure effects that describe computations without executing them immediately.

```scala
import net.ghoula.eru.*

// Compose effects using flatMap and map
val program = for {
  a <- Eru.succeed(42)
  b <- Eru.succeed("hello")
  result <- Eru.succeed(s"$a-$b")
} yield s"Composed: $result"
// program: Eru[Nothing, String] = Succeed(value = "Composed: 42-hello")

program.unsafeRunSync()
// res0: String = "Composed: 42-hello"
```

## Error Handling and Recovery

Eru provides powerful error handling capabilities that work seamlessly with effect composition.

```scala
// Handle errors gracefully using recover and attempt
val errorProgram = for {
  // An effect that might fail
  result1 <- Eru.fail("Something went wrong").recover {
    case "Something went wrong" => "Recovered!"
    case _ => "Unknown error"
  }
  
  // Convert effects to Result for explicit handling
  result2 <- Eru.succeed(42).attempt
  result3 <- Eru.fail("oops").attempt
  
} yield (result1, result2, result3) match {
  case (recovered, Result.Success(value), Result.Failure(error)) =>
    s"Recovered: $recovered, Success: $value, Failed: $error"
  case null => "Unexpected pattern"
}
// errorProgram: Eru[String, String] = Chain(
//   source = RecoverWith(
//     source = Fail(error = "Something went wrong"),
//     pf = <function1>
//   ),
//   f = repl.MdocSession$MdocApp$$Lambda/0x00007fd199db5218@1d253660
// )

errorProgram.unsafeRunSync()
// res1: String = "Recovered: Recovered!, Success: 42, Failed: oops"
```

## Sequential Operations and Zipping

Eru's `zip` operation allows you to combine the results of two effects that run sequentially.

```scala
// Combine multiple effects sequentially
val leftEffect = Eru.effect {
  println("Running left computation...")
  "Left result"
}
// leftEffect: Eru[Throwable, String] = Effect(
//   thunk = net.ghoula.eru.Eru$$$Lambda/0x00007fd199dba9b8@1ffb1404
// )

val rightEffect = Eru.effect {
  println("Running right computation...")
  "Right result"
}
// rightEffect: Eru[Throwable, String] = Effect(
//   thunk = net.ghoula.eru.Eru$$$Lambda/0x00007fd199dba9b8@43941385
// )

val zipProgram = for {
  // Zip the effects together
  combined <- leftEffect.zip(rightEffect)
} yield s"Combined: ${combined._1} and ${combined._2}"
// zipProgram: Eru[Throwable, String] = MapChain(
//   source = Zip(
//     left = Effect(
//       thunk = net.ghoula.eru.Eru$$$Lambda/0x00007fd199dba9b8@1ffb1404
//     ),
//     right = Effect(
//       thunk = net.ghoula.eru.Eru$$$Lambda/0x00007fd199dba9b8@43941385
//     )
//   ),
//   f = repl.MdocSession$MdocApp$$Lambda/0x00007fd199dc0be0@2b8c750f
// )

zipProgram.unsafeRunSync()
// Running left computation...
// Running right computation...
// res2: String = "Combined: Left result and Right result"
```

## Resource Management with Ensure

Eru's `ensure` provides resource cleanup guarantees, similar to try-finally blocks but composable.

```scala
// Ensure cleanup happens even on failure
val resourceProgram = for {
  result <- Eru.effect {
    println("Acquiring resource...")
    "important data"
  }.ensure(
    Eru.effect(println("Cleaning up resource..."))
  ).flatMap { data =>
    Eru.effect {
      println(s"Processing: $data")
      data.toUpperCase
    }
  }
} yield result
// resourceProgram: Eru[Throwable, String] = MapChain(
//   source = Chain(
//     source = Ensure(
//       source = Effect(
//         thunk = net.ghoula.eru.Eru$$$Lambda/0x00007fd199dba9b8@3b7fef86
//       ),
//       finalizer = net.ghoula.eru.Eru$$Lambda/0x00007fd199dc4000@4e85530f
//     ),
//     f = repl.MdocSession$MdocApp$$Lambda/0x00007fd199dc1970@538ffd10
//   ),
//   f = repl.MdocSession$MdocApp$$Lambda/0x00007fd199dc1d40@138c7693
// )

resourceProgram.unsafeRunSync()
// Acquiring resource...
// Processing: important data
// Cleaning up resource...
// res3: String = "IMPORTANT DATA"
```

## Working with Side Effects

Eru provides safe ways to work with side-effecting code through the `effect` constructor.

```scala
// Safely handle side effects
val sideEffectProgram = for {
  // Wrap side effects safely
  result1 <- Eru.effect {
    val x = scala.util.Random.nextInt(100)
    println(s"Generated random number: $x")
    x
  }
  
  // Handle potential exceptions
  result2 <- Eru.effect {
    if (result1 > 50) "High number"
    else throw new RuntimeException("Low number")
  }.recover {
    case _: RuntimeException => "Recovered from low number"
  }
  
} yield s"Random: $result1, Result: $result2"
// sideEffectProgram: Eru[Throwable, String] = Chain(
//   source = Effect(
//     thunk = net.ghoula.eru.Eru$$$Lambda/0x00007fd199dba9b8@363fcdcd
//   ),
//   f = repl.MdocSession$MdocApp$$Lambda/0x00007fd199dc2d90@269aafbb
// )

sideEffectProgram.unsafeRunSync()
// Generated random number: 13
// res4: String = "Random: 13, Result: Recovered from low number"
```

## Advanced Error Handling Patterns

Eru provides sophisticated error handling patterns that can be composed together.

```scala
// Advanced error handling with multiple recovery strategies
val advancedErrorHandling = for {
  result1 <- Eru.fail("network-error").recover {
    case "network-error" => "recovered from network"
    case _ => "unknown recovery"
  }
  
  result2 <- Eru.effect {
    throw new IllegalArgumentException("invalid input")
  }.recover {
    case _: IllegalArgumentException => "recovered from validation"
  }
  
  // Chain multiple transformations
  result3 <- Eru.succeed("data")
    .map(_.toUpperCase)
    .flatMap(s => if (s.length > 10) Eru.fail("too long") else Eru.succeed(s))
    .recover { case "too long" => "TRUNCATED" }
    
} yield s"Results: $result1, $result2, $result3"
// advancedErrorHandling: Eru[String | Throwable, String] = Chain(
//   source = RecoverWith(source = Fail(error = "network-error"), pf = <function1>),
//   f = repl.MdocSession$MdocApp$$Lambda/0x00007fd199dc86a8@510aa2f
// )

advancedErrorHandling.unsafeRunSync()
// res5: String = "Results: recovered from network, recovered from validation, DATA"
```

## Working with Either and Try

Eru integrates smoothly with standard Scala error handling types.

```scala
// Convert between different error representations
val conversionProgram = for {
  // From Either
  fromEither <- Eru.fromEither(Right("success"): Either[String, String])
  
  // From Try
  fromTry <- Eru.fromTry(scala.util.Try("computed value"))
  
  // Convert to Result for explicit handling
  successResult <- Eru.succeed(42).attempt
  failureResult <- Eru.fail("oops").attempt
  
} yield {
  val successMsg = successResult match {
    case Result.Success(n) => s"Got number: $n"
    case Result.Failure(e) => s"Unexpected error: $e"
  }
  
  val failureMsg = failureResult match {
    case Result.Success(_) => "Unexpected success"
    case Result.Failure(e) => s"Expected error: $e"
  }
  
  s"Either: $fromEither, Try: $fromTry, Success: $successMsg, Failure: $failureMsg"
}
// conversionProgram: Eru[String | Throwable, String] = Chain(
//   source = Effect(
//     thunk = net.ghoula.eru.Eru$$$Lambda/0x00007fd199dba9b8@72a7422e
//   ),
//   f = repl.MdocSession$MdocApp$$Lambda/0x00007fd199dcb058@5cfd7d46
// )

conversionProgram.unsafeRunSync()
// res6: String = "Either: success, Try: computed value, Success: Got number: 42, Failure: Expected error: oops"
```

## Nested Computations

Complex programs can be built by nesting and composing multiple effects.

```scala
// Build complex nested computations
val nestedProgram = for {
  // Inner computation
  inner <- Eru.succeed(10)
    .map(_ * 2)
    .flatMap { x =>
      if (x > 15) Eru.succeed("Large")
      else Eru.succeed("Small")
    }
  
  // Outer computation with the inner result
  outer <- Eru.effect {
    s"Processed: $inner"
  }.map(_.toUpperCase)
  
} yield s"Final: $outer"
// nestedProgram: Eru[Throwable, String] = MapChain(
//   source = Effect(
//     thunk = net.ghoula.eru.Eru$$$Lambda/0x00007fd199dba9b8@9242036
//   ),
//   f = scala.Function1$$Lambda/0x00007fd199dbceb0@11d3def0
// )

nestedProgram.unsafeRunSync()
// res7: String = "Final: PROCESSED: LARGE"
```

## Next Steps

This quickstart has shown you the fundamental patterns for effect programming with Eru:

- **Pure composition** with flatMap and map
- **Error handling** with recover and attempt  
- **Sequential operations** with zip
- **Resource management** with ensure
- **Side effect safety** with the effect constructor
- **Type conversions** with fromEither and fromTry

These primitives form the foundation for building more complex concurrent applications. The pure, composable nature of Eru effects ensures that your programs are both correct and testable.

For more advanced patterns including true concurrency primitives like fibers, parallel execution, and coordination mechanisms, see the full Eru runtime documentation.