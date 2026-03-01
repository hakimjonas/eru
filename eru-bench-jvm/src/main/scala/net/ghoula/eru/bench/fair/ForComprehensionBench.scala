package net.ghoula.eru.bench.fair

import org.openjdk.jmh.annotations.*

import net.ghoula.eru.prelude.*

/** For-Comprehension Optimization Benchmarks (SIP-62, Scala 3.8.2)
  *
  * SIP-62 (Better Fors) eliminates redundant allocations in for-comprehensions:
  *   - Change 2: val bindings desugar to simple vals instead of tuple wrapping
  *   - Change 3: compiler skips the final .map when yield returns the last binding
  *
  * These benchmarks measure the effect of these optimizations on Eru's for-comprehension-driven
  * API. Since Eru's entire composition model is built on flatMap/map chains, these compiler
  * improvements directly reduce allocation pressure in real Eru programs.
  */
class ForComprehensionBench extends FairBenchmarkBase {

  // =============================================================================
  // Redundant map elimination (SIP-62 Change 3)
  // =============================================================================

  /** When `yield` returns the last `<-` binding, 3.8 skips the final `.map` call entirely. */
  @Benchmark
  def forYieldLastBinding(): Int = runEru {
    for {
      a <- Eru.succeed(1)
      b <- Eru.succeed(a + 1)
      c <- Eru.succeed(b + 1)
      result <- Eru.succeed(c + 1)
    } yield result
  }

  // =============================================================================
  // Intermediate val binding — tuple elimination (SIP-62 Change 2)
  // =============================================================================

  /** Val bindings in for-comprehensions previously created intermediate tuples. 3.8 desugars them
    * to simple vals, eliminating tuple allocation.
    */
  @Benchmark
  def forWithIntermediateVals(): Int = runEru {
    for {
      a <- Eru.succeed(10)
      b = a * 2
      c <- Eru.succeed(b + 1)
      d = c * 3
      result <- Eru.succeed(d)
    } yield result
  }

  // =============================================================================
  // Deeply nested for-comprehension (combined effect)
  // =============================================================================

  /** Real-world pattern: multiple `<-` bindings interleaved with `=` vals in a deep chain.
    * Exercises both optimizations together.
    */
  @Benchmark
  def forDeepChain(): Int = runEru {
    for {
      a <- Eru.succeed(1)
      b = a + 1
      c <- Eru.succeed(b)
      d = c + 1
      e <- Eru.succeed(d)
      f = e + 1
      g <- Eru.succeed(f)
      h = g + 1
      result <- Eru.succeed(h)
    } yield result
  }

  // =============================================================================
  // Effectful chain (interpreter path)
  // =============================================================================

  /** Uses Eru.effect to exercise the TailCalls interpreter, not just eager Succeed. This ensures
    * the optimization benefits hold for suspended effects too.
    */
  @Benchmark
  def forEffectfulChain(): Int = runEru {
    for {
      a <- Eru.effect(1)
      b = a + 1
      c <- Eru.effect(b)
      d = c + 1
      result <- Eru.effect(d)
    } yield result
  }
}
