package net.ghoula.eru.bench.fair

import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Benchmark to isolate Ref operation costs. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
class RefOperationsBench extends FairBenchmarkBase {

  given eruRuntime: EruRuntime = EruRuntime.create()

  // Test simple state for Ref
  sealed trait SimpleState
  case object Empty extends SimpleState
  case class Full(value: Int) extends SimpleState

  // Pre-created ref for operations
  private var testRef: Ref[SimpleState] = uninitialized

  @Setup
  def setup(): Unit = {
    testRef = runEru(Eru.ref[SimpleState](Empty))
  }

  // Just Ref.get
  @Benchmark
  def refGet(): SimpleState = runEru {
    testRef.get
  }

  // Just Ref.set
  @Benchmark
  def refSet(): Unit = runEru {
    testRef.set(Full(42))
  }

  // Ref.modify (what Promise.succeed uses)
  @Benchmark
  def refModify(): Boolean = runEru {
    testRef.modify {
      case Empty => (Full(42), true)
      case s @ Full(_) => (s, false)
    }
  }

  // Ref.update (simpler than modify)
  @Benchmark
  def refUpdate(): SimpleState = runEru {
    testRef.update {
      case Empty => Full(42)
      case s => s
    }
  }

  // Compare with AtomicReference directly
  private val atomicRef = new java.util.concurrent.atomic.AtomicReference[SimpleState](Empty)

  @Benchmark
  def atomicGet(): SimpleState = {
    atomicRef.get()
  }

  @Benchmark
  def atomicSet(): Unit = {
    atomicRef.set(Full(42))
  }

  @Benchmark
  def atomicCompareAndSet(): Boolean = {
    val current = atomicRef.get()
    current match {
      case Empty => atomicRef.compareAndSet(current, Full(42))
      case _ => false
    }
  }
}
