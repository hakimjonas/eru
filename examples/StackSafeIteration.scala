/** Stack-Safe Iteration Examples for Eru
  *
  * This file demonstrates how to perform safe iterative operations with Eru,
  * avoiding stack overflow issues that can occur with naive recursive patterns.
  */
package examples

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

object StackSafeIteration extends App {

  println("=== Stack-Safe Iteration Examples ===\n")

  // Example 1: Using Eru.iterate for conditional loops
  def example1(): Unit = {
    println("Example 1: Building a large number with Eru.iterate")

    val computation = Eru.iterate(0)(current => Eru.succeed(current + 1))(_ >= 10000)
    val result = computation.unsafeRunSync()

    println(s"Final result: $result")
    println("✅ Success! No stack overflow with 10,000 iterations\n")
  }

  // Example 2: Using Eru.iterateN for exact iteration count
  def example2(): Unit = {
    println("Example 2: Exact iteration count with Eru.iterateN")

    val computation = Eru.iterateN("", 5000) { current =>
      Eru.succeed(current + ".")
    }
    val result = computation.unsafeRunSync()

    println(s"Result length: ${result.length} characters")
    println("✅ Success! Exactly 5,000 iterations completed safely\n")
  }

  // Example 3: Using foldLeft for accumulation patterns
  def example3(): Unit = {
    println("Example 3: Safe accumulation with foldLeft")

    val numbers = (1 to 10000).toList
    val computation = numbers.foldLeft(Eru.succeed(0L)) { (accEru, num) =>
      accEru.flatMap(acc => Eru.succeed(acc + num))
    }
    val result = computation.unsafeRunSync()
    val expected = numbers.sum

    println(s"Sum of 1 to 10,000: $result")
    println(s"Expected: $expected")
    println(s"✅ Correct: ${result == expected}\n")
  }

  // Example 4: Using Eru.traverse for processing collections
  def example4(): Unit = {
    println("Example 4: Processing collections with Eru.traverse")

    val items = (1 to 1000).toList
    val computation = Eru.traverse(items) { item =>
      Eru.succeed(item * item)
    }
    val result = computation.unsafeRunSync()

    println(s"Processed ${items.size} items")
    println(s"First few squares: ${result.take(5)}")
    println(s"Last few squares: ${result.takeRight(5)}")
    println("✅ Success! All items processed safely\n")
  }

  // Example 5: Using Eru.unfold for generating sequences
  def example5(): Unit = {
    println("Example 5: Generating sequences with Eru.unfold")

    // Generate first 20 Fibonacci numbers
    val computation = Eru.unfold((0L, 1L)) { case (a, b) =>
      if (a > 1000000) Eru.succeed(None) // Stop when we exceed 1 million
      else Eru.succeed(Some((a, (b, a + b))))
    }
    val result = computation.unsafeRunSync()

    println(s"Fibonacci numbers up to 1 million:")
    println(result.mkString(", "))
    println("✅ Success! Sequence generated safely\n")
  }

  // Example 6: Demonstrating what NOT to do (commented out to prevent stack overflow)
  def showBadPattern(): Unit = {
    println("Example 6: What NOT to do (pattern shown, not executed)")

    println("""
// ❌ DON'T DO THIS - Will cause stack overflow:
def badRecursive(n: Int): Eru[Nothing, Int] =
  if (n <= 0) Eru.succeed(0)
  else Eru.succeed(n).flatMap(_ => badRecursive(n - 1))

// The problem: This creates deep Scala recursion BEFORE Eru can provide stack safety
// Each recursive call adds a frame to the Scala call stack

// ✅ INSTEAD DO THIS:
val safe = Eru.iterate(10000)(current => Eru.succeed(current - 1))(_ <= 0)
// This creates a single Eru data structure that the runtime executes safely
""")
    println()
  }

  // Example 7: Complex nested iteration
  def example7(): Unit = {
    println("Example 7: Complex nested safe iteration")

    // Process a "matrix" of operations safely
    val rows = (1 to 100).toList
    val computation = Eru.traverse(rows) { row =>
      // For each row, process columns
      val cols = (1 to 100).toList
      Eru.traverse(cols) { col =>
        Eru.succeed(row * col)
      }.map(_.sum) // Sum each row
    }
    val result = computation.unsafeRunSync()

    println(s"Processed 100x100 matrix safely")
    println(s"First few row sums: ${result.take(5)}")
    println(s"Total sum: ${result.sum}")
    println("✅ Success! Nested iterations completed safely\n")
  }

  // Run all examples
  example1()
  example2()
  example3()
  example4()
  example5()
  showBadPattern()
  example7()

  println("=== All Examples Completed Successfully! ===")
  println("Key takeaway: Use Eru's iterative builders instead of Scala recursion")
}