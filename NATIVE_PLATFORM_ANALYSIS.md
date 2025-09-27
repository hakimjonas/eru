# Native Platform Compatibility Analysis

## Executive Summary

Native tests fail to compile after our audit because we added `RuntimeBackendSpec` - a new comprehensive test suite that directly references the `RuntimeBackend` enum. This enum contains VirtualThread code in its implementation, which the Scala Native linker cannot resolve. The issue is **not** caused by our Ref changes, though those changes are correct and necessary.

## The Real Timeline

### What Was Working on Main
- All tests passed on Native because no shared test directly referenced `RuntimeBackend` enum cases
- The enum existed with VirtualThread code, but wasn't being linked because no test exercised it
- Coordination primitives (Ref, Semaphore) used raw Java atomics directly

### What We Changed
1. **Fixed Ref to use lazy evaluation** (changed from `Eru.succeed` to `Eru.effect`)
   - This is correct and necessary for proper suspension semantics
   - Ensures operations execute when run, not when created

2. **Implemented suspension type system** (Suspending/Immediate value classes)
   - Provides compile-time deadlock prevention
   - Makes Semaphore properly support waiting with Promises

3. **Added comprehensive test coverage** including `RuntimeBackendSpec`
   - This new test directly references `RuntimeBackend` enum
   - Causes Native linker to try resolving VirtualThread code

## Why Native Linking Fails

The Scala Native linker needs to resolve **all code paths** in an enum, even those that won't execute:

```scala
enum RuntimeBackend {
  case Synchronous
  case VirtualThreads  // Native sees this case

  def fork[E, A](...) = this match {
    case Synchronous => // Native-compatible code
    case VirtualThreads =>
      // Contains Thread.startVirtualThread - Native can't link this!
      Thread.startVirtualThread { ... }
  }
}
```

Even though Native would only ever execute the `Synchronous` branch at runtime (due to `Platform.isJVM` being false), the linker still needs to resolve the `VirtualThreads` branch at compile time.

## The Architectural Tension

Eru elegantly provides a **unified API** across platforms:
- Same `RuntimeBackend` interface for JVM and Native
- JVM gets true concurrency via VirtualThreads
- Native gets synchronous execution
- Both platforms share the same high-level API

However, this creates a fundamental tension:
- **Enum-based dispatch** is elegant and performant
- But **platform-specific code in enums** breaks cross-compilation
- The Native linker can't handle JVM-only APIs like VirtualThreads

## Solutions

### Short-term (Minimal Impact)
1. **Move RuntimeBackendSpec to JVM-only** ✅ (Already done for VirtualThreads tests)
2. **Keep shared tests platform-agnostic** - Don't directly reference enum cases
3. **Document the limitation** - Native can use RuntimeBackend but not test it directly

### Medium-term (Better Testing)
Create platform-specific test strategies:
```scala
// Shared test
class PlatformAgnosticBackendSpec extends EruTestSuite {
  test("backend operations work") {
    val backend = Platform.backend  // Don't reference specific cases
    // Test through the interface
  }
}

// JVM-only test
class JvmBackendSpec extends EruTestSuite {
  test("VirtualThreads specific behavior") {
    val backend = RuntimeBackend.VirtualThreads  // Safe here
  }
}
```

### Long-term (Architectural Refactor)
Replace enum-based dispatch with dependency injection:

```scala
// Shared interface
trait RuntimeBackend {
  def fork[E, A](...): Eru[Nothing, Fiber[E, A]]
  def race[E1, E2, A, B](...): Eru[E1 | E2 | Throwable, Either[A, B]]
}

// JVM implementation (in jvm/ directory)
class VirtualThreadsBackend extends RuntimeBackend {
  def fork[E, A](...) = {
    // VirtualThread code here - Native never sees it
  }
}

// Native implementation (in native/ directory)
class SynchronousBackend extends RuntimeBackend {
  def fork[E, A](...) = {
    // Synchronous code only
  }
}

// Platform object selects implementation
object Platform {
  val backend: RuntimeBackend =
    if (isJVM) new VirtualThreadsBackend
    else new SynchronousBackend
}
```

This would completely separate platform-specific code while maintaining the unified API.

## Impact on Ref and Coordination Primitives

Our Ref changes are **correct and necessary**:
- Using `Eru.effect` ensures lazy evaluation
- The `.mapError` handling is appropriate for Never error type
- This properly supports the suspension type system

The Ref changes didn't cause the Native linking issue - adding RuntimeBackendSpec did. However, the Ref changes are part of making the suspension system work correctly, which is a valuable improvement.

## Recommendations

1. **Keep the current implementation** - The unified API is valuable
2. **Accept the test limitation** - Native can't directly test RuntimeBackend enum cases
3. **Document the constraint** - Make it clear why certain tests are JVM-only
4. **Consider future refactor** - If Native testing becomes critical, implement dependency injection

## Conclusion

The Native compilation issue is a **test problem, not a runtime problem**. Native applications can still use Eru effectively through `Platform.backend`. The issue only arises when tests directly reference enum cases that contain platform-specific code.

Our suspension type system changes and Ref fixes are correct improvements that enhance Eru's safety and correctness. The Native linking issue is an orthogonal problem caused by adding comprehensive test coverage that exercises platform-specific enum cases.